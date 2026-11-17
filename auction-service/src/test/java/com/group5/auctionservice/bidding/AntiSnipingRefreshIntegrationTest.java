package com.group5.auctionservice.bidding;

import com.group5.auctionservice.AuctionServiceApplication;
import com.group5.auctionservice.bidding.dto.PlaceBidRequest;
import com.group5.auctionservice.shared.config.AuctionProperties;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import com.group5.auctionservice.TestPostgres;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** #149: actual actuator refresh and lifecycle ticks against an isolated PostgreSQL database. */
class AntiSnipingRefreshIntegrationTest {
    private static final TestPostgres POSTGRES = new TestPostgres();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final CurrentUser BUYER = new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of("AUCTION.BID"));
    private static ConfigurableApplicationContext context;
    private static Path configDirectory;
    private static Path configFile;
    private static JdbcTemplate jdbc;
    private static BidService bids;
    private static AuctionProperties properties;

    @BeforeAll
    static void start() throws Exception {
        POSTGRES.start();
        configDirectory = Files.createTempDirectory("auction-anti-sniping-");
        configFile = configDirectory.resolve("auction.properties");
        // No policy settings: verify the SRS enabled/5-minute defaults, plus the finite default cap.
        Files.writeString(configFile, "# Isolated anti-sniping test configuration\n");
        context = new SpringApplicationBuilder(AuctionServiceApplication.class, TokenConfiguration.class)
                .web(WebApplicationType.SERVLET).run(
                        "--server.port=0", "--spring.config.location=" + configFile.toUri(),
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.jpa.hibernate.ddl-auto=validate", "--spring.jpa.open-in-view=false",
                        "--spring.cloud.config.enabled=false", "--spring.cloud.config.import-check.enabled=false",
                        "--spring.cloud.discovery.enabled=false", "--eureka.client.enabled=false",
                        "--spring.kafka.admin.auto-create=false", "--spring.kafka.listener.auto-startup=false",
                        "--management.endpoints.web.exposure.include=health,refresh",
                        "--management.tracing.export.zipkin.enabled=false", "--nexus.outbox.relay.enabled=false",
                        "--nexus.auction.settlement.enabled=false");
        jdbc = context.getBean(JdbcTemplate.class);
        bids = context.getBean(BidService.class);
        properties = context.getBean(AuctionProperties.class);
        assertThat(properties.isAntiSnipingEnabled()).isTrue();
        assertThat(properties.getAntiSnipingExtensionMinutes()).isEqualTo(5);
        assertThat(properties.getAntiSnipingMaxExtensionMinutes()).isEqualTo(30);
    }

    @BeforeEach
    void restoreDefaults() throws Exception {
        writeConfig(true, 5, 30);
        refresh();
    }

    @AfterAll
    static void stop() throws Exception {
        if (context != null) context.close();
        POSTGRES.stop();
        if (configFile != null) Files.deleteIfExists(configFile);
        if (configDirectory != null) Files.deleteIfExists(configDirectory);
    }

    @Test
    void actuatorRefreshChangesTheSwitchWindowAndCapWithoutRestartingTheService() throws Exception {
        long startup = context.getStartupDate();
        Fixture enabled = auction(120, 0);
        place(enabled.id(), "110");
        assertDeadline(enabled, enabled.end().plusSeconds(300), 1);

        writeConfig(false, 5, 30);
        refresh();
        assertThat(properties.isAntiSnipingEnabled()).isFalse();
        Fixture disabled = auction(120, 0);
        place(disabled.id(), "110");
        assertDeadline(disabled, disabled.end(), 0);

        writeConfig(true, 2, 7);
        refresh();
        assertThat(properties.isAntiSnipingEnabled()).isTrue();
        assertThat(properties.getAntiSnipingExtensionMinutes()).isEqualTo(2);
        assertThat(properties.getAntiSnipingMaxExtensionMinutes()).isEqualTo(7);
        Fixture smallerWindow = auction(180, 0);
        place(smallerWindow.id(), "110");
        assertDeadline(smallerWindow, smallerWindow.end(), 0);
        Fixture changedExtension = auction(60, 0);
        place(changedExtension.id(), "110");
        assertDeadline(changedExtension, changedExtension.end().plusSeconds(120), 1);
        // Already extended six minutes: the refreshed seven-minute cap allows only one more minute.
        Fixture changedCap = auction(60, 360);
        place(changedCap.id(), "110");
        assertDeadline(changedCap, changedCap.originalEnd().plusSeconds(420), 1);

        assertThat(context.getBean(BidService.class)).isSameAs(bids);
        assertThat(context.getStartupDate()).isEqualTo(startup);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id = ?", Integer.class,
                disabled.id())).isEqualTo(1);
    }

    @Test
    void repeatedBidsAtTheCapStopExtendingAndTheRealSchedulerEndsTheAuction() {
        // Effective deadline already equals original_end_at + 30m, but it has not ended yet.
        Fixture capped = auction(4, 1800);
        place(capped.id(), "110");
        place(capped.id(), "120");
        assertDeadline(capped, capped.end(), 0);
        assertThat(jdbc.queryForObject("SELECT bid_count FROM auctions WHERE id = ?", Integer.class,
                capped.id())).isEqualTo(2);

        // Do not invoke tick manually: the normal ten-second lifecycle scheduler must end it.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status::text FROM auctions WHERE id = ?", String.class, capped.id())).isEqualTo("ENDED"));
        assertDeadline(capped, capped.end(), 0);
        String endedPayload = jdbc.queryForObject("""
                SELECT payload->'payload'->>'endAt' FROM outbox
                WHERE aggregate_id = ? AND event_type = 'AuctionEnded'
                """, String.class, capped.id().toString());
        assertThat(Instant.parse(endedPayload)).isEqualTo(capped.end());
        Map<String, Object> ended = jdbc.queryForMap("SELECT * FROM auctions WHERE id = ?", capped.id());
        assertThatThrownBy(() -> place(capped.id(), "130"))
                .isInstanceOf(DomainException.class).extracting("errorCode").isEqualTo("AUCTION_NOT_ACTIVE");
        assertThat(jdbc.queryForMap("SELECT * FROM auctions WHERE id = ?", capped.id())).isEqualTo(ended);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id = ?", Integer.class,
                capped.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Integer.class,
                capped.id().toString())).isEqualTo(3);
    }

    private static void writeConfig(boolean enabled, int extension, int maxExtension) throws Exception {
        Files.writeString(configFile, "nexus.auction.anti-sniping-enabled=" + enabled
                + "\nnexus.auction.anti-sniping-extension-minutes=" + extension
                + "\nnexus.auction.anti-sniping-max-extension-minutes=" + maxExtension + "\n");
    }

    private static void refresh() throws Exception {
        String port = context.getEnvironment().getRequiredProperty("local.server.port");
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/actuator/refresh"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer test-admin")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("actuator refresh: %s", response.body()).isEqualTo(200);
    }

    private static void place(UUID auctionId, String amount) {
        Tracer tracer = context.getBean(Tracer.class);
        Span span = tracer.nextSpan().name("anti-sniping.test-bid").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            bids.place(auctionId, UUID.randomUUID().toString(), new PlaceBidRequest(new BigDecimal(amount)), BUYER);
        } finally {
            span.end();
        }
    }

    private static Fixture auction(int secondsToEnd, int secondsAlreadyExtended) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO auctions (product_id, sku_id, seller_id, title, starting_price,
                    bid_increment, current_price, status, start_at, end_at, original_end_at)
                VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'anti-sniping refresh fixture',
                    100, 10, 100, 'ACTIVE', now() - interval '1 hour',
                    now() + (? * interval '1 second'), now() + ((? - ?) * interval '1 second'))
                RETURNING id
                """, UUID.class, secondsToEnd, secondsToEnd, secondsAlreadyExtended);
        return new Fixture(id, instant(id, "end_at"), instant(id, "original_end_at"));
    }

    private static void assertDeadline(Fixture auction, Instant expected, int extensionCount) {
        assertThat(instant(auction.id(), "end_at")).isEqualTo(expected);
        assertThat(instant(auction.id(), "original_end_at")).isEqualTo(auction.originalEnd());
        assertThat(jdbc.queryForObject("SELECT extension_count FROM auctions WHERE id = ?", Integer.class,
                auction.id())).isEqualTo(extensionCount);
    }

    private static Instant instant(UUID id, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM auctions WHERE id = ?",
                (rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant(), id);
    }

    private record Fixture(UUID id, Instant end, Instant originalEnd) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class TokenConfiguration {
        @Bean
        JwtDecoder jwtDecoder() {
            // Token verification is isolated; actuator authentication and refresh are the real HTTP stack.
            return token -> Jwt.withTokenValue(token).header("alg", "RS256").subject(BUYER.id().toString())
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                    .claim("roles", List.of("test-admin".equals(token) ? "ADMIN" : "BUYER"))
                    .claim("privileges", List.of("test-admin".equals(token) ? "SYSTEM.CONFIG.UPDATE" : "AUCTION.BID")).build();
        }
    }
}
