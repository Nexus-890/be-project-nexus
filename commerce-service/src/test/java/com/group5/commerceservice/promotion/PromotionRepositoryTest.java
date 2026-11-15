package com.group5.commerceservice.promotion;

import org.flywaydb.core.Flyway;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "promotion.test.jdbc-url", matches = ".+")
class PromotionRepositoryTest {
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 16, 0, 0, 0, 0, ZoneOffset.UTC);
    private static JdbcClient jdbc;
    private static TransactionTemplate transaction;
    private static VoucherLimitRepository limits;

    @BeforeAll
    static void migrate() {
        var source = new PGSimpleDataSource();
        source.setURL(System.getProperty("promotion.test.jdbc-url"));
        source.setUser(System.getProperty("promotion.test.jdbc-user", "postgres"));
        source.setPassword(System.getProperty("promotion.test.jdbc-password", ""));
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = JdbcClient.create(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        limits = new VoucherLimitRepository(jdbc);
    }

    @Test
    void checksClaimLimitAgainstFiftyConcurrentTransactions() throws Exception {
        UUID voucher = createVoucher(null);
        assertThat(accepted(() -> limits.takeClaim(voucher, NOW))).isEqualTo(10);
        assertThat(counter(voucher, "claim_count")).isEqualTo(10);
    }

    @Test
    void checksUsageLimitAndBudgetIndependentlyUnderContention() throws Exception {
        UUID limitedByCount = createVoucher(BigDecimal.valueOf(10_000));
        assertThat(accepted(() -> limits.reserve(limitedByCount, 1, BigDecimal.TEN, NOW))).isEqualTo(10);
        assertThat(counter(limitedByCount, "committed_uses")).isEqualTo(10);

        UUID limitedByBudget = createVoucher(BigDecimal.valueOf(73));
        assertThat(accepted(() -> limits.reserve(limitedByBudget, 1, BigDecimal.TEN, NOW))).isEqualTo(7);
        assertThat(counter(limitedByBudget, "committed_discount")).isEqualTo(70);
    }

    @Test
    void returnsCapacityAfterReleaseAndRollsBackAReservationWithItsTransaction() {
        UUID voucher = createVoucher(BigDecimal.TEN);
        transaction.executeWithoutResult(status -> {
            assertThat(limits.reserve(voucher, 1, BigDecimal.TEN, NOW)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(counter(voucher, "committed_uses")).isZero();
        transaction.executeWithoutResult(status -> {
            assertThat(limits.reserve(voucher, 1, BigDecimal.TEN, NOW)).isTrue();
            assertThat(limits.reserve(voucher, 1, BigDecimal.TEN, NOW)).isFalse();
            limits.release(voucher, 1, BigDecimal.TEN, NOW);
            assertThat(limits.reserve(voucher, 1, BigDecimal.TEN, NOW)).isTrue();
        });
        assertThat(counter(voucher, "committed_discount")).isEqualTo(10);
    }

    @Test
    void createsOpensAndReviewsAnEventThenAdvancesItByItsDates() {
        var service = new MegaSaleEventService(new MegaSaleEventRepository(jdbc));
        var admin = new CurrentUser(UUID.randomUUID(), Set.of("ADMIN"), Set.of());
        var approved = new CurrentUser(UUID.randomUUID(), Set.of("SELLER"), Set.of());
        var rejected = new CurrentUser(UUID.randomUUID(), Set.of("SELLER"), Set.of());
        var outsider = new CurrentUser(UUID.randomUUID(), Set.of("SELLER"), Set.of());
        var now = MegaSaleEventService.now();
        var request = new MegaSaleEvent.Write("November event", null, null,
                now.minusHours(1), now.plusHours(1), now.plusDays(1), now.plusDays(2));

        var event = transaction.execute(status -> service.create(admin.id(), request));
        assertThat(event.status()).isEqualTo(MegaSaleEvent.Status.DRAFT);
        assertThatThrownBy(() -> service.find(outsider, event.id())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> transaction.execute(status -> service.register(outsider.id(), event.id())))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).getErrorCode())
                .isEqualTo("COMMERCE_PROMOTION_REGISTRATION_CLOSED");

        var opened = transaction.execute(status -> service.status(admin.id(), event.id(), MegaSaleEvent.Action.OPEN));
        assertThat(opened.status()).isEqualTo(MegaSaleEvent.Status.REGISTRATION_OPEN);
        assertThat(service.list(outsider, MegaSaleEvent.Status.REGISTRATION_OPEN, 0, 10).getContent())
                .extracting(MegaSaleEvent::id).contains(event.id());

        var first = transaction.execute(status -> service.register(approved.id(), event.id()));
        var second = transaction.execute(status -> service.register(rejected.id(), event.id()));
        assertThat(first.status()).isEqualTo(MegaSaleEvent.SellerStatus.PENDING);
        transaction.execute(status -> service.review(admin.id(), first.id(),
                new MegaSaleEvent.Review(MegaSaleEvent.ReviewAction.APPROVED, "Eligible")));
        transaction.execute(status -> service.review(admin.id(), second.id(),
                new MegaSaleEvent.Review(MegaSaleEvent.ReviewAction.REJECTED, "Outside scope")));
        assertThat(service.sellers(event.id(), MegaSaleEvent.SellerStatus.APPROVED, 0, 10).getContent())
                .singleElement().satisfies(row -> {
                    assertThat(row.sellerId()).isEqualTo(approved.id());
                    assertThat(row.reviewNote()).isEqualTo("Eligible");
                });
        assertThat(service.sellers(event.id(), MegaSaleEvent.SellerStatus.REJECTED, 0, 10).getTotalElements())
                .isEqualTo(1);

        transaction.execute(status -> service.advance(request.registrationEndAt()));
        assertThat(service.find(approved, event.id()).status()).isEqualTo(MegaSaleEvent.Status.REGISTRATION_CLOSED);
        assertThatThrownBy(() -> service.find(outsider, event.id())).isInstanceOf(DomainException.class);
        transaction.execute(status -> service.advance(request.startAt()));
        assertThat(service.find(admin, event.id()).status()).isEqualTo(MegaSaleEvent.Status.ACTIVE);
        transaction.execute(status -> service.advance(request.endAt()));
        assertThat(service.find(admin, event.id()).status()).isEqualTo(MegaSaleEvent.Status.ENDED);
    }

    @Test
    void registrationCanBeScheduledAndOpensAtTheConfiguredTime() {
        var service = new MegaSaleEventService(new MegaSaleEventRepository(jdbc));
        var admin = new CurrentUser(UUID.randomUUID(), Set.of("ADMIN"), Set.of());
        var seller = new CurrentUser(UUID.randomUUID(), Set.of("SELLER"), Set.of());
        var now = MegaSaleEventService.now();
        var request = new MegaSaleEvent.Write("Scheduled event", null, null,
                now.plusDays(3), now.plusDays(4), now.plusDays(5), now.plusDays(6));

        var event = transaction.execute(status -> service.create(admin.id(), request));
        var scheduled = transaction.execute(status -> service.status(admin.id(), event.id(), MegaSaleEvent.Action.OPEN));
        assertThat(scheduled.registrationOpenRequested()).isTrue();
        assertThat(scheduled.status()).isEqualTo(MegaSaleEvent.Status.DRAFT);
        assertThatThrownBy(() -> service.find(seller, event.id())).isInstanceOf(DomainException.class);

        transaction.execute(status -> service.advance(request.registrationStartAt().plusSeconds(1)));
        assertThat(service.find(admin, event.id()).status()).isEqualTo(MegaSaleEvent.Status.REGISTRATION_OPEN);
    }

    private static long accepted(Callable<Boolean> attempt) throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(50)) {
            List<Callable<Boolean>> attempts = IntStream.range(0, 50).mapToObj(index -> (Callable<Boolean>) () -> {
                start.await();
                return transaction.execute(status -> {
                    try {
                        return attempt.call();
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
            }).toList();
            var futures = attempts.stream().map(workers::submit).toList();
            start.countDown();
            long accepted = 0;
            for (var result : futures) {
                if (result.get(45, TimeUnit.SECONDS)) accepted++;
            }
            return accepted;
        }
    }

    private static UUID createVoucher(BigDecimal budget) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO promo_vouchers
                (id, code, name, source_type, discount_type, discount_value, funding_type, product_scope,
                 claim_limit, usage_limit, discount_budget, claim_start_at, claim_end_at, valid_from, valid_until, status)
                VALUES (:id, :code, 'Platform fixture', 'PLATFORM', 'FIXED', 10, 'PLATFORM', 'ALL',
                        10, 10, :budget, :start, :end, :start, :end, 'ACTIVE')
                """)
                .param("id", id).param("code", id.toString()).param("budget", budget)
                .param("start", NOW.minusDays(1)).param("end", NOW.plusDays(1)).update();
        return id;
    }

    private static long counter(UUID voucher, String column) {
        return jdbc.sql("SELECT " + column + " FROM promo_vouchers WHERE id = :id")
                .param("id", voucher).query(Long.class).single();
    }
}
