package com.group5.acceptance;

import com.group5.common_events.EventEnvelope;
import com.group5.common_core.exception.DomainException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reads actual committed commerce outbox records and runs the production downstream consumer. */
class PayoutCompletionAcceptanceTest {
    @Test void actualConfirmationCreatesOneHeldPayoutWithItsActualMoneyOnlyAfterCompletion() {
        try(var commerce=new CommerceReceiptFixture();var payment=new PaymentPayoutFixture()) {
            var order=commerce.order("25000.00");payment.fund(order);
            assertThat(commerce.status(order)).isEqualTo("DELIVERED");
            assertThat(commerce.events(order.id())).isEmpty();assertThat(payment.eligible(order.id())).isZero();
            assertThat(payment.payouts(order.id())).isZero();
            commerce.completions.confirm(order.buyer(),order.id());
            assertThatThrownBy(() -> commerce.completions.confirm(order.buyer(), order.id()))
                    .isInstanceOf(DomainException.class).extracting("errorCode").isEqualTo("COMMERCE_ORDER_NOT_DELIVERED");
            var records=commerce.events(order.id());assertThat(records).hasSize(1);
            var actual=records.getFirst();payment.receive(actual);payment.receive(actual);
            assertThat(actual.payload().path("orderAmount").asString()).isEqualTo("325000.00");
            assertThat(actual.payload().path("shippingFee").asString()).isEqualTo("25000.00");
            payment.receive(new EventEnvelope<>(UUID.randomUUID(),actual.eventType(),actual.aggregateId(),actual.occurredAt(),actual.traceId(),actual.payload()));
            assertThat(payment.eligible(order.id())).isEqualTo(1);
            assertThat(payment.payouts(order.id())).isEqualTo(1);
            var payout=payment.payout(order.id());
            assertThat(payout.get("status").toString()).isEqualTo("ON_HOLD");
            assertThat((BigDecimal)payout.get("order_amount")).isEqualByComparingTo(order.amount());
            assertThat((BigDecimal)payout.get("shipping_fee")).isEqualByComparingTo(order.shipping());
            assertThat((BigDecimal)payout.get("net_amount")).isEqualByComparingTo("300000.00");
            assertThat(payment.jdbc.queryForList("SELECT event::text FROM payout_history WHERE payout_id=? ORDER BY event_seq",String.class,payout.get("id")))
                    .containsExactly("CREATED","HELD");
        }
    }

    @Test void actualAutomaticCompletionCreatesPayoutButReportedIssueDoesNot() {
        try(var commerce=new CommerceReceiptFixture();var payment=new PaymentPayoutFixture()) {
            var held=commerce.order();payment.fund(held);commerce.due(held);
            commerce.completions.reportIssue(held.buyer(),held.id(),"Missing item");
            var due=commerce.order();payment.fund(due);commerce.due(due);
            commerce.job.runOnce();
            assertThat(commerce.status(held)).isEqualTo("DELIVERED");assertThat(commerce.events(held.id())).isEmpty();
            assertThat(payment.eligible(held.id())).isZero();
            assertThat(payment.payouts(held.id())).isZero();
            assertThat(commerce.status(due)).isEqualTo("COMPLETED");
            var records=commerce.events(due.id());assertThat(records).hasSize(1);
            payment.receive(records.getFirst());payment.receive(records.getFirst());
            assertThat(payment.eligible(due.id())).isEqualTo(1);
            assertThat(payment.payouts(due.id())).isEqualTo(1);
            assertThat(payment.payout(due.id()).get("status").toString()).isEqualTo("ON_HOLD");
        }
    }

    @Test void discountedCompletionPaysOnlyFundedAmountLessShippingWithoutVoucherCompensation() {
        try (var commerce = new CommerceReceiptFixture(); var payment = new PaymentPayoutFixture()) {
            // Snapshot sau giảm giá là số buyer đã trả; payout không cộng bù phần voucher của sàn.
            var order = commerce.order("25000.00", "40000.00");
            payment.fund(order);
            commerce.completions.confirm(order.buyer(), order.id());
            var actual = commerce.events(order.id()).getFirst();
            assertThat(actual.payload().path("orderAmount").asString()).isEqualTo("285000.00");
            assertThat(actual.payload().path("shippingFee").asString()).isEqualTo("25000.00");
            payment.receive(actual);
            payment.receive(actual);
            var payout = payment.payout(order.id());
            assertThat((BigDecimal) payout.get("order_amount")).isEqualByComparingTo("285000.00");
            assertThat((BigDecimal) payout.get("platform_fee")).isEqualByComparingTo("0.00");
            assertThat((BigDecimal) payout.get("net_amount")).isEqualByComparingTo("260000.00");
            assertThat(payment.payouts(order.id())).isEqualTo(1);
        }
    }

    @Test void actualCompletedEventsCannotCreatePayoutForRefundedOrUnfundedOrders() {
        try(var commerce=new CommerceReceiptFixture();var payment=new PaymentPayoutFixture()) {
            for(String blocked:java.util.List.of("refunded","unfunded")) {
                var order=commerce.order();payment.fund(order);
                if(blocked.equals("refunded"))payment.jdbc.update("""
                        INSERT INTO refunds(order_id,amount,reason,idempotency_key,requested_by)
                        VALUES (?,?,'Acceptance refund',?,?)
                        """,order.id(),order.amount(),UUID.randomUUID().toString(),UUID.randomUUID());
                else payment.jdbc.update("UPDATE payment_instructions SET status='PENDING',funded_transaction_id=NULL WHERE order_id=?",order.id());
                commerce.completions.confirm(order.buyer(),order.id());
                var actual=commerce.events(order.id()).getFirst();payment.receive(actual);payment.receive(actual);
                assertThat(payment.eligible(order.id())).as(blocked).isZero();
                assertThat(payment.payouts(order.id())).as(blocked).isZero();
            }
        }
    }
}
