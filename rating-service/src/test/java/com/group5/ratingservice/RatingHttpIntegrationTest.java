package com.group5.ratingservice;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.group5.common_events.EventEnvelope;
import com.group5.ratingservice.rating.RatingEventKeyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP, signature validation, service transactions, PostgreSQL/Flyway and outbox. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=rating-http-test", "spring.cloud.config.enabled=false",
        "spring.cloud.config.import-check.enabled=false", "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.kafka.listener.auto-startup=false", "nexus.outbox.relay.enabled=false",
        "management.tracing.export.enabled=false"
})
@Import(RatingHttpIntegrationTest.TokenConfig.class)
class RatingHttpIntegrationTest extends RatingPostgresSupport {
    private static final RSAKey KEY = key();
    private static final UUID BUYER = UUID.fromString("68000000-0000-4000-8000-000000000003");
    private static final UUID SELLER = UUID.fromString("68000000-0000-4000-8000-000000000004");
    private static final UUID ORDER = UUID.fromString("68000000-0000-4000-8000-000000000002");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private RatingEventKeyResolver keys;
    @Value("${local.server.port}") private int port;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE ratings, rating_eligibility, user_reputations, outbox, processed_events");
        jdbc.update("""
                INSERT INTO rating_eligibility(order_id,buyer_id,seller_id,completed_at,source_event_id)
                VALUES (?,?,?,now(),?)
                """, ORDER, BUYER, SELLER, UUID.randomUUID());
    }

    @Test
    void buyerWritesOnceAndReadsThePersistedRatingAndCanonicalEvent() throws Exception {
        var created = post(BUYER, List.of("REPUTATION.RATE"), body(ORDER, SELLER, 4, "Well packed."));
        assertThat(created.statusCode()).isEqualTo(201);
        var resource = json.readTree(created.body()).path("data");
        assertThat(resource.path("fromUserId").asText()).isEqualTo(BUYER.toString());
        assertThat(resource.path("toUserId").asText()).isEqualTo(SELLER.toString());
        assertThat(resource.path("score").asInt()).isEqualTo(4);
        assertThat(resource.path("comment").asText()).isEqualTo("Well packed.");
        var read = get("/ratings?userId=" + SELLER + "&page=0&size=100", List.of("REPUTATION.VIEW"));
        assertThat(read.statusCode()).isEqualTo(200);
        var list = json.readTree(read.body());
        assertThat(list.path("data").size()).isEqualTo(1);
        assertThat(list.path("data").get(0)).isEqualTo(resource);
        assertThat(list.path("page").path("totalElements").asInt()).isEqualTo(1);
        assertProblem(post(BUYER, List.of("REPUTATION.RATE"), body(ORDER, SELLER, 5, null)), 409, "RATING_ALREADY_EXISTS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ratings", Integer.class)).isEqualTo(1);
        var event = json.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox", String.class));
        assertThat(event.path("eventType").asText()).isEqualTo("UserRated");
        assertThat(event.path("payload").path("ratingId").asText()).isEqualTo(resource.path("id").asText());
        assertThat(event.path("payload").path("actorId").asText()).isEqualTo(BUYER.toString());
        assertThat(event.path("payload").path("sourceVersion").asInt()).isZero();
        assertThat(event.path("payload").has("comment")).isFalse();
        assertThat(event.path("traceId").asText()).isNotBlank();
        assertThat(keys.resolve(new EventEnvelope<>(UUID.fromString(event.path("eventId").asText()),
                "UserRated", event.path("aggregateId").asText(), Instant.parse(event.path("occurredAt").asText()),
                event.path("traceId").asText(), event.path("payload")))).isEqualTo(SELLER.toString());
    }

    @Test
    void concurrentSubmissionsHaveOneWinnerAndOneOutboxEvent() throws Exception {
        var gate = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> { gate.await(); return post(BUYER, List.of("REPUTATION.RATE"), body(ORDER, SELLER, 4, null)); });
            var second = workers.submit(() -> { gate.await(); return post(BUYER, List.of("REPUTATION.RATE"), body(ORDER, SELLER, 5, null)); });
            gate.countDown();
            assertThat(Set.of(first.get().statusCode(), second.get().statusCode())).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ratings", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void ratingRequiresBuyerAndCompletedEligibilityAndTheRealSeller() throws Exception {
        assertProblem(post(SELLER, List.of("REPUTATION.RATE"), body(ORDER, BUYER, 4, null)), 403, "RATING_FORBIDDEN");
        assertProblem(post(UUID.randomUUID(), List.of("REPUTATION.RATE"), body(ORDER, SELLER, 4, null)), 403, "RATING_FORBIDDEN");
        assertProblem(post(BUYER, List.of("REPUTATION.RATE"), body(UUID.randomUUID(), SELLER, 4, null)), 409, "RATING_NOT_ELIGIBLE");
        assertProblem(post(BUYER, List.of("REPUTATION.RATE"), body(ORDER, BUYER, 4, null)), 400, "RATING_INVALID_RECIPIENT");
        assertProblem(post(BUYER, List.of("REPUTATION.RATE"), body(ORDER, UUID.randomUUID(), 4, null)), 400, "RATING_INVALID_RECIPIENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ratings", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isZero();
    }

    @Test
    void validationAndPrivilegesRejectBeforePersistence() throws Exception {
        assertThat(post(BUYER, List.of(), body(ORDER, SELLER, 4, null)).statusCode()).isEqualTo(403);
        assertThat(get("/ratings?userId=" + SELLER, List.of()).statusCode()).isEqualTo(403);
        assertThat(get("/reputations/" + SELLER, List.of()).statusCode()).isEqualTo(403);
        for (int score : new int[]{0, 6}) assertProblem(post(BUYER, List.of("REPUTATION.RATE"), body(ORDER, SELLER, score, null)), 400, "VALIDATION_FAILED");
        assertProblem(post(BUYER, List.of("REPUTATION.RATE"), body(ORDER, SELLER, 5, "a".repeat(1001))), 400, "VALIDATION_FAILED");
        assertProblem(get("/ratings?userId=" + SELLER + "&size=101", List.of("REPUTATION.VIEW")), 400, "RATING_INVALID_QUERY");
        assertProblem(get("/ratings?userId=" + SELLER + "&page=-1", List.of("REPUTATION.VIEW")), 400, "RATING_INVALID_QUERY");
        assertProblem(get("/ratings?userId=" + SELLER + "&page=2147483647&size=100", List.of("REPUTATION.VIEW")), 400, "RATING_INVALID_QUERY");
        var anonymous = HTTP.send(HttpRequest.newBuilder(uri("/ratings?userId=" + SELLER)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ratings", Integer.class)).isZero();
    }

    @Test
    void readsPersistedReputationAndMissingOrEmptyDataHonestly() throws Exception {
        assertProblem(get("/reputations/" + SELLER, List.of("REPUTATION.VIEW")), 404, "REPUTATION_NOT_FOUND");
        jdbc.update("""
                INSERT INTO user_reputations(user_id,score,trust_level,total_transaction,successful_transaction,failed_transaction)
                VALUES (?,78,'TRUSTED',42,40,2)
                """, SELLER);
        var result = get("/reputations/" + SELLER, List.of("REPUTATION.VIEW"));
        assertThat(result.statusCode()).isEqualTo(200);
        var data = json.readTree(result.body()).path("data");
        assertThat(data.path("score").asInt()).isEqualTo(78);
        assertThat(data.path("trustLevel").asText()).isEqualTo("TRUSTED");
        assertThat(data.path("successfulTransaction").asInt()).isEqualTo(40);
        var empty = get("/ratings?userId=" + SELLER + "&page=10", List.of("REPUTATION.VIEW"));
        assertThat(json.readTree(empty.body()).path("data").isEmpty()).isTrue();
        assertThat(json.readTree(empty.body()).path("page").path("totalElements").asInt()).isZero();
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/problem+json");
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }
    private HttpResponse<String> post(UUID actor, List<String> privileges, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(uri("/ratings")).header("Authorization", "Bearer " + token(actor, privileges))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String path, List<String> privileges) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token(BUYER, privileges)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api/v1" + path); }
    private String body(UUID order, UUID seller, int score, String comment) {
        return "{\"orderId\":\"%s\",\"toUserId\":\"%s\",\"score\":%d,\"comment\":%s}".formatted(
                order, seller, score, comment == null ? "null" : json.writeValueAsString(comment));
    }
    private static RSAKey key() {
        try { return new RSAKeyGenerator(2048).keyID("rating-test").generate(); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static String token(UUID actor, List<String> privileges) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(actor.toString()).issuer("https://rating.test")
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("roles", List.of("BUYER")).claim("privileges", privileges).build();
        var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        signed.sign(new RSASSASigner(KEY));
        return signed.serialize();
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TokenConfig {
        @Bean JwtDecoder jwtDecoder() throws Exception {
            var decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://rating.test"));
            return decoder;
        }
    }
}
