package com.group5.paymentservice.payment;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.Topics;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.event.PaymentSessionEventConsumer;
import com.group5.paymentservice.payment.event.payload.CheckoutPaymentRequested;
import com.group5.paymentservice.payment.event.payload.OrderGroupCancelled;
import com.group5.paymentservice.payment.service.PaymentSessionEventHandler;
import com.group5.paymentservice.payment.service.PaymentService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class PaymentSessionEventsTest extends PaymentDatabaseTestSupport {
    @Autowired private PaymentSessionEventHandler handler;
    @Autowired private PaymentSessionEventConsumer listener;
    @Autowired private PaymentService payments;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper mapper;

    @Test
    void fullManifestCreatesOneQrForTwoOrdersAndAllRetriesPreserveEveryBusinessField() {
        var manifest = manifest();
        var firstEvent = checkout(manifest);
        listener.receive(record(firstEvent));
        var session = session(manifest.orderGroupId());
        UUID sessionId = (UUID) session.get("id");
        var instructions = instructions(sessionId);
        assertThat(instructions).hasSize(2);
        assertThat(instructions.stream().map(row -> (BigDecimal) row.get("amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("350000");
        assertThat(session.get("memo_code").toString()).matches("NX[A-Z0-9]{1,30}");
        assertThat(session.get("source_event_id")).isEqualTo(firstEvent.eventId());
        var response = payments.initiate(buyer(manifest), manifest.orderGroupId());
        assertThat(response.amount()).isEqualByComparingTo("350000");
        assertThat(response.memoCode()).isEqualTo(session.get("memo_code"));
        assertThat(response.bankAccount()).isEqualTo("0000000002");
        assertThat(response.qrImageUrl()).startsWith("https://vietqr.app/img?")
                .contains("amount=350000&des=" + response.memoCode());
        var repeated = payments.initiate(buyer(manifest), manifest.orderGroupId());
        assertThat(repeated).usingRecursiveComparison().ignoringFields("serverNow").isEqualTo(response);
        assertThat(repeated.serverNow()).isAfterOrEqualTo(response.serverNow());
        listener.receive(record(firstEvent));
        handler.checkout(checkout(manifest)); // Another envelope ID for the same business request.
        var reordered = new CheckoutPaymentRequested(manifest.orderGroupId(), manifest.buyerId(), "VND",
                manifest.totalAmount(), manifest.dueAt(), manifest.occurredAt(), 2, manifest.orders().reversed());
        handler.checkout(checkout(reordered));
        assertThat(session(manifest.orderGroupId())).isEqualTo(session);
        assertThat(instructions(sessionId)).isEqualTo(instructions);
        assertThat(count("payment_sessions", "order_group_id", manifest.orderGroupId())).isEqualTo(1);
        assertThat(count("payment_transactions", "session_id", sessionId)).isZero();
        assertThat(marker(firstEvent.eventId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"count", "duplicate-order", "duplicate-seller", "sum", "deadline", "currency"})
    void invalidManifestsCreateNoPartialPlanOrProcessedMarker(String defect) {
        var original = manifest();
        var orders = new ArrayList<>(original.orders());
        var first = orders.getFirst();
        if (defect.equals("duplicate-order")) orders.set(1, first);
        if (defect.equals("duplicate-seller")) {
            var second = orders.get(1);
            orders.set(1, new CheckoutPaymentRequested.SellerOrder(second.orderId(), first.sellerId(),
                    second.amount(), second.paymentDueAt(), second.orderVersion()));
        }
        if (defect.equals("deadline")) orders.set(0, new CheckoutPaymentRequested.SellerOrder(first.orderId(),
                first.sellerId(), first.amount(), original.dueAt().minusSeconds(1), first.orderVersion()));
        var invalid = new CheckoutPaymentRequested(original.orderGroupId(), original.buyerId(),
                defect.equals("currency") ? "USD" : "VND",
                defect.equals("sum") ? new BigDecimal("350001") : original.totalAmount(),
                original.dueAt(), original.occurredAt(), defect.equals("count") ? 3 : 2, orders);
        var event = checkout(invalid);
        assertThatThrownBy(() -> handler.checkout(event)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("payment_sessions", "order_group_id", original.orderGroupId())).isZero();
        assertThat(marker(event.eventId())).isZero();
    }

    @Test
    void duplicateOrderAcrossGroupsRollsBackSessionInstructionsAndConsumerMarker() {
        var original = manifest();
        handler.checkout(checkout(original));
        var another = manifest();
        var originalOrder = original.orders().getFirst();
        var reusedOrder = new CheckoutPaymentRequested.SellerOrder(originalOrder.orderId(), UUID.randomUUID(),
                new BigDecimal("200000"), another.dueAt(), 0L);
        var conflicting = new CheckoutPaymentRequested(another.orderGroupId(), another.buyerId(), "VND",
                another.totalAmount(), another.dueAt(), another.occurredAt(), 2,
                List.of(reusedOrder, another.orders().get(1)));
        var event = checkout(conflicting);
        assertThatThrownBy(() -> handler.checkout(event)).isInstanceOf(RuntimeException.class);
        assertThat(count("payment_sessions", "order_group_id", another.orderGroupId())).isZero();
        assertThat(marker(event.eventId())).isZero();
        assertThat(count("payment_instructions", "order_id", another.orders().get(1).orderId())).isZero();
    }

    @Test
    void changedSellerOrAmountCannotOverwriteTheIssuedPlan() {
        var original = manifest();
        handler.checkout(checkout(original));
        var before = session(original.orderGroupId());
        var first = original.orders().getFirst();
        var changed = new CheckoutPaymentRequested.SellerOrder(first.orderId(), UUID.randomUUID(),
                first.amount(), first.paymentDueAt(), first.orderVersion());
        var conflict = new CheckoutPaymentRequested(original.orderGroupId(), original.buyerId(), "VND",
                original.totalAmount(), original.dueAt(), original.occurredAt(), 2,
                List.of(changed, original.orders().get(1)));
        var event = checkout(conflict);
        assertThatThrownBy(() -> handler.checkout(event)).isInstanceOf(IllegalArgumentException.class);
        assertThat(session(original.orderGroupId())).isEqualTo(before);
        assertThat(marker(event.eventId())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUYER_CANCELLED", "PAYMENT_DEADLINE_EXPIRED"})
    void cancellationClosesWholeGroupAndCreationReplayNeverReopensIt(String reason) {
        var manifest = manifest();
        handler.checkout(checkout(manifest));
        var cancellation = cancellation(manifest, reason);
        listener.receive(record(cancellation));
        var expected = reason.equals("PAYMENT_DEADLINE_EXPIRED") ? "EXPIRED" : "CANCELED";
        var session = session(manifest.orderGroupId());
        assertThat(session.get("status").toString()).isEqualTo(expected);
        var plan = instructions((UUID) session.get("id"));
        assertThat(plan).allSatisfy(row -> assertThat(row.get("status").toString()).isEqualTo(expected));
        handler.cancel(cancellation);
        handler.checkout(checkout(manifest));
        assertThat(session(manifest.orderGroupId())).isEqualTo(session);
        assertThat(instructions((UUID) session.get("id"))).isEqualTo(plan);
        assertThatThrownBy(() -> payments.initiate(buyer(manifest), manifest.orderGroupId()))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getCode()).isEqualTo(409));
    }

    @Test
    void cancellationBeforeManifestIsStashedAndAppliedWhenManifestArrives() {
        var manifest = manifest();
        var cancellation = cancellation(manifest, "BUYER_CANCELLED");
        handler.cancel(cancellation);
        // The consumer acknowledges the early cancellation instead of retrying
        // forever: the processed marker and the stashed row commit together.
        assertThat(marker(cancellation.eventId())).isEqualTo(1);
        assertThat(count("payment_sessions", "order_group_id", manifest.orderGroupId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_pending_cancellations WHERE order_group_id = ?",
                Integer.class, manifest.orderGroupId())).isEqualTo(1);
        handler.checkout(checkout(manifest));
        // The manifest transaction applies the stashed cancellation and removes it.
        assertThat(session(manifest.orderGroupId()).get("status").toString()).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_pending_cancellations WHERE order_group_id = ?",
                Integer.class, manifest.orderGroupId())).isZero();
        assertThatThrownBy(() -> payments.initiate(buyer(manifest), manifest.orderGroupId()))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getCode()).isEqualTo(409));
    }

    @Test
    void incompleteCancellationCannotCloseOnlyOneSellerOrder() {
        var manifest = manifest();
        handler.checkout(checkout(manifest));
        var before = session(manifest.orderGroupId());
        var full = cancellation(manifest, "BUYER_CANCELLED").payload();
        var partial = new OrderGroupCancelled(full.orderGroupId(), full.buyerId(), full.groupVersion(),
                full.reason(), full.actorId(), full.cancelledAt(), List.of(full.orders().getFirst()));
        var event = new EventEnvelope<>(UUID.randomUUID(), "OrderGroupCancelled", full.orderGroupId().toString(),
                full.cancelledAt(), "payment-test", partial);
        assertThatThrownBy(() -> handler.cancel(event)).isInstanceOf(IllegalArgumentException.class);
        assertThat(session(manifest.orderGroupId())).isEqualTo(before);
        assertThat(marker(event.eventId())).isZero();
    }

    @Test
    void simultaneousBusinessDuplicatesCreateOnlyOneSessionAndTwoInstructions() throws Exception {
        var manifest = manifest();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { await(start); handler.checkout(checkout(manifest)); });
            var second = pool.submit(() -> { await(start); handler.checkout(checkout(manifest)); });
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }
        assertThat(count("payment_sessions", "order_group_id", manifest.orderGroupId())).isEqualTo(1);
        assertThat(instructions((UUID) session(manifest.orderGroupId()).get("id"))).hasSize(2);
    }

    @Test
    void expiredSessionCannotIssueQrAndAnotherBuyerAlwaysGets403() {
        var original = manifest();
        var created = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        var due = created.plusSeconds(900);
        var orders = original.orders().stream().map(order -> new CheckoutPaymentRequested.SellerOrder(
                order.orderId(), order.sellerId(), order.amount(), due, order.orderVersion())).toList();
        var manifest = new CheckoutPaymentRequested(original.orderGroupId(), original.buyerId(), "VND",
                original.totalAmount(), due, created, 2, orders);
        handler.checkout(checkout(manifest));
        assertThatThrownBy(() -> payments.initiate(buyer(manifest), manifest.orderGroupId()))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getCode()).isEqualTo(409));
        var other = new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of("CHECKOUT.START"));
        assertThatThrownBy(() -> payments.initiate(other, manifest.orderGroupId()))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getCode()).isEqualTo(403));
    }

    @Test
    void orderCreatedAndMiskeyedEventsCannotCreateQr() {
        var manifest = manifest();
        var event = checkout(manifest);
        var ignored = new EventEnvelope<>(event.eventId(), "OrderCreated", event.aggregateId(),
                event.occurredAt(), event.traceId(), event.payload());
        listener.receive(record(ignored));
        var badKey = new ConsumerRecord<>(Topics.COMMERCE, 0, 0L, "another-group", record(event).value());
        assertThatThrownBy(() -> listener.receive(badKey)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("payment_sessions", "order_group_id", manifest.orderGroupId())).isZero();
    }

    private CheckoutPaymentRequested manifest() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant due = now.plusSeconds(900);
        return new CheckoutPaymentRequested(UUID.randomUUID(), UUID.randomUUID(), "VND", new BigDecimal("350000.00"),
                due, now, 2, List.of(
                new CheckoutPaymentRequested.SellerOrder(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("200000.00"), due, 0L),
                new CheckoutPaymentRequested.SellerOrder(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("150000.00"), due, 0L)));
    }

    private EventEnvelope<CheckoutPaymentRequested> checkout(CheckoutPaymentRequested payload) {
        return new EventEnvelope<>(UUID.randomUUID(), "CheckoutPaymentRequested", payload.orderGroupId().toString(),
                payload.occurredAt(), "payment-test", payload);
    }

    private EventEnvelope<OrderGroupCancelled> cancellation(CheckoutPaymentRequested manifest, String reason) {
        var cancelledAt = manifest.occurredAt().plusSeconds(1);
        var payload = new OrderGroupCancelled(manifest.orderGroupId(), manifest.buyerId(), 1L, reason,
                manifest.buyerId(), cancelledAt, manifest.orders().stream()
                .map(order -> new OrderGroupCancelled.CancelledOrder(order.orderId(), order.orderVersion() + 1)).toList());
        return new EventEnvelope<>(UUID.randomUUID(), "OrderGroupCancelled", manifest.orderGroupId().toString(),
                cancelledAt, "payment-test", payload);
    }

    private ConsumerRecord<String, EventEnvelope<JsonNode>> record(EventEnvelope<?> envelope) {
        var event = new EventEnvelope<JsonNode>(envelope.eventId(), envelope.eventType(), envelope.aggregateId(),
                envelope.occurredAt(), envelope.traceId(), mapper.valueToTree(envelope.payload()));
        return new ConsumerRecord<>(Topics.COMMERCE, 0, 0L, event.aggregateId(), event);
    }

    private CurrentUser buyer(CheckoutPaymentRequested manifest) {
        return new CurrentUser(manifest.buyerId(), Set.of("BUYER"), Set.of("CHECKOUT.START"));
    }

    private Map<String, Object> session(UUID groupId) {
        return jdbc.queryForMap("SELECT * FROM payment_sessions WHERE order_group_id = ?", groupId);
    }

    private List<Map<String, Object>> instructions(UUID sessionId) {
        return jdbc.queryForList("SELECT * FROM payment_instructions WHERE session_id = ? ORDER BY order_id", sessionId);
    }

    private int count(String table, String column, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
    }

    private int marker(UUID eventId) {
        return count("processed_events", "event_id", eventId);
    }

    private void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
