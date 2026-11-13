package com.group5.shipmentservice.shipping.service;

import com.group5.shipmentservice.shipping.dto.response.ShipmentTrackingEntryResponse;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.shipmentservice.shared.config.PersistenceConfiguration;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.adapter.CarrierAdapter;
import com.group5.shipmentservice.shipping.adapter.CarrierRegistry;
import com.group5.shipmentservice.shipping.adapter.model.CarrierTrackingUpdate;
import com.group5.shipmentservice.shipping.dto.response.ShipmentResponse;
import com.group5.shipmentservice.shipping.entity.Shipment;
import com.group5.shipmentservice.shipping.entity.ShipmentHistory;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;
import com.group5.shipmentservice.shipping.event.ShipmentEventPublisher;
import com.group5.shipmentservice.shipping.repository.ShipmentHistoryRepository;
import com.group5.shipmentservice.shipping.repository.ShipmentRepository;
import com.group5.shipmentservice.shipping.repository.ShipmentStatusReceiptRepository;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ShipmentStatusService {
    private static final Logger log = LoggerFactory.getLogger(ShipmentStatusService.class);
    private final ShipmentRepository shipments;
    private final ShipmentHistoryRepository histories;
    private final CarrierRegistry carriers;
    private final CarrierExecutor executor;
    private final ShipmentEventPublisher events;
    private final ShipmentReadService reads;
    private final ShipmentStatusReceiptRepository receipts;
    private final TransactionTemplate transaction;
    private final long pollIntervalMs;

    public ShipmentStatusService(ShipmentRepository shipments, ShipmentHistoryRepository histories,
            CarrierRegistry carriers, CarrierExecutor executor, ShipmentEventPublisher events,
            ShipmentReadService reads, ShipmentStatusReceiptRepository receipts, PlatformTransactionManager manager,
            @Value("${nexus.shipping.status-poll-interval-ms:60000}") long pollIntervalMs) {
        this.shipments = shipments;
        this.histories = histories;
        this.carriers = carriers;
        this.executor = executor;
        this.events = events;
        this.reads = reads;
        this.receipts = receipts;
        this.transaction = new TransactionTemplate(manager);
        if (pollIntervalMs < 1 || pollIntervalMs > 3600000) throw new IllegalArgumentException("Invalid poll interval");
        this.pollIntervalMs = pollIntervalMs;
    }

    public Page<ShipmentTrackingEntryResponse> tracking(CurrentUser user, UUID id, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new DomainException(ShipmentErrorCode.INVALID_PARAMETER);
        return transaction.execute(ignored -> {
            reads.get(user, id);
            return histories.findByShipment_Id(id, PageRequest.of(page, size,
                    Sort.by(Sort.Direction.DESC, "createdAt", "id"))).map(ShipmentTrackingEntryResponse::from);
        });
    }

    public ShipmentResponse update(CurrentUser user, UUID id, ShipmentStatus target, String reason, boolean override) {
        requireOperator(user, override ? "SHIPPING.MANUAL_OVERRIDE" : "SHIPPING.UPDATE_STATUS");
        String description = sanitize(reason);
        if (override && (description == null || description.isBlank())) {
            throw new DomainException(ShipmentErrorCode.VALIDATION_FAILED);
        }
        String auditReason = description == null ? "Manual shipment status update" : description;
        return transaction.execute(ignored -> {
            Shipment shipment = shipments.findByIdForUpdate(id)
                    .orElseThrow(() -> new DomainException(ShipmentErrorCode.NOT_FOUND));
            ShipmentTransitions.requireAllowed(shipment.getShipmentStatus(), target, shipment.getHandedOverAt() != null);
            if (shipment.getShipmentStatus() != target) {
                if (shipment.getTrackingCode() == null && target != ShipmentStatus.CANCELLED) {
                    throw new DomainException(ShipmentErrorCode.INVALID_TRANSITION);
                }
                if (target == ShipmentStatus.CANCELLED) cancelAtCarrier(shipment);
                apply(shipment, target, "MANUAL", null, null, auditReason, user.id(), null);
            }
            return ShipmentResponse.from(shipment);
        });
    }

    public void webhook(String carrier, byte[] body, String signature) {
        CarrierAdapter adapter = adapter(carrier);
        adapter.verifyWebhook(body, signature);
        if (body == null || body.length == 0 || body.length > 262144) {
            throw new DomainException(ShipmentErrorCode.INVALID_WEBHOOK);
        }
        CarrierTrackingUpdate update = adapter.parseWebhook(body);
        requireUpdate(update, null);
        String identity = update.carrierEventId() == null ? digest(body) : update.carrierEventId();
        if (identity.length() > 160 || identity.isBlank()) throw new DomainException(ShipmentErrorCode.INVALID_WEBHOOK);
        transaction.executeWithoutResult(ignored -> {
            Shipment shipment = shipments.findByCarrierTrackingForUpdate(adapter.code(), update.trackingCode())
                    .orElseThrow(() -> new DomainException(ShipmentErrorCode.NOT_FOUND));
            if (!receipts.claimWebhook(adapter.code(), identity, shipment.getId())) return;
            apply(shipment, update.status(), "CARRIER_WEBHOOK", identity, update.occurredAt(),
                    sanitize(update.description()), PersistenceConfiguration.SYSTEM_ID, null);
        });
    }

    public void cancelOrder(UUID orderId, String reason, UUID eventId, String traceId) {
        if (orderId == null || eventId == null || reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("OrderCancelled requires orderId, eventId and reason up to 500 characters");
        }
        String sanitized = reason.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (sanitized.isBlank()) throw new IllegalArgumentException("OrderCancelled reason must contain text");
        String historyReason = sanitized.length() > 255 ? sanitized.substring(0, 255) : sanitized;
        transaction.executeWithoutResult(ignored -> {
            shipments.lockKey("shipment-order:" + orderId);
            receipts.recordCancellation(orderId, eventId, sanitized);
            var found = shipments.findByOrderIdForUpdate(orderId);
            if (found.isEmpty()) return;
            Shipment shipment = found.get();
            if (shipment.getShipmentStatus() == ShipmentStatus.CANCELLED) return;
            if (shipment.getHandedOverAt() != null || ShipmentTransitions.pickedUp(shipment.getShipmentStatus())) {
                log.warn("Support action required: OrderCancelled arrived after pickup orderId={} shipmentId={} eventId={}",
                        orderId, shipment.getId(), eventId);
                return;
            }
            cancelAtCarrier(shipment);
            apply(shipment, ShipmentStatus.CANCELLED, "SYSTEM", null, null,
                    historyReason, PersistenceConfiguration.SYSTEM_ID, traceId);
        });
    }

    public void poll() {
        var ids = transaction.execute(ignored -> shipments.findDueStatusPollIds(now()));
        for (UUID id : ids) {
            try {
                transaction.executeWithoutResult(ignored -> pollOne(id));
            } catch (RuntimeException failure) {
                log.warn("Tracking poll rolled back; will retry shipmentId={}", id, failure);
            }
        }
    }

    private void pollOne(UUID id) {
        var found = shipments.findDueStatusPollForUpdate(id, now());
        if (found.isEmpty()) return;
        Shipment shipment = found.get();
        CarrierTrackingUpdate update;
        try {
            CarrierAdapter adapter = adapter(shipment.getCarrier());
            update = executor.execute(adapter.code(), deadline -> adapter.track(shipment.getTrackingCode(), deadline));
            requireUpdate(update, shipment.getTrackingCode());
        } catch (RuntimeException failure) {
            long delay = Math.min(3600000L, pollIntervalMs * (1L << Math.min(shipment.getStatusPollFailures(), 6)));
            shipment.scheduleStatusPoll(now().plusNanos(delay * 1000000L), true);
            log.warn("Carrier tracking unavailable; retry scheduled shipmentId={} carrier={}", id, shipment.getCarrier());
            return;
        }
        shipment.scheduleStatusPoll(now().plusNanos(pollIntervalMs * 1000000L), false);
        try {
            apply(shipment, update.status(), "POLLER", update.carrierEventId(), update.occurredAt(),
                    sanitize(update.description()), PersistenceConfiguration.SYSTEM_ID, null);
        } catch (DomainException failure) {
            if (!failure.getErrorCode().equals(ShipmentErrorCode.INVALID_TRANSITION.code())) throw failure;
            log.debug("Ignored stale tracking result shipmentId={}", id);
        }
    }

    private void apply(Shipment shipment, ShipmentStatus target, String source, String carrierEventId,
                       OffsetDateTime observedAt, String reason, UUID actorId, String traceId) {
        if (carrierEventId != null && histories.existsByShipment_IdAndCarrierEventId(shipment.getId(), carrierEventId)) return;
        if (observedAt != null && shipment.getLastCarrierUpdateAt() != null
                && observedAt.isBefore(shipment.getLastCarrierUpdateAt())) {
            throw new DomainException(ShipmentErrorCode.INVALID_TRANSITION);
        }
        if (shipment.getShipmentStatus() == target) {
            if (observedAt != null) shipments.recordCarrierObservation(shipment.getId(), observedAt);
            return;
        }
        ShipmentTransitions.requireAllowed(shipment.getShipmentStatus(), target, shipment.getHandedOverAt() != null);
        if (ShipmentTransitions.pickedUp(target) && shipment.getTrackingCode() == null) {
            throw new DomainException(ShipmentErrorCode.INVALID_TRANSITION);
        }
        shipment.changeStatus(target, now(), observedAt, ShipmentTransitions.pickedUp(target));
        shipments.flush();
        if (observedAt != null) shipments.recordCarrierObservation(shipment.getId(), observedAt);
        ShipmentHistory history = histories.saveAndFlush(ShipmentHistory.change(shipment, reason, source,
                carrierEventId, actorId));
        events.changed(shipment, history, reason, traceId);
    }

    private void cancelAtCarrier(Shipment shipment) {
        if (shipment.getTrackingCode() == null) return;
        CarrierAdapter adapter = adapter(shipment.getCarrier());
        executor.execute(adapter.code(), deadline -> {
            adapter.cancelShipment(shipment.getTrackingCode(), shipment.getId(), deadline);
            return Boolean.TRUE;
        });
    }

    private CarrierAdapter adapter(String code) {
        String normalized = code == null ? "" : code.toUpperCase(Locale.ROOT);
        return carriers.getAdapters().stream().filter(candidate -> candidate.code().equals(normalized)).findFirst()
                .orElseThrow(() -> new DomainException(ShipmentErrorCode.UNSUPPORTED_CARRIER));
    }

    private static void requireUpdate(CarrierTrackingUpdate update, String expectedCode) {
        if (update == null || update.trackingCode() == null || update.trackingCode().isBlank()
                || update.trackingCode().length() > 80 || update.status() == null
                || (expectedCode != null && !expectedCode.equals(update.trackingCode()))
                || (update.carrierEventId() != null && (update.carrierEventId().isBlank() || update.carrierEventId().length() > 160))) {
            throw new DomainException(ShipmentErrorCode.INVALID_CARRIER_RESPONSE);
        }
    }

    private static void requireOperator(CurrentUser user, String privilege) {
        if (user == null || user.id() == null) throw new DomainException(ShipmentErrorCode.UNAUTHENTICATED);
        if ((!user.hasRole("ADMIN") && !user.hasRole("SUPPORT_STAFF")) || !user.hasPrivilege(privilege)) {
            throw new DomainException(ShipmentErrorCode.FORBIDDEN);
        }
    }

    private static String sanitize(String reason) {
        if (reason == null) return null;
        String sanitized = reason.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (sanitized.length() > 255) throw new DomainException(ShipmentErrorCode.VALIDATION_FAILED);
        return sanitized.isBlank() ? null : sanitized;
    }

    private static String digest(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
}
