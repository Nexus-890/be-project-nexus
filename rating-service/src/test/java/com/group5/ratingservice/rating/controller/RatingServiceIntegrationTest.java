package com.group5.ratingservice.rating.controller;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.ratingservice.rating.event.RatingEventKeyResolver;
import com.group5.ratingservice.shared.RatingPostgresIntegrationTest;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.config.name=rating-service-test",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.kafka.listener.auto-startup=false",
        "nexus.outbox.relay.enabled=false",
        "nexus.security.jwt.issuer=rating-test",
        "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1/.well-known/jwks.json"
})
@Import(RatingServiceIntegrationTest.Config.class)
class RatingServiceIntegrationTest extends RatingPostgresIntegrationTest {
    private static final Map<String, Jwt> TOKENS = new ConcurrentHashMap<>();
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final String TRACE_ID = "21200000000000000000000000000000";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OutboxWriter outbox;
    @Autowired private RatingEventKeyResolver keys;
    private MockMvc client;

    @BeforeEach
    void setup() {
        reset(outbox);
        TOKENS.clear();
        jdbc.execute("TRUNCATE reputation_adjustment_requests, reputation_history, reputation_effects, reputation_sources, reputation_outcomes, ratings, rating_eligibility, user_reputations, outbox, processed_events");
        client = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void completedBuyerRatingCommitsMaskedSnapshotAuditAndExactUserRatedEvent() throws Exception {
        Fixture order = completedOrder(UUID.randomUUID());
        MvcResult result = client.perform(create(order, order.buyerId(), "BUYER", "Nguyễn Thị Ánh", 5))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.orderId").value(order.orderId().toString()))
                .andExpect(jsonPath("$.data.auctionId").value(order.auctionId().toString()))
                .andExpect(jsonPath("$.data.score").value(5))
                .andExpect(jsonPath("$.data.comment").value("Delivered as described"))
                .andExpect(jsonPath("$.data.raterName").value("N***h"))
                .andExpect(jsonPath("$.data.fromUserId").doesNotExist())
                .andExpect(jsonPath("$.page").doesNotExist()).andReturn();

        JsonNode data = JSON.readTree(result.getResponse().getContentAsString()).path("data");
        UUID ratingId = UUID.fromString(data.path("id").asString());
        assertThat(ratingId.version()).isEqualTo(7);
        Map<String, Object> rating = jdbc.queryForMap("SELECT * FROM ratings WHERE id = ?", ratingId);
        assertThat(rating.get("from_user_id")).isEqualTo(order.buyerId());
        assertThat(rating.get("to_user_id")).isEqualTo(order.sellerId());
        assertThat(rating.get("created_by")).isEqualTo(order.buyerId());
        assertThat(rating.get("updated_by")).isEqualTo(order.buyerId());
        assertThat(rating.get("rater_name")).isEqualTo("N***h");

        Map<String, Object> stored = jdbc.queryForMap("SELECT * FROM outbox");
        JsonNode event = JSON.readTree(stored.get("payload").toString());
        assertThat(stored.get("topic")).isEqualTo(Topics.RATING);
        assertThat(stored.get("aggregate_type")).isEqualTo("Rating");
        assertThat(stored.get("aggregate_id")).isEqualTo(ratingId.toString());
        assertThat(stored.get("published_at")).isNull();
        assertThat(event.path("eventType").asString()).isEqualTo("UserRated");
        assertThat(event.path("aggregateId").asString()).isEqualTo(ratingId.toString());
        assertThat(event.path("eventId").asString()).isEqualTo(stored.get("id").toString());
        assertThat(event.path("traceId").asString()).isEqualTo(TRACE_ID);
        JsonNode payload = event.path("payload");
        assertThat(payload.size()).isEqualTo(9);
        assertThat(payload.path("ratingId").asString()).isEqualTo(ratingId.toString());
        assertThat(payload.path("orderId").asString()).isEqualTo(order.orderId().toString());
        assertThat(payload.path("auctionId").asString()).isEqualTo(order.auctionId().toString());
        assertThat(payload.path("fromUserId").asString()).isEqualTo(order.buyerId().toString());
        assertThat(payload.path("toUserId").asString()).isEqualTo(order.sellerId().toString());
        assertThat(payload.path("actorId").asString()).isEqualTo(order.buyerId().toString());
        assertThat(payload.path("sourceVersion").asLong()).isZero();
        assertThat(payload.path("score").asInt()).isEqualTo(5);
        assertThat(payload.path("ratedAt").asString()).endsWith("Z");
        Instant persistedTime = jdbc.queryForObject("SELECT created_at FROM ratings WHERE id = ?",
                (row, index) -> row.getObject(1, OffsetDateTime.class).toInstant(), ratingId);
        assertThat(Instant.parse(payload.path("ratedAt").asString())).isEqualTo(persistedTime);
        assertThat(data.path("createdAt").asString()).isEqualTo(payload.path("ratedAt").asString());
        assertThat(event.toString()).doesNotContain("Nguyễn", "Delivered as described", "raterName", "email");
        assertThat(keys.resolve(new EventEnvelope<>(UUID.randomUUID(), "UserRated", ratingId.toString(),
                Instant.now(), TRACE_ID, payload))).isEqualTo(order.sellerId().toString());
        assertThat(count("user_reputations")).isZero();
    }

    @Test
    void eachPartyCanRateOnceAndTheSecondSubmissionReturns409WithoutAnotherEvent() throws Exception {
        Fixture order = completedOrder(null);
        client.perform(create(order, order.buyerId(), "BUYER", "Nguyễn Văn An", 4))
                .andExpect(status().isCreated());
        client.perform(create(order, order.buyerId(), "BUYER", "Nguyễn Văn An", 1))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RATING_ALREADY_EXISTS"));
        client.perform(create(order, order.sellerId(), "SELLER", "Trần Văn Bình", 5))
                .andExpect(status().isCreated());

        assertThat(count("ratings")).isEqualTo(2);
        assertThat(count("outbox")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT to_user_id FROM ratings", UUID.class))
                .containsExactlyInAnyOrder(order.buyerId(), order.sellerId());
    }

    @Test
    void missingCompletionAndOutsiderCannotWriteARating() throws Exception {
        Fixture missing = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null);
        client.perform(create(missing, missing.buyerId(), "BUYER", "Nguyễn Văn An", 4))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RATING_ORDER_NOT_COMPLETED"));

        Fixture order = completedOrder(null);
        String body = JSON.writeValueAsString(Map.of(
                "orderId", order.orderId(), "score", 4, "fromUserId", order.buyerId(),
                "toUserId", order.sellerId()));
        client.perform(post("/api/v1/ratings").header("Authorization", authorization(
                        UUID.randomUUID(), "BUYER", List.of("REPUTATION.RATE"), "Outsider"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RATING_NOT_ORDER_PARTICIPANT"));

        assertThat(count("ratings")).isZero();
        assertThat(count("outbox")).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidRatingInputsReturn400WithoutWrites(String bodyTemplate) throws Exception {
        Fixture order = completedOrder(null);
        client.perform(post("/api/v1/ratings").header("Authorization", authorization(
                        order.buyerId(), "BUYER", List.of("REPUTATION.RATE"), "Buyer Name"))
                .contentType(MediaType.APPLICATION_JSON).content(bodyTemplate.replace("ORDER", order.orderId().toString())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RATING_VALIDATION_FAILED"));
        assertThat(count("ratings")).isZero();
        assertThat(count("outbox")).isZero();
    }

    static Stream<String> invalidBodies() {
        return Stream.of(
                "{\"orderId\":\"ORDER\",\"score\":0}",
                "{\"orderId\":\"ORDER\",\"score\":6}",
                "{\"orderId\":\"ORDER\",\"score\":null}",
                "{\"orderId\":\"ORDER\"}",
                "{\"score\":4}",
                "{\"orderId\":\"ORDER\",\"score\":4,\"comment\":\"" + "x".repeat(1001) + "\"}");
    }

    @ParameterizedTest
    @MethodSource("invalidScoreTypes")
    void nonIntegerScoresAreRejectedWithoutCoercion(String score) throws Exception {
        Fixture order = completedOrder(null);
        client.perform(post("/api/v1/ratings").header("Authorization", authorization(
                        order.buyerId(), "BUYER", List.of("REPUTATION.RATE"), "Buyer Name"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + order.orderId() + "\",\"score\":" + score + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RATING_INVALID_REQUEST_BODY"));
        assertThat(count("ratings")).isZero();
        assertThat(count("outbox")).isZero();
    }

    static Stream<String> invalidScoreTypes() {
        return Stream.of("4.5", "5.0", "\"4\"", "true", "2147483648");
    }

    @Test
    void scoreBoundsAndThousandCharacterOrNullCommentAreAccepted() throws Exception {
        Fixture first = completedOrder(null);
        client.perform(post("/api/v1/ratings").header("Authorization", authorization(
                        first.buyerId(), "BUYER", List.of("REPUTATION.RATE"), null))
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("orderId", first.orderId(), "score", 1, "comment", "x".repeat(1000)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.score").value(1))
                .andExpect(jsonPath("$.data.comment").value("x".repeat(1000)))
                .andExpect(jsonPath("$.data.raterName").value("***"));
        Fixture second = completedOrder(null);
        client.perform(post("/api/v1/ratings").header("Authorization", authorization(
                        second.buyerId(), "BUYER", List.of("REPUTATION.RATE"), null))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + second.orderId() + "\",\"score\":5,\"comment\":null}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.score").value(5));
        assertThat(count("ratings")).isEqualTo(2);
        assertThat(count("outbox")).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"ADMIN,REPUTATION.RATE", "SUPPORT_STAFF,REPUTATION.RATE", "BUYER,REPUTATION.VIEW", "SELLER,REPUTATION.VIEW"})
    void ratingCreationRequiresBothAllowedRoleAndRatePrivilege(String role, String privilege) throws Exception {
        Fixture order = completedOrder(null);
        client.perform(post("/api/v1/ratings").header("Authorization", authorization(
                        order.buyerId(), role, List.of(privilege), "Buyer Name"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + order.orderId() + "\",\"score\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RATING_FORBIDDEN"));
        assertThat(count("ratings")).isZero();
        assertThat(count("outbox")).isZero();
    }

    @Test
    void missingAndInvalidTokensReturnRatingProblemDetailsWithAuthenticateHeader() throws Exception {
        client.perform(get("/api/v1/ratings").param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.code").value("RATING_UNAUTHENTICATED"));
        client.perform(get("/api/v1/ratings").param("userId", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("RATING_UNAUTHENTICATED"));
    }

    @Test
    void malformedJsonUnsupportedMediaAndMissingReadPrivilegeUseRatingErrors() throws Exception {
        String auth = authorization(UUID.randomUUID(), "BUYER", List.of("REPUTATION.RATE"), null);
        client.perform(post("/api/v1/ratings").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RATING_INVALID_REQUEST_BODY"));
        client.perform(post("/api/v1/ratings").header("Authorization", auth)
                        .contentType(MediaType.TEXT_PLAIN).content("rating"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RATING_UNSUPPORTED_MEDIA_TYPE"));
        client.perform(get("/api/v1/ratings").header("Authorization", auth)
                        .param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RATING_FORBIDDEN"));
        assertThat(count("ratings")).isZero();
        assertThat(count("outbox")).isZero();
    }

    @ParameterizedTest
    @CsvSource({"ADMIN", "SELLER", "BUYER", "SUPPORT_STAFF"})
    void allReadRolesReceiveEmptyPageWithRequiredViewPrivilege(String role) throws Exception {
        client.perform(get("/api/v1/ratings").param("userId", UUID.randomUUID().toString())
                        .header("Authorization", authorization(UUID.randomUUID(), role,
                                List.of("REPUTATION.VIEW"), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
    }

    @Test
    void receiverFilterStableOrderAndLegacyFallbackDoNotLeakRaterIdentity() throws Exception {
        UUID receiver = UUID.randomUUID();
        UUID rater = UUID.randomUUID();
        UUID first = UUID.fromString("00000000-0000-4000-8000-000000000003");
        UUID second = UUID.fromString("00000000-0000-4000-8000-000000000002");
        insertLegacyRating(first, rater, receiver);
        insertLegacyRating(second, UUID.randomUUID(), receiver);
        insertLegacyRating(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        String authorization = authorization(UUID.randomUUID(), "SUPPORT_STAFF", List.of("REPUTATION.VIEW"), null);
        MvcResult result = client.perform(get("/api/v1/ratings").param("userId", receiver.toString())
                        .param("size", "1").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(first.toString()))
                .andExpect(jsonPath("$.data[0].raterName").value("***"))
                .andExpect(jsonPath("$.data[0].fromUserId").doesNotExist())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.totalPages").value(2)).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(rater.toString());
        client.perform(get("/api/v1/ratings").param("userId", receiver.toString())
                        .param("page", "1").param("size", "1").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(second.toString()));
    }

    @ParameterizedTest
    @MethodSource("invalidListParameters")
    void invalidListParametersAreRejected(String query) throws Exception {
        client.perform(get("/api/v1/ratings" + query).header("Authorization",
                        authorization(UUID.randomUUID(), "BUYER", List.of("REPUTATION.VIEW"), null)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    static Stream<String> invalidListParameters() {
        String user = "?userId=" + UUID.randomUUID();
        return Stream.of("", "?userId=not-a-uuid", user + "&page=-1", user + "&size=0", user + "&size=101");
    }

    @Test
    void anOutboxFailureRollsBackBothTheRatingAndItsInsertedEvent() throws Exception {
        Fixture order = completedOrder(null);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Forced failure after the outbox insert");
        }).when(outbox).write(anyString(), anyString(), any());

        client.perform(create(order, order.buyerId(), "BUYER", "Buyer Name", 5))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("RATING_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected rating service error occurred"));
        assertThat(count("ratings")).isZero();
        assertThat(count("outbox")).isZero();
    }

    @Test
    void concurrentDuplicateRequestsCommitOneRatingAndOneEvent() throws Exception {
        Fixture order = completedOrder(null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var responses = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int index = 0; index < 2; index++) {
                responses.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent rating start timed out");
                    }
                    return client.perform(create(order, order.buyerId(), "BUYER", "Buyer Name", 5))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var response : responses) {
                statuses.add(response.get(20, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(count("ratings")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    private MockHttpServletRequestBuilder create(Fixture order, UUID actor, String role, String name, int score) {
        return post("/api/v1/ratings").header("Authorization", authorization(actor, role, List.of("REPUTATION.RATE"), name))
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of(
                        "orderId", order.orderId(), "score", score, "comment", "Delivered as described")));
    }

    private static String authorization(UUID actor, String role, List<String> privileges, String name) {
        String token = UUID.randomUUID().toString();
        var builder = Jwt.withTokenValue(token).header("alg", "RS256").subject(actor.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .claim("roles", List.of(role)).claim("privileges", privileges);
        if (name != null) {
            builder.claim("name", name);
        }
        TOKENS.put(token, builder.build());
        return "Bearer " + token;
    }

    private Fixture completedOrder(UUID auctionId) {
        Fixture order = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), auctionId);
        jdbc.update("""
                INSERT INTO rating_eligibility
                    (order_id, buyer_id, seller_id, completed_at, auction_id, source_event_id, source_version)
                VALUES (?, ?, ?, now() - interval '1 hour', ?, ?, 1)
                """, order.orderId(), order.buyerId(), order.sellerId(), auctionId, UUID.randomUUID());
        return order;
    }

    private void insertLegacyRating(UUID id, UUID rater, UUID receiver) {
        jdbc.update("""
                INSERT INTO ratings (id, from_user_id, to_user_id, score, created_at)
                VALUES (?, ?, ?, 3, '2026-11-01T00:00:00Z')
                """, id, rater, receiver);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private record Fixture(UUID orderId, UUID buyerId, UUID sellerId, UUID auctionId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        JwtDecoder decoder() {
            return token -> {
                Jwt jwt = TOKENS.get(token);
                if (jwt == null) {
                    throw new BadJwtException("Invalid test token");
                }
                return jwt;
            };
        }

        @Bean
        EventEnvelopeFactory envelopes() {
            Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
            when(tracer.currentSpan().context().traceId()).thenReturn(TRACE_ID);
            return new EventEnvelopeFactory(tracer, Clock.systemUTC());
        }

        @Bean
        OutboxWriter outbox(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
            return spy(new OutboxWriter(jdbc, JSON, transactions));
        }
    }
}
