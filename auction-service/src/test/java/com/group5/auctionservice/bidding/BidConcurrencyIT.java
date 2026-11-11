package com.group5.auctionservice.bidding;

import com.group5.common_test.PostgresIntegrationTest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Timeout;
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

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** HTTP, security, Flyway, real repositories/transactions/outbox and PostgreSQL 18. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/bid-concurrency-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.datasource.hikari.maximum-pool-size=50",
        "spring.datasource.hikari.connection-init-sql=SET statement_timeout = '30s'",
        "server.tomcat.threads.max=60",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "nexus.outbox.relay.enabled=false", "management.tracing.export.enabled=false"
})
@Import(BidConcurrencyIT.TokenConfig.class)
class BidConcurrencyIT extends PostgresIntegrationTest {
    private static final int REQUESTS = 50;
    private static final int BIDDERS = 10;
    private static final BigDecimal START = new BigDecimal("1000000.00");
    private static final BigDecimal INCREMENT = new BigDecimal("50000.00");
    private static final String ISSUER = "https://bid-concurrency.test";
    private static final RSAKey KEY = key();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Value("${local.server.port}") private int port;

    @BeforeEach
    void recordActualDatabaseUpdateOrder() {
        // Test-only observer, never a production migration. It does not lock or reject bids.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS bid_test_updates (
                    sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    auction_id uuid NOT NULL, old_price numeric(19,2), price numeric(19,2),
                    old_count integer, bid_count integer, bidder_id uuid)
                """);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION bid_test_record_update() RETURNS trigger AS $$
                BEGIN
                    INSERT INTO bid_test_updates (auction_id, old_price, price, old_count, bid_count, bidder_id)
                    VALUES (NEW.id, OLD.current_price, NEW.current_price, OLD.bid_count,
                            NEW.bid_count, NEW.leader_bidder_id);
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("DROP TRIGGER IF EXISTS bid_test_observe ON auctions");
        jdbc.execute("""
                CREATE TRIGGER bid_test_observe AFTER UPDATE OF current_price, bid_count ON auctions
                FOR EACH ROW EXECUTE FUNCTION bid_test_record_update()
                """);
    }

    @RepeatedTest(10)
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void fiftyConcurrentBidsPreserveEveryInvariant(RepetitionInfo repetition) throws Exception {
        UUID auction = seedAuction();
        List<Attempt> attempts = attempts(repetition.getCurrentRepetition());
        List<Result> results = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Result>> futures = new ArrayList<>();
            for (Attempt attempt : attempts) {
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Bid release latch timed out");
                    }
                    return post(auction, attempt);
                }));
            }
            try {
                assertThat(ready.await(15, TimeUnit.SECONDS)).as("all 50 workers ready").isTrue();
            } finally {
                start.countDown();
            }
            for (Future<Result> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
        }

        List<Result> accepted = results.stream().filter(r -> r.status() == 201).toList();
        assertThat(results).hasSize(REQUESTS);
        assertThat(accepted).as("at least one bid must succeed").isNotEmpty();
        assertThat(results).allSatisfy(result -> {
            assertThat(result.status()).as("response for %s: %s", result.attempt(), result.body())
                    .isIn(201, 400);
            assertThat(result.contentType()).contains(result.status() == 201
                    ? "application/json" : "application/problem+json");
            JsonNode body = json.readTree(result.body());
            if (result.status() == 201) {
                assertThat(body.path("code").asInt()).isZero();
                assertThat(body.path("message").asText()).isEqualTo("response success");
                assertThat(body.path("data").path("currentPrice").decimalValue())
                        .isEqualByComparingTo(result.attempt().amount());
                assertThat(body.path("data").path("leaderBidderId").asText())
                        .isEqualTo(result.attempt().bidder().toString());
            } else {
                assertThat(body.path("code").asText()).isEqualTo("AUCTION_BID_TOO_LOW");
                assertThat(body.path("status").asInt()).isEqualTo(400);
                assertThat(body.path("minimum").decimalValue()).isGreaterThan(result.attempt().amount());
            }
        });

        Map<UUID, AcceptedBid> expected = new HashMap<>();
        for (Result result : accepted) {
            UUID id = UUID.fromString(json.readTree(result.body()).path("data").path("bidId").asText());
            assertThat(expected.put(id, new AcceptedBid(result.attempt().bidder(), result.attempt().amount())))
                    .as("each 201 has a unique bidId").isNull();
        }
        Map<UUID, AcceptedBid> stored = new HashMap<>();
        jdbc.query("SELECT id, bidder_id, amount FROM bids WHERE auction_id = ?", row -> {
            stored.put(row.getObject("id", UUID.class),
                    new AcceptedBid(row.getObject("bidder_id", UUID.class), row.getBigDecimal("amount")));
        }, auction);
        List<Update> updates = jdbc.query("""
                SELECT old_price, price, old_count, bid_count, bidder_id
                FROM bid_test_updates WHERE auction_id = ? ORDER BY sequence
                """, (row, n) -> new Update(row.getBigDecimal("old_price"), row.getBigDecimal("price"),
                row.getInt("old_count"), row.getInt("bid_count"), row.getObject("bidder_id", UUID.class)), auction);
        BigDecimal highest = accepted.stream().map(r -> r.attempt().amount()).max(BigDecimal::compareTo).orElseThrow();
        Map<String, Object> finalAuction = jdbc.queryForMap(
                "SELECT current_price, bid_count, version, leader_bidder_id FROM auctions WHERE id = ?", auction);

        // Keep all four checks visible in red evidence even when several invariants break.
        assertAll("bid concurrency invariants, repetition " + repetition.getCurrentRepetition(),
                () -> assertIncreasingPrices(updates, expected),
                () -> assertThat(finalAuction.get("bid_count")).as("bid_count equals HTTP 201 count")
                        .isEqualTo(accepted.size()),
                () -> assertThat((BigDecimal) finalAuction.get("current_price"))
                        .as("current_price equals highest accepted amount").isEqualByComparingTo(highest),
                () -> {
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id = ?", Long.class, auction))
                            .as("stored bids count equals HTTP 201 count").isEqualTo((long) accepted.size());
                    assertThat(stored).as("exact accepted bid rows, with no orphan or missing row")
                            .containsExactlyInAnyOrderEntriesOf(expected);
                });
        assertThat(finalAuction.get("version")).isEqualTo((long) accepted.size());
        assertThat(updates.getLast().bidder()).isEqualTo(finalAuction.get("leader_bidder_id"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'BidPlaced'
                """, Long.class, auction.toString())).isEqualTo((long) accepted.size());
        System.out.printf("BID_CONCURRENCY run=%d requests=50 bidders=10 accepted=%d rejected=%d highest=%s invariants=PASS%n",
                repetition.getCurrentRepetition(), accepted.size(), REQUESTS - accepted.size(), highest);
    }

    private void assertIncreasingPrices(List<Update> updates, Map<UUID, AcceptedBid> accepted) {
        assertThat(updates).as("one committed update per 201").hasSize(accepted.size());
        List<AcceptedBid> transitions = new ArrayList<>();
        BigDecimal previous = START;
        for (int index = 0; index < updates.size(); index++) {
            Update update = updates.get(index);
            // Same-row locks order the trigger sequence until commit. HTTP arrival order does not.
            assertThat(update.oldPrice()).isEqualByComparingTo(previous);
            assertThat(update.price()).as("201 currentPrice strictly increases in database update order")
                    .isGreaterThanOrEqualTo(previous.add(INCREMENT));
            assertThat(update.oldCount()).isEqualTo(index);
            assertThat(update.count()).isEqualTo(index + 1);
            transitions.add(new AcceptedBid(update.bidder(), update.price()));
            previous = update.price();
        }
        assertThat(transitions).as("every 201 corresponds to a winning update")
                .containsExactlyInAnyOrderElementsOf(accepted.values());
    }

    private List<Attempt> attempts(int repetition) throws Exception {
        List<Attempt> attempts = new ArrayList<>();
        for (int bidderIndex = 0; bidderIndex < BIDDERS; bidderIndex++) {
            UUID bidder = UUID.randomUUID();
            String token = token(bidder);
            for (int bid = 0; bid < REQUESTS / BIDDERS; bid++) {
                // 50 mức giá riêng, cách nhau một bước giá; thứ tự đến vẫn do các request cạnh tranh.
                int level = bidderIndex * (REQUESTS / BIDDERS) + bid + 1;
                BigDecimal amount = START.add(INCREMENT.multiply(BigDecimal.valueOf(level)));
                attempts.add(new Attempt(bidder, token, UUID.randomUUID(), amount));
            }
        }
        Collections.shuffle(attempts, new Random(148L + repetition));
        return attempts;
    }

    private Result post(UUID auction, Attempt attempt) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/auctions/" + auction + "/bids"))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + attempt.token())
                .header("Idempotency-Key", attempt.key().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"amount\":" + attempt.amount().toPlainString() + "}"))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return new Result(attempt, response.statusCode(), response.body(),
                response.headers().firstValue("Content-Type").orElse(""));
    }

    private UUID seedAuction() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auctions (id, product_id, sku_id, seller_id, title, starting_price,
                    current_price, bid_increment, status, start_at, end_at, original_end_at)
                VALUES (?, ?, ?, ?, 'Bid concurrency #148', ?, ?, ?, 'ACTIVE',
                    now() - interval '1 hour', now() + interval '1 hour', now() + interval '1 hour')
                """, id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), START, START, INCREMENT);
        return id;
    }

    private static RSAKey key() {
        try {
            return new RSAKeyGenerator(2048).generate();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String token(UUID bidder) throws Exception {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject(bidder.toString()).audience("nexus-services")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(600)))
                .claim("roles", List.of("BUYER")).claim("privileges", List.of("AUCTION.BID")).build());
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TokenConfig {
        @Bean
        JwtDecoder jwtDecoder() throws Exception {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }
    }

    private record Attempt(UUID bidder, String token, UUID key, BigDecimal amount) { }
    private record Result(Attempt attempt, int status, String body, String contentType) { }
    private record AcceptedBid(UUID bidder, BigDecimal amount) { }
    private record Update(BigDecimal oldPrice, BigDecimal price, int oldCount, int count, UUID bidder) { }
}
