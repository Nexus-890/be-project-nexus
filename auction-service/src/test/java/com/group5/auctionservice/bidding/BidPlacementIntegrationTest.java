package com.group5.auctionservice.bidding;

import com.group5.auctionservice.auction.Auction;
import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUserAuditor;
import com.group5.common_security.autoconfigure.SecurityAutoConfiguration;
import com.group5.common_web.exception.GlobalExceptionHandler;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.Filter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** #146: real HTTP boundary, PostgreSQL 18, Flyway, conditional update and outbox transaction. */
@SpringJUnitConfig(BidPlacementIntegrationTest.Config.class)
@WebAppConfiguration
class BidPlacementIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    private static final Instant NOW = Instant.parse("2026-11-08T03:00:00Z");
    private static final BigDecimal MINIMUM = new BigDecimal("1050000.00");
    static { POSTGRES.start(); }

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OutboxWriter outbox;
    private MockMvc client;

    @BeforeEach
    void setup() {
        reset(outbox);
        client = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void tooLowReturns400WithMinimumAndWritesNothing() throws Exception {
        Fixture auction = auction("ACTIVE");
        Snapshot before = snapshot(auction.id());

        client.perform(request(auction.id(), UUID.randomUUID(), "1020000.00"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUCTION_BID_TOO_LOW"))
                .andExpect(jsonPath("$.minimum").value(1050000.00))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(snapshot(auction.id())).isEqualTo(before);
    }

    @Test
    void acceptedBidReturns201AndCommitsAuctionBidAndOneBidPlacedTogether() throws Exception {
        Fixture auction = auction("ACTIVE");
        UUID bidder = UUID.randomUUID();

        MvcResult response = client.perform(request(auction.id(), bidder, MINIMUM.toPlainString()))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.currentPrice").value(1050000.00))
                .andExpect(jsonPath("$.data.leaderBidderId").value(bidder.toString()))
                .andExpect(jsonPath("$.page").doesNotExist()).andReturn();

        JsonNode body = JsonMapper.shared().readTree(response.getResponse().getContentAsString());
        UUID bidId = UUID.fromString(body.get("data").get("bidId").asString());
        Map<String, Object> stored = assertAccepted(auction.id(), bidder, bidId);
        assertThat(stored.get("status").toString()).isEqualTo("PLACED");
        assertThat(stored.get("idempotency_key").toString()).isNotBlank();
        Instant storedPlacedAt = jdbc.queryForObject("SELECT placed_at FROM bids WHERE id = ?",
                (rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant(), bidId);
        assertThat(storedPlacedAt).isEqualTo(NOW);
        assertThat(stored.get("created_by")).isEqualTo(bidder);
        assertThat(stored.get("updated_by")).isEqualTo(bidder);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM outbox WHERE aggregate_id = ?",
                auction.id().toString());
        assertThat(row.get("aggregate_type")).isEqualTo("Auction");
        assertThat(row.get("topic")).isEqualTo(Topics.AUCTION);
        assertThat(row.get("published_at")).isNull();
        JsonNode event = JsonMapper.shared().readTree(row.get("payload").toString());
        assertThat(event.get("eventType").asString()).isEqualTo("BidPlaced");
        assertThat(event.get("aggregateId").asString()).isEqualTo(auction.id().toString());
        assertThat(event.get("payload").get("auctionId").asString()).isEqualTo(auction.id().toString());
        assertThat(event.get("payload").get("bidId").asString()).isEqualTo(bidId.toString());
        assertThat(event.get("payload").get("bidderId").asString()).isEqualTo(bidder.toString());
        assertThat(event.get("payload").get("amount").asString()).isEqualTo("1050000.00");
        assertThat(event.get("payload").get("currency").asString()).isEqualTo("VND");
        assertThat(Instant.parse(event.get("payload").get("placedAt").asString())).isEqualTo(NOW);
    }

    @Test
    void createdAuctionReturns409AndDoesNotWriteABidOrOutbox() throws Exception {
        Fixture auction = auction("CREATED");
        Snapshot before = snapshot(auction.id());

        client.perform(request(auction.id(), UUID.randomUUID(), MINIMUM.toPlainString()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_ACTIVE"));

        assertThat(snapshot(auction.id())).isEqualTo(before);
    }

    @Test
    void sellerWithBuyerPrivilegesStillGets409AndCannotChangeTheirOwnAuction() throws Exception {
        Fixture auction = auction("ACTIVE");
        Snapshot before = snapshot(auction.id());

        client.perform(request(auction.id(), auction.seller(), MINIMUM.toPlainString()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUCTION_SELLER_CANNOT_BID"));

        assertThat(snapshot(auction.id())).isEqualTo(before);
    }

    @Test
    void aFailureAfterOutboxInsertionRollsBackPriceLeaderCountVersionAndBid() throws Exception {
        Fixture auction = auction("ACTIVE");
        Snapshot before = snapshot(auction.id());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Test failure after BidPlaced insert");
        }).when(outbox).write(anyString(), anyString(), any());

        client.perform(request(auction.id(), UUID.randomUUID(), MINIMUM.toPlainString()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(snapshot(auction.id())).isEqualTo(before);
    }

    @Test
    void parallelBidsAtTheSameMinimumAcceptOneAndRejectTheOtherWithoutALostUpdate() throws Exception {
        Fixture auction = auction("ACTIVE");
        List<UUID> bidders = List.of(UUID.randomUUID(), UUID.randomUUID());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<MvcResult> results;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = bidders.stream().map(bidder -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Missing race start");
                return client.perform(request(auction.id(), bidder, MINIMUM.toPlainString())).andReturn();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            results = List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }

        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(201, 400);
        MvcResult accepted = results.stream().filter(result -> result.getResponse().getStatus() == 201).findFirst().orElseThrow();
        MvcResult refused = results.stream().filter(result -> result.getResponse().getStatus() == 400).findFirst().orElseThrow();
        JsonNode acceptedBody = JsonMapper.shared().readTree(accepted.getResponse().getContentAsString());
        JsonNode refusedBody = JsonMapper.shared().readTree(refused.getResponse().getContentAsString());
        UUID winner = UUID.fromString(acceptedBody.get("data").get("leaderBidderId").asString());
        assertThat(bidders).contains(winner);
        assertAccepted(auction.id(), winner, UUID.fromString(acceptedBody.get("data").get("bidId").asString()));
        assertThat(refusedBody.get("code").asString()).isEqualTo("AUCTION_BID_TOO_LOW");
        assertThat(refusedBody.get("minimum").decimalValue()).isEqualByComparingTo("1100000.00");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            UUID auction, UUID bidder, String amount) {
        // #146 ignores this header; sending one keeps the same HTTP tests valid after dependent #147 merges.
        return post("/api/v1/auctions/{id}/bids", auction)
                .header("Authorization", "Bearer " + bidder)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}");
    }

    private Fixture auction(String status) {
        UUID id = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auctions (id, product_id, sku_id, seller_id, title, starting_price,
                    bid_increment, current_price, start_at, end_at, original_end_at, status)
                VALUES (?, ?, ?, ?, 'Bid placement integration fixture', 1000000.00, 50000.00, 1000000.00,
                    '2026-11-08T00:00:00Z', '2026-11-09T00:00:00Z', '2026-11-09T00:00:00Z', CAST(? AS auction_status))
                """, id, UUID.randomUUID(), UUID.randomUUID(), seller, status);
        return new Fixture(id, seller);
    }

    private Map<String, Object> assertAccepted(UUID auction, UUID bidder, UUID bidId) {
        Map<String, Object> storedAuction = jdbc.queryForMap("SELECT * FROM auctions WHERE id = ?", auction);
        assertThat((BigDecimal) storedAuction.get("current_price")).isEqualByComparingTo(MINIMUM);
        assertThat(storedAuction.get("leader_bidder_id")).isEqualTo(bidder);
        assertThat(((Number) storedAuction.get("bid_count")).intValue()).isEqualTo(1);
        assertThat(((Number) storedAuction.get("version")).longValue()).isEqualTo(1);
        assertThat(storedAuction.get("updated_by")).isEqualTo(bidder);
        List<Map<String, Object>> storedBids = jdbc.queryForList("SELECT * FROM bids WHERE auction_id = ?", auction);
        assertThat(storedBids).hasSize(1);
        Map<String, Object> bid = storedBids.getFirst();
        assertThat(bid.get("id")).isEqualTo(bidId);
        assertThat(bid.get("auction_id")).isEqualTo(auction);
        assertThat(bid.get("bidder_id")).isEqualTo(bidder);
        assertThat((BigDecimal) bid.get("amount")).isEqualByComparingTo(MINIMUM);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'BidPlaced'",
                Long.class, auction.toString())).isEqualTo(1);
        return bid;
    }

    private Snapshot snapshot(UUID auction) {
        return new Snapshot(jdbc.queryForMap("SELECT * FROM auctions WHERE id = ?", auction),
                jdbc.queryForList("SELECT * FROM bids WHERE auction_id = ? ORDER BY id", auction),
                jdbc.queryForList("SELECT * FROM outbox WHERE aggregate_id = ? ORDER BY id", auction.toString()));
    }

    private record Fixture(UUID id, UUID seller) { }
    private record Snapshot(Map<String, Object> auction, List<Map<String, Object>> bids, List<Map<String, Object>> events) { }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {AuctionRepository.class, BidRepository.class})
    @EnableJpaAuditing(auditorAwareRef = "auditor")
    @ImportAutoConfiguration(SecurityAutoConfiguration.class)
    @Import({BidService.class, BidController.class})
    static class Config {
        @Bean
        DataSource dataSource() {
            String url = POSTGRES.getJdbcUrl();
            DataSource source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                    + "currentSchema=auction_bid_placement", POSTGRES.getUsername(), POSTGRES.getPassword());
            Flyway.configure().dataSource(source).schemas("auction_bid_placement")
                    .defaultSchema("auction_bid_placement").load().migrate();
            return source;
        }

        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean AuditorAware<UUID> auditor() { return new CurrentUserAuditor(); }
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(null); }
        @Bean JwtDecoder decoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "RS256").subject(token)
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                    .claim("roles", List.of("BUYER")).claim("privileges", List.of("AUCTION.BID")).build();
        }
        @Bean
        EventEnvelopeFactory envelopes(Clock clock) {
            Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
            when(tracer.currentSpan().context().traceId()).thenReturn("146bid");
            return new EventEnvelopeFactory(tracer, clock);
        }
        @Bean
        OutboxWriter outbox(DataSource source, PlatformTransactionManager transactions) {
            return spy(new OutboxWriter(new JdbcTemplate(source), JsonMapper.shared(), transactions));
        }
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(Auction.class.getPackageName(), Bid.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
            return factory;
        }
        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
    }
}


