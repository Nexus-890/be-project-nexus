package com.group5.paymentservice.payment.repository;

import com.group5.common_events.EventEnvelope;
import com.group5.paymentservice.payment.event.payload.OrderCompleted;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PayoutEligibilityRepository {
    public record CompletionCursor(UUID orderId, OffsetDateTime recordedAt) { }

    private static final String FUNDED_ORDER_CONDITIONS = """
              AND i.status='PAID' AND s.status='PAID' AND i.canceled_at IS NULL
              AND i.paid_at IS NOT NULL AND i.paid_at<=c.completed_at AND i.amount>0
              AND i.buyer_id=c.buyer_id AND s.buyer_id=c.buyer_id AND i.seller_id=c.seller_id
              AND s.order_group_id=c.order_group_id AND i.last_order_version<=c.order_version
              AND t.status='SUCCESS' AND t.type='NORMAL' AND t.session_id=s.id
              AND t.currency=i.currency AND s.currency=i.currency
              AND NOT EXISTS(SELECT 1 FROM refunds r WHERE r.order_id=c.order_id)
              AND NOT EXISTS(SELECT 1 FROM payment_pending_order_cancellations p WHERE p.order_id=c.order_id)
              AND NOT EXISTS(SELECT 1 FROM payment_pending_cancellations p WHERE p.order_group_id=c.order_group_id)
            """;
    private static final String PENDING_COMPLETIONS = """
            SELECT c.order_id,c.recorded_at FROM payment_order_completions c
            JOIN payment_instructions i ON i.order_id=c.order_id
            JOIN payment_sessions s ON s.id=i.session_id
            JOIN payment_transactions t ON t.id=i.funded_transaction_id
            LEFT JOIN payout_eligibility e ON e.order_id=c.order_id
            LEFT JOIN payouts p ON p.order_id=c.order_id
            WHERE (e.order_id IS NULL OR (p.id IS NULL AND c.order_amount IS NOT NULL))
              AND (c.order_amount IS NULL OR (c.order_amount=i.amount AND c.order_amount>c.shipping_fee))
            """ + FUNDED_ORDER_CONDITIONS;
    private final JdbcTemplate jdbc;

    public void recordCompletion(EventEnvelope<OrderCompleted> event) {
        OrderCompleted order = event.payload();
        jdbc.update("""
                INSERT INTO payment_order_completions(order_id,order_group_id,buyer_id,seller_id,order_version,
                    completed_at,order_number,source_event_id,order_amount,shipping_fee)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(order_id) DO NOTHING
                """, order.orderId(), order.orderGroupId(), order.buyerId(), order.sellerId(), order.orderVersion(),
                order.completedAt().atOffset(ZoneOffset.UTC), order.orderNumber(), event.eventId(),
                decimalOrNull(order.orderAmount()), decimalOrNull(order.shippingFee()));
    }

    public boolean matchesCompletion(OrderCompleted order) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT order_group_id=? AND buyer_id=? AND seller_id=? AND order_version=?
                    AND completed_at=? AND order_number=? AND order_amount IS NOT DISTINCT FROM ?::numeric
                    AND shipping_fee IS NOT DISTINCT FROM ?::numeric
                FROM payment_order_completions WHERE order_id=?
                """, Boolean.class, order.orderGroupId(), order.buyerId(), order.sellerId(), order.orderVersion(),
                order.completedAt().atOffset(ZoneOffset.UTC), order.orderNumber(), order.orderAmount(),
                order.shippingFee(), order.orderId()));
    }

    public List<UUID> findPendingOrderIds() {
        return jdbc.query(PENDING_COMPLETIONS + " ORDER BY c.recorded_at,c.order_id LIMIT 100",
                (rs, row) -> rs.getObject("order_id", UUID.class));
    }

    public Optional<CompletionCursor> findPendingUpperBound() {
        return jdbc.query(PENDING_COMPLETIONS + " ORDER BY c.recorded_at DESC,c.order_id DESC LIMIT 1",
                (rs, row) -> new CompletionCursor(rs.getObject("order_id", UUID.class),
                        rs.getObject("recorded_at", OffsetDateTime.class))).stream().findFirst();
    }

    // Cursor dựa trên khóa bất biến, không phụ thuộc bản ghi còn ở hàng đợi sau khi xử lý hay không.
    public List<CompletionCursor> findPendingBatch(CompletionCursor after, CompletionCursor upperBound) {
        String query = PENDING_COMPLETIONS + " AND (c.recorded_at,c.order_id)<=(?,?)";
        List<Object> parameters = new ArrayList<>(List.of(upperBound.recordedAt(), upperBound.orderId()));
        if (after != null) {
            query += " AND (c.recorded_at,c.order_id)>(?,?)";
            parameters.add(after.recordedAt());
            parameters.add(after.orderId());
        }
        return jdbc.query(query + " ORDER BY c.recorded_at,c.order_id LIMIT 100",
                (rs, row) -> new CompletionCursor(rs.getObject("order_id", UUID.class),
                        rs.getObject("recorded_at", OffsetDateTime.class)), parameters.toArray());
    }

    public Optional<UUID> findOrderGroup(UUID orderId) {
        return jdbc.queryForList("SELECT order_group_id FROM payment_order_completions WHERE order_id=?",
                UUID.class, orderId).stream().findFirst();
    }

    public void recordEligibility(UUID orderId) {
        jdbc.update("""
                INSERT INTO payout_eligibility(order_id,seller_id,buyer_id,order_group_id,funded_transaction_id,
                    amount,currency,completed_at,source_event_id)
                SELECT c.order_id,c.seller_id,c.buyer_id,c.order_group_id,i.funded_transaction_id,
                    i.amount,i.currency,c.completed_at,c.source_event_id
                FROM payment_order_completions c
                JOIN payment_instructions i ON i.order_id=c.order_id
                JOIN payment_sessions s ON s.id=i.session_id
                JOIN payment_transactions t ON t.id=i.funded_transaction_id
                WHERE c.order_id=?
                """ + FUNDED_ORDER_CONDITIONS + " ON CONFLICT(order_id) DO NOTHING", orderId);
    }

    public boolean existsForOrder(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM payout_eligibility WHERE order_id=?)", Boolean.class, orderId));
    }

    private static BigDecimal decimalOrNull(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
