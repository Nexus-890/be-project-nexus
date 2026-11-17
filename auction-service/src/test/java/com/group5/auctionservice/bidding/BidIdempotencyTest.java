package com.group5.auctionservice.bidding;

import com.group5.auctionservice.auction.Auction;
import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.auctionservice.bidding.dto.PlaceBidRequest;
import com.group5.auctionservice.shared.config.AuctionProperties;
import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.group5.common_security.CurrentUserAuditor;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import com.group5.auctionservice.TestPostgres;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(BidIdempotencyTest.JpaConfig.class)
class BidIdempotencyTest {
    private static final TestPostgres POSTGRES = new TestPostgres();
    private static final BigDecimal MINIMUM = new BigDecimal("1050000.00");
    private static final BigDecimal HIGHER = new BigDecimal("1100000.00");

    static {
        POSTGRES.start();
    }

    @Autowired private BidService service;
    @Autowired private DataSource dataSource;
    @MockitoSpyBean private BidRepository bids;
    @MockitoSpyBean private AuctionRepository auctions;
    @MockitoSpyBean private OutboxWriter outbox;

    @Test
    void replayKeepsTheOriginalResponseAndEveryStoredValue() {
        Fixture fixture = auction();
        String key = key();
        BidService.PlacementResult first = place(fixture, key, MINIMUM);
        Snapshot accepted = snapshot(fixture.id());

        BidService.PlacementResult replay = place(fixture, key, new BigDecimal("1050000"));

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.response()).isEqualTo(first.response());
        assertThat(snapshot(fixture.id())).isEqualTo(accepted);
        assertSingleBid(fixture, key, MINIMUM);

        service.place(fixture.id(), key(), new PlaceBidRequest(HIGHER), buyer());
        jdbc().update("UPDATE auctions SET status = 'ENDED' WHERE id = ?", fixture.id());
        Snapshot ended = snapshot(fixture.id());

        // Cùng key và amount vẫn replay sau khi phiên đã kết thúc hoặc giá đã thay đổi.
        BidService.PlacementResult afterEnd = place(fixture, key.toUpperCase(Locale.ROOT), MINIMUM);

        assertThat(afterEnd.created()).isFalse();
        assertThat(afterEnd.response()).isEqualTo(first.response());
        assertThat(snapshot(fixture.id())).isEqualTo(ended);
    }

    @Test
    void changedAmountConflictsWithoutChangingTheAcceptedBidEvenAfterTheAuctionEnds() {
        Fixture fixture = auction();
        String key = key();
        place(fixture, key, MINIMUM);
        Snapshot accepted = snapshot(fixture.id());

        for (BigDecimal amount : List.of(HIGHER, new BigDecimal("1020000.00"))) {
            assertThatThrownBy(() -> place(fixture, key, amount))
                    .isInstanceOf(DomainException.class)
                    .extracting("errorCode").isEqualTo("AUCTION_IDEMPOTENCY_KEY_CONFLICT");
            assertThat(snapshot(fixture.id())).isEqualTo(accepted);
        }
        jdbc().update("UPDATE auctions SET status = 'ENDED' WHERE id = ?", fixture.id());
        Snapshot ended = snapshot(fixture.id());
        assertThatThrownBy(() -> place(fixture, key, HIGHER))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo("AUCTION_IDEMPOTENCY_KEY_CONFLICT");
        assertThat(snapshot(fixture.id())).isEqualTo(ended);
    }

    @Test
    void lastMinuteReplayAndChangedAmountLeaveTheExtendedAuctionBidAndOutboxUnchanged() {
        Fixture fixture = auction();
        String key = key();
        jdbc().update("UPDATE auctions SET end_at=now()+interval '2 minutes', "
                + "original_end_at=now()+interval '2 minutes' WHERE id=?", fixture.id());
        Instant originalEnd = jdbc().queryForObject("SELECT original_end_at FROM auctions WHERE id=?",
                (rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant(), fixture.id());

        BidService.PlacementResult accepted = place(fixture, key, MINIMUM);
        Snapshot afterFirstBid = snapshot(fixture.id());
        assertThat(accepted.created()).isTrue();
        assertThat(jdbc().<Instant>queryForObject("SELECT end_at FROM auctions WHERE id=?",
                (rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant(), fixture.id()))
                .isEqualTo(originalEnd.plusSeconds(300));
        assertThat(jdbc().<Instant>queryForObject("SELECT original_end_at FROM auctions WHERE id=?",
                (rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant(), fixture.id()))
                .isEqualTo(originalEnd);
        assertThat(((Number) afterFirstBid.auction().get("extension_count")).intValue()).isEqualTo(1);
        assertThat(((Number) afterFirstBid.auction().get("bid_count")).intValue()).isEqualTo(1);
        assertThat(afterFirstBid.bids()).hasSize(1);
        assertThat(afterFirstBid.events()).hasSize(1);

        // Replay cả UUID hoa/thường và cách viết amount khác vẫn giữ nguyên deadline đã gia hạn.
        BidService.PlacementResult replay = place(fixture, key.toUpperCase(Locale.ROOT),
                new BigDecimal("1050000"));
        assertThat(replay.created()).isFalse();
        assertThat(replay.response()).isEqualTo(accepted.response());
        assertThat(snapshot(fixture.id())).isEqualTo(afterFirstBid);

        assertThatThrownBy(() -> place(fixture, key, HIGHER))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> {
                    DomainException conflict = (DomainException) error;
                    assertThat(conflict.getCode()).isEqualTo(409);
                    assertThat(conflict.getErrorCode()).isEqualTo("AUCTION_IDEMPOTENCY_KEY_CONFLICT");
                });
        assertThat(snapshot(fixture.id())).isEqualTo(afterFirstBid);
    }

    @Test
    void aKeyCannotReplayAnotherBiddersOrAuctionsResult() {
        Fixture first = auction();
        Fixture other = auction();
        String key = key();
        place(first, key, MINIMUM);
        Snapshot accepted = snapshot(first.id());
        Snapshot untouched = snapshot(other.id());

        assertThatThrownBy(() -> service.place(first.id(), key, new PlaceBidRequest(MINIMUM), buyer()))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo("AUCTION_IDEMPOTENCY_KEY_CONFLICT");
        assertThatThrownBy(() -> service.place(other.id(), key, new PlaceBidRequest(MINIMUM), first.bidder()))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo("AUCTION_IDEMPOTENCY_KEY_CONFLICT");

        assertThat(snapshot(first.id())).isEqualTo(accepted);
        assertThat(snapshot(other.id())).isEqualTo(untouched);
    }

    @Test
    void aRejectedBidDoesNotConsumeItsKey() {
        Fixture fixture = auction();
        String key = key();
        Snapshot before = snapshot(fixture.id());

        assertThatThrownBy(() -> place(fixture, key, new BigDecimal("1020000.00")))
                .isInstanceOf(DomainException.class).extracting("errorCode").isEqualTo("AUCTION_BID_TOO_LOW");

        assertThat(snapshot(fixture.id())).isEqualTo(before);
        assertThat(place(fixture, key, MINIMUM).created()).isTrue();
        assertSingleBid(fixture, key, MINIMUM);
    }

    @Test
    void failureAfterWritingOutboxRollsBackTheBidAuctionAndKey() {
        Fixture fixture = auction();
        String key = key();
        Snapshot before = snapshot(fixture.id());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Fail after writing BidPlaced");
        }).when(outbox).write(anyString(), anyString(), any());

        assertThatThrownBy(() -> place(fixture, key, MINIMUM))
                .isInstanceOf(IllegalStateException.class).hasMessage("Fail after writing BidPlaced");

        assertThat(snapshot(fixture.id())).isEqualTo(before);
        doCallRealMethod().when(outbox).write(anyString(), anyString(), any());
        assertThat(place(fixture, key, MINIMUM).created()).isTrue();
        assertSingleBid(fixture, key, MINIMUM);
    }

    @Test
    void concurrentSameAmountFindsTheWinnerAfterTheConditionalUpdateLoses() throws Exception {
        Fixture fixture = auction();
        String key = key();
        bothRequestsMissTheKey(key);

        List<BidService.PlacementResult> results = race(
                () -> place(fixture, key, MINIMUM), () -> place(fixture, key, MINIMUM));

        assertThat(results).extracting(BidService.PlacementResult::created).containsExactlyInAnyOrder(true, false);
        assertThat(results.getFirst().response()).isEqualTo(results.getLast().response());
        assertSingleBid(fixture, key, MINIMUM);
    }

    @Test
    void uniqueConstraintLoserRollsBackItsHigherPriceAndReturnsConflict() throws Exception {
        Fixture fixture = auction();
        String key = key();
        bothRequestsMissTheKey(key);
        CountDownLatch lowerUpdated = new CountDownLatch(1);
        AtomicInteger successfulUpdates = new AtomicInteger();
        var repository = mockingDetails(auctions).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            BigDecimal amount = invocation.getArgument(2);
            if (amount.compareTo(HIGHER) == 0) {
                assertThat(lowerUpdated.await(15, TimeUnit.SECONDS)).isTrue();
            }
            int updated = (int) repository.answer(invocation);
            successfulUpdates.addAndGet(updated);
            if (amount.compareTo(MINIMUM) == 0) {
                lowerUpdated.countDown();
            }
            return updated;
        }).when(auctions).acceptBid(eq(fixture.id()), eq(fixture.bidder().id()), any(), eq("***"));

        List<Attempt> results = race(
                () -> attempt(fixture, key, MINIMUM), () -> attempt(fixture, key, HIGHER));

        // Both UPDATEs succeeded; the higher one must therefore have lost on the UNIQUE insert.
        assertThat(successfulUpdates).hasValue(2);
        assertThat(results.getFirst().failure()).isNull();
        assertThat(results.getFirst().result().created()).isTrue();
        assertThat(results.getLast().failure()).extracting("errorCode")
                .isEqualTo("AUCTION_IDEMPOTENCY_KEY_CONFLICT");
        assertSingleBid(fixture, key, MINIMUM);
    }

    @Test
    void lowerAmountConflictsAfterWaitingForTheWinningConditionalUpdate() throws Exception {
        Fixture fixture = auction();
        String key = key();
        bothRequestsMissTheKey(key);
        CountDownLatch higherUpdated = new CountDownLatch(1);
        AtomicInteger successfulUpdates = new AtomicInteger();
        var repository = mockingDetails(auctions).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            BigDecimal amount = invocation.getArgument(2);
            if (amount.compareTo(MINIMUM) == 0) {
                assertThat(higherUpdated.await(15, TimeUnit.SECONDS)).isTrue();
            }
            int updated = (int) repository.answer(invocation);
            successfulUpdates.addAndGet(updated);
            if (amount.compareTo(HIGHER) == 0) {
                higherUpdated.countDown();
            }
            return updated;
        }).when(auctions).acceptBid(eq(fixture.id()), eq(fixture.bidder().id()), any(), eq("***"));

        List<Attempt> results = race(
                () -> attempt(fixture, key, HIGHER), () -> attempt(fixture, key, MINIMUM));

        // UPDATE thấp hơn không ghi; nhánh đọc lại key phải báo conflict thay vì replay hoặc BID_TOO_LOW.
        assertThat(successfulUpdates).hasValue(1);
        assertThat(results.getFirst().failure()).isNull();
        assertThat(results.getFirst().result().created()).isTrue();
        assertThat(results.getLast().failure()).extracting("errorCode")
                .isEqualTo("AUCTION_IDEMPOTENCY_KEY_CONFLICT");
        assertSingleBid(fixture, key, HIGHER);
    }

    @Test
    void concurrentKeyReuseAcrossAuctionsRollsBackTheLosingAuction() throws Exception {
        Fixture first = auction();
        Fixture other = auction();
        String key = key();
        Snapshot firstBefore = snapshot(first.id());
        Snapshot otherBefore = snapshot(other.id());
        bothRequestsMissTheKey(key);

        List<Attempt> results = race(
                () -> attempt(first, key), () -> attempt(other, key));

        Attempt winner = results.stream().filter(result -> result.failure() == null).findFirst().orElseThrow();
        Attempt loser = results.stream().filter(result -> result.failure() != null).findFirst().orElseThrow();
        assertThat(winner.result().created()).isTrue();
        assertThat(loser.failure()).extracting("errorCode").isEqualTo("AUCTION_IDEMPOTENCY_KEY_CONFLICT");
        assertSingleBid(winner.fixture(), key, MINIMUM);
        assertThat(snapshot(loser.fixture().id())).isEqualTo(
                loser.fixture().id().equals(first.id()) ? firstBefore : otherBefore);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM bids WHERE idempotency_key = ?", Long.class, key))
                .isEqualTo(1);
    }

    private void bothRequestsMissTheKey(String key) {
        CyclicBarrier initialMisses = new CyclicBarrier(2);
        AtomicInteger misses = new AtomicInteger();
        // Spring's repository spy delegates to its real JDK proxy; the interface has no real method.
        var repository = mockingDetails(bids).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Optional<?> result = (Optional<?>) repository.answer(invocation);
            if (result.isEmpty()
                    && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    && !org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    && misses.incrementAndGet() <= 2) {
                // Only gate the business write transaction. The initial repository read also opens
                // a read-only transaction; gating that read lets one write finish before the other starts.
                initialMisses.await(15, TimeUnit.SECONDS);
            }
            return result;
        }).when(bids).findByIdempotencyKey(key);
    }

    private static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(first);
            var two = executor.submit(second);
            return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        }
    }

    private Attempt attempt(Fixture fixture, String key) {
        return attempt(fixture, key, MINIMUM);
    }

    private Attempt attempt(Fixture fixture, String key, BigDecimal amount) {
        try {
            return new Attempt(fixture, place(fixture, key, amount), null);
        } catch (DomainException failure) {
            return new Attempt(fixture, null, failure);
        }
    }

    private BidService.PlacementResult place(Fixture fixture, String key, BigDecimal amount) {
        return service.place(fixture.id(), key, new PlaceBidRequest(amount), fixture.bidder());
    }

    private void assertSingleBid(Fixture fixture, String key, BigDecimal amount) {
        Map<String, Object> auction = jdbc().queryForMap("SELECT * FROM auctions WHERE id = ?", fixture.id());
        assertThat((BigDecimal) auction.get("current_price")).isEqualByComparingTo(amount);
        assertThat(auction.get("leader_bidder_id")).isEqualTo(fixture.bidder().id());
        assertThat(((Number) auction.get("bid_count")).intValue()).isEqualTo(1);
        assertThat(((Number) auction.get("version")).longValue()).isEqualTo(1);
        List<Map<String, Object>> rows = jdbc().queryForList("SELECT * FROM bids WHERE auction_id = ?", fixture.id());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("idempotency_key")).isEqualTo(key);
        assertThat((BigDecimal) rows.getFirst().get("amount")).isEqualByComparingTo(amount);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'BidPlaced'",
                Long.class, fixture.id().toString())).isEqualTo(1);
    }

    private Snapshot snapshot(UUID id) {
        return new Snapshot(jdbc().queryForMap("SELECT * FROM auctions WHERE id = ?", id),
                jdbc().queryForList("SELECT * FROM bids WHERE auction_id = ? ORDER BY id", id),
                jdbc().queryForList("SELECT * FROM outbox WHERE aggregate_id = ? ORDER BY id", id.toString()));
    }

    private Fixture auction() {
        UUID id = UUID.randomUUID();
        jdbc().update("""
                INSERT INTO auctions (id, product_id, sku_id, seller_id, title, starting_price,
                    bid_increment, current_price, start_at, end_at, original_end_at, status)
                VALUES (?, ?, ?, ?, 'Idempotency test auction', 1000000.00, 50000.00, 1000000.00,
                    now()-interval '1 hour', now()+interval '1 hour', now()+interval '1 hour', 'ACTIVE')
                """, id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        return new Fixture(id, buyer());
    }

    private static String key() { return UUID.randomUUID().toString(); }
    private static CurrentUser buyer() {
        return new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of("AUCTION.BID"));
    }
    private JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }

    private record Fixture(UUID id, CurrentUser bidder) { }
    private record Attempt(Fixture fixture, BidService.PlacementResult result, DomainException failure) { }
    private record Snapshot(Map<String, Object> auction, List<Map<String, Object>> bids,
                            List<Map<String, Object>> events) { }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {AuctionRepository.class, BidRepository.class})
    @EnableJpaAuditing(auditorAwareRef = "auditor")
    @Import({BidService.class, AntiSnipingPolicy.class})
    static class JpaConfig {
        @Bean
        DataSource dataSource() {
            String jdbcUrl = POSTGRES.getJdbcUrl();
            DataSource source = new DriverManagerDataSource(jdbcUrl
                    + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=auction_bid_idempotency",
                    POSTGRES.getUsername(), POSTGRES.getPassword());
            Flyway.configure().dataSource(source).schemas("auction_bid_idempotency")
                    .defaultSchema("auction_bid_idempotency").load().migrate();
            return source;
        }

        @Bean AuctionProperties auctionProperties() { return new AuctionProperties(); }
        @Bean com.group5.auctionservice.reputation.ReputationGuard reputationGuard(AuctionProperties properties) {
            return new com.group5.auctionservice.reputation.ReputationGuard(properties,
                    (user, action) -> { throw new IllegalStateException("Enforcement is disabled in this fixture"); });
        }
        @Bean AuditorAware<UUID> auditor() { return new CurrentUserAuditor(); }
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC); }

        @Bean
        EventEnvelopeFactory envelopes(Clock clock) {
            Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
            when(tracer.currentSpan().context().traceId()).thenReturn("147bid");
            return new EventEnvelopeFactory(tracer, clock);
        }

        @Bean
        OutboxWriter outbox(DataSource source, PlatformTransactionManager transactions) {
            return new OutboxWriter(new JdbcTemplate(source), JsonMapper.shared(), transactions);
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
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
