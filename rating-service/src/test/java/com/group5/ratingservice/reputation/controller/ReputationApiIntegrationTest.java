package com.group5.ratingservice.reputation.controller;

import com.group5.common_events.EventEnvelopeFactory;
import com.group5.ratingservice.shared.RatingPostgresIntegrationTest;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
@Import(ReputationApiIntegrationTest.Config.class)
class ReputationApiIntegrationTest extends RatingPostgresIntegrationTest {
    private static final Map<String, Jwt> TOKENS = new ConcurrentHashMap<>();
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final String BASE = "/api/v1/reputations/";
    private static final String INTERNAL = "/internal/v1/eligibility/";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    private MockMvc client;

    @BeforeEach
    void setup() {
        TOKENS.clear();
        jdbc.execute("TRUNCATE rating_disputes,reputation_restrictions,reputation_moderation_audit,reputation_adjustment_requests, reputation_history, reputation_effects, reputation_sources, reputation_outcomes, ratings, rating_eligibility, user_reputations, outbox, processed_events");
        client = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SELLER", "BUYER", "SUPPORT_STAFF"})
    void eachPermittedRoleReadsNeutralReputationWithoutCreatingAnAggregate(String role) throws Exception {
        UUID target = UUID.randomUUID();
        client.perform(auth(get(BASE + target), user(role, "REPUTATION.VIEW")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(target.toString()))
                .andExpect(jsonPath("$.data.score").value(50))
                .andExpect(jsonPath("$.data.trustLevel").value("NEW"))
                .andExpect(jsonPath("$.data.totalTransaction").value(0))
                .andExpect(jsonPath("$.data.ratingCount").value(0))
                .andExpect(jsonPath("$.data.policyVersion").value("reputation-policy-1"))
                .andExpect(jsonPath("$.page").doesNotExist());
        assertThat(count("user_reputations")).isZero();
    }

    @Test
    void adminAdjustmentPersistsReasonActorHistoryAndExactReplayResult() throws Exception {
        UUID target = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        String admin = user(actor, "ADMIN", "USER.REPUTATION.ADJUST");
        String body = "{\"delta\":7,\"reason\":\"  Verified late-payment correction  \"}";
        MvcResult created = client.perform(auth(post(BASE + target + "/adjustments"), admin)
                        .header("Idempotency-Key", "213-adjustment").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reputation.score").value(57))
                .andExpect(jsonPath("$.data.replayed").value(false)).andReturn();
        String operation = JSON.readTree(created.getResponse().getContentAsString()).path("data").path("operationId").asString();
        client.perform(auth(post(BASE + target + "/adjustments"), admin)
                        .header("Idempotency-Key", "213-adjustment").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationId").value(operation))
                .andExpect(jsonPath("$.data.reputation.score").value(57))
                .andExpect(jsonPath("$.data.replayed").value(true));
        assertThat(count("reputation_history")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM reputation_history", UUID.class)).isEqualTo(actor);
        assertThat(jdbc.queryForObject("SELECT created_by FROM user_reputations WHERE user_id = ?", UUID.class, target))
                .isEqualTo(actor);
        assertThat(jdbc.queryForObject("SELECT reason FROM reputation_history", String.class))
                .isEqualTo("Verified late-payment correction");
        assertThat(jdbc.queryForObject("SELECT score FROM user_reputations WHERE user_id = ?", Integer.class, target))
                .isEqualTo(57);
    }

    @Test
    void adjustmentReplayRetainsItsOriginalResultAfterSubsequentAdjustments() throws Exception {
        UUID target = UUID.randomUUID();
        String admin = user("ADMIN", "USER.REPUTATION.ADJUST");
        adjustment(target, admin, 10, "first", "first-key").andExpect(status().isCreated());
        adjustment(target, admin, 5, "second", "second-key").andExpect(status().isCreated());
        adjustment(target, admin, 10, "first", "first-key")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reputation.score").value(60));
        assertThat(jdbc.queryForObject("SELECT score FROM user_reputations WHERE user_id = ?", Integer.class, target))
                .isEqualTo(65);
        assertThat(count("reputation_history")).isEqualTo(2);
    }

    @Test
    void anAdjustmentKeyCannotBeReusedWithDifferentPayloadForTheSameTarget() throws Exception {
        UUID target = UUID.randomUUID();
        String admin = user("ADMIN", "USER.REPUTATION.ADJUST");
        adjustment(target, admin, 5, "review", "same-key").andExpect(status().isCreated());
        adjustment(target, admin, 6, "review", "same-key")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RATING_IDEMPOTENCY_CONFLICT"));
        adjustment(target, admin, 5, "different reason", "same-key").andExpect(status().isConflict());
        adjustment(target, user("ADMIN", "USER.REPUTATION.ADJUST"), 5, "review", "same-key").andExpect(status().isConflict());
        assertThat(count("reputation_history")).isEqualTo(1);
        adjustment(UUID.randomUUID(), admin, 5, "review", "same-key").andExpect(status().isCreated());
        assertThat(count("reputation_history")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {-51, 51, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void outOfRangeAdjustmentRollsBackAllEffectsAndDoesNotConsumeItsKey(int delta) throws Exception {
        UUID target = UUID.randomUUID();
        String admin = user("ADMIN", "USER.REPUTATION.ADJUST");
        adjustment(target, admin, delta, "invalid", "retry-key")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RATING_ADJUSTMENT_OUT_OF_RANGE"));
        assertThat(count("user_reputations")).isZero();
        assertThat(count("reputation_history")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(count("reputation_adjustment_requests")).isZero();
        adjustment(target, admin, 0, "valid", "retry-key").andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"delta\":null,\"reason\":\"why\"}", "{\"delta\":1}",
            "{\"delta\":1,\"reason\":\"   \"}", "{\"delta\":1.5,\"reason\":\"why\"}",
            "{\"delta\":\"1\",\"reason\":\"why\"}", "{\"delta\":true,\"reason\":\"why\"}",
            "{\"delta\":2147483648,\"reason\":\"why\"}"
    })
    void invalidAdjustmentBodiesCannotMutateState(String body) throws Exception {
        client.perform(auth(post(BASE + UUID.randomUUID() + "/adjustments"), user("ADMIN", "USER.REPUTATION.ADJUST"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.startsWith("RATING_")));
        assertThat(count("user_reputations")).isZero();
        assertThat(count("reputation_history")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "two words", "nonascii-é"})
    void invalidIdempotencyKeysAreRejectedBeforeWriting(String key) throws Exception {
        adjustment(UUID.randomUUID(), user("ADMIN", "USER.REPUTATION.ADJUST"), 1, "why", key)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RATING_VALIDATION_FAILED"));
        assertThat(count("reputation_history")).isZero();
    }

    @Test
    void longReasonAndLongKeyAreRejected() throws Exception {
        adjustment(UUID.randomUUID(), user("ADMIN", "USER.REPUTATION.ADJUST"), 1, "r".repeat(1001), "key")
                .andExpect(status().isBadRequest());
        adjustment(UUID.randomUUID(), user("ADMIN", "USER.REPUTATION.ADJUST"), 1, "why", "k".repeat(129))
                .andExpect(status().isBadRequest());
        assertThat(count("reputation_history")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUYER", "SELLER", "SUPPORT_STAFF"})
    void privilegedNonAdminsStillCannotAdjustScores(String role) throws Exception {
        adjustment(UUID.randomUUID(), user(role, "USER.REPUTATION.ADJUST"), 1, "why", "key")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("RATING_FORBIDDEN"));
        assertThat(count("reputation_history")).isZero();
    }

    @Test
    void adminWithoutAdjustmentPrivilegeCannotWrite() throws Exception {
        adjustment(UUID.randomUUID(), user("ADMIN", "REPUTATION.VIEW"), 1, "why", "key")
                .andExpect(status().isForbidden());
        assertThat(count("reputation_history")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPPORT_STAFF"})
    void authorizedHistoryHasStablePagesAndAuditEvidence(String role) throws Exception {
        UUID target = UUID.randomUUID();
        String admin = user("ADMIN", "USER.REPUTATION.ADJUST");
        adjustment(target, admin, 1, "first", "one").andExpect(status().isCreated());
        adjustment(target, admin, 1, "second", "two").andExpect(status().isCreated());
        client.perform(auth(get(BASE + target + "/history").param("size", "1"), user(role, "USER.REPUTATION.VIEW")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.data[0].reason").value("second"))
                .andExpect(jsonPath("$.data[0].previousScore").value(51))
                .andExpect(jsonPath("$.data[0].newScore").value(52))
                .andExpect(jsonPath("$.data[0].delta").value(1))
                .andExpect(jsonPath("$.data[0].policyVersion").value("reputation-policy-1"));
        client.perform(auth(get(BASE + target + "/history").param("page", "99"), user(role, "USER.REPUTATION.VIEW")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUYER", "SELLER"})
    void endUsersCannotReadPrivateHistoryEvenWithItsPrivilege(String role) throws Exception {
        client.perform(auth(get(BASE + UUID.randomUUID() + "/history"), user(role, "USER.REPUTATION.VIEW")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("RATING_FORBIDDEN"));
    }

    @ParameterizedTest
    @CsvSource({"39,BID,false,40", "40,BID,true,40", "49,SELL,false,50", "50,SELL,true,50", "0,BID,false,40", "100,SELL,true,50"})
    void machineEligibilityUsesActionSpecificThresholds(int score, String action, boolean expected, int minimum) throws Exception {
        UUID target = seedScore(score);
        client.perform(auth(get(INTERNAL + target).param("action", action), machine("nexus-auction-service", true, "RATING.ELIGIBILITY.CHECK")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(target.toString()))
                .andExpect(jsonPath("$.data.action").value(action))
                .andExpect(jsonPath("$.data.eligible").value(expected))
                .andExpect(jsonPath("$.data.minimumScore").value(minimum))
                .andExpect(jsonPath("$.data.reason").isNotEmpty());
    }

    @Test
    void uninitializedUserIsEligibleAtNeutralDefaults() throws Exception {
        String machine = machine("nexus-auction-service", true, "RATING.ELIGIBILITY.CHECK");
        client.perform(auth(get(INTERNAL + UUID.randomUUID()).param("action", "SELL"), machine))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.eligible").value(true))
                .andExpect(jsonPath("$.data.score").value(50)).andExpect(jsonPath("$.data.trustLevel").value("NEW"));
        assertThat(count("user_reputations")).isZero();
    }

    @Test
    void unreconciledHistoricalCountersFailClosedForEligibilityAndAdjustment() throws Exception {
        UUID target = seedScore(100);
        jdbc.update("UPDATE user_reputations SET total_transaction=10,successful_transaction=10,policy_version=NULL WHERE user_id=?", target);
        client.perform(auth(get(INTERNAL + target).param("action", "SELL"), machine("nexus-auction-service", true, "RATING.ELIGIBILITY.CHECK")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RATING_RECONCILIATION_REQUIRED"));
        adjustment(target, user("ADMIN", "USER.REPUTATION.ADJUST"), 0, "cannot bypass reconciliation", "key")
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RATING_RECONCILIATION_REQUIRED"));
        client.perform(auth(get(BASE + target), user("ADMIN", "REPUTATION.VIEW")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.score").value(100));
        assertThat(count("reputation_history")).isZero();
        assertThat(count("outbox")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPPORT_STAFF", "BUYER", "SELLER"})
    void everyEndUserRoleIsDeniedInternalEligibility(String role) throws Exception {
        client.perform(auth(get(INTERNAL + UUID.randomUUID()).param("action", "BID"), user(role, "RATING.ELIGIBILITY.CHECK")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("RATING_FORBIDDEN"));
    }

    @Test
    void partialMachineCredentialsCannotBypassInternalAuthorization() throws Exception {
        for (String token : List.of(machine("other-client", true, "RATING.ELIGIBILITY.CHECK"),
                machine("nexus-auction-service", false, "RATING.ELIGIBILITY.CHECK"),
                machine("nexus-auction-service", true, "REPUTATION.VIEW"))) {
            client.perform(auth(get(INTERNAL + UUID.randomUUID()).param("action", "BID"), token))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void invalidAndMissingActionOrIdentifiersReturnRatingProblemDetails() throws Exception {
        String machine = machine("nexus-auction-service", true, "RATING.ELIGIBILITY.CHECK");
        client.perform(auth(get(INTERNAL + UUID.randomUUID()).param("action", "bid"), machine))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RATING_INVALID_PARAMETER"));
        client.perform(auth(get(INTERNAL + UUID.randomUUID()), machine)).andExpect(status().isBadRequest());
        client.perform(auth(get(INTERNAL + "invalid").param("action", "BID"), machine)).andExpect(status().isBadRequest());
        client.perform(auth(get(BASE + "invalid"), user("ADMIN", "REPUTATION.VIEW")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RATING_INVALID_PARAMETER"));
    }

    @Test
    void missingAndInvalidTokensReturnBearerChallengeWithoutWriting() throws Exception {
        client.perform(get(INTERNAL + UUID.randomUUID()).param("action", "BID"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("RATING_UNAUTHENTICATED"));
        client.perform(get(BASE + UUID.randomUUID()).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        assertThat(count("user_reputations")).isZero();
    }

    @Test
    void ownersReadTheirReputationAndOnlyTheScoreLossesAsPenalties() throws Exception {
        UUID owner = UUID.randomUUID();
        String admin = user("ADMIN", "USER.REPUTATION.ADJUST");
        adjustment(owner, admin, -8, "Unpaid auction win", "penalty-1").andExpect(status().isCreated());
        adjustment(owner, admin, 3, "Goodwill", "bonus-1").andExpect(status().isCreated());
        adjustment(owner, admin, -2, "Late shipment", "penalty-2").andExpect(status().isCreated());
        adjustment(UUID.randomUUID(), admin, -5, "Someone else", "other-1").andExpect(status().isCreated());
        jdbc.update("UPDATE user_reputations SET rating_count = 3, rating_score_sum = 13, successful_auction_count = 2 WHERE user_id = ?", owner);
        String token = user(owner, "BUYER", "REPUTATION.VIEW");

        client.perform(auth(get("/api/v1/users/me/reputation"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(43))
                .andExpect(jsonPath("$.data.averageRating").value(4.3))
                .andExpect(jsonPath("$.data.successfulAuctions").value(2))
                .andExpect(jsonPath("$.data.penaltyCount").value(2))
                .andExpect(jsonPath("$.data.userId").doesNotExist());
        client.perform(auth(get("/api/v1/users/me/penalties").param("size", "1"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].reason").value("Late shipment"))
                .andExpect(jsonPath("$.data[0].delta").value(-2))
                .andExpect(jsonPath("$.data[0].actorId").doesNotExist())
                .andExpect(jsonPath("$.page.totalElements").value(2));
        client.perform(auth(get("/api/v1/users/me/penalties").param("page", "1").param("size", "1"), token))
                .andExpect(jsonPath("$.data[0].reason").value("Unpaid auction win"))
                .andExpect(jsonPath("$.data[0].previousScore").value(50))
                .andExpect(jsonPath("$.data[0].newScore").value(42));
    }

    @Test
    void aNewUserSeesNeutralReputationAndNoPenaltiesWithoutCreatingAnAggregate() throws Exception {
        String token = user(UUID.randomUUID(), "SELLER", "REPUTATION.VIEW");
        client.perform(auth(get("/api/v1/users/me/reputation"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(50))
                .andExpect(jsonPath("$.data.trustLevel").value("NEW"))
                .andExpect(jsonPath("$.data.averageRating").isEmpty())
                .andExpect(jsonPath("$.data.penaltyCount").value(0));
        client.perform(auth(get("/api/v1/users/me/penalties"), token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(count("user_reputations")).isZero();
    }

    @Test
    void ownReputationNeedsTheViewPrivilegeAValidTokenAndSanePages() throws Exception {
        client.perform(auth(get("/api/v1/users/me/reputation"), user("BUYER", "PROFILE.VIEW")))
                .andExpect(status().isForbidden());
        client.perform(get("/api/v1/users/me/penalties")).andExpect(status().isUnauthorized());
        client.perform(auth(get("/api/v1/users/me/penalties").param("size", "51"), user("BUYER", "REPUTATION.VIEW")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RATING_VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "size,0", "size,101"})
    void historyRejectsInvalidPagination(String parameter, String value) throws Exception {
        client.perform(auth(get(BASE + UUID.randomUUID() + "/history").param(parameter, value), user("ADMIN", "USER.REPUTATION.VIEW")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RATING_VALIDATION_FAILED"));
    }

    private org.springframework.test.web.servlet.ResultActions adjustment(
            UUID target, String token, int delta, String reason, String key) throws Exception {
        MockHttpServletRequestBuilder request = auth(post(BASE + target + "/adjustments"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("delta", delta, "reason", reason)));
        if (key != null) request.header("Idempotency-Key", key);
        return client.perform(request);
    }

    private UUID seedScore(int score) {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO user_reputations (user_id,score,trust_level,policy_version) VALUES (?,?,'BASIC','reputation-policy-1')", user, score);
        return user;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private static String user(String role, String privilege) {
        return user(UUID.randomUUID(), role, privilege);
    }

    private static String user(UUID id, String role, String privilege) {
        String token = UUID.randomUUID().toString();
        TOKENS.put(token, Jwt.withTokenValue(token).header("alg", "RS256").subject(id.toString())
                .claim("roles", List.of(role)).claim("privileges", List.of(privilege)).claim("name", "Administrator")
                .claim("azp", "nexus-web").build());
        return token;
    }

    private static String machine(String client, boolean serviceAccount, String privilege) {
        String token = UUID.randomUUID().toString();
        TOKENS.put(token, Jwt.withTokenValue(token).header("alg", "RS256").subject(UUID.randomUUID().toString())
                .claim("azp", client).claim("service_account", serviceAccount).claim("privileges", List.of(privilege))
                .claim("roles", List.of()).build());
        return token;
    }

    @TestConfiguration
    static class Config {
        @Bean
        JwtDecoder testJwtDecoder() {
            return token -> {
                Jwt jwt = TOKENS.get(token);
                if (jwt == null) throw new BadJwtException("Invalid test token");
                return jwt;
            };
        }

        @Bean
        EventEnvelopeFactory testEnvelopes() {
            Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
            when(tracer.currentSpan().context().traceId()).thenReturn("21300000000000000000000000000000");
            return new EventEnvelopeFactory(tracer, Clock.systemUTC());
        }
    }
}
