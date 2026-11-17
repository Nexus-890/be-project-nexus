package com.group5.paymentservice.payment.service;

import com.group5.common_events.EventEnvelope;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.paymentservice.payment.event.payload.OrderCompleted;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import com.group5.paymentservice.payment.repository.PayoutEligibilityRepository;
import com.group5.paymentservice.payment.repository.PayoutEligibilityRepository.CompletionCursor;
import com.group5.paymentservice.payout.PayoutService;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PayoutEligibilityService {
    public static final String CONSUMER = "payment-completed-payout-eligibility";
    private final IdempotentConsumer consumer;
    private final PaymentSessionRepository sessions;
    private final PayoutEligibilityRepository eligibility;
    private final Validator validator;
    private final PayoutService payouts;

    /** Ghi nhận hoàn tất, dấu chống trùng và payout trong cùng một giao dịch local. */
    public void completed(EventEnvelope<OrderCompleted> event) {
        OrderCompleted order = event.payload();
        require("OrderCompleted".equals(event.eventType()) && validator.validate(order).isEmpty(), "Invalid completed order");
        require(event.aggregateId().equals(order.orderId().toString()) && !order.buyerId().equals(order.sellerId()),
                "Completion identity mismatch");
        require("STANDARD".equals(order.orderType()) ? order.referenceId() == null
                : "AUCTION".equals(order.orderType()) && order.referenceId() != null, "Invalid completion order type");
        require((order.orderAmount() == null) == (order.shippingFee() == null), "Incomplete completion amounts");
        if (order.orderAmount() != null) {
            require(order.orderAmount().matches("[0-9]{1,17}(\\.[0-9]{1,2})?")
                    && order.shippingFee().matches("[0-9]{1,17}(\\.[0-9]{1,2})?"), "Invalid decimal money fields");
            require(new BigDecimal(order.orderAmount()).signum() > 0, "Invalid order amount");
        }
        consumer.handle(CONSUMER, event, this::record);
    }

    private void record(EventEnvelope<OrderCompleted> event) {
        OrderCompleted order = event.payload();
        sessions.lockKey("payout-order:" + order.orderId());
        eligibility.recordCompletion(event);
        require(eligibility.matchesCompletion(order), "Conflicting immutable order completion");
        qualify(order.orderId());
    }

    @Transactional
    public void retry(UUID orderId) {
        sessions.lockKey("payout-order:" + orderId);
        qualify(orderId);
    }

    @Transactional(readOnly = true)
    public List<UUID> pendingIds() {
        return eligibility.findPendingOrderIds();
    }

    @Transactional(readOnly = true)
    public Optional<CompletionCursor> pendingUpperBound() {
        return eligibility.findPendingUpperBound();
    }

    @Transactional(readOnly = true)
    public List<CompletionCursor> pendingBatch(CompletionCursor after, CompletionCursor upperBound) {
        return eligibility.findPendingBatch(after, upperBound);
    }

    private void qualify(UUID orderId) {
        var group = eligibility.findOrderGroup(orderId);
        if (group.isEmpty()) return;
        // Dùng cùng khóa với funding và refund để không đọc dở một thay đổi tài chính.
        sessions.lockKey("payment-group:" + group.get());
        eligibility.recordEligibility(orderId);
        payouts.createEligible(orderId);
        if (!eligibility.existsForOrder(orderId)) {
            log.warn("Payout not eligible: orderId={} reason=unfunded_refunded_or_identity_mismatch", orderId);
        }
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalArgumentException(detail);
    }
}
