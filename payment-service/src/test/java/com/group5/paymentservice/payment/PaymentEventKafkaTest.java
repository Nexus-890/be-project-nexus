package com.group5.paymentservice.payment;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.event.payload.CheckoutPaymentRequested;
import com.group5.paymentservice.payment.event.payload.OrderGroupCancelled;
import com.group5.paymentservice.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@EmbeddedKafka(partitions = 1, topics = Topics.COMMERCE, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = {
        "spring.kafka.listener.auto-startup=true", "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.enable-auto-commit=false", "spring.kafka.listener.ack-mode=record",
        "nexus.payment.consumer-group=payment-issue-121-kafka-test"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentEventKafkaTest extends PaymentDatabaseTestSupport {
    @Autowired @Qualifier(EventKafka.TEMPLATE) private KafkaTemplate<String, EventEnvelope<?>> kafka;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentService payments;

    @Test
    void earlyCancellationDoesNotBlockItsManifestOrAnotherGroupInTheSamePartition() throws Exception {
        UUID buyerId = UUID.randomUUID();
        Instant created = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant due = created.plusSeconds(900);
        UUID firstGroup = UUID.randomUUID();
        UUID secondGroup = UUID.randomUUID();
        var firstOrder = new CheckoutPaymentRequested.SellerOrder(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("200000.00"), due, 0L);
        var secondOrder = new CheckoutPaymentRequested.SellerOrder(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("150000.00"), due, 0L);
        var manifestA = new CheckoutPaymentRequested(firstGroup, buyerId, "VND", new BigDecimal("350000.00"),
                due, created, 2, List.of(firstOrder, secondOrder));
        var manifestB = new CheckoutPaymentRequested(secondGroup, buyerId, "VND", new BigDecimal("350000.00"),
                due, created, 1, List.of(new CheckoutPaymentRequested.SellerOrder(
                        UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("350000.00"), due, 0L)));
        var cancellation = new OrderGroupCancelled(firstGroup, buyerId, 1L, "BUYER_CANCELLED", buyerId,
                created.plusSeconds(1), List.of(new OrderGroupCancelled.CancelledOrder(firstOrder.orderId(), 1L),
                        new OrderGroupCancelled.CancelledOrder(secondOrder.orderId(), 1L)));
        var canceled = new EventEnvelope<>(UUID.randomUUID(), "OrderGroupCancelled", firstGroup.toString(),
                cancellation.cancelledAt(), "early-cancel-test", cancellation);
        var checkoutA = new EventEnvelope<>(UUID.randomUUID(), "CheckoutPaymentRequested", firstGroup.toString(),
                created, "early-cancel-test", manifestA);
        var checkoutB = new EventEnvelope<>(UUID.randomUUID(), "CheckoutPaymentRequested", secondGroup.toString(),
                created, "early-cancel-test", manifestB);

        kafka.send(Topics.COMMERCE, 0, firstGroup.toString(), canceled).get(10, TimeUnit.SECONDS);
        kafka.send(Topics.COMMERCE, 0, firstGroup.toString(), checkoutA).get(10, TimeUnit.SECONDS);
        kafka.send(Topics.COMMERCE, 0, secondGroup.toString(), checkoutB).get(10, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT status::text FROM payment_sessions WHERE order_group_id = ?",
                    String.class, firstGroup)).isEqualTo("CANCELED");
            assertThat(jdbc.queryForObject("SELECT status::text FROM payment_sessions WHERE order_group_id = ?",
                    String.class, secondGroup)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id IN (?, ?, ?)",
                    Integer.class, canceled.eventId(), checkoutA.eventId(), checkoutB.eventId())).isEqualTo(3);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_pending_cancellations WHERE order_group_id = ?",
                Integer.class, firstGroup)).isZero();
        var buyer = new CurrentUser(buyerId, Set.of("BUYER"), Set.of("CHECKOUT.START"));
        assertThatThrownBy(() -> payments.initiate(buyer, firstGroup)).isInstanceOfSatisfying(
                DomainException.class, error -> assertThat(error.getCode()).isEqualTo(409));
        assertThat(payments.initiate(buyer, secondGroup).amount()).isEqualByComparingTo("350000");
    }

    @Test
    void kafkaDeserializesTheManifestThenAppliesCancellationUsingTheSameGroupKey() throws Exception {
        UUID groupId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        Instant created = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant due = created.plusSeconds(900);
        var first = new CheckoutPaymentRequested.SellerOrder(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("200000.00"), due, 0L);
        var second = new CheckoutPaymentRequested.SellerOrder(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("150000.00"), due, 0L);
        var manifest = new CheckoutPaymentRequested(groupId, buyerId, "VND", new BigDecimal("350000.00"),
                due, created, 2, List.of(first, second));
        var request = new EventEnvelope<>(UUID.randomUUID(), "CheckoutPaymentRequested", groupId.toString(),
                created, "payment-kafka-test", manifest);
        kafka.send(Topics.COMMERCE, groupId.toString(), request).get(10, TimeUnit.SECONDS);
        kafka.send(Topics.COMMERCE, groupId.toString(), request).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_sessions WHERE order_group_id = ?", Integer.class, groupId)).isEqualTo(1));
        var buyer = new CurrentUser(buyerId, Set.of("BUYER"), Set.of("CHECKOUT.START"));
        var qr = payments.initiate(buyer, groupId);
        assertThat(qr.amount()).isEqualByComparingTo("350000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_instructions WHERE session_id = ?",
                Integer.class, qr.sessionId())).isEqualTo(2);

        var cancellation = new OrderGroupCancelled(groupId, buyerId, 1L, "BUYER_CANCELLED", buyerId,
                created.plusSeconds(1), List.of(new OrderGroupCancelled.CancelledOrder(first.orderId(), 1L),
                new OrderGroupCancelled.CancelledOrder(second.orderId(), 1L)));
        var cancelled = new EventEnvelope<>(UUID.randomUUID(), "OrderGroupCancelled", groupId.toString(),
                cancellation.cancelledAt(), "payment-kafka-test", cancellation);
        kafka.send(Topics.COMMERCE, groupId.toString(), cancelled).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status::text FROM payment_sessions WHERE id = ?", String.class, qr.sessionId()))
                .isEqualTo("CANCELED"));
        assertThatThrownBy(() -> payments.initiate(buyer, groupId)).isInstanceOfSatisfying(
                DomainException.class, error -> assertThat(error.getCode()).isEqualTo(409));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id IN (?, ?)",
                Integer.class, request.eventId(), cancelled.eventId())).isEqualTo(2);
    }
}
