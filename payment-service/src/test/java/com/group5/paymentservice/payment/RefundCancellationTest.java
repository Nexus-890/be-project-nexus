package com.group5.paymentservice.payment;

import com.group5.paymentservice.payment.event.PaymentSessionEventConsumer;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundCancellationTest extends PaymentDatabaseTestSupport {
    @Autowired private PaymentSessionEventConsumer listener;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper mapper;
    @Autowired private Tracer tracer;
    private RefundFixtures fixtures;

    @BeforeEach
    void setup() {
        fixtures = new RefundFixtures(jdbc, mapper, tracer);
    }

    @Test
    void cancellingAPaidOrderMarksOnlyThatOrderRefundableAndCreatesNoRefund() {
        var group = fixtures.paidGroup();
        var event = fixtures.cancelled(group.bigOrder(), group.groupId(), group.buyerId(), RefundFixtures.BIG);

        fixtures.traced(() -> listener.receive(fixtures.record(event)));

        assertThat(canceledAt(group.bigOrder())).isNotNull();
        assertThat(canceledAt(group.smallOrder())).isNull();
        assertThat(jdbc.queryForObject("SELECT status::text FROM payment_instructions WHERE order_id = ?",
                String.class, group.bigOrder())).isEqualTo("PAID");
        assertThat(fixtures.refunds(group.bigOrder())).isZero();
        assertThat(fixtures.refunds(group.smallOrder())).isZero();
        assertThat(fixtures.outbox("OrderRefundRequested", group.bigOrder())).isZero();
        assertThat(fixtures.marker(event.eventId())).isEqualTo(1);
    }

    @Test
    void theSameCancellationDeliveredAgainIsHandledOnce() {
        var group = fixtures.paidGroup();
        var again = fixtures.cancelled(group.bigOrder(), group.groupId(), group.buyerId(), RefundFixtures.BIG);

        fixtures.traced(() -> listener.receive(fixtures.record(again)));
        Object first = canceledAt(group.bigOrder());
        fixtures.traced(() -> listener.receive(fixtures.record(again)));

        assertThat(canceledAt(group.bigOrder())).isEqualTo(first);
        assertThat(fixtures.marker(again.eventId())).isEqualTo(1);
        assertThat(fixtures.refunds(group.bigOrder())).isZero();
    }

    @Test
    void anUnpaidInstructionGetsNoRefundEvenIfCommerceSaysItWasPaid() {
        UUID orderId = fixtures.unpaidOrder();
        var event = fixtures.cancelled(orderId, UUID.randomUUID(), UUID.randomUUID(), "100000.00");

        fixtures.traced(() -> listener.receive(fixtures.record(event)));

        assertThat(fixtures.refunds(orderId)).isZero();
        assertThat(fixtures.outbox("OrderRefundRequested", orderId)).isZero();
        assertThat(fixtures.marker(event.eventId())).isEqualTo(1);
    }

    @Test
    void aPaidOrderWithoutAnInstructionIsRetriedAndNotMarkedHandled() {
        UUID orderId = UUID.randomUUID();
        var event = fixtures.cancelled(orderId, UUID.randomUUID(), UUID.randomUUID(), RefundFixtures.BIG);

        assertThatThrownBy(() -> fixtures.traced(() -> listener.receive(fixtures.record(event))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(fixtures.marker(event.eventId())).isZero();
    }

    @Test
    void anUnpaidOrderWithoutAnInstructionIsSkipped() {
        UUID orderId = UUID.randomUUID();
        var event = fixtures.cancelled(orderId, UUID.randomUUID(), UUID.randomUUID(), "0.00");

        fixtures.traced(() -> listener.receive(fixtures.record(event)));

        assertThat(fixtures.marker(event.eventId())).isEqualTo(1);
        assertThat(fixtures.refunds(orderId)).isZero();
    }

    private Object canceledAt(UUID orderId) {
        return jdbc.queryForObject("SELECT canceled_at FROM payment_instructions WHERE order_id = ?",
                Object.class, orderId);
    }
}
