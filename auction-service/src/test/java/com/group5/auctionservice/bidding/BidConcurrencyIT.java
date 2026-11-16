package com.group5.auctionservice.bidding;

import com.group5.auctionservice.AuctionPostgresIntegrationTest;
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
class BidConcurrencyIT extends AuctionPostgresIntegrationTest {
    private static final int REQUESTS = 50;
    private static final int BIDDERS = 10;
    private static final BigDecimal START = new BigDecimal("1000000.00");
    private static final BigDecimal INCREMENT = new BigDecimal("50000.00");
    private static final String ISSUER = "https://bid-concurrency.test";
    private static final RSAKey KEY = key();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private BidCommandConsumer consumer;
    @Autowired private JsonMapper json;
    @Value("${local.server.port}") private int port;

    /** Trigger chỉ dùng test để quan sát thứ tự giá tăng trong DB, không can thiệp xử lý bid. */
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

    /** 50 HTTP request từ10 bidder đều nhận202; drain qua consumer thật và kiểm tra DB cuối. */
    @RepeatedTest(10)
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void fiftyConcurrentBidsPreserveEveryInvariant(RepetitionInfo repetition) throws Exception {
        UUID auction=seedAuction();List<Attempt> attempts=attempts(repetition.getCurrentRepetition());
        List<Result> results=new ArrayList<>();CountDownLatch ready=new CountDownLatch(REQUESTS),start=new CountDownLatch(1);
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            List<Future<Result>> futures=new ArrayList<>();
            for(Attempt attempt:attempts) futures.add(workers.submit(()->{ready.countDown();
                if(!start.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Bid release timeout");return post(auction,attempt);}));
            assertThat(ready.await(15,TimeUnit.SECONDS)).isTrue();start.countDown();
            for(Future<Result> future:futures)results.add(future.get(60,TimeUnit.SECONDS));
        }
        assertThat(results).hasSize(REQUESTS).allSatisfy(result->{
            assertThat(result.status()).as("response: %s",result.body()).isEqualTo(202);
            assertThat(result.contentType()).contains("application/json");
            JsonNode body=json.readTree(result.body());assertThat(body.path("data").path("status").asString()).isEqualTo("QUEUED");
            assertThat(body.path("data").path("requestedAt").asString()).isNotBlank();
        });
        assertThat(jdbc.queryForObject("SELECT current_price FROM auctions WHERE id=?",BigDecimal.class,auction)).isEqualByComparingTo(START);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id=? AND status='QUEUED'",Integer.class,auction)).isEqualTo(50);
        // Cố tình đảo delivery để chứng minh consumer khôi phục thứ tự requestedAt đã lưu trong DB.
        List<String> commands=jdbc.queryForList("SELECT payload::text FROM outbox WHERE aggregate_id=? AND event_type='BidRequested' ORDER BY occurred_at DESC,id DESC",String.class,auction.toString());
        long began=System.nanoTime();
        for(String payload:commands){com.group5.common_events.EventEnvelope<JsonNode> event=json.readValue(payload,
            json.getTypeFactory().constructParametricType(com.group5.common_events.EventEnvelope.class,JsonNode.class));consumer.onEvent(event);}
        long drainMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id=? AND status='QUEUED'",Integer.class,auction)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id=? AND status IN ('PLACED','REJECTED')",Integer.class,auction)).isEqualTo(50);
        BigDecimal highest=attempts.stream().map(Attempt::amount).max(BigDecimal::compareTo).orElseThrow();
        var winner=attempts.stream().filter(a->a.amount().compareTo(highest)==0).findFirst().orElseThrow();
        Map<String,Object> finalState=jdbc.queryForMap("SELECT current_price,leader_bidder_id,bid_count FROM auctions WHERE id=?",auction);
        assertThat((BigDecimal)finalState.get("current_price")).isEqualByComparingTo(highest);
        assertThat(finalState.get("leader_bidder_id")).isEqualTo(winner.bidder());
        Integer placed=jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id=? AND status='PLACED'",Integer.class,auction);
        assertThat(finalState.get("bid_count")).isEqualTo(placed);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id=? AND event_type='BidPlaced'",Integer.class,auction.toString())).isEqualTo(placed);
        List<BigDecimal> updates=jdbc.queryForList("SELECT price FROM bid_test_updates WHERE auction_id=? ORDER BY sequence",BigDecimal.class,auction);
        assertThat(updates).isSorted().doesNotHaveDuplicates();
        System.out.printf("BID_QUEUE run=%d requests=50 bidders=10 queued=50 placed=%d rejected=%d remaining=0 highest=%s drain_ms=%d local_only=true%n",repetition.getCurrentRepetition(),placed,50-placed,highest,drainMillis);
    }

    /** Tạo50 giá riêng từ10 buyer rồi xáo thứ tự HTTP để tăng tranh chấp. */
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

    /** Gửi HTTP thật tới server, giữ body và content-type để xác nhận hợp đồng202. */
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

    /** Seed auction riêng cho mỗi lần lặp; tắt anti-sniping bằng deadline còn một giờ. */
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

    /** Ký JWT fixture bằng RSA, để security stack xác thực token thật. */
    private static RSAKey key() {
        try {
            return new RSAKeyGenerator(2048).generate();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /** Mỗi bidder có subject khác và đúng role/privilege đặt bid. */
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
}
