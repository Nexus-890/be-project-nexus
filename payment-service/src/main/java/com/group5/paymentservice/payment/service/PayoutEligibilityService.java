package com.group5.paymentservice.payment.service;

import com.group5.common_events.EventEnvelope;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.paymentservice.payment.event.payload.OrderCompleted;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Prepares eligibility for #261. No bank transfer or payout instruction is executed here. */
@Service
@RequiredArgsConstructor
public class PayoutEligibilityService {
    public static final String CONSUMER = "payment-completed-payout-eligibility";
    private final IdempotentConsumer consumer;
    private final PaymentSessionRepository sessions;
    private final JdbcTemplate jdbc;
    private final Validator validator;

    public void completed(EventEnvelope<OrderCompleted> event) {
        OrderCompleted order = event.payload();
        require("OrderCompleted".equals(event.eventType()) && validator.validate(order).isEmpty(), "Invalid completed order");
        require(event.aggregateId().equals(order.orderId().toString()) && !order.buyerId().equals(order.sellerId()),
                "Completion identity mismatch");
        require("STANDARD".equals(order.orderType()) ? order.referenceId() == null
                : "AUCTION".equals(order.orderType()) && order.referenceId() != null, "Invalid completion order type");
        consumer.handle(CONSUMER, event, this::record);
    }

    private void record(EventEnvelope<OrderCompleted> event) {
        OrderCompleted order = event.payload();
        sessions.lockKey("payout-order:" + order.orderId());
        jdbc.update("""
                INSERT INTO payment_order_completions(order_id,order_group_id,buyer_id,seller_id,order_version,
                                                      completed_at,order_number,source_event_id)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(order_id) DO NOTHING
                """, order.orderId(), order.orderGroupId(), order.buyerId(), order.sellerId(), order.orderVersion(),
                order.completedAt().atOffset(ZoneOffset.UTC), order.orderNumber(), event.eventId());
        boolean same = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT order_group_id=? AND buyer_id=? AND seller_id=? AND order_version=?
                    AND completed_at=? AND order_number=? FROM payment_order_completions WHERE order_id=?
                """, Boolean.class, order.orderGroupId(), order.buyerId(), order.sellerId(), order.orderVersion(),
                order.completedAt().atOffset(ZoneOffset.UTC), order.orderNumber(), order.orderId()));
        require(same, "Conflicting immutable order completion");
        qualify(order.orderId());
    }

    @Transactional
    public void retry(UUID orderId) {
        sessions.lockKey("payout-order:" + orderId);
        qualify(orderId);
    }

    public List<UUID> pendingIds() {
        return jdbc.queryForList("""
                SELECT c.order_id FROM payment_order_completions c
                JOIN payment_instructions i ON i.order_id=c.order_id
                JOIN payment_sessions s ON s.id=i.session_id
                JOIN payment_transactions t ON t.id=i.funded_transaction_id
                LEFT JOIN payout_eligibility e ON e.order_id=c.order_id
                WHERE e.order_id IS NULL AND i.status='PAID' AND s.status='PAID' AND i.canceled_at IS NULL
                  AND i.paid_at IS NOT NULL AND i.paid_at<=c.completed_at AND i.amount>0
                  AND i.buyer_id=c.buyer_id AND s.buyer_id=c.buyer_id AND i.seller_id=c.seller_id
                  AND s.order_group_id=c.order_group_id AND i.last_order_version<=c.order_version
                  AND t.status='SUCCESS' AND t.type='NORMAL' AND t.session_id=s.id
                  AND t.currency=i.currency AND s.currency=i.currency
                  AND NOT EXISTS(SELECT 1 FROM refunds r WHERE r.order_id=c.order_id)
                  AND NOT EXISTS(SELECT 1 FROM payment_pending_order_cancellations p WHERE p.order_id=c.order_id)
                  AND NOT EXISTS(SELECT 1 FROM payment_pending_cancellations p WHERE p.order_group_id=c.order_group_id)
                ORDER BY c.recorded_at,c.order_id LIMIT 100
                """, UUID.class);
    }

    private void qualify(UUID orderId) {
        var group = jdbc.queryForList("SELECT order_group_id FROM payment_order_completions WHERE order_id=?", UUID.class, orderId);
        if (group.isEmpty()) return;
        // Same group lock as funding/cancellation/refunds prevents observing half a financial transition.
        sessions.lockKey("payment-group:" + group.getFirst());
        jdbc.update("""
                INSERT INTO payout_eligibility(order_id,seller_id,buyer_id,order_group_id,funded_transaction_id,
                                               amount,currency,completed_at,source_event_id)
                SELECT c.order_id,c.seller_id,c.buyer_id,c.order_group_id,i.funded_transaction_id,
                       i.amount,i.currency,c.completed_at,c.source_event_id
                FROM payment_order_completions c
                JOIN payment_instructions i ON i.order_id=c.order_id
                JOIN payment_sessions s ON s.id=i.session_id
                JOIN payment_transactions t ON t.id=i.funded_transaction_id
                WHERE c.order_id=? AND i.status='PAID' AND s.status='PAID' AND i.canceled_at IS NULL
                  AND i.paid_at IS NOT NULL AND i.paid_at<=c.completed_at AND i.amount>0
                  AND i.buyer_id=c.buyer_id AND s.buyer_id=c.buyer_id AND i.seller_id=c.seller_id
                  AND s.order_group_id=c.order_group_id AND i.last_order_version<=c.order_version
                  AND t.status='SUCCESS' AND t.type='NORMAL' AND t.session_id=s.id
                  AND t.currency=i.currency AND s.currency=i.currency
                  AND NOT EXISTS(SELECT 1 FROM refunds r WHERE r.order_id=c.order_id)
                  AND NOT EXISTS(SELECT 1 FROM payment_pending_order_cancellations p WHERE p.order_id=c.order_id)
                  AND NOT EXISTS(SELECT 1 FROM payment_pending_cancellations p WHERE p.order_group_id=c.order_group_id)
                ON CONFLICT(order_id) DO NOTHING
                """, orderId);
    }

    private static void require(boolean condition, String detail) { if (!condition) throw new IllegalArgumentException(detail); }
}
