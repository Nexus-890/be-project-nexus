package com.group5.paymentservice.payment.service;

import com.group5.common_events.EventEnvelope;
import com.group5.paymentservice.payment.event.payload.CheckoutPaymentRequested;
import com.group5.paymentservice.payment.event.payload.OrderGroupCancelled;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.paymentservice.payment.entity.PaymentInstruction;
import com.group5.paymentservice.payment.entity.PaymentPendingCancellation;
import com.group5.paymentservice.payment.entity.PaymentSession;
import com.group5.paymentservice.payment.enums.PaymentInstructionStatus;
import com.group5.paymentservice.payment.enums.PaymentSessionStatus;
import com.group5.paymentservice.payment.repository.PaymentInstructionRepository;
import com.group5.paymentservice.payment.repository.PaymentPendingCancellationRepository;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PaymentSessionEventHandler {
    public static final String CHECKOUT_CONSUMER = "payment-checkout-requested";
    public static final String CANCELLATION_CONSUMER = "payment-order-group-cancelled";
    private final IdempotentConsumer consumer;
    private final PaymentSessionRepository sessions;
    private final PaymentInstructionRepository instructions;
    private final PaymentPendingCancellationRepository pendingCancellations;
    private final RefundService refunds;
    private final Validator validator;

    public PaymentSessionEventHandler(IdempotentConsumer consumer, PaymentSessionRepository sessions,
                                      PaymentInstructionRepository instructions,
                                      PaymentPendingCancellationRepository pendingCancellations,
                                      RefundService refunds, Validator validator) {
        this.consumer = consumer;
        this.sessions = sessions;
        this.instructions = instructions;
        this.pendingCancellations = pendingCancellations;
        this.refunds = refunds;
        this.validator = validator;
    }

    public void checkout(EventEnvelope<CheckoutPaymentRequested> event) {
        CheckoutPaymentRequested manifest = event.payload();
        validate(manifest);
        require(event.eventType().equals("CheckoutPaymentRequested"), "Unexpected event type");
        require(event.aggregateId().equals(manifest.orderGroupId().toString()), "Group and aggregate must match");
        require(event.occurredAt().equals(manifest.occurredAt()), "Manifest and envelope times must match");
        require(manifest.dueAt().isAfter(manifest.occurredAt()), "Deadline must follow manifest creation");
        require(manifest.orders().size() == manifest.orderCount(), "Incomplete order manifest");
        require(manifest.orders().stream().map(CheckoutPaymentRequested.SellerOrder::orderId).distinct().count()
                == manifest.orderCount(), "Duplicate order in manifest");
        require(manifest.orders().stream().map(CheckoutPaymentRequested.SellerOrder::sellerId).distinct().count()
                == manifest.orderCount(), "Expected one order per seller");
        require(manifest.orders().stream().noneMatch(order -> order.paymentDueAt().isBefore(manifest.dueAt())),
                "Group deadline must not exceed a child deadline");
        require(manifest.orders().stream().map(CheckoutPaymentRequested.SellerOrder::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(manifest.totalAmount()) == 0,
                "Instruction amounts must equal the group total");
        consumer.handle(CHECKOUT_CONSUMER, event, this::createPlan);
    }

    private void createPlan(EventEnvelope<CheckoutPaymentRequested> event) {
        CheckoutPaymentRequested manifest = event.payload();
        sessions.lockKey("payment-group:" + manifest.orderGroupId());
        String identity = manifestIdentity(manifest);
        Optional<PaymentSession> previous = sessions.findLockedByOrderGroupId(manifest.orderGroupId());
        if (previous.isPresent()) {
            PaymentSession existing = previous.get();
            require(identity.equals(existing.getIdempotencyKey()), "Conflicting immutable payment manifest");
            verifyStoredPlan(existing, manifest);
            drainPendingCancellation(existing);
            refunds.applyPendingCancellations(manifest.orderGroupId());
            return;
        }

        PaymentSession session = new PaymentSession();
        session.setOrderGroupId(manifest.orderGroupId());
        session.setBuyerId(manifest.buyerId());
        session.setTotalAmount(manifest.totalAmount());
        session.setCurrency(manifest.currency());
        // Base-36 encodes all 128 group-ID bits, without truncation. One group has one lifetime session.
        session.setMemoCode("NX" + new BigInteger(manifest.orderGroupId().toString().replace("-", ""), 16)
                .toString(36).toUpperCase(Locale.ROOT));
        session.setDueAt(manifest.dueAt().atOffset(ZoneOffset.UTC));
        session.setSourceEventId(event.eventId());
        session.setIdempotencyKey(identity);
        PaymentSession storedSession = sessions.saveAndFlush(session);

        instructions.saveAll(manifest.orders().stream().map(order -> {
            PaymentInstruction instruction = new PaymentInstruction();
            instruction.setOrderId(order.orderId());
            instruction.setSession(storedSession);
            instruction.setBuyerId(manifest.buyerId());
            instruction.setSellerId(order.sellerId());
            instruction.setAmount(order.amount());
            instruction.setCurrency(manifest.currency());
            instruction.setDueAt(order.paymentDueAt().atOffset(ZoneOffset.UTC));
            instruction.setLastOrderVersion(order.orderVersion());
            instruction.setLastOrderEventAt(event.occurredAt().atOffset(ZoneOffset.UTC));
            return instruction;
        }).toList());

        // A cancellation that arrived before this manifest is applied now, in the
        // same transaction, so the group never exposes a QR after being cancelled.
        drainPendingCancellation(storedSession);
        refunds.applyPendingCancellations(manifest.orderGroupId());
    }

    private void verifyStoredPlan(PaymentSession session, CheckoutPaymentRequested manifest) {
        require(session.getBuyerId().equals(manifest.buyerId())
                && session.getCurrency().equals(manifest.currency())
                && session.getTotalAmount().compareTo(manifest.totalAmount()) == 0
                && session.getDueAt().toInstant().equals(manifest.dueAt()), "Stored session differs from manifest");
        var expected = manifest.orders().stream().collect(Collectors.toMap(
                CheckoutPaymentRequested.SellerOrder::orderId, Function.identity()));
        List<PaymentInstruction> plan = instructions.findSessionForUpdate(session.getId());
        require(plan.size() == expected.size(), "Stored instruction set is incomplete");
        for (PaymentInstruction instruction : plan) {
            var order = expected.get(instruction.getOrderId());
            require(order != null && instruction.getAmount().compareTo(order.amount()) == 0
                    && instruction.getBuyerId().equals(manifest.buyerId())
                    && instruction.getCurrency().equals(manifest.currency())
                    && instruction.getDueAt().toInstant().equals(order.paymentDueAt()),
                    "Stored instruction differs from manifest");
            require(instruction.getSellerId() == null || instruction.getSellerId().equals(order.sellerId()),
                    "Stored instruction seller differs from manifest");
            // A verified replay of the original immutable manifest can populate historical seller ownership.
            if (instruction.getSellerId() == null) instruction.setSellerId(order.sellerId());
        }
        // A replay must not reopen a canceled, expired or paid plan or overwrite its funding/version.
    }

    public void cancel(EventEnvelope<OrderGroupCancelled> event) {
        OrderGroupCancelled cancellation = event.payload();
        validate(cancellation);
        require(event.eventType().equals("OrderGroupCancelled"), "Unexpected event type");
        require(event.aggregateId().equals(cancellation.orderGroupId().toString()), "Group and aggregate must match");
        require(cancellation.orders().stream().map(OrderGroupCancelled.CancelledOrder::orderId).distinct().count()
                == cancellation.orders().size(), "Duplicate order in cancellation");
        consumer.handle(CANCELLATION_CONSUMER, event, this::cancelPlan);
    }

    private void cancelPlan(EventEnvelope<OrderGroupCancelled> event) {
        OrderGroupCancelled cancellation = event.payload();
        sessions.lockKey("payment-group:" + cancellation.orderGroupId());
        Optional<PaymentSession> existing = sessions.findLockedByOrderGroupId(cancellation.orderGroupId());
        if (existing.isEmpty()) {
            // The manifest has not arrived yet. Stash the cancellation (marker and
            // stash commit together) so the consumer can move on; createPlan
            // applies it when the manifest arrives. Never throw here: that would
            // retry forever and block every group behind this record.
            stashPendingCancellation(event);
            return;
        }
        PaymentSession session = existing.get();
        List<PaymentInstruction> plan = instructions.findSessionForUpdate(session.getId());
        var versions = cancellation.orders().stream().collect(Collectors.toMap(
                OrderGroupCancelled.CancelledOrder::orderId, OrderGroupCancelled.CancelledOrder::orderVersion));
        applyCancellation(session, plan, cancellation.buyerId(), cancellation.reason(), cancellation.actorId(),
                cancellation.cancelledAt().atOffset(ZoneOffset.UTC),
                event.occurredAt().atOffset(ZoneOffset.UTC), versions);
    }

    private void stashPendingCancellation(EventEnvelope<OrderGroupCancelled> event) {
        OrderGroupCancelled cancellation = event.payload();
        Optional<PaymentPendingCancellation> previous =
                pendingCancellations.findLockedByOrderGroupId(cancellation.orderGroupId());
        if (previous.isPresent() && previous.get().getGroupVersion() >= cancellation.groupVersion()) {
            return;
        }
        PaymentPendingCancellation pending = previous.orElseGet(PaymentPendingCancellation::new);
        pending.setOrderGroupId(cancellation.orderGroupId());
        pending.setBuyerId(cancellation.buyerId());
        pending.setGroupVersion(cancellation.groupVersion());
        pending.setReason(cancellation.reason());
        pending.setActorId(cancellation.actorId());
        pending.setCancelledAt(cancellation.cancelledAt().atOffset(ZoneOffset.UTC));
        pending.setOccurredAt(event.occurredAt().atOffset(ZoneOffset.UTC));
        pending.setOrders(cancellation.orders().stream()
                .map(order -> Map.of("orderId", order.orderId().toString(),
                        "orderVersion", Long.toString(order.orderVersion())))
                .toList());
        pending.setSourceEventId(event.eventId());
        pendingCancellations.save(pending);
    }

    private void drainPendingCancellation(PaymentSession session) {
        Optional<PaymentPendingCancellation> stashed =
                pendingCancellations.findLockedByOrderGroupId(session.getOrderGroupId());
        if (stashed.isEmpty()) {
            return;
        }
        PaymentPendingCancellation pending = stashed.get();
        Map<UUID, Long> versions = pending.getOrders().stream().collect(Collectors.toMap(
                order -> UUID.fromString(order.get("orderId")),
                order -> Long.parseLong(order.get("orderVersion"))));
        List<PaymentInstruction> plan = instructions.findSessionForUpdate(session.getId());
        applyCancellation(session, plan, pending.getBuyerId(), pending.getReason(), pending.getActorId(),
                pending.getCancelledAt(), pending.getOccurredAt(), versions);
        pendingCancellations.delete(pending);
    }

    private void applyCancellation(PaymentSession session, List<PaymentInstruction> plan, UUID buyerId,
                                   String reason, UUID actorId, OffsetDateTime cancelledAt,
                                   OffsetDateTime eventAt, Map<UUID, Long> versions) {
        require(session.getBuyerId().equals(buyerId), "Cancellation buyer must match");
        require(!plan.isEmpty() && plan.size() == versions.size()
                && plan.stream().allMatch(instruction -> versions.containsKey(instruction.getOrderId())),
                "Cancellation must cover the complete payment plan");
        if (plan.stream().anyMatch(instruction -> versions.get(instruction.getOrderId()) < instruction.getLastOrderVersion())) {
            return;
        }
        // TIỀN VỀ TRƯỚC KHI XỬ LÝ HỦY NHÓM -> GIỮ SESSION PAID, HOÀN TIỀN TỪNG ĐƠN ĐÃ CÓ TIỀN
        if (session.getStatus() == PaymentSessionStatus.PAID) {
            refunds.refundFundedPlan(plan, versions, reason, actorId, cancelledAt, eventAt);
            return;
        }
        if (session.getStatus() != PaymentSessionStatus.PENDING) {
            return;
        }
        require(plan.stream().allMatch(instruction -> instruction.getStatus() == PaymentInstructionStatus.PENDING
                && instruction.getFundedTransaction() == null), "Cannot cancel inconsistent funding evidence");
        boolean expired = "PAYMENT_DEADLINE_EXPIRED".equals(reason);
        session.setStatus(expired ? PaymentSessionStatus.EXPIRED : PaymentSessionStatus.CANCELED);
        session.setUpdatedBy(actorId);
        for (PaymentInstruction instruction : plan) {
            instruction.setStatus(expired ? PaymentInstructionStatus.EXPIRED : PaymentInstructionStatus.CANCELED);
            instruction.setCanceledAt(cancelledAt);
            instruction.setLastOrderVersion(versions.get(instruction.getOrderId()));
            instruction.setLastOrderEventAt(eventAt);
            instruction.setUpdatedBy(actorId);
        }
    }

    private void validate(Object payload) {
        require(validator.validate(payload).isEmpty(), "Invalid payment event payload");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static String manifestIdentity(CheckoutPaymentRequested manifest) {
        StringBuilder canonical = new StringBuilder().append(manifest.orderGroupId()).append('|')
                .append(manifest.buyerId()).append('|').append(manifest.currency()).append('|')
                .append(manifest.totalAmount().setScale(2).toPlainString()).append('|').append(manifest.dueAt());
        manifest.orders().stream().sorted(Comparator.comparing(CheckoutPaymentRequested.SellerOrder::orderId))
                .forEach(order -> canonical.append('|').append(order.orderId()).append('|').append(order.sellerId())
                        .append('|').append(order.amount().setScale(2).toPlainString()).append('|')
                        .append(order.paymentDueAt()).append('|').append(order.orderVersion()));
        try {
            return "checkout:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
