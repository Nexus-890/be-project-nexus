package com.group5.auctionservice.auction;

import com.group5.auctionservice.auction.dto.CancelAuctionRequest;
import com.group5.auctionservice.bidding.Bid;
import com.group5.auctionservice.bidding.BidRepository;
import com.group5.auctionservice.shared.config.AuctionProperties;
import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PostgreSQL/Flyway/JPA/outbox thật kiểm tra snapshot bidder và rollback của transaction hủy. */
@SpringJUnitConfig(AuctionCancellationNotificationTest.Config.class)
class AuctionCancellationNotificationTest {
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final UUID SELLER = UUID.randomUUID();
    private static final CurrentUser ADMIN = new CurrentUser(UUID.randomUUID(), Set.of("ADMIN"), Set.of("AUCTION.ADMIN_CANCEL"));
    @Autowired private AuctionService auctions;
    @Autowired private DataSource source;

    @Test
    void cancellationContainsEveryDistinctBidderAndPreservesTheExistingPayload() {
        UUID auction = auction();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        // Một người bid nhiều lần với các trạng thái khác nhau vẫn chỉ nhận một email hủy.
        bid(auction, first, "PLACED");
        bid(auction, first, "OUTBID");
        bid(auction, second, "CANCELLED");
        bid(auction(), UUID.randomUUID(), "PLACED");

        auctions.cancel(auction, new CancelAuctionRequest("  Listing removed  "), ADMIN, true);

        JsonNode payload = payload(auction);
        assertThat(payload.path("bidderIds").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder(first.toString(), second.toString()).hasSize(2);
        assertThat(payload.path("sellerId").asString()).isEqualTo(SELLER.toString());
        assertThat(payload.path("actorId").asString()).isEqualTo(ADMIN.id().toString());
        assertThat(payload.path("reason").asString()).isEqualTo("Listing removed");
        assertThat(payload.path("cancelledBy").asString()).isEqualTo("ADMIN");
        assertThat(payload.path("auctionVersion").asLong()).isEqualTo(1);
        assertThat(payload.path("cancelledAt").asString()).isNotBlank();
        assertThat(events(auction)).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT topic FROM outbox WHERE aggregate_id = ?", String.class,
                auction.toString())).isEqualTo(Topics.AUCTION);
    }

    @Test
    void auctionWithoutBidsPublishesAnEmptyListAndKeepsTheSellerRecipient() {
        UUID auction = auction();
        // Seller chỉ được hủy khi chưa có bid hợp lệ; bidderIds rỗng vẫn là payload hợp lệ.
        auctions.cancel(auction, new CancelAuctionRequest("Seller cancelled"),
                new CurrentUser(SELLER, Set.of("SELLER"), Set.of("AUCTION.CANCEL")), false);

        assertThat(payload(auction).path("bidderIds").isArray()).isTrue();
        assertThat(payload(auction).path("bidderIds").size()).isZero();
        assertThat(payload(auction).path("sellerId").asString()).isEqualTo(SELLER.toString());
    }

    @Test
    void cancellingAgainDoesNotPublishAnotherRecipientSnapshot() {
        UUID auction = auction();
        auctions.cancel(auction, new CancelAuctionRequest("Admin cancelled"), ADMIN, true);
        // Request hủy lần hai bị rule trạng thái chặn trước khi tạo outbox thứ hai.
        assertThatThrownBy(() -> auctions.cancel(auction, new CancelAuctionRequest("Again"), ADMIN, true))
                .isInstanceOf(DomainException.class);
        assertThat(events(auction)).isEqualTo(1);
    }

    @Test
    void outboxFailureRollsBackCancellationAndAuditThenRetryPublishesOnce() {
        UUID auction = auction();
        bid(auction, UUID.randomUUID(), "PLACED");
        // Ép outbox lỗi sau flush; trạng thái/audit không được commit riêng khỏi danh sách người nhận.
        jdbc().execute("ALTER TABLE outbox ADD CONSTRAINT reject_notification_cancel CHECK (event_type <> 'AuctionCancelled') NOT VALID");
        try {
            assertThatThrownBy(() -> auctions.cancel(auction, new CancelAuctionRequest("Admin cancelled"), ADMIN, true))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc().execute("ALTER TABLE outbox DROP CONSTRAINT reject_notification_cancel");
        }
        assertThat(jdbc().queryForObject("SELECT status::text FROM auctions WHERE id = ?", String.class, auction))
                .isEqualTo("ACTIVE");
        assertThat(jdbc().queryForObject("SELECT cancelled_at IS NULL AND cancelled_actor_id IS NULL FROM auctions WHERE id = ?",
                Boolean.class, auction)).isTrue();
        assertThat(events(auction)).isZero();

        auctions.cancel(auction, new CancelAuctionRequest("Admin cancelled"), ADMIN, true);
        assertThat(events(auction)).isEqualTo(1);
    }

    private UUID auction() {
        return jdbc().queryForObject("""
                INSERT INTO auctions(product_id, sku_id, seller_id, title, starting_price, bid_increment,
                    current_price, status, start_at, end_at, original_end_at)
                VALUES (?, ?, ?, 'Notification cancellation fixture', 100000, 10000, 100000,
                    'ACTIVE', now() - interval '1 hour', now() + interval '1 hour', now() + interval '1 hour') RETURNING id
                """, UUID.class, UUID.randomUUID(), UUID.randomUUID(), SELLER);
    }

    private void bid(UUID auction, UUID bidder, String status) {
        jdbc().update("""
                INSERT INTO bids(auction_id, bidder_id, amount, status, idempotency_key)
                VALUES (?, ?, 110000, CAST(? AS bid_status), ?)
                """, auction, bidder, status, UUID.randomUUID().toString());
    }

    private JsonNode payload(UUID auction) {
        return JSON.readTree(jdbc().queryForObject("SELECT payload::text FROM outbox WHERE aggregate_id = ?",
                String.class, auction.toString())).path("payload");
    }

    private long events(UUID auction) {
        return jdbc().queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'AuctionCancelled'",
                Long.class, auction.toString());
    }

    private JdbcTemplate jdbc() { return new JdbcTemplate(source); }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {AuctionRepository.class, BidRepository.class})
    @Import({AuctionService.class, AuctionStateMachine.class})
    static class Config {
        @Bean DataSource dataSource() {
            String url = System.getProperty("notification.events.test.jdbc-url");
            String user = "postgres";
            String password = "";
            if (url == null) {
                var database = new PostgreSQLContainer("postgres:18-alpine");
                database.start();
                url = database.getJdbcUrl();
                user = database.getUsername();
                password = database.getPassword();
            }
            String schema = "auction_notification_" + UUID.randomUUID().toString().replace("-", "");
            var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                    user, password);
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            return source;
        }

        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(Auction.class.getPackageName(), Bid.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
            return factory;
        }

        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean AuctionProperties properties() { return new AuctionProperties(); }
        // Hủy chỉ dùng dữ liệu local; các dependency tạo auction không được gọi trong test này.
        @Bean AuctionCatalog catalog() { return mock(AuctionCatalog.class); }
        @Bean AuctionCreation creation() { return mock(AuctionCreation.class); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean Tracer tracer() {
            var tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
            when(tracer.currentSpan().context().traceId()).thenReturn("259-cancellation");
            return tracer;
        }
        @Bean OutboxWriter outbox(DataSource source, PlatformTransactionManager tx) { return new OutboxWriter(new JdbcTemplate(source), JSON, tx); }
        @Bean EventEnvelopeFactory envelopes(Tracer tracer, Clock clock) { return new EventEnvelopeFactory(tracer, clock); }
    }
}
