package com.group5.paymentservice.payment;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.Topics;
import com.group5.paymentservice.payment.event.payload.OrderCancelled;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

final class RefundFixtures {
    static final String BIG = "300000.00";
    static final String SMALL = "200000.00";

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final Tracer tracer;

    RefundFixtures(JdbcTemplate jdbc, JsonMapper mapper, Tracer tracer) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tracer = tracer;
    }

    void traced(Runnable action) {
        traced(() -> {
            action.run();
            return null;
        });
    }

    <T> T traced(Supplier<T> action) {
        Span span = tracer.nextSpan().name("refund-test").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return action.get();
        } finally {
            span.end();
        }
    }

    record PaidGroup(UUID groupId, UUID buyerId, UUID sessionId, UUID transactionId, UUID bigOrder, UUID smallOrder) {
    }

    PaidGroup paidGroup() {
        UUID groupId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID sessionId = jdbc.queryForObject("""
                INSERT INTO payment_sessions (order_group_id, buyer_id, total_amount, currency, memo_code, due_at,
                                              status, source_event_id, idempotency_key, paid_at)
                VALUES (?, ?, 500000.00, 'VND', ?, now() + interval '15 minutes', 'PAID', ?, ?, now())
                RETURNING id
                """, UUID.class, groupId, buyerId, "NX" + Long.toString(Math.abs(groupId.getMostSignificantBits()), 36)
                .toUpperCase(), UUID.randomUUID(), "checkout:" + groupId);
        UUID transactionId = jdbc.queryForObject("""
                INSERT INTO payment_transactions (session_id, provider, txn_reference, amount, currency, status, type,
                                                  idempotency_key, received_at)
                VALUES (?, 'SEPAY', ?, 500000.00, 'VND', 'SUCCESS', 'NORMAL', ?, now())
                RETURNING id
                """, UUID.class, sessionId, "FT" + groupId, "sepay:" + groupId);
        UUID bigOrder = instruction(sessionId, buyerId, BIG, "PAID", transactionId);
        UUID smallOrder = instruction(sessionId, buyerId, SMALL, "PAID", transactionId);
        return new PaidGroup(groupId, buyerId, sessionId, transactionId, bigOrder, smallOrder);
    }

    UUID unpaidOrder() {
        UUID groupId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID sessionId = jdbc.queryForObject("""
                INSERT INTO payment_sessions (order_group_id, buyer_id, total_amount, currency, memo_code, due_at,
                                              source_event_id, idempotency_key)
                VALUES (?, ?, 100000.00, 'VND', ?, now() + interval '15 minutes', ?, ?)
                RETURNING id
                """, UUID.class, groupId, buyerId, "NX" + Long.toString(Math.abs(groupId.getMostSignificantBits()), 36)
                .toUpperCase(), UUID.randomUUID(), "checkout:" + groupId);
        return instruction(sessionId, buyerId, "100000.00", "PENDING", null);
    }

    private UUID instruction(UUID sessionId, UUID buyerId, String amount, String status, UUID funded) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_instructions (order_id, session_id, funded_transaction_id, buyer_id, amount,
                                                  currency, due_at, status, paid_at)
                VALUES (?, ?, ?, ?, ?, 'VND', now() + interval '15 minutes', CAST(? AS payment_instruction_status), ?)
                """, orderId, sessionId, funded, buyerId, new BigDecimal(amount), status,
                funded == null ? null : java.sql.Timestamp.from(Instant.now()));
        return orderId;
    }

    EventEnvelope<OrderCancelled> cancelled(UUID orderId, UUID groupId, UUID buyerId, String paidAmount) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OrderCancelled payload = new OrderCancelled(orderId, buyerId, "NX-20261104-0001", "STANDARD", null, now,
                "Buyer changed their mind", groupId, UUID.randomUUID(), 3L, paidAmount, "VND", buyerId);
        return new EventEnvelope<>(UUID.randomUUID(), "OrderCancelled", orderId.toString(), now, "refund-test", payload);
    }

    ConsumerRecord<String, EventEnvelope<JsonNode>> record(EventEnvelope<?> envelope) {
        var event = new EventEnvelope<JsonNode>(envelope.eventId(), envelope.eventType(), envelope.aggregateId(),
                envelope.occurredAt(), envelope.traceId(), mapper.valueToTree(envelope.payload()));
        return new ConsumerRecord<>(Topics.COMMERCE, 0, 0L, event.aggregateId(), event);
    }

    Map<String, Object> refund(UUID orderId) {
        return jdbc.queryForMap("""
                SELECT id, order_id, amount, status::text AS status, idempotency_key, requested_by, version,
                       completed_by, completed_transaction_id
                FROM refunds WHERE order_id = ?
                """, orderId);
    }

    int refunds(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM refunds WHERE order_id = ?", Integer.class, orderId);
    }

    int outbox(String eventType, UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, eventType, orderId.toString());
    }

    JsonNode lastPayload(String eventType, UUID orderId) {
        String payload = jdbc.queryForObject("""
                SELECT payload::text FROM outbox WHERE event_type = ? AND aggregate_id = ?
                ORDER BY occurred_at DESC LIMIT 1
                """, String.class, eventType, orderId.toString());
        return mapper.readTree(payload).get("payload");
    }

    int marker(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
    }
}
