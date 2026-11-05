package com.group5.paymentservice.payment;

import com.group5.paymentservice.payment.event.PaymentSessionEventConsumer;
import com.group5.paymentservice.payment.service.RefundService;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RefundCancellationTest extends PaymentDatabaseTestSupport {
    @Autowired private PaymentSessionEventConsumer listener;
    @Autowired private RefundService refunds;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper mapper;
    @Autowired private Tracer tracer;
    private RefundFixtures fixtures;

    @BeforeEach
    void setup() {
        fixtures = new RefundFixtures(jdbc, mapper, tracer);
    }

    @Test
    void cancellingAPaidOrderRequestsARefundForThatOrderOnly() {
        var group = fixtures.paidGroup();
        var event = fixtures.cancelled(group.bigOrder(), group.groupId(), group.buyerId(), RefundFixtures.BIG);

        fixtures.traced(() -> listener.receive(fixtures.record(event)));

        var refund = fixtures.refund(group.bigOrder());
        assertThat((BigDecimal) refund.get("amount")).isEqualByComparingTo(RefundFixtures.BIG);
        assertThat(refund.get("status")).isEqualTo("REQUESTED");
        assertThat(refund.get("idempotency_key")).isEqualTo("system:cancel-refund:" + group.bigOrder());
        assertThat(refund.get("requested_by")).isEqualTo(new UUID(0, 0));
        assertThat(refund.get("version")).isEqualTo(0L);
        assertThat(fixtures.refunds(group.smallOrder())).isZero();
        assertThat(fixtures.outbox("OrderRefundRequested", group.bigOrder())).isEqualTo(1);

        var payload = fixtures.lastPayload("OrderRefundRequested", group.bigOrder());
        assertThat(payload.get("refundId").asString()).isEqualTo(refund.get("id").toString());
        assertThat(payload.get("orderGroupId").asString()).isEqualTo(group.groupId().toString());
        assertThat(payload.get("sessionId").asString()).isEqualTo(group.sessionId().toString());
        assertThat(payload.get("incomingTransactionId").asString()).isEqualTo(group.transactionId().toString());
        assertThat(payload.get("amount").asString()).isEqualTo(RefundFixtures.BIG);
        assertThat(payload.get("status").asString()).isEqualTo("REQUESTED");
        assertThat(payload.get("refundVersion").asLong()).isZero();
        assertThat(payload.get("requestedAt").asString()).endsWith("Z");
        assertThat(jdbc.queryForObject("SELECT canceled_at IS NOT NULL FROM payment_instructions WHERE order_id = ?",
                Boolean.class, group.bigOrder())).isTrue();
        assertThat(jdbc.queryForObject("SELECT status::text FROM payment_instructions WHERE order_id = ?",
                String.class, group.bigOrder())).isEqualTo("PAID");
    }

    @Test
    void theSameCancellationDeliveredAgainWithANewEventIdKeepsOneRefund() {
        var group = fixtures.paidGroup();
        fixtures.traced(() -> listener.receive(fixtures.record(
                fixtures.cancelled(group.bigOrder(), group.groupId(), group.buyerId(), RefundFixtures.BIG))));
        var first = fixtures.refund(group.bigOrder());

        var again = fixtures.cancelled(group.bigOrder(), group.groupId(), group.buyerId(), RefundFixtures.BIG);
        fixtures.traced(() -> listener.receive(fixtures.record(again)));
        fixtures.traced(() -> listener.receive(fixtures.record(again)));

        assertThat(fixtures.refunds(group.bigOrder())).isEqualTo(1);
        assertThat(fixtures.refund(group.bigOrder())).isEqualTo(first);
        assertThat(fixtures.outbox("OrderRefundRequested", group.bigOrder())).isEqualTo(1);
        assertThat(fixtures.marker(again.eventId())).isEqualTo(1);
    }

    @Test
    void anUnpaidInstructionGetsNoRefundAndTheCancellationWaitsForFunding() {
        UUID orderId = fixtures.unpaidOrder();
        var event = fixtures.cancelled(orderId, UUID.randomUUID(), UUID.randomUUID(), "100000.00");

        fixtures.traced(() -> listener.receive(fixtures.record(event)));

        assertThat(fixtures.refunds(orderId)).isZero();
        assertThat(fixtures.outbox("OrderRefundRequested", orderId)).isZero();
        assertThat(fixtures.marker(event.eventId())).isEqualTo(1);
        assertThat(fixtures.pending(orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT order_group_id FROM payment_pending_order_cancellations WHERE order_id = ?",
                UUID.class, orderId)).isEqualTo(fixtures.groupOf(orderId));
    }

    @Test
    void aPaidOrderWithoutAnInstructionIsStoredAndAcknowledged() {
        UUID orderId = UUID.randomUUID();
        var event = fixtures.cancelled(orderId, UUID.randomUUID(), UUID.randomUUID(), RefundFixtures.BIG);

        fixtures.traced(() -> listener.receive(fixtures.record(event)));
        fixtures.traced(() -> listener.receive(fixtures.record(event)));

        assertThat(fixtures.marker(event.eventId())).isEqualTo(1);
        assertThat(fixtures.pending(orderId)).isEqualTo(1);
        assertThat(fixtures.refunds(orderId)).isZero();
        var stored = jdbc.queryForMap("SELECT * FROM payment_pending_order_cancellations WHERE order_id = ?", orderId);
        assertThat(stored.get("order_group_id")).isEqualTo(event.payload().orderGroupId());
        assertThat(stored.get("order_version")).isEqualTo(3L);
        assertThat(stored.get("source_event_id")).isEqualTo(event.eventId());
        assertThat((BigDecimal) stored.get("paid_amount")).isEqualByComparingTo(RefundFixtures.BIG);
    }

    @Test
    void aPendingCancellationIsRefundedOnlyAfterFundingAndThenRemoved() {
        UUID orderId = fixtures.unpaidOrder();
        UUID groupId = fixtures.groupOf(orderId);
        fixtures.traced(() -> listener.receive(fixtures.record(
                fixtures.cancelled(orderId, groupId, UUID.randomUUID(), "100000.00"))));

        fixtures.traced(() -> refunds.applyPendingCancellations(groupId));
        assertThat(fixtures.pending(orderId)).isEqualTo(1);
        assertThat(fixtures.refunds(orderId)).isZero();

        fixtures.fund(orderId);
        fixtures.traced(() -> refunds.applyPendingCancellations(groupId));
        fixtures.traced(() -> refunds.applyPendingCancellations(groupId));

        assertThat(fixtures.pending(orderId)).isZero();
        assertThat(fixtures.refunds(orderId)).isEqualTo(1);
        var refund = fixtures.refund(orderId);
        assertThat(refund.get("status")).isEqualTo("REQUESTED");
        assertThat(refund.get("idempotency_key")).isEqualTo("system:cancel-refund:" + orderId);
        assertThat((BigDecimal) refund.get("amount")).isEqualByComparingTo("100000.00");
        assertThat(fixtures.outbox("OrderRefundRequested", orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT canceled_at IS NOT NULL FROM payment_instructions WHERE order_id = ?",
                Boolean.class, orderId)).isTrue();
    }

    @Test
    void anUnpaidOrderWithoutAnInstructionIsSkipped() {
        UUID orderId = UUID.randomUUID();
        var event = fixtures.cancelled(orderId, UUID.randomUUID(), UUID.randomUUID(), "0.00");

        fixtures.traced(() -> listener.receive(fixtures.record(event)));

        assertThat(fixtures.marker(event.eventId())).isEqualTo(1);
        assertThat(fixtures.refunds(orderId)).isZero();
    }
}
