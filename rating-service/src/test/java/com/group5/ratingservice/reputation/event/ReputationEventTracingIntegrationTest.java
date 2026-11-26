package com.group5.ratingservice.reputation.event;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.ratingservice.shared.RatingPostgresIntegrationTest;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.config.name=rating-service-test", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.kafka.listener.auto-startup=false", "nexus.outbox.relay.enabled=false",
        "nexus.security.jwt.issuer=rating-test", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1/.well-known/jwks.json",
        "management.tracing.export.zipkin.enabled=false", "management.tracing.sampling.probability=1"
})
class ReputationEventTracingIntegrationTest extends RatingPostgresIntegrationTest {
    @Autowired private Tracer tracer;
    @Autowired private EventEnvelopeFactory envelopes;
    @Autowired private ReputationEventConsumer consumer;
    @Autowired private JdbcTemplate jdbc;
    private static final JsonMapper JSON = JsonMapper.shared();

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE rating_disputes,reputation_restrictions,reputation_moderation_audit,reputation_adjustment_requests,reputation_history,reputation_effects,reputation_sources,reputation_outcomes,ratings,rating_eligibility,user_reputations,outbox,processed_events");
        assertThat(tracer.currentSpan()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"OrderCompleted", "UserRated", "AuctionPaymentTimeout"})
    void listenerWithoutAnIncomingSpanCommitsRealTracedEventsAndRestoresTheThread(String type) {
        assertThatThrownBy(() -> envelopes.create("Probe", "probe", Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No active trace");
        ConsumerRecord<String, EventEnvelope<JsonNode>> record = input(type, UUID.randomUUID(), UUID.randomUUID());

        receive(record);

        assertThat(tracer.currentSpan()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id=?", Long.class,
                record.value().eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_history", Long.class))
                .isEqualTo(type.equals("OrderCompleted") ? 2 : 1);
        List<Map<String, Object>> events = jdbc.queryForList("SELECT * FROM outbox");
        assertThat(events).isNotEmpty();
        for (Map<String, Object> event : events) {
            String trace = (String) event.get("trace_id");
            assertThat(trace).matches("[0-9a-f]{16}|[0-9a-f]{32}").doesNotMatch("0+");
            assertThat(JSON.readTree(event.get("payload").toString()).path("traceId").asString()).isEqualTo(trace);
        }
        assertThat(events.stream().map(event -> event.get("trace_id")).distinct()).hasSize(1);
        if (type.equals("OrderCompleted")) {
            assertThat(jdbc.queryForObject("SELECT sum(successful_transaction) FROM user_reputations", Long.class)).isEqualTo(2);
        } else if (type.equals("UserRated")) {
            assertThat(jdbc.queryForObject("SELECT sum(rating_count) FROM user_reputations", Long.class)).isEqualTo(1);
        } else {
            assertThat(jdbc.queryForObject("SELECT score FROM user_reputations", Integer.class)).isEqualTo(40);
        }
        receive(record);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class)).isEqualTo(events.size());
        assertThat(tracer.currentSpan()).isNull();
    }

    @Test
    void listenerRestoresAnExistingParentSpanAfterSuccessAndTransactionalFailure() {
        Span parent = tracer.nextSpan().name("existing-caller").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(parent)) {
            String parentTrace = tracer.currentSpan().context().traceId();
            String parentSpan = tracer.currentSpan().context().spanId();
            ConsumerRecord<String, EventEnvelope<JsonNode>> accepted = input("UserRated", UUID.randomUUID(), UUID.randomUUID());
            consumer.rating(accepted);
            assertThat(tracer.currentSpan().context().traceId()).isEqualTo(parentTrace);
            assertThat(tracer.currentSpan().context().spanId()).isEqualTo(parentSpan);
            assertThat(jdbc.queryForObject("SELECT trace_id FROM outbox LIMIT 1", String.class)).isEqualTo(parentTrace);

            var changed = JSON.readTree(accepted.value().payload().toString()).deepCopy();
            ((tools.jackson.databind.node.ObjectNode) changed).put("score", 1);
            UUID rejectedEventId = UUID.randomUUID();
            var rejected = new EventEnvelope<>(rejectedEventId, "UserRated", accepted.value().aggregateId(), Instant.now(), "input", changed);
            assertThatThrownBy(() -> consumer.rating(new ConsumerRecord<>(Topics.RATING, 0, 0, accepted.key(), rejected)))
                    .isInstanceOf(DomainException.class);
            assertThat(tracer.currentSpan().context().traceId()).isEqualTo(parentTrace);
            assertThat(tracer.currentSpan().context().spanId()).isEqualTo(parentSpan);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id=?", Long.class, rejectedEventId)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_history", Long.class)).isEqualTo(1);
        } finally {
            parent.end();
        }
        assertThat(tracer.currentSpan()).isNull();
    }

    @Test
    void buyerCancelledAuctionOrderAppliesNoPenalty() {
        var record = input("AuctionPaymentTimeout", UUID.randomUUID(), UUID.randomUUID());
        var payload = (tools.jackson.databind.node.ObjectNode) record.value().payload().deepCopy();
        payload.put("reason", "BUYER_CANCELLED");
        var cancelled = new EventEnvelope<>(UUID.randomUUID(), "AuctionPaymentTimeout", record.value().aggregateId(),
                Instant.now(), "input", (JsonNode) payload);

        consumer.auction(new ConsumerRecord<>(Topics.AUCTION, 0, 0, record.key(), cancelled));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_history", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_reputations", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class)).isZero();
    }

    @Test
    void failedListenerWithoutAParentDoesNotLeakItsSpanOrProcessedMarker() {
        var record = input("UserRated", UUID.randomUUID(), UUID.randomUUID());
        UUID userId = UUID.fromString(record.value().payload().path("toUserId").asString());
        jdbc.update("INSERT INTO user_reputations (user_id,score) VALUES (?,30)", userId);

        assertThatThrownBy(() -> consumer.rating(record)).isInstanceOf(DomainException.class);

        assertThat(tracer.currentSpan()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_history", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class)).isZero();
    }

    private void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        switch (record.value().eventType()) {
            case "OrderCompleted" -> consumer.commerce(record);
            case "UserRated" -> consumer.rating(record);
            case "AuctionPaymentTimeout" -> consumer.auction(record);
            default -> throw new IllegalArgumentException("Unknown test input");
        }
    }

    private static ConsumerRecord<String, EventEnvelope<JsonNode>> input(String type, UUID buyer, UUID seller) {
        UUID order = UUID.randomUUID();
        UUID auction = UUID.randomUUID();
        UUID rating = UUID.randomUUID();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("orderId", order);
        UUID aggregate;
        UUID key;
        String topic;
        if (type.equals("OrderCompleted")) {
            payload.put("orderGroupId", UUID.randomUUID());
            payload.put("buyerId", buyer);
            payload.put("sellerId", seller);
            payload.put("orderType", "STANDARD");
            payload.put("referenceId", null);
            payload.put("orderVersion", 1);
            payload.put("completedAt", "2026-11-01T10:00:00.123456Z");
            aggregate = order;
            key = order;
            topic = Topics.COMMERCE;
        } else if (type.equals("UserRated")) {
            payload.put("ratingId", rating);
            payload.put("auctionId", null);
            payload.put("fromUserId", buyer);
            payload.put("toUserId", seller);
            payload.put("score", 5);
            payload.put("sourceVersion", 0);
            payload.put("actorId", buyer);
            payload.put("ratedAt", "2026-11-01T11:00:00.123456Z");
            aggregate = rating;
            key = seller;
            topic = Topics.RATING;
        } else {
            payload.put("auctionId", auction);
            payload.put("winnerId", buyer);
            payload.put("sellerId", seller);
            payload.put("paymentDueAt", "2026-11-01T10:00:00Z");
            payload.put("timedOutAt", "2026-11-01T10:30:00Z");
            payload.put("reason", "PAYMENT_DEADLINE_EXPIRED");
            payload.put("auctionVersion", 1);
            aggregate = auction;
            key = auction;
            topic = Topics.AUCTION;
        }
        EventEnvelope<JsonNode> envelope = new EventEnvelope<>(UUID.randomUUID(), type, aggregate.toString(),
                Instant.now(), "upstream-without-observation", JSON.valueToTree(payload));
        return new ConsumerRecord<>(topic, 0, 0, key.toString(), envelope);
    }
}
