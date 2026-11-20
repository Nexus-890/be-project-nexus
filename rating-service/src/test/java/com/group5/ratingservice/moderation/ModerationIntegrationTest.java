package com.group5.ratingservice.moderation;

import com.group5.common_events.EventEnvelopeFactory;
import com.group5.ratingservice.moderation.dto.DisputeStatus;
import com.group5.ratingservice.moderation.service.ModerationService;
import com.group5.ratingservice.reputation.backfill.ReputationBackfill;
import com.group5.ratingservice.reputation.event.ReputationInputs;
import com.group5.ratingservice.reputation.service.ReputationService;
import com.group5.ratingservice.shared.RatingPostgresIntegrationTest;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.config.name=rating-service-test", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.kafka.listener.auto-startup=false", "nexus.outbox.relay.enabled=false",
        "nexus.security.jwt.issuer=rating-test", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1/.well-known/jwks.json"
})
@Import(ModerationIntegrationTest.Config.class)
class ModerationIntegrationTest extends RatingPostgresIntegrationTest {
    private static final Map<String, Jwt> TOKENS = new ConcurrentHashMap<>();
    private static final JsonMapper JSON = JsonMapper.shared();
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReputationService reputations;
    @Autowired private ModerationService moderation;
    @Autowired private ReputationBackfill backfill;
    private MockMvc client;

    @BeforeEach
    void setup() {
        TOKENS.clear();
        jdbc.execute("TRUNCATE rating_disputes,reputation_restrictions,reputation_moderation_audit,reputation_adjustment_requests,reputation_history,reputation_effects,reputation_sources,reputation_outcomes,ratings,rating_eligibility,user_reputations,outbox,processed_events");
        client = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void restrictionsDenyHighScoresByActionAndRevokeWithAudit() throws Exception {
        UUID user = UUID.randomUUID(), adminId = UUID.randomUUID();
        String admin = user(adminId, "ADMIN", "USER.REPUTATION.ADJUST");
        reputations.adjust(user, 40, "verified", adminId, null);
        String base = "/api/v1/reputations/" + user + "/restrictions";
        String until = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString();
        String id = createRestriction(base, admin, "BID", until);
        eligibility(user, "BID", false).andExpect(jsonPath("$.data.restrictedUntil").isNotEmpty());
        eligibility(user, "SELL", true);
        client.perform(auth(get(base), user(user, "BUYER", "REPUTATION.VIEW")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].active").value(true));
        client.perform(auth(delete(base + "/" + id).param("reason", "Appeal verified"), admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.revokedBy").value(adminId.toString()));
        client.perform(auth(delete(base + "/" + id).param("reason", "Appeal verified"), admin)).andExpect(status().isOk());
        eligibility(user, "BID", true).andExpect(jsonPath("$.data.restrictedUntil").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_moderation_audit", Integer.class)).isEqualTo(2);
    }

    @Test
    void overlappingRestrictionsUseTheLatestExpiryAndExpiredRestrictionsStopApplying() throws Exception {
        UUID user = UUID.randomUUID();
        String admin = user(UUID.randomUUID(), "ADMIN", "USER.REPUTATION.ADJUST");
        String base = "/api/v1/reputations/" + user + "/restrictions";
        String first = createRestriction(base, admin, "BID", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).toString());
        String second = createRestriction(base, admin, "BID", OffsetDateTime.now(ZoneOffset.UTC).plusHours(2).toString());
        client.perform(auth(delete(base + "/" + first).param("reason", "First resolved"), admin)).andExpect(status().isOk());
        eligibility(user, "BID", false);
        jdbc.update("UPDATE reputation_restrictions SET created_at=clock_timestamp()-interval '2 days', restricted_until=clock_timestamp()-interval '1 day' WHERE id=?", UUID.fromString(second));
        eligibility(user, "BID", true);
    }

    @Test
    void restrictionsRequirePrivilegesOwnershipAndValidFutureData() throws Exception {
        UUID target = UUID.randomUUID();
        String base = "/api/v1/reputations/" + target + "/restrictions";
        String payload = JSON.writeValueAsString(Map.of("action", "BID", "reason", "abuse", "restrictedUntil", OffsetDateTime.now().plusDays(1)));
        client.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isUnauthorized());
        for (String token : List.of(user(UUID.randomUUID(), "BUYER", "USER.REPUTATION.ADJUST"),
                user(UUID.randomUUID(), "ADMIN", "REPUTATION.VIEW"))) {
            client.perform(auth(post(base), token).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
        }
        client.perform(auth(get(base), user(UUID.randomUUID(), "BUYER", "REPUTATION.VIEW"))).andExpect(status().isForbidden());
        String admin = user(UUID.randomUUID(), "ADMIN", "USER.REPUTATION.ADJUST");
        for (String body : List.of("{\"action\":\"BID\",\"reason\":\"  \",\"restrictedUntil\":\"2099-01-01T00:00:00Z\"}",
                "{\"action\":\"SELL\",\"reason\":\"abuse\",\"restrictedUntil\":\"2020-01-01T00:00:00Z\"}")) {
            client.perform(auth(post(base), admin).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_restrictions", Integer.class)).isZero();
    }

    @Test
    void approvedDisputeHidesReviewRecalculatesAverageAndCannotBeReplayed() throws Exception {
        var low = rating(UUID.randomUUID(), 1, true);
        var high = rating(low.toUserId(), 5, true);
        String id = createDispute(low, "Seller disputes false review");
        String owner = user(low.toUserId(), "SELLER", "REPUTATION.VIEW");
        client.perform(auth(get("/api/v1/ratings").param("userId", low.toUserId().toString()), owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(2));
        String admin = user(UUID.randomUUID(), "ADMIN", "USER.REPUTATION.ADJUST");
        resolve(id, admin, "APPROVED", "Evidence accepted").andExpect(status().isOk());
        resolve(id, admin, "APPROVED", "Evidence accepted").andExpect(status().isOk());
        resolve(id, admin, "REJECTED", "Changed mind").andExpect(status().isConflict());
        assertThat(reputations.get(low.toUserId()).ratingCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT rating_score_sum FROM user_reputations WHERE user_id=?", Long.class, low.toUserId())).isEqualTo(5);
        assertThat(reputations.get(low.toUserId()).score()).isEqualTo(100);
        assertThat(reputations.rate(low, UUID.randomUUID())).isFalse();
        assertThat(reputations.rate(high, UUID.randomUUID())).isFalse();
        backfill.batch(null, null, 100, false);
        assertThat(reputations.get(low.toUserId()).ratingCount()).isEqualTo(1);
        client.perform(auth(get("/api/v1/ratings").param("userId", low.toUserId().toString()), owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].id").value(high.ratingId().toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_history WHERE change_type='RATING_DISPUTE_APPROVED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_moderation_audit WHERE action='DISPUTE_APPROVED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void removingAFavorableRatingAppearsOnceInOwnPenaltiesAndUpdatesOwnReputation() throws Exception {
        var favorable = rating(UUID.randomUUID(), 5, true);
        String owner = user(favorable.toUserId(), "SELLER", "REPUTATION.VIEW");
        client.perform(auth(get("/api/v1/users/me/reputation"), owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.score").value(100))
                .andExpect(jsonPath("$.data.averageRating").value(5.0))
                .andExpect(jsonPath("$.data.ratingCount").value(1))
                .andExpect(jsonPath("$.data.penaltyCount").value(0));

        String id = createDispute(favorable, "Favorable review belongs to another transaction");
        String admin = user(UUID.randomUUID(), "ADMIN", "USER.REPUTATION.ADJUST");
        resolve(id, admin, "APPROVED", "Unrelated rating removed").andExpect(status().isOk());
        resolve(id, admin, "APPROVED", "Unrelated rating removed").andExpect(status().isOk());

        client.perform(auth(get("/api/v1/users/me/penalties"), owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].changeType").value("RATING_DISPUTE_APPROVED"))
                .andExpect(jsonPath("$.data[0].sourceId").value(favorable.ratingId().toString()))
                .andExpect(jsonPath("$.data[0].delta").value(-30))
                .andExpect(jsonPath("$.data[0].previousScore").value(100))
                .andExpect(jsonPath("$.data[0].newScore").value(70))
                .andExpect(jsonPath("$.data[0].reason").value("Unrelated rating removed"));
        client.perform(auth(get("/api/v1/users/me/reputation"), owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.score").value(70))
                .andExpect(jsonPath("$.data.averageRating").isEmpty())
                .andExpect(jsonPath("$.data.ratingCount").value(0))
                .andExpect(jsonPath("$.data.successfulTransaction").value(1))
                .andExpect(jsonPath("$.data.penaltyCount").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_moderation_audit WHERE action='DISPUTE_APPROVED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void approvalBeforeUserRatedPreventsLateEventAndBackfillFromRestoringTheRating() throws Exception {
        var rating = rating(UUID.randomUUID(), 1, false);
        String id = createDispute(rating, "Misleading review");
        resolve(id, user(UUID.randomUUID(), "ADMIN", "USER.REPUTATION.ADJUST"), "APPROVED", "Verified").andExpect(status().isOk());
        assertThat(reputations.rate(rating, UUID.randomUUID())).isFalse();
        assertThat(backfill.batch(null, null, 100, false).appliedRatings()).isZero();
        assertThat(reputations.get(rating.toUserId()).ratingCount()).isZero();
        assertThat(reputations.get(rating.toUserId()).score()).isEqualTo(70);
    }

    @Test
    void concurrentApprovalAndRatingEventLeaveNoContribution() throws Exception {
        var rating = rating(UUID.randomUUID(), 1, false);
        UUID dispute = UUID.fromString(createDispute(rating, "Incorrect"));
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var event = workers.submit(() -> { start.await(); return reputations.rate(rating, UUID.randomUUID()); });
            var approval = workers.submit(() -> { start.await(); return moderation.resolve(dispute, DisputeStatus.APPROVED, "Verified", UUID.randomUUID()); });
            start.countDown();
            event.get(10, TimeUnit.SECONDS);
            approval.get(10, TimeUnit.SECONDS);
        }
        assertThat(reputations.get(rating.toUserId()).ratingCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT hidden FROM ratings WHERE id=?", Boolean.class, rating.ratingId())).isTrue();
    }

    @Test
    void onlyTheRatedUserCanDisputeAndRejectionPreservesRating() throws Exception {
        var rating = rating(UUID.randomUUID(), 2, true);
        String path = "/api/v1/ratings/" + rating.ratingId() + "/disputes";
        client.perform(auth(post(path), user(rating.fromUserId(), "BUYER", "REPUTATION.RATE"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Own review\"}")).andExpect(status().isForbidden());
        String id = createDispute(rating, "Please review");
        client.perform(auth(post(path), user(rating.toUserId(), "SELLER", "REPUTATION.RATE"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Duplicate\"}")).andExpect(status().isConflict());
        String view = user(UUID.randomUUID(), "ADMIN", "USER.REPUTATION.VIEW");
        client.perform(auth(get("/api/v1/ratings/disputes").param("status", "PENDING"), view))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].ratingScore").value(2));
        resolve(id, view, "APPROVED", "Forbidden mutation").andExpect(status().isForbidden());
        client.perform(auth(get("/api/v1/ratings/disputes"), user(rating.toUserId(), "SELLER", "USER.REPUTATION.VIEW"))).andExpect(status().isForbidden());
        String admin = user(UUID.randomUUID(), "ADMIN", "USER.REPUTATION.ADJUST");
        resolve(id, admin, "PENDING", "Not a resolution").andExpect(status().isBadRequest());
        resolve(id, admin, "REJECTED", "Review is valid").andExpect(status().isOk());
        assertThat(reputations.get(rating.toUserId()).ratingCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT hidden FROM ratings WHERE id=?", Boolean.class, rating.ratingId())).isFalse();
        client.perform(auth(get("/api/v1/ratings").param("userId", rating.toUserId().toString()), user(rating.toUserId(), "SELLER", "REPUTATION.VIEW")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].disputeStatus").value("REJECTED"));
    }

    private ReputationInputs.RatingInput rating(UUID recipient, int score, boolean apply) {
        UUID buyer = UUID.randomUUID(), order = UUID.randomUUID(), rating = UUID.randomUUID();
        OffsetDateTime completed = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).withNano(0);
        var input = new ReputationInputs.RatingInput(rating, order, null, buyer, recipient, score, 0, buyer, completed.plusMinutes(1));
        jdbc.update("INSERT INTO rating_eligibility(order_id,buyer_id,seller_id,completed_at,source_event_id,source_version) VALUES (?,?,?,?,?,1)",
                order, buyer, recipient, completed, UUID.randomUUID());
        jdbc.update("INSERT INTO ratings(id,from_user_id,to_user_id,order_id,score,comment,created_at) VALUES (?,?,?,?,?,'Review',?)",
                rating, buyer, recipient, order, score, input.ratedAt());
        reputations.complete(new ReputationInputs.Completion(order, buyer, recipient, null, completed, 1L), UUID.randomUUID());
        if (apply) reputations.rate(input, UUID.randomUUID());
        return input;
    }

    private String createDispute(ReputationInputs.RatingInput rating, String reason) throws Exception {
        var response = client.perform(auth(post("/api/v1/ratings/" + rating.ratingId() + "/disputes"), user(rating.toUserId(), "SELLER", "REPUTATION.RATE"))
                        .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("reason", reason))))
                .andExpect(status().isCreated()).andReturn();
        return JSON.readTree(response.getResponse().getContentAsString()).path("data").path("id").asString();
    }

    private String createRestriction(String base, String admin, String action, String until) throws Exception {
        var response = client.perform(auth(post(base), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("action", action, "reason", "Repeated abuse", "restrictedUntil", until))))
                .andExpect(status().isCreated()).andReturn();
        return JSON.readTree(response.getResponse().getContentAsString()).path("data").path("id").asString();
    }

    private org.springframework.test.web.servlet.ResultActions resolve(String id, String admin, String status, String reason) throws Exception {
        return client.perform(auth(patch("/api/v1/ratings/disputes/" + id), admin).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("status", status, "reason", reason))));
    }

    private org.springframework.test.web.servlet.ResultActions eligibility(UUID user, String action, boolean expected) throws Exception {
        return client.perform(auth(get("/internal/v1/eligibility/" + user).param("action", action), machine()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.eligible").value(expected));
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private static String user(UUID id, String role, String privilege) {
        String token = UUID.randomUUID().toString();
        TOKENS.put(token, Jwt.withTokenValue(token).header("alg", "RS256").subject(id.toString())
                .claim("roles", List.of(role)).claim("privileges", List.of(privilege)).claim("azp", "nexus-web").build());
        return token;
    }

    private static String machine() {
        String token = UUID.randomUUID().toString();
        TOKENS.put(token, Jwt.withTokenValue(token).header("alg", "RS256").subject(UUID.randomUUID().toString())
                .claim("azp", "nexus-auction-service").claim("service_account", true)
                .claim("privileges", List.of("RATING.ELIGIBILITY.CHECK")).claim("roles", List.of()).build());
        return token;
    }

    @TestConfiguration
    static class Config {
        @Bean JwtDecoder testJwtDecoder() {
            return token -> { Jwt jwt = TOKENS.get(token); if (jwt == null) throw new BadJwtException("Invalid"); return jwt; };
        }
        @Bean EventEnvelopeFactory testEnvelopes() {
            Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
            when(tracer.currentSpan().context().traceId()).thenReturn("12300000000000000000000000000000");
            return new EventEnvelopeFactory(tracer, Clock.systemUTC());
        }
    }
}
