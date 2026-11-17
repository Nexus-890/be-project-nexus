package com.group5.ratingservice.eligibility.service;

import com.group5.ratingservice.eligibility.event.OrderCompletedConsumer;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.Topics;
import com.group5.ratingservice.shared.TestPostgres;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.config.name=rating-service-test",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.kafka.listener.auto-startup=false",
        "nexus.outbox.relay.enabled=false",
        "nexus.security.jwt.issuer=user-service",
        "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1/.well-known/jwks.json"
})
class RatingEligibilityIntegrationTest {
    private static final TestPostgres POSTGRES = new TestPostgres();
    private static final OffsetDateTime COMPLETED_AT = OffsetDateTime.parse("2026-10-30T10:00:00.123456Z");

    static {
        POSTGRES.start();
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderCompletedConsumer consumer;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void clearEventState() {
        jdbc.execute("TRUNCATE rating_eligibility, processed_events");
    }

    @Test
    void contextLoads() {
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """, String.class)).contains("ratings", "user_reputations", "rating_eligibility", "outbox", "processed_events");
    }

    @Test
    void duplicateOrderCompletedCreatesOneEligibilityAndOneProcessedMarker() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        ConsumerRecord<String, EventEnvelope<JsonNode>> record = completion(eventId, orderId, buyerId, sellerId);

        consumer.receive(record);
        consumer.receive(record);

        assertThat(eligibilityCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer = ?
                """, Integer.class, eventId, OrderCompletedConsumer.CONSUMER)).isEqualTo(1);
        assertEligibility(orderId, buyerId, sellerId, eventId);
    }

    @Test
    void conflictingCompletionRollsBackItsProcessedMarkerAndPreservesTheOriginal() {
        UUID originalEventId = UUID.randomUUID();
        UUID conflictingEventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        consumer.receive(completion(originalEventId, orderId, buyerId, sellerId));

        assertThatThrownBy(() -> consumer.receive(completion(
                conflictingEventId, orderId, buyerId, UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting OrderCompleted snapshot");

        assertThat(eligibilityCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                Integer.class, conflictingEventId)).isZero();
        assertEligibility(orderId, buyerId, sellerId, originalEventId);
    }

    private int eligibilityCount() {
        return jdbc.queryForObject("SELECT count(*) FROM rating_eligibility", Integer.class);
    }

    private void assertEligibility(UUID orderId, UUID buyerId, UUID sellerId, UUID sourceEventId) {
        var rows = jdbc.query("""
                SELECT buyer_id, seller_id, completed_at, auction_id, source_event_id, source_version
                FROM rating_eligibility WHERE order_id = ?
                """, (row, index) -> {
            assertThat(row.getObject("buyer_id", UUID.class)).isEqualTo(buyerId);
            assertThat(row.getObject("seller_id", UUID.class)).isEqualTo(sellerId);
            assertThat(row.getObject("completed_at", OffsetDateTime.class).toInstant()).isEqualTo(COMPLETED_AT.toInstant());
            assertThat(row.getObject("auction_id")).isNull();
            assertThat(row.getObject("source_event_id", UUID.class)).isEqualTo(sourceEventId);
            assertThat(row.getLong("source_version")).isEqualTo(1);
            return row.getObject("source_event_id", UUID.class);
        }, orderId);
        assertThat(rows).containsExactly(sourceEventId);
    }

    private static ConsumerRecord<String, EventEnvelope<JsonNode>> completion(
            UUID eventId, UUID orderId, UUID buyerId, UUID sellerId) {
        JsonNode payload = JsonMapper.shared().readTree("""
                {"orderId":"%s","orderGroupId":"%s","buyerId":"%s","sellerId":"%s",
                 "orderType":"STANDARD","referenceId":null,"orderVersion":1,"completedAt":"%s"}
                """.formatted(orderId, UUID.randomUUID(), buyerId, sellerId, COMPLETED_AT));
        EventEnvelope<JsonNode> envelope = new EventEnvelope<>(eventId, "OrderCompleted", orderId.toString(),
                COMPLETED_AT.toInstant(), "rating-service-integration-test", payload);
        return new ConsumerRecord<>(Topics.COMMERCE, 0, 0L, orderId.toString(), envelope);
    }
}
