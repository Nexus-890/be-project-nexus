package com.group5.auctionservice.settlement;

import com.group5.auctionservice.TestPostgres;
import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.auctionservice.auction.AuctionStockRejectedConsumer;
import com.group5.common_events.EventEnvelope;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(args = {
        "--spring.config.location=optional:classpath:/settlement-test.properties",
        "--spring.config.import=file:../config-service/src/main/resources/config/auction-service.yml",
        "--spring.cloud.config.enabled=false", "--spring.cloud.config.import-check.enabled=false"
}, properties = {
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "management.endpoints.web.exposure.include=health,refresh",
        "nexus.auction.scheduler.enabled=false", "nexus.outbox.relay.enabled=false"
})
class SettlementIntegrationTest {
    private static final TestPostgres POSTGRES = new TestPostgres();
    private static final AtomicInteger DEADLINE_HOURS = new AtomicInteger(24);
    private static final OffsetDateTime END = OffsetDateTime.now(ZoneOffset.UTC)
            .minusMinutes(1).truncatedTo(ChronoUnit.SECONDS);

    @Autowired JdbcTemplate jdbc;
    @Autowired AuctionRepository auctions;
    @Autowired SettlementService settlement;
    @Autowired AuctionStockRejectedConsumer stockRejections;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Tracer tracer;
    @Autowired WebApplicationContext context;
    @MockitoBean JwtDecoder decoder;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("nexus.auction.payment-deadline-hours", DEADLINE_HOURS::get);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void choosesHighestAndEarliestBidAndMakesReplayANoOp() {
        UUID auctionId = endedAuction();
        UUID winner = UUID.randomUUID();
        bid(auctionId, UUID.randomUUID(), 1_500_000, END.minusSeconds(1), "PLACED");
        bid(auctionId, winner, 1_500_000, END.minusSeconds(2), "PLACED");
        bid(auctionId, UUID.randomUUID(), 1_600_000, END.minusSeconds(3), "CANCELLED");
        bid(auctionId, UUID.randomUUID(), 1_400_000, END.minusSeconds(4), "PLACED");

        assertThat(auctions.findEndedIds(null, 100)).contains(auctionId);
        assertThat(auctions.findEndedIds(auctionId, 100)).doesNotContain(auctionId);
        settle(auctionId);

        var won = auctions.findById(auctionId).orElseThrow();
        assertThat(won.getStatus().name()).isEqualTo("WON");
        assertThat(won.getWinnerId()).isEqualTo(winner);
        assertThat(won.getFinalPrice()).isEqualByComparingTo("1500000.00");
        assertThat(won.getPaymentDueAt()).isEqualTo(END.plusHours(24).toInstant());

        var payload = JsonMapper.shared().readTree(outbox(auctionId, "AuctionWon")).required("payload");
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder("auctionId", "sellerId", "productId",
                "skuId", "winnerId", "finalPrice", "currency", "title", "imageUrl", "quantity", "endAt", "paymentDueAt");
        assertThat(payload).allMatch(field -> !field.isNull());
        assertThat(payload.required("finalPrice").asText()).isEqualTo("1500000.00");
        assertThat(payload.required("imageUrl").asText()).isEqualTo("https://example.com/auction.png");

        var row = jdbc.queryForMap("SELECT * FROM auctions WHERE id = ?", auctionId);
        settle(auctionId);
        assertThat(jdbc.queryForMap("SELECT * FROM auctions WHERE id = ?", auctionId)).isEqualTo(row);
        assertThat(eventCount(auctionId)).isEqualTo(1);
    }

    @Test
    void failsWithoutBidsAndPublishesOnlyOneEvent() {
        UUID auctionId = endedAuction();
        settle(auctionId);
        settle(auctionId);

        var failed = auctions.findById(auctionId).orElseThrow();
        assertThat(failed.getStatus().name()).isEqualTo("FAILED");
        assertThat(failed.getWinnerId()).isNull();
        var payload = JsonMapper.shared().readTree(outbox(auctionId, "AuctionFailed")).required("payload");
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder(
                "auctionId", "sellerId", "productId", "skuId", "reason", "endAt", "failedAt");
        assertThat(payload.required("reason").asText()).isEqualTo("NO_BIDS");
        assertThat(payload).allMatch(field -> !field.isNull());
        assertThat(eventCount(auctionId)).isEqualTo(1);
    }

    @Test
    void rollbackLeavesNeitherTheWinnerNorTheOutboxEvent() {
        UUID auctionId = endedAuction();
        bid(auctionId, UUID.randomUUID(), 1_500_000, END.minusSeconds(1), "PLACED");

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            settle(auctionId);
            auctions.flush();
            assertThat(eventCount(auctionId)).isEqualTo(1);
            transaction.setRollbackOnly();
        });

        assertThat(auctions.findById(auctionId).orElseThrow().getStatus().name()).isEqualTo("ENDED");
        assertThat(eventCount(auctionId)).isZero();
        settle(auctionId);
        assertThat(eventCount(auctionId)).isEqualTo(1);
    }

    @Test
    void actuatorRefreshChangesTheNextDeadlineWithoutARestart() throws Exception {
        UUID auctionId = endedAuction();
        bid(auctionId, UUID.randomUUID(), 1_500_000, END.minusSeconds(1), "PLACED");
        when(decoder.decode("settlement-admin")).thenReturn(Jwt.withTokenValue("settlement-admin")
                .header("alg", "RS256").subject(UUID.randomUUID().toString())
                .claim("roles",java.util.List.of("ADMIN")).claim("privileges",java.util.List.of("SYSTEM.CONFIG.UPDATE")).build());
        var mvc = webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        try {
            DEADLINE_HOURS.set(48);
            mvc.perform(post("/actuator/refresh").header("Authorization", "Bearer settlement-admin"))
                    .andExpect(status().isOk());
            settle(auctionId);
            assertThat(auctions.findById(auctionId).orElseThrow().getPaymentDueAt())
                    .isEqualTo(END.plusHours(48).toInstant());
        } finally {
            DEADLINE_HOURS.set(24);
            mvc.perform(post("/actuator/refresh").header("Authorization", "Bearer settlement-admin"))
                    .andExpect(status().isOk());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "SCHEDULED", "ACTIVE"})
    void outOfStockCancelsOpenAuctionAndPublishesOnce(String status) {
        UUID id = endedAuction();
        jdbc.update("UPDATE auctions SET status = ?::auction_status, end_at = now() + interval '1 hour' WHERE id = ?",
                status, id);
        var event = rejection(id);
        stockRejections.receive(event);
        var row = jdbc.queryForMap("SELECT * FROM auctions WHERE id = ?", id);
        assertThat(row).containsEntry("cancelled_by", "SYSTEM")
                .containsEntry("cancelled_reason", "STOCK_UNAVAILABLE");
        assertThat(auctions.findById(id).orElseThrow().getStatus().name()).isEqualTo("CANCELLED");
        var payload = JsonMapper.shared().readTree(outbox(id, "AuctionCancelled")).required("payload");
        assertThat(payload.required("reason").asString()).isEqualTo("STOCK_UNAVAILABLE");
        assertThat(payload.required("actorId").asString()).isEqualTo(new UUID(0, 0).toString());
        stockRejections.receive(event);
        stockRejections.receive(rejection(id));
        assertThat(jdbc.queryForMap("SELECT * FROM auctions WHERE id = ?", id)).isEqualTo(row);
        assertThat(eventCount(id)).isEqualTo(1);
    }

    @Test
    void outOfStockDoesNotChangeAnAuctionWithAResult() {
        UUID id = endedAuction();
        bid(id, UUID.randomUUID(), 1_500_000, END.minusSeconds(1), "PLACED");
        settle(id);
        var row = jdbc.queryForMap("SELECT * FROM auctions WHERE id = ?", id);
        stockRejections.receive(rejection(id));
        assertThat(jdbc.queryForMap("SELECT * FROM auctions WHERE id = ?", id)).isEqualTo(row);
        assertThat(eventCount(id)).isEqualTo(1);
    }

    private EventEnvelope<JsonNode> rejection(UUID id) {
        UUID sku = auctions.findById(id).orElseThrow().getSkuId();
        JsonNode payload = JsonMapper.shared().valueToTree(Map.of(
                "auctionId", id, "skuId", sku, "reason", "OUT_OF_STOCK"));
        return new EventEnvelope<>(UUID.randomUUID(), "AuctionStockRejected", id.toString(),
                Instant.now(), "8f30a3a71d670bc4", payload);
    }

    private void settle(UUID auctionId) {
        Span span = tracer.nextSpan().name("test.auction.settlement").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            settlement.settle(auctionId);
        } finally {
            span.end();
        }
    }

    private UUID endedAuction() {
        return jdbc.queryForObject("""
                INSERT INTO auctions (product_id, sku_id, seller_id, title, image_url, starting_price,
                    bid_increment, current_price, status, start_at, end_at, original_end_at)
                VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'Settlement fixture',
                    'https://example.com/auction.png', 100, 10, 100, 'ENDED', ?, ?, ?)
                RETURNING id
                """, UUID.class, END.minusHours(1), END, END);
    }

    private void bid(UUID auctionId, UUID bidder, int amount, OffsetDateTime placedAt, String status) {
        jdbc.update("""
                INSERT INTO bids (auction_id, bidder_id, amount, placed_at, idempotency_key, bidder_name, status)
                VALUES (?, ?, ?, ?, ?, 'B***r', ?::bid_status)
                """, auctionId, bidder, amount, placedAt, UUID.randomUUID().toString(), status);
    }

    private String outbox(UUID auctionId, String type) {
        return jdbc.queryForObject("""
                SELECT payload::text FROM outbox WHERE aggregate_id = ? AND event_type = ?
                """, String.class, auctionId.toString(), type);
    }

    private int eventCount(UUID auctionId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                Integer.class, auctionId.toString());
    }
}
