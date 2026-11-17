package com.group5.commerceservice.promotion;

import org.flywaydb.core.Flyway;
import com.group5.common_core.model.ApiResponse;
import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.common_security.CurrentUserArgumentResolver;
import com.group5.common_security.JwtAuthConverter;
import com.group5.common_web.exception.GlobalExceptionHandler;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.json.JsonMapper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfSystemProperty(named = "promotion.test.jdbc-url", matches = ".+")
class PromotionRepositoryTest {
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 16, 0, 0, 0, 0, ZoneOffset.UTC);
    private static JdbcClient jdbc;
    private static TransactionTemplate transaction;
    private static VoucherLimitRepository limits;
    private static DataSourceTransactionManager transactions;

    @BeforeAll
    static void migrate() {
        var source = new PGSimpleDataSource();
        source.setURL(System.getProperty("promotion.test.jdbc-url"));
        source.setUser(System.getProperty("promotion.test.jdbc-user", "postgres"));
        source.setPassword(System.getProperty("promotion.test.jdbc-password", ""));
        String schema = "promotion_" + UUID.randomUUID().toString().replace("-", "");
        source.setCurrentSchema(schema);
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        jdbc = JdbcClient.create(source);
        transactions = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(transactions);
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

    @Test
    void limitsClaimsAcrossFiftyBuyersAndRejectsARepeatClaim() throws Exception {
        var service = vouchers(mock(CatalogClient.class));
        var admin = new CurrentUser(UUID.randomUUID(), Set.of("ADMIN"), Set.of());
        UUID id = transaction.execute(status -> service.create(admin,
                write(VoucherPricing.Source.PLATFORM, null, Voucher.Funding.PLATFORM, Voucher.Scope.ALL, 10,
                        MegaSaleEventService.now().minusHours(1), MegaSaleEventService.now().plusDays(1)))).voucher().id();
        transaction.execute(status -> service.status(admin, id, Voucher.Action.ACTIVE));

        var winners = new java.util.concurrent.ConcurrentLinkedQueue<UUID>();
        assertThat(accepted(() -> {
            UUID buyer = UUID.randomUUID();
            try {
                service.claim(buyer, id);
                winners.add(buyer);
                return true;
            } catch (DomainException error) {
                assertThat(error.getErrorCode()).isEqualTo("COMMERCE_VOUCHER_CLAIM_LIMIT_REACHED");
                return false;
            }
        })).isEqualTo(10);
        UUID winner = winners.element();
        assertThatThrownBy(() -> transaction.execute(status -> service.claim(winner, id)))
                .isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo("COMMERCE_VOUCHER_ALREADY_CLAIMED");

        assertThat(jdbc.sql("SELECT count(*) FROM promo_customer_vouchers WHERE voucher_id = :id")
                .param("id", id).query(Long.class).single()).isEqualTo(10);
        assertThat(counter(id, "claim_count")).isEqualTo(10);
        assertThat(service.wallet(winner, Voucher.WalletStatus.CLAIMED, 0, 10).getContent())
                .singleElement().satisfies(entry -> assertThat(entry.status()).isEqualTo(Voucher.WalletStatus.CLAIMED));
        assertThat(service.wallet(UUID.randomUUID(), null, 0, 10).getContent()).isEmpty();
    }

    @Test
    void enforcesShopAndProductOwnershipAndFiltersThePublicList() {
        var catalog = mock(CatalogClient.class);
        var service = vouchers(catalog);
        var seller = new CurrentUser(UUID.randomUUID(), Set.of("SELLER"), Set.of());
        var other = new CurrentUser(UUID.randomUUID(), Set.of("SELLER"), Set.of());
        var goodProduct = UUID.randomUUID();
        var wrongProduct = UUID.randomUUID();
        when(catalog.getProductById(goodProduct)).thenReturn(ApiResponse.of(
                new CatalogClient.ProductResponse(goodProduct, seller.id(), "Good", "ACTIVE", List.of())));
        when(catalog.getProductById(wrongProduct)).thenReturn(ApiResponse.of(
                new CatalogClient.ProductResponse(wrongProduct, other.id(), "Wrong", "ACTIVE", List.of())));
        var now = MegaSaleEventService.now();
        var request = write(VoucherPricing.Source.SHOP, null, Voucher.Funding.SELLER, Voucher.Scope.SELECTED, null,
                now.minusHours(1), now.plusDays(1));
        UUID id = transaction.execute(status -> service.create(seller, request)).voucher().id();

        assertThatThrownBy(() -> service.detail(other, id)).isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).getErrorCode()).isEqualTo("COMMERCE_FORBIDDEN");
        assertThatThrownBy(() -> transaction.execute(status -> service.edit(other, id, request)))
                .isInstanceOf(DomainException.class).extracting(error -> ((DomainException) error).getErrorCode())
                .isEqualTo("COMMERCE_FORBIDDEN");
        assertThatThrownBy(() -> transaction.execute(status -> service.products(seller, id, Set.of(wrongProduct))))
                .isInstanceOf(DomainException.class).extracting(error -> ((DomainException) error).getErrorCode())
                .isEqualTo("COMMERCE_FORBIDDEN");

        transaction.execute(status -> service.edit(seller, id, request));
        transaction.execute(status -> service.products(seller, id, Set.of(goodProduct)));
        transaction.execute(status -> service.status(seller, id, Voucher.Action.ACTIVE));
        assertThat(service.publicList(goodProduct, null, 0, 100).getContent()).extracting(Voucher.PublicView::id).contains(id);
        assertThat(service.publicList(wrongProduct, null, 0, 100).getContent()).extracting(Voucher.PublicView::id).doesNotContain(id);
        assertThat(service.manage(other, VoucherPricing.Source.SHOP, null, null, 0, 10).getContent()).isEmpty();
    }

    @Test
    void approvedSellerAcceptsTheEventVoucherTermsAndFundingSnapshot() {
        var events = new MegaSaleEventService(new MegaSaleEventRepository(jdbc));
        var service = vouchers(mock(CatalogClient.class));
        var admin = new CurrentUser(UUID.randomUUID(), Set.of("ADMIN"), Set.of());
        var seller = new CurrentUser(UUID.randomUUID(), Set.of("SELLER"), Set.of());
        var now = MegaSaleEventService.now();
        var event = transaction.execute(status -> events.create(admin.id(), new MegaSaleEvent.Write("Voucher event",
                null, null, now.minusHours(3), now.plusHours(1), now.plusDays(2), now.plusDays(4))));
        transaction.execute(status -> events.status(admin.id(), event.id(), MegaSaleEvent.Action.OPEN));
        var registration = transaction.execute(status -> events.register(seller.id(), event.id()));
        var request = write(VoucherPricing.Source.EVENT, event.id(), Voucher.Funding.CO_FUNDED, Voucher.Scope.ALL, null,
                now.plusDays(2).plusHours(1), now.plusDays(3));
        UUID id = transaction.execute(status -> service.create(admin, request)).voucher().id();
        transaction.execute(status -> service.status(admin, id, Voucher.Action.ACTIVE));

        assertThatThrownBy(() -> transaction.execute(status -> service.join(seller.id(), id, 1)))
                .isInstanceOf(DomainException.class).extracting(error -> ((DomainException) error).getErrorCode())
                .isEqualTo("COMMERCE_FORBIDDEN");
        transaction.execute(status -> events.review(admin.id(), registration.id(),
                new MegaSaleEvent.Review(MegaSaleEvent.ReviewAction.APPROVED, null)));
        assertThat(service.manage(seller, VoucherPricing.Source.EVENT, null, event.id(), 0, 10).getContent())
                .extracting(Voucher.View::voucher).extracting(Voucher::id).containsExactly(id);
        assertThatThrownBy(() -> transaction.execute(status -> service.join(seller.id(), id, 2)))
                .isInstanceOf(DomainException.class).extracting(error -> ((DomainException) error).getErrorCode())
                .isEqualTo("COMMERCE_VOUCHER_TERMS_CHANGED");

        var joined = transaction.execute(status -> service.join(seller.id(), id, 1));
        assertThat(joined.acceptedTermsVersion()).isEqualTo(1);
        assertThat(joined.acceptedTermsSnapshot()).isEqualTo("Voucher terms");
        assertThat(joined.sellerFundingPercentSnapshot()).isEqualByComparingTo("35.00");
        assertThat(transaction.execute(status -> service.join(seller.id(), id, 1)).id()).isEqualTo(joined.id());
        transaction.executeWithoutResult(status -> service.withdraw(seller.id(), id));
        assertThat(new VoucherRepository(jdbc).participants(id)).isEmpty();
    }

    @Test
    void voucherControllersEnforceClaimsAndOwnershipAndReturnProblemDetails() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(PromotionSecurity.class, VoucherController.class, VoucherService.class,
                    MegaSaleEventRepository.class, VoucherRepository.class, VoucherLimitRepository.class);
            context.registerBean(JdbcClient.class, () -> jdbc);
            context.registerBean(DataSourceTransactionManager.class, () -> transactions);
            context.registerBean(CatalogClient.class, () -> mock(CatalogClient.class));
            context.refresh();
            var errors = new GlobalExceptionHandler(null);
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(VoucherController.class))
                    .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                    .setControllerAdvice(new PromotionAccessAdvice(errors), errors).build();
            var now = MegaSaleEventService.now();
            var shop = write(VoucherPricing.Source.SHOP, null, Voucher.Funding.SELLER, Voucher.Scope.ALL, 10,
                    now.minusHours(1), now.plusDays(1));
            var json = JsonMapper.shared();
            UUID seller = UUID.randomUUID();
            authenticate(seller, "SELLER", List.of("VOUCHER.MANAGE"));
            var created = mvc.perform(post("/api/v1/vouchers").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(shop))).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.voucher.ownerSellerId").value(seller.toString()))
                    .andReturn();
            String id = json.readTree(created.getResponse().getContentAsString()).at("/data/voucher/id").asString();
            mvc.perform(patch("/api/v1/vouchers/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ACTIVE\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.voucher.status").value("ACTIVE"));

            authenticate(UUID.randomUUID(), "SELLER", List.of("VOUCHER.MANAGE"));
            mvc.perform(get("/api/v1/vouchers/" + id)).andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("COMMERCE_FORBIDDEN"));
            mvc.perform(put("/api/v1/vouchers/" + id).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(shop)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMMERCE_FORBIDDEN"));

            UUID buyer = UUID.randomUUID();
            authenticate(buyer, "BUYER", List.of("VOUCHER.CLAIM", "VOUCHER.VIEW"));
            mvc.perform(post("/api/v1/vouchers/" + id + "/claim")).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("CLAIMED"));
            mvc.perform(post("/api/v1/vouchers/" + id + "/claim")).andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("COMMERCE_VOUCHER_ALREADY_CLAIMED"));
            mvc.perform(get("/api/v1/vouchers/me")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].voucher.id").value(id))
                    .andExpect(jsonPath("$.page.totalElements").value(1));
            mvc.perform(get("/api/v1/vouchers/manage")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMERCE_FORBIDDEN"));
            SecurityContextHolder.clearContext();
            mvc.perform(get("/api/v1/vouchers").param("sellerId", seller.toString())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id == '" + id + "')]").isNotEmpty());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void expiresActiveVouchersAndUnspentClaimsAtTheEndOfTheirWindow() {
        var service = vouchers(mock(CatalogClient.class));
        var admin = new CurrentUser(UUID.randomUUID(), Set.of("ADMIN"), Set.of());
        var buyer = UUID.randomUUID();
        var now = MegaSaleEventService.now();
        var ends = now.plusHours(1);
        UUID id = transaction.execute(status -> service.create(admin,
                write(VoucherPricing.Source.PLATFORM, null, Voucher.Funding.PLATFORM, Voucher.Scope.ALL, 10,
                        now.minusHours(1), ends))).voucher().id();
        transaction.execute(status -> service.status(admin, id, Voucher.Action.ACTIVE));
        var claim = transaction.execute(status -> service.claim(buyer, id));
        var repo = new VoucherRepository(jdbc);
        var lifecycle = new VoucherLifecycleService(new VoucherUsageRepository(jdbc, JsonMapper.shared()), limits, repo,
                mock(OrderEventPublisher.class));
        transaction.executeWithoutResult(status -> lifecycle.expireVoucherWindows(ends.plusSeconds(1)));
        assertThat(repo.find(id).orElseThrow().status()).isEqualTo(Voucher.Status.EXPIRED);
        assertThat(repo.claim(buyer, id).orElseThrow().status()).isEqualTo(Voucher.WalletStatus.EXPIRED);
        assertThat(service.wallet(buyer, Voucher.WalletStatus.EXPIRED, 0, 10).getContent())
                .extracting(Voucher.WalletEntry::id).containsExactly(claim.id());
    }

    private static void authenticate(UUID actor, String role, List<String> privileges) {
        var jwt = Jwt.withTokenValue("promotion-fixture").header("alg", "RS256").subject(actor.toString())
                .claim(JwtAuthConverter.ROLES_CLAIM, List.of(role)).claim(JwtAuthConverter.PRIVILEGES_CLAIM, privileges).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthConverter().convert(jwt));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableMethodSecurity
    static class PromotionSecurity { }

    private static VoucherService vouchers(CatalogClient catalog) {
        return new VoucherService(new VoucherRepository(jdbc), limits, new MegaSaleEventRepository(jdbc), catalog);
    }

    private static Voucher.Write write(VoucherPricing.Source source, UUID eventId, Voucher.Funding funding,
                                        Voucher.Scope scope, Integer claimLimit, OffsetDateTime from, OffsetDateTime until) {
        return new Voucher.Write(UUID.randomUUID().toString(), "Test voucher", null, source, eventId,
                VoucherPricing.Discount.FIXED, BigDecimal.TEN, null, BigDecimal.ZERO, funding,
                funding == Voucher.Funding.CO_FUNDED ? BigDecimal.valueOf(35) : null, scope, claimLimit, null, null,
                from.minusHours(1), until, from, until, "Voucher terms");
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
