package com.group5.paymentservice.payment;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.paymentservice.payment.event.payload.OrderCompleted;
import com.group5.paymentservice.payment.service.PayoutEligibilityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Real broker -> production consumer -> funded PostgreSQL evidence; no bank transfer is simulated. */
@EmbeddedKafka(partitions = 1, topics = Topics.COMMERCE, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = {"spring.kafka.listener.auto-startup=true",
        "spring.kafka.consumer.auto-offset-reset=earliest", "spring.kafka.consumer.enable-auto-commit=false",
        "spring.kafka.listener.ack-mode=record", "nexus.payment.payout-eligibility.cron=-"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PayoutEligibilityKafkaTest extends PaymentDatabaseTestSupport {
    @Autowired @Qualifier(EventKafka.TEMPLATE) KafkaTemplate<String, EventEnvelope<?>> kafka;
    @Autowired JdbcTemplate jdbc;
    @Autowired PayoutEligibilityService payouts;
    @Autowired EmbeddedKafkaBroker broker;

    @Test void paidAndDeliveredNeverStartPayoutButCanonicalCompletionAndReplaysQualifyOnce() throws Exception {
        Fixture order = funded("PAID");
        var completion = event(order);
        long offset = 0;
        for (String type : List.of("OrderPaid", "ShipmentDelivered"))
            offset = kafka.send(Topics.COMMERCE, 0, order.id().toString(), new EventEnvelope<>(UUID.randomUUID(),
                    type, order.id().toString(), completion.occurredAt(), completion.traceId(), completion.payload()))
                    .get(10, TimeUnit.SECONDS).getRecordMetadata().offset();
        final long observed = offset;
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var committed = KafkaTestUtils.getCurrentOffset(broker.getBrokersAsString(),
                    "payment-payout-eligibility", Topics.COMMERCE, 0);
            assertThat(committed).isNotNull();
            assertThat(committed.offset()).isGreaterThan(observed);
        });
        assertThat(eligible(order)).isZero();
        assertThat(completions(order)).isZero();
        kafka.send(Topics.COMMERCE, order.id().toString(), completion).get(10, TimeUnit.SECONDS);
        kafka.send(Topics.COMMERCE, order.id().toString(), completion).get(10, TimeUnit.SECONDS);
        var replay = new EventEnvelope<>(UUID.randomUUID(), completion.eventType(), completion.aggregateId(),
                completion.occurredAt(), completion.traceId(), completion.payload());
        kafka.send(Topics.COMMERCE, order.id().toString(), replay).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(eligible(order)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id=? AND consumer=?",
                    Integer.class, replay.eventId(), PayoutEligibilityService.CONSUMER)).isEqualTo(1);
        });
        assertThat(jdbc.queryForObject("SELECT amount FROM payout_eligibility WHERE order_id=?", java.math.BigDecimal.class, order.id()))
                .isEqualByComparingTo("125000");
    }

    @Test void completionBeforeFundingIsDurableAndRetryQualifiesOnlyAfterFunding() {
        Fixture order = funded("PENDING");
        payouts.completed(event(order));
        assertThat(completions(order)).isEqualTo(1);
        assertThat(eligible(order)).isZero();
        assertThat(payouts.pendingIds()).doesNotContain(order.id());
        payouts.retry(order.id());
        assertThat(eligible(order)).isZero();
        jdbc.update("UPDATE payment_sessions SET status='PAID' WHERE id=?", order.session());
        jdbc.update("UPDATE payment_instructions SET status='PAID' WHERE order_id=?", order.id());
        assertThat(payouts.pendingIds()).contains(order.id());
        payouts.retry(order.id());
        payouts.retry(order.id());
        assertThat(eligible(order)).isEqualTo(1);
    }

    @Test void refundsCancellationWrongSellerBuyerGroupAndStaleCompletionVersionCannotQualify() {
        for (String failure : List.of("refund", "canceled", "seller", "buyer", "group", "version", "transaction")) {
            Fixture order = funded("PAID");
            switch (failure) {
                case "refund" -> jdbc.update("INSERT INTO refunds(order_id,amount,currency,reason,idempotency_key,requested_by) VALUES (?,125000,'VND','refund',?,?)",
                        order.id(), UUID.randomUUID().toString(), order.buyer());
                case "canceled" -> jdbc.update("UPDATE payment_instructions SET status='CANCELED',canceled_at=now() WHERE order_id=?", order.id());
                case "seller" -> jdbc.update("UPDATE payment_instructions SET seller_id=? WHERE order_id=?", UUID.randomUUID(), order.id());
                case "buyer" -> jdbc.update("UPDATE payment_instructions SET buyer_id=? WHERE order_id=?", UUID.randomUUID(), order.id());
                case "group" -> jdbc.update("UPDATE payment_sessions SET order_group_id=? WHERE id=?", UUID.randomUUID(), order.session());
                case "version" -> jdbc.update("UPDATE payment_instructions SET last_order_version=100 WHERE order_id=?", order.id());
                case "transaction" -> jdbc.update("UPDATE payment_transactions SET status='UNRECONCILED' WHERE session_id=?", order.session());
            }
            payouts.completed(event(order));
            payouts.retry(order.id());
            assertThat(eligible(order)).as(failure).isZero();
            assertThat(payouts.pendingIds()).as(failure).doesNotContain(order.id());
        }
    }

    @Test void concurrentDifferentEnvelopesAndConflictingReplayCannotDuplicateOrRewriteEligibility() throws Exception {
        Fixture order = funded("PAID");
        var first = event(order);
        var second = new EventEnvelope<>(UUID.randomUUID(), first.eventType(), first.aggregateId(), first.occurredAt(), first.traceId(), first.payload());
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { start.await(); payouts.completed(first); return true; });
            var two = pool.submit(() -> { start.await(); payouts.completed(second); return true; });
            start.countDown();
            assertThat(one.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(two.get(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(eligible(order)).isEqualTo(1);
        var payload = first.payload();
        var wrong = new OrderCompleted(payload.orderId(), payload.orderGroupId(), payload.buyerId(), UUID.randomUUID(),
                payload.orderType(), payload.referenceId(), payload.orderVersion(), payload.completedAt(), payload.orderNumber());
        var conflict = new EventEnvelope<>(UUID.randomUUID(), first.eventType(), first.aggregateId(), first.occurredAt(), first.traceId(), wrong);
        assertThatThrownBy(() -> payouts.completed(conflict)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conflicting");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id=? AND consumer=?",
                Integer.class, conflict.eventId(), PayoutEligibilityService.CONSUMER)).isZero();
        assertThat(jdbc.queryForObject("SELECT seller_id FROM payout_eligibility WHERE order_id=?", UUID.class, order.id())).isEqualTo(order.seller());
    }

    @Test void failedEligibilityWriteRollsBackCompletionAndProcessedMarkerForRetry() {
        Fixture order = funded("PAID");
        var event = event(order);
        jdbc.execute("ALTER TABLE payout_eligibility ADD CONSTRAINT test_payout_rollback CHECK (order_id <> '" + order.id() + "'::uuid)");
        try {
            assertThatThrownBy(() -> payouts.completed(event)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(completions(order)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id=? AND consumer=?", Integer.class,
                    event.eventId(), PayoutEligibilityService.CONSUMER)).isZero();
        } finally { jdbc.execute("ALTER TABLE payout_eligibility DROP CONSTRAINT test_payout_rollback"); }
        payouts.completed(event);
        assertThat(eligible(order)).isEqualTo(1);
    }

    private long eligible(Fixture order) { return jdbc.queryForObject("SELECT count(*) FROM payout_eligibility WHERE order_id=?", Long.class, order.id()); }
    private long completions(Fixture order) { return jdbc.queryForObject("SELECT count(*) FROM payment_order_completions WHERE order_id=?", Long.class, order.id()); }
    private EventEnvelope<OrderCompleted> event(Fixture order) {
        var time = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return new EventEnvelope<>(UUID.randomUUID(), "OrderCompleted", order.id().toString(), time, "payout-eligibility-test",
                new OrderCompleted(order.id(), order.group(), order.buyer(), order.seller(), "STANDARD", null, 4,
                        time, "NX-PAYOUT-" + order.id()));
    }
    private Fixture funded(String status) {
        UUID group=UUID.randomUUID(), buyer=UUID.randomUUID(), seller=UUID.randomUUID(), order=UUID.randomUUID();
        UUID session=jdbc.queryForObject("""
                INSERT INTO payment_sessions(order_group_id,buyer_id,total_amount,currency,memo_code,due_at,status,
                                             source_event_id,idempotency_key,paid_at)
                VALUES (?,?,125000,'VND',?,now()+interval '1 hour',CAST(? AS payment_session_status),?,?,now()-interval '1 hour') RETURNING id
                """, UUID.class, group,buyer,group.toString().replace("-","").toUpperCase(java.util.Locale.ROOT),status,UUID.randomUUID(),UUID.randomUUID().toString());
        UUID transaction=jdbc.queryForObject("""
                INSERT INTO payment_transactions(session_id,provider,txn_reference,amount,currency,status,type,idempotency_key,received_at)
                VALUES (?,'SEPAY',?,125000,'VND','SUCCESS','NORMAL',?,now()-interval '1 hour') RETURNING id
                """, UUID.class, session,UUID.randomUUID().toString(),UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO payment_instructions(order_id,session_id,funded_transaction_id,buyer_id,seller_id,amount,currency,
                                                 due_at,status,paid_at,last_order_version)
                VALUES (?,?,?,?,?,125000,'VND',now()+interval '1 hour',CAST(? AS payment_instruction_status),now()-interval '1 hour',1)
                """,order,session,transaction,buyer,seller,status);
        return new Fixture(order,group,buyer,seller,session);
    }
    private record Fixture(UUID id,UUID group,UUID buyer,UUID seller,UUID session) { }
}
