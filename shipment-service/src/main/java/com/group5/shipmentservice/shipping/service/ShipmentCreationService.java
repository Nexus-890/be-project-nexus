package com.group5.shipmentservice.shipping.service;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelope;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.event.payload.OrderPaidSnapshot;
import com.group5.shipmentservice.shipping.adapter.CarrierRegistry;
import com.group5.shipmentservice.shipping.adapter.model.CarrierShipmentRequest;
import com.group5.shipmentservice.shipping.dto.request.CreateShipmentRequest;
import com.group5.shipmentservice.shipping.entity.Shipment;
import com.group5.shipmentservice.shipping.entity.ShipmentHistory;
import com.group5.shipmentservice.shipping.enums.ShippingServiceType;
import com.group5.shipmentservice.shipping.adapter.model.PickupAddress;
import com.group5.shipmentservice.shipping.event.ShipmentEventPublisher;
import com.group5.shipmentservice.shipping.repository.ShipmentHistoryRepository;
import com.group5.shipmentservice.shipping.repository.ShipmentRepository;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ShipmentCreationService {
    private static final Logger log = LoggerFactory.getLogger(ShipmentCreationService.class);
    private static final String FAILED_TRACKING_ATTEMPT = "Carrier tracking attempt failed";
    private final JsonMapper mapper;
    private final ShipmentRepository shipments;
    private final ShipmentHistoryRepository histories;
    private final CarrierRegistry carriers;
    private final CarrierExecutor executor;
    private final ShippingProperties properties;
    private final ShipmentEventPublisher events;
    private final PaidOrderSnapshotService paidOrders;
    private final TransactionTemplate transaction;
    private final long retryBackoffMs;

    public ShipmentCreationService(JsonMapper mapper, ShipmentRepository shipments, ShipmentHistoryRepository histories,
            CarrierRegistry carriers, CarrierExecutor executor, ShippingProperties properties,
            ShipmentEventPublisher events, PaidOrderSnapshotService paidOrders, PlatformTransactionManager manager,
            @Value("${nexus.shipping.tracking-retry-backoff-ms:30000}") long retryBackoffMs) {
        this.mapper = mapper;
        this.shipments = shipments;
        this.histories = histories;
        this.carriers = carriers;
        this.executor = executor;
        this.properties = properties;
        this.events = events;
        this.paidOrders = paidOrders;
        this.transaction = new TransactionTemplate(manager);
        if (retryBackoffMs < 1) throw new IllegalArgumentException("tracking retry backoff must be positive");
        this.retryBackoffMs = retryBackoffMs;
    }


    public CreationResult createPaid(EventEnvelope<JsonNode> event, OrderPaidSnapshot snapshot) {
        return transaction.execute(status -> {
            shipments.lockKey("shipment-order:" + snapshot.orderId());
            if (shipments.isOrderCancelled(snapshot.orderId())) return new CreationResult(null, false);
            var existing = shipments.findByOrderId(snapshot.orderId());
            if (existing.isPresent()) {
                requireSameSnapshot(existing.get(), snapshot);
                return new CreationResult(existing.get().getId(), false);
            }

            String carrier = bookingCarrier(snapshot.shippingInfo());
            String service = selectedService(snapshot.shippingInfo(), carrier);
            Map<String, Object> info = prepareShippingInfo(snapshot.shippingInfo(), snapshot.warehouseId(), false);
            Shipment shipment = persist(snapshot, snapshot.warehouseId(), carrier, service, info,
                    null, null, event.traceId());
            return new CreationResult(shipment.getId(), true);
        });
    }

    public Shipment createManually(String key, CreateShipmentRequest request) {
        if (key == null || key.isBlank()) throw new DomainException(ShipmentErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        if (key.length() > 100) throw new DomainException(ShipmentErrorCode.INVALID_PARAMETER);
        String fingerprint = fingerprint(request);
        Shipment existing = transaction.execute(status -> claimExisting(key, request, fingerprint));
        if (existing != null) return existing;

        // The remote read happens before taking local DB locks. Commerce owns paid status and snapshots.
        OrderPaidSnapshot snapshot = paidOrders.load(request.orderId());
        Dispatch dispatch = selectDispatch(snapshot, request);
        CreationResult result = transaction.execute(status -> {
            Shipment raced = claimExisting(key, request, fingerprint);
            if (raced != null) return new CreationResult(raced.getId(), false);
            Shipment created = persist(snapshot, dispatch.warehouseId(), dispatch.carrier(), dispatch.serviceType(),
                    dispatch.shippingInfo(), key, fingerprint, null);
            return new CreationResult(created.getId(), true);
        });
        if (result.created()) {
            try {
                assignInitialTracking(result.id());
            } catch (RuntimeException error) {
                log.warn("Manual shipment committed; tracking worker will retry shipmentId={}", result.id(), error);
            }
        }
        return transaction.execute(status -> shipments.findById(result.id()).orElseThrow());
    }

    private Shipment claimExisting(String key, CreateShipmentRequest request, String fingerprint) {
            shipments.lockKey("shipment-key:" + key);
            var replay = shipments.findByIdempotencyKey(key);
            if (replay.isPresent()) {
                if (!fingerprint.equals(replay.get().getIdempotencyFingerprint())) conflict();
                return replay.get();
            }

            shipments.lockKey("shipment-order:" + request.orderId());
            if (shipments.isOrderCancelled(request.orderId())) throw new DomainException(ShipmentErrorCode.ORDER_NOT_PAID);
            var existing = shipments.findByOrderIdForUpdate(request.orderId());
            if (existing.isEmpty()) return null;
            Shipment shipment = existing.get();
            if (shipment.getIdempotencyKey() != null) conflict();
            requireSelections(shipment, request);
            shipment.claimManualRequest(key, fingerprint);
            shipments.flush();
            return shipment;
    }

    private Shipment persist(OrderPaidSnapshot snapshot, UUID warehouseId, String carrier, String serviceType,
                             Map<String, Object> info, String key, String fingerprint, String traceId) {
        Shipment shipment = Shipment.create(UUID.randomUUID(), snapshot.orderId(), snapshot.buyerId(),
                warehouseId, carrier, serviceType, snapshot.shippingFee(), info, key);
        shipment.assignSeller(snapshot.sellerId());
        if (key != null) shipment.claimManualRequest(key, fingerprint);
        shipment.trackingPending("NONE".equals(carrier) ? null : now());
        shipment = shipments.saveAndFlush(shipment);
        history(shipment, "Shipment created");
        events.created(shipment, traceId);
        return shipment;
    }

    public void assignInitialTracking(UUID id) {
        assignTracking(id);
    }

    public void assignTracking(UUID id) {
        transaction.executeWithoutResult(status -> {
            var due = shipments.findDueTrackingForUpdate(id, now());
            if (due.isEmpty()) return;
            Shipment shipment = due.get();
            boolean retry = histories.existsByShipment_IdAndDescriptionAndSource(id, FAILED_TRACKING_ATTEMPT, "SYSTEM");
            TrackingAttempt attempt = acquireTracking(shipment, retry);
            if (attempt.addressFallback()) history(shipment, "GHN address fallback: ward and province used after TO_ADDRESS_CONFLICT");
            if (shipment.getTrackingCode() != null) history(shipment, "Carrier tracking assigned");
            else if (attempt.failed()) history(shipment, FAILED_TRACKING_ATTEMPT);
            // DB failures must roll this transaction back, not be hidden as carrier failures.
            shipments.flush();
        });
    }

    public void retryDueTracking() {
        var ids = transaction.execute(status -> shipments.findDueTrackingIds(now()));
        for (UUID id : ids) {
            try {
                assignTracking(id);
            } catch (RuntimeException error) {
                log.warn("Tracking update rolled back; will retry shipmentId={}", id, error);
            }
        }
    }

    private TrackingAttempt acquireTracking(Shipment shipment, boolean retry) {
        AtomicBoolean attempted = new AtomicBoolean();
        try {
            if (properties.effectiveBookingCarrier() == ShippingProperties.CarrierMode.NONE
                    || "NONE".equals(shipment.getCarrier())) {
                shipment.trackingPending(null);
                return new TrackingAttempt(false, false);
            }
            shipment.completeBookingPreparation(selectedService(shipment.getShippingInfo(), shipment.getCarrier()),
                    prepareShippingInfo(shipment.getShippingInfo(), shipment.getWarehouseId(), false));
            if (!preparedForBooking(shipment)) throw new DomainException(ShipmentErrorCode.CARRIER_CONFIGURATION_ERROR);
            var adapter = carriers.getAdapters().stream().filter(a -> a.code().equals(shipment.getCarrier())).findFirst()
                    .orElseThrow(() -> new DomainException(ShipmentErrorCode.CARRIER_CONFIGURATION_ERROR));
            var booking = executor.execute(shipment.getCarrier(), deadline -> {
                if (retry) shipment.countTrackingRetry();
                attempted.set(true);
                return adapter.bookShipment(new CarrierShipmentRequest(shipment.getId(), shipment.getOrderId(),
                        shipment.getWarehouseId(), shipment.getServiceType(), shipment.getShippingFee(), shipment.getShippingInfo()), deadline);
            });
            String code = booking == null ? null : booking.trackingCode();
            if (code == null || code.isBlank() || code.length() > 80) throw new DomainException(ShipmentErrorCode.INVALID_CARRIER_RESPONSE);
            shipment.trackingAssigned(code);
            shipment.recordEstimatedDelivery(booking.estimatedDeliveryAt());
            return new TrackingAttempt(false, booking.addressFallback());
        } catch (RuntimeException error) {
            log.warn("Tracking pending: shipmentId={} carrier={} failure={}", shipment.getId(),
                    shipment.getCarrier(), error.getClass().getSimpleName());
            long multiplier = 1L << Math.min(shipment.getTrackingRetryCount(), 10);
            long delay = retryBackoffMs > 3600000L / multiplier ? 3600000L : retryBackoffMs * multiplier;
            shipment.trackingPending(now().plusNanos(delay * 1000000L));
            return new TrackingAttempt(attempted.get(), false);
        }
    }

    private void requireSameSnapshot(Shipment shipment, OrderPaidSnapshot snapshot) {
        if (!shipment.getBuyerId().equals(snapshot.buyerId())
                || (shipment.getSellerId() != null && !shipment.getSellerId().equals(snapshot.sellerId()))
                || shipment.getShippingFee().compareTo(snapshot.shippingFee()) != 0
                || !sameRecipient(shipment.getShippingInfo(), snapshot.shippingInfo())
                || !sortedItems(shipment.getShippingInfo()).equals(sortedItems(snapshot.shippingInfo()))) {
            throw new IllegalArgumentException("Conflicting OrderPaid snapshot for existing order");
        }
        shipment.assignSeller(snapshot.sellerId());
    }

    private boolean sameRecipient(Map<String, Object> first, Map<String, Object> second) {
        JsonNode a = mapper.valueToTree(first).path("recipient");
        JsonNode b = mapper.valueToTree(second).path("recipient");
        if (!a.path("name").equals(b.path("name")) || !a.path("phone").equals(b.path("phone"))
                || !a.path("address").path("line1").equals(b.path("address").path("line1"))) return false;
        for (String code : new String[]{"provinceCode", "wardCode"}) {
            JsonNode x = a.path("address").path(code);
            JsonNode y = b.path("address").path(code);
            if (!x.isMissingNode() && !x.isNull() && !y.isMissingNode() && !y.isNull() && !x.equals(y)) return false;
        }
        return true;
    }

    private java.util.List<String> sortedItems(Map<String, Object> info) {
        var items = new ArrayList<String>();
        for (JsonNode item : mapper.valueToTree(info).path("items")) {
            items.add(item.path("skuId").asString() + ":" + item.path("productName").asString()
                    + ":" + item.path("quantity").asInt());
        }
        items.sort(Comparator.naturalOrder());
        return items;
    }

    private Dispatch selectDispatch(OrderPaidSnapshot snapshot, CreateShipmentRequest request) {
        UUID warehouse = request.warehouseId() != null ? request.warehouseId()
                : snapshot.warehouseId() != null ? snapshot.warehouseId() : properties.defaultWarehouseId();
        if (warehouse == null) throw new DomainException(ShipmentErrorCode.WAREHOUSE_NOT_FOUND);
        String carrier = request.carrier() == null ? bookingCarrier(snapshot.shippingInfo()) : request.carrier();
        if (!"NONE".equals(carrier) && carriers.getAdapters().stream().noneMatch(adapter -> adapter.code().equals(carrier))) {
            throw new DomainException(ShipmentErrorCode.INVALID_PARAMETER);
        }
        String service = request.serviceType() == null ? selectedService(snapshot.shippingInfo(), carrier) : request.serviceType();
        if (service != null) {
            try { ShippingServiceType.valueOf(service); }
            catch (IllegalArgumentException error) { throw new DomainException(ShipmentErrorCode.NO_SERVICE); }
        }
        boolean differentWarehouse = !warehouse.equals(snapshot.warehouseId());
        Map<String, Object> info = prepareShippingInfo(snapshot.shippingInfo(), warehouse, differentWarehouse);
        return new Dispatch(warehouse, carrier, service, info);
    }

    private Map<String, Object> prepareShippingInfo(Map<String, Object> shippingInfo, UUID warehouse,
                                                   boolean differentWarehouse) {
        // Commerce's paid event can omit dispatch details. Only use a trusted pickup for this exact warehouse.
        Map<String, Object> info = new LinkedHashMap<>(shippingInfo);
        PickupAddress configured = properties.pickups().get(warehouse);
        if (configured == null && warehouse != null
                && (!differentWarehouse || warehouse.equals(properties.defaultWarehouseId()))) {
            configured = properties.platformPickup();
        }
        if (differentWarehouse || info.get("pickupAddress") == null) {
            if (configured != null && configured.addressLine() != null && !configured.addressLine().isBlank()
                    && configured.provinceCode() != null && !configured.provinceCode().isBlank()
                    && configured.wardCode() != null && !configured.wardCode().isBlank()) {
                info.put("pickupAddress", Map.of("line1", configured.addressLine(), "provinceCode", configured.provinceCode(),
                        "wardCode", configured.wardCode()));
            } else if (differentWarehouse) {
                throw new DomainException(ShipmentErrorCode.WAREHOUSE_NOT_FOUND);
            }
        }
        return info;
    }

    private boolean preparedForBooking(Shipment shipment) {
        if (shipment.getServiceType() == null) return false;
        JsonNode info = mapper.valueToTree(shipment.getShippingInfo());
        for (String address : new String[]{"recipient", "pickupAddress"}) {
            JsonNode node = "recipient".equals(address) ? info.path(address).path("address") : info.path(address);
            for (String field : new String[]{"line1", "provinceCode", "wardCode"}) {
                if (!node.path(field).isString() || node.path(field).asString().isBlank()) return false;
            }
        }
        JsonNode parcel = info.path("parcel");
        for (String field : new String[]{"weightGrams", "lengthCm", "widthCm", "heightCm"}) {
            if (!parcel.path(field).isNumber() || parcel.path(field).decimalValue().signum() <= 0) return false;
        }
        return parcel.path("weightGrams").isIntegralNumber() && parcel.path("weightGrams").canConvertToInt();
    }

    private static void requireSelections(Shipment shipment, CreateShipmentRequest request) {
        if ((request.warehouseId() != null && !request.warehouseId().equals(shipment.getWarehouseId()))
                || (request.carrier() != null && !request.carrier().equals(shipment.getCarrier()))
                || (request.serviceType() != null && !request.serviceType().equals(shipment.getServiceType()))) conflict();
    }

    private String fingerprint(CreateShipmentRequest request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required", error);
        }
    }

    private void history(Shipment shipment, String description) {
        histories.save(ShipmentHistory.system(shipment, description));
    }

    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }

    private static void conflict() { throw new DomainException(ShipmentErrorCode.IDEMPOTENCY_CONFLICT); }

    private String selectedCarrier() {
        return properties.effectiveBookingCarrier().name();
    }

    private String bookingCarrier(Map<String, Object> shippingInfo) {
        if (!properties.buyerChoosesCarrier() || properties.effectiveBookingCarrier() == ShippingProperties.CarrierMode.NONE) {
            return selectedCarrier();
        }
        Object chosen = shippingInfo == null ? null : shippingInfo.get("carrier");
        if (chosen instanceof String code && properties.offeredCarriers().contains(code)
                && carriers.getAdapters().stream().anyMatch(adapter -> adapter.code().equals(code))) {
            return code;
        }
        return selectedCarrier();
    }

    private String selectedService(Map<String, Object> shippingInfo, String carrier) {
        String service = (String) shippingInfo.get("serviceType");
        if (service == null && ("FAKE".equals(carrier) || "GHN".equals(carrier))) {
            JsonNode weight = mapper.valueToTree(shippingInfo).path("parcel").path("weightGrams");
            if (weight.isIntegralNumber() && weight.canConvertToInt() && weight.asInt() > 0) {
                return ShippingServiceType.forWeight(weight.asInt()).name();
            }
        }
        return service;
    }

    public record CreationResult(UUID id, boolean created) { }
    private record TrackingAttempt(boolean failed, boolean addressFallback) { }
    private record Dispatch(UUID warehouseId, String carrier, String serviceType, Map<String, Object> shippingInfo) { }
}
