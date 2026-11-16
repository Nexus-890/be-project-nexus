package com.group5.auctionservice.bidding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.group5.auctionservice.TestPostgres;
import com.group5.auctionservice.auction.Auction;
import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.auctionservice.shared.config.AuctionProperties;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUserAuditor;
import com.group5.common_security.autoconfigure.SecurityAutoConfiguration;
import com.group5.common_web.exception.GlobalExceptionHandler;

import io.micrometer.tracing.Tracer;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.Filter;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;

import javax.sql.DataSource;

/** HTTP thật qua MVC/security; DB, migration, transaction nhận bid đều dùng PostgreSQL. */
@SpringJUnitConfig(BidPlacementIntegrationTest.Config.class)
@org.springframework.test.context.web.WebAppConfiguration
class BidPlacementIntegrationTest {
    private static final TestPostgres POSTGRES = new TestPostgres();
    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");

    static {
        POSTGRES.start();
    }

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    private MockMvc client;

    /** Giữ security filter thật để kiểm tra authentication và ownership trước controller. */
    @BeforeEach
    void setup() {
        client =
                MockMvcBuilders.webAppContextSetup(context)
                        .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                        .build();
    }

    /** Giá thấp chưa phụ thuộc bid nào tại POST: trả202 và QUEUED, không trả400 ngay. */
    @Test
    void postReturnsQueuedReceiptAndDoesNotChangePrice() throws Exception {
        UUID auction = auction("ACTIVE");
        UUID bidder = UUID.randomUUID();
        var result =
                client.perform(request(auction, bidder, "1"))
                        .andExpect(status().isAccepted())
                        .andExpect(
                                header().string(
                                                "Location",
                                                org.hamcrest.Matchers.startsWith(
                                                        "/api/v1/auctions/" + auction + "/bids/")))
                        .andExpect(jsonPath("$.code").value(0))
                        .andExpect(jsonPath("$.data.status").value("QUEUED"))
                        .andExpect(jsonPath("$.data.requestedAt").isNotEmpty())
                        .andExpect(jsonPath("$.data.currentPrice").doesNotExist())
                        .andReturn();
        UUID bid = bidId(result);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT current_price FROM auctions WHERE id=?",
                                java.math.BigDecimal.class,
                                auction))
                .isEqualByComparingTo("1000000");
        client.perform(
                        get("/api/v1/auctions/{id}/bids/{bidId}", auction, bid)
                                .header("Authorization", "Bearer " + bidder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.amount").value(1));
    }

    /** Mọi trạng thái ngoài ACTIVE đều trả409 và không tạo request. */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "CREATED",
                "SCHEDULED",
                "ENDED",
                "WON",
                "SETTLED",
                "FAILED",
                "PAYMENT_TIMEOUT",
                "CANCELLED"
            })
    void inactiveAuctionReturnsConflict(String state) throws Exception {
        UUID auction = auction(state);
        client.perform(request(auction, UUID.randomUUID(), "1050000"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_ACTIVE"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM bids WHERE auction_id=?",
                                Integer.class,
                                auction))
                .isZero();
    }

    /** ACTIVE nhưng đã qua deadline vẫn bị từ chối kể cả scheduler chưa chạy. */
    @Test
    void elapsedActiveAuctionReturnsConflict() throws Exception {
        UUID auction = auction("ACTIVE");
        jdbc.update("UPDATE auctions SET end_at=now()-interval '1 second' WHERE id=?", auction);
        client.perform(request(auction, UUID.randomUUID(), "1050000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_ACTIVE"));
    }

    /** Seller có thêm quyền BUYER cũng không được tự đặt bid trên auction của mình. */
    @Test
    void sellerCannotQueueOnOwnAuction() throws Exception {
        UUID auction = auction("ACTIVE");
        UUID seller =
                jdbc.queryForObject(
                        "SELECT seller_id FROM auctions WHERE id=?", UUID.class, auction);
        client.perform(request(auction, seller, "1050000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_SELLER_CANNOT_BID"));
    }

    /** Validation định dạng/số tiền xảy ra trước khi lưu QUEUED. */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"0", "-1", "1.001", "100000000000000000"})
    void malformedAmountIsRejected(String amount) throws Exception {
        client.perform(request(auction("ACTIVE"), UUID.randomUUID(), amount))
                .andExpect(status().isBadRequest());
    }

    /** Thiếu key và UUID sai được trả named error theo hợp đồng. */
    @Test
    void idempotencyKeyIsRequiredAndMustBeUuid() throws Exception {
        UUID auction = auction("ACTIVE");
        UUID bidder = UUID.randomUUID();
        client.perform(
                        post("/api/v1/auctions/{id}/bids", auction)
                                .header("Authorization", "Bearer " + bidder)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":1050000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUCTION_IDEMPOTENCY_KEY_REQUIRED"));
        client.perform(
                        post("/api/v1/auctions/{id}/bids", auction)
                                .header("Authorization", "Bearer " + bidder)
                                .header("Idempotency-Key", "bad-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":1050000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUCTION_INVALID_IDEMPOTENCY_KEY"));
    }

    /** Không có JWT thì cả POST và trạng thái riêng tư đều trả401. */
    @Test
    void anonymousCannotQueueOrReadBidStatus() throws Exception {
        UUID auction = auction("ACTIVE");
        client.perform(
                        post("/api/v1/auctions/{id}/bids", auction)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized());
        client.perform(get("/api/v1/auctions/{id}/bids/{bidId}", auction, UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    /** BidId hợp lệ nhưng sai chủ hoặc sai auction đều trả404, không rò rỉ dữ liệu bidder. */
    @Test
    void ownerOnlyStatusHidesOtherBiddersAndAuctionIds() throws Exception {
        UUID auction = auction("ACTIVE");
        UUID bidder = UUID.randomUUID();
        UUID bid = bidId(client.perform(request(auction, bidder, "1050000")).andReturn());
        for (UUID pathAuction : List.of(auction, UUID.randomUUID())) {
            client.perform(
                            get("/api/v1/auctions/{id}/bids/{bidId}", pathAuction, bid)
                                    .header("Authorization", "Bearer " + UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(
                            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("AUCTION_BID_NOT_FOUND"));
        }
    }

    /** Tạo request HTTP có đủ key và buyer token của fixture. */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            UUID auction, UUID bidder, String amount) {
        return post("/api/v1/auctions/{id}/bids", auction)
                .header("Authorization", "Bearer " + bidder)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + "}");
    }

    /** Lấy ID từ ApiResponse để kiểm tra endpoint đọc kết quả. */
    private UUID bidId(MvcResult result) throws Exception {
        return UUID.fromString(
                JsonMapper.shared()
                        .readTree(result.getResponse().getContentAsString())
                        .path("data")
                        .path("bidId")
                        .asString());
    }

    /** Mỗi test dùng auction riêng, thời gian DB và owner khác nhau. */
    private UUID auction(String state) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
INSERT INTO auctions(id,product_id,sku_id,seller_id,title,starting_price,bid_increment,current_price,start_at,end_at,original_end_at,status)
VALUES(?,?,?,?,'Queue HTTP',1000000,50000,1000000,now()-interval '1 hour',now()+interval '1 hour',now()+interval '1 hour',CAST(? AS auction_status))
""",
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                state);
        return id;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {AuctionRepository.class, BidRepository.class})
    @EnableJpaAuditing(auditorAwareRef = "auditor")
    @ImportAutoConfiguration(SecurityAutoConfiguration.class)
    @Import({BidService.class, BidController.class, BidHistoryQuery.class, AntiSnipingPolicy.class})
    static class Config {
        @Bean
        DataSource dataSource() {
            String url = POSTGRES.getJdbcUrl();
            DataSource source =
                    new DriverManagerDataSource(
                            url
                                    + (url.contains("?") ? "&" : "?")
                                    + "currentSchema=auction_bid_placement",
                            POSTGRES.getUsername(),
                            POSTGRES.getPassword());
            Flyway.configure()
                    .dataSource(source)
                    .schemas("auction_bid_placement")
                    .defaultSchema("auction_bid_placement")
                    .load()
                    .migrate();
            return source;
        }

        @Bean
        AuctionProperties auctionProperties() {
            return new AuctionProperties();
        }

        @Bean
        JdbcTemplate jdbc(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        AuditorAware<UUID> auditor() {
            return new CurrentUserAuditor();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        GlobalExceptionHandler errors() {
            return new GlobalExceptionHandler(null);
        }

        @Bean
        JwtDecoder decoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "RS256")
                            .subject(token)
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(300))
                            .claim("name", "Nguyen Van A")
                            .claim("roles", List.of("BUYER"))
                            .claim("privileges", List.of("AUCTION.BID"))
                            .build();
        }

        @Bean
        EventEnvelopeFactory envelopes(Clock clock) {
            Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
            when(tracer.currentSpan().context().traceId()).thenReturn("146bid");
            return new EventEnvelopeFactory(tracer, clock);
        }

        @Bean
        OutboxWriter outbox(DataSource source, PlatformTransactionManager transactions) {
            return spy(
                    new OutboxWriter(new JdbcTemplate(source), JsonMapper.shared(), transactions));
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(Auction.class.getPackageName(), Bid.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(
                    Map.of(
                            "hibernate.hbm2ddl.auto",
                            "validate",
                            "hibernate.physical_naming_strategy",
                            "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
