package com.group5.paymentservice.payment;

import com.group5.paymentservice.payment.event.PaymentSessionEventConsumer;
import com.group5.paymentservice.payment.job.PendingOrderCancellationJob;
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
    @Autowired private PendingOrderCancellationJob job;
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
        // Hoàn tiền tự động cũng bổ sung người nhận từ dữ liệu Payment đã lưu.
        assertThat(payload.get("buyerId").asString()).isEqualTo(group.buyerId().toString());
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
    void theJobRefundsAPendingCancellationOnlyAfterFundingAndThenRemovesIt() {
        UUID orderId = fixtures.unpaidOrder();
        UUID groupId = fixtures.groupOf(orderId);
        fixtures.traced(() -> listener.receive(fixtures.record(
                fixtures.cancelled(orderId, groupId, UUID.randomUUID(), "100000.00"))));

        fixtures.traced(job::applyFundedCancellations);
        assertThat(fixtures.pending(orderId)).isEqualTo(1);
        assertThat(fixtures.refunds(orderId)).isZero();

        fixtures.fund(orderId);
        fixtures.traced(job::applyFundedCancellations);
        fixtures.traced(job::applyFundedCancellations);

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
    void aGroupCancellationAfterFundingWonRefundsEveryPaidOrderOnce() {
        var manifest = fixtures.manifest();
        UUID groupId = manifest.orderGroupId();
        UUID big = manifest.orders().get(0).orderId();
        UUID small = manifest.orders().get(1).orderId();
        fixtures.traced(() -> listener.receive(fixtures.record(fixtures.checkout(manifest))));
        var early = fixtures.cancelled(big, groupId, manifest.buyerId(), "0.00");
        fixtures.traced(() -> listener.receive(fixtures.record(early)));
        assertThat(fixtures.marker(early.eventId())).isEqualTo(1);
        assertThat(fixtures.refunds(big)).isZero();
        assertThat(fixtures.pending(big)).isZero();

        fixtures.fundGroup(groupId);
        var group = fixtures.groupCancelled(manifest);
        fixtures.traced(() -> listener.receive(fixtures.record(group)));

        assertThat(fixtures.marker(group.eventId())).isEqualTo(1);
        assertThat(fixtures.sessionStatus(groupId)).isEqualTo("PAID");
        assertThat((BigDecimal) fixtures.refund(big).get("amount")).isEqualByComparingTo(RefundFixtures.BIG);
        assertThat((BigDecimal) fixtures.refund(small).get("amount")).isEqualByComparingTo(RefundFixtures.SMALL);
        assertThat(fixtures.refund(big).get("idempotency_key")).isEqualTo("system:cancel-refund:" + big);
        assertThat(fixtures.outbox("OrderRefundRequested", big)).isEqualTo(1);
        assertThat(fixtures.outbox("OrderRefundRequested", small)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT status::text AS status, canceled_at IS NOT NULL AS cancelled, last_order_version
                FROM payment_instructions WHERE order_id IN (?, ?)
                """, big, small)).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("PAID");
            assertThat(row.get("cancelled")).isEqualTo(true);
            assertThat(row.get("last_order_version")).isEqualTo(3L);
        });

        fixtures.traced(() -> listener.receive(fixtures.record(fixtures.groupCancelled(manifest))));
        fixtures.traced(() -> listener.receive(fixtures.record(
                fixtures.cancelled(small, groupId, manifest.buyerId(), "0.00"))));
        fixtures.traced(job::applyFundedCancellations);

        assertThat(fixtures.refunds(big)).isEqualTo(1);
        assertThat(fixtures.refunds(small)).isEqualTo(1);
        assertThat(fixtures.outbox("OrderRefundRequested", big)).isEqualTo(1);
        assertThat(fixtures.outbox("OrderRefundRequested", small)).isEqualTo(1);
    }

    @Test
    void aGroupCancellationBeforeFundingClosesThePlanWithoutARefund() {
        var manifest = fixtures.manifest();
        UUID groupId = manifest.orderGroupId();
        fixtures.traced(() -> listener.receive(fixtures.record(fixtures.checkout(manifest))));
        for (var order : manifest.orders()) {
            fixtures.traced(() -> listener.receive(fixtures.record(
                    fixtures.cancelled(order.orderId(), groupId, manifest.buyerId(), "0.00"))));
        }

        fixtures.traced(() -> listener.receive(fixtures.record(fixtures.groupCancelled(manifest))));

        assertThat(fixtures.sessionStatus(groupId)).isEqualTo("CANCELED");
        for (var order : manifest.orders()) {
            assertThat(fixtures.refunds(order.orderId())).isZero();
            assertThat(fixtures.pending(order.orderId())).isZero();
            assertThat(fixtures.outbox("OrderRefundRequested", order.orderId())).isZero();
        }
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
