package com.group5.acceptance;

import com.group5.common_events.EventEnvelope;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Reads actual committed commerce outbox records and runs the production downstream consumer. */
class PayoutCompletionAcceptanceTest {
    @Test void actualConfirmationStartsFundedPayoutEligibilityOnlyAfterCompletionAndExactlyOnce() {
        try(var commerce=new CommerceReceiptFixture();var payment=new PaymentPayoutFixture()) {
            var order=commerce.order();payment.fund(order);
            assertThat(commerce.status(order)).isEqualTo("DELIVERED");
            assertThat(commerce.events(order.id())).isEmpty();assertThat(payment.eligible(order.id())).isZero();
            commerce.completions.confirm(order.buyer(),order.id());
            commerce.completions.confirm(order.buyer(),order.id());
            var records=commerce.events(order.id());assertThat(records).hasSize(1);
            var actual=records.getFirst();payment.receive(actual);payment.receive(actual);
            payment.receive(new EventEnvelope<>(UUID.randomUUID(),actual.eventType(),actual.aggregateId(),actual.occurredAt(),actual.traceId(),actual.payload()));
            assertThat(payment.eligible(order.id())).isEqualTo(1);
        }
    }

    @Test void actualAutomaticCompletionStartsEligibilityButReportedIssueDoesNot() {
        try(var commerce=new CommerceReceiptFixture();var payment=new PaymentPayoutFixture()) {
            var held=commerce.order();payment.fund(held);commerce.due(held);
            commerce.completions.reportIssue(held.buyer(),held.id(),"Missing item");
            var due=commerce.order();payment.fund(due);commerce.due(due);
            commerce.job.runOnce();
            assertThat(commerce.status(held)).isEqualTo("DELIVERED");assertThat(commerce.events(held.id())).isEmpty();
            assertThat(payment.eligible(held.id())).isZero();
            assertThat(commerce.status(due)).isEqualTo("COMPLETED");
            var records=commerce.events(due.id());assertThat(records).hasSize(1);
            payment.receive(records.getFirst());payment.receive(records.getFirst());
            assertThat(payment.eligible(due.id())).isEqualTo(1);
        }
    }
}
