package com.group5.paymentservice.payment;

import com.group5.common_events.EventEnvelope;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.paymentservice.payment.entity.PaymentInstruction;
import com.group5.paymentservice.payment.event.payload.CheckoutPaymentRequested;
import com.group5.paymentservice.payment.event.payload.OrderCompleted;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import com.group5.paymentservice.payment.service.PaymentSessionEventHandler;
import com.group5.paymentservice.payment.service.PayoutEligibilityService;
import com.group5.paymentservice.payment.service.RefundService;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real V4 paid rows and original processed marker survive V5; exact source-event replay repairs ownership. */
class LegacySellerReplayUpgradeTest {
    private static final UUID GROUP = id(1), BUYER = id(2), SESSION = id(3), PAYMENT = id(4), EVENT = id(5);
    private static final UUID ORDER_A = id(10), ORDER_B = id(11), SELLER_A = id(20), SELLER_B = id(21);
    private static final Instant CREATED = Instant.parse("2026-11-14T01:00:00Z");
    private static final Instant DUE = CREATED.plusSeconds(900), PAID = CREATED.plusSeconds(60);
    private static final String REPAIR_CONSUMER = "payment-checkout-seller-backfill-v1";

    @Test void paidBeforeV5CompletesAfterUpgradeAndExactOriginalReplayRestoresEligibilityOnce() throws Exception {
        try (var legacy = new Legacy()) {
            var before = legacy.financialSnapshot();
            assertThat(legacy.marker(PaymentSessionEventHandler.CHECKOUT_CONSUMER)).isEqualTo(1);
            assertThat(legacy.sellers()).containsExactly(null, null);
            var completion = completed(SELLER_A);
            legacy.payouts.completed(completion);
            assertThat(legacy.payouts.pendingIds()).doesNotContain(ORDER_A);
            assertThat(legacy.eligible()).isZero();

            legacy.checkout.checkout(original());
            assertThat(legacy.sellers()).containsExactly(SELLER_A, SELLER_B);
            assertThat(legacy.financialSnapshot()).isEqualTo(before);
            assertThat(legacy.marker(PaymentSessionEventHandler.CHECKOUT_CONSUMER)).isEqualTo(1);
            assertThat(legacy.marker(REPAIR_CONSUMER)).isEqualTo(1);
            assertThat(legacy.payouts.pendingIds()).contains(ORDER_A);
            legacy.payouts.retry(ORDER_A);
            legacy.checkout.checkout(original());
            legacy.payouts.completed(completion);
            legacy.payouts.retry(ORDER_A);

            assertThat(legacy.eligible()).isEqualTo(1);
            assertThat(legacy.jdbc.queryForMap("SELECT seller_id,funded_transaction_id,amount FROM payout_eligibility WHERE order_id=?", ORDER_A))
                    .containsEntry("seller_id", SELLER_A).containsEntry("funded_transaction_id", PAYMENT)
                    .containsEntry("amount", new BigDecimal("125000.00"));
            assertThat(legacy.financialSnapshot()).isEqualTo(before);
            verifyNoInteractions(legacy.context.getBean(RefundService.class));
        }
    }

    @Test void alteredOriginalEventCannotBackfillAndPartialSellerConflictRollsBackTheWholeRepair() throws Exception {
        try (var legacy = new Legacy()) {
            var before = legacy.financialSnapshot();
            var manifest = original().payload();
            var first = manifest.orders().getFirst();
            var changed = new CheckoutPaymentRequested.SellerOrder(first.orderId(), UUID.randomUUID(),
                    first.amount(), first.paymentDueAt(), first.orderVersion());
            var tampered = new CheckoutPaymentRequested(manifest.orderGroupId(), manifest.buyerId(), manifest.currency(),
                    manifest.totalAmount(), manifest.dueAt(), manifest.occurredAt(), 2, List.of(changed, manifest.orders().get(1)));
            var event = new EventEnvelope<>(EVENT, original().eventType(), GROUP.toString(), CREATED, "legacy-upgrade", tampered);
            assertThatThrownBy(() -> legacy.checkout.checkout(event)).isInstanceOf(IllegalArgumentException.class);
            assertThat(legacy.sellers()).containsExactly(null, null);
            assertThat(legacy.marker(REPAIR_CONSUMER)).isZero();


            for (String changedField : List.of("amount", "deadline", "buyer", "order")) {
                var changedFirst = new CheckoutPaymentRequested.SellerOrder(
                        changedField.equals("order") ? UUID.randomUUID() : first.orderId(), first.sellerId(),
                        changedField.equals("amount") ? first.amount().add(BigDecimal.ONE) : first.amount(),
                        changedField.equals("deadline") ? first.paymentDueAt().plusSeconds(1) : first.paymentDueAt(),
                        first.orderVersion());
                var changedManifest = new CheckoutPaymentRequested(GROUP,
                        changedField.equals("buyer") ? UUID.randomUUID() : BUYER, "VND",
                        changedField.equals("amount") ? manifest.totalAmount().add(BigDecimal.ONE) : manifest.totalAmount(),
                        DUE, CREATED, 2, List.of(changedFirst, manifest.orders().get(1)));
                var changedEvent = new EventEnvelope<>(EVENT, original().eventType(), GROUP.toString(),
                        CREATED, "legacy-upgrade", changedManifest);
                assertThatThrownBy(() -> legacy.checkout.checkout(changedEvent)).as(changedField)
                        .isInstanceOf(IllegalArgumentException.class);
                assertThat(legacy.sellers()).containsExactly(null, null);
                assertThat(legacy.marker(REPAIR_CONSUMER)).isZero();
            }
            legacy.jdbc.update("UPDATE payment_instructions SET seller_id=? WHERE order_id=?", UUID.randomUUID(), ORDER_B);
            assertThatThrownBy(() -> legacy.checkout.checkout(original())).isInstanceOf(IllegalArgumentException.class);
            assertThat(legacy.sellers().getFirst()).isNull();
            assertThat(legacy.marker(REPAIR_CONSUMER)).isZero();
            assertThat(legacy.financialSnapshot()).isEqualTo(before);

            legacy.jdbc.update("UPDATE payment_instructions SET seller_id=null WHERE order_id=?", ORDER_B);
            legacy.checkout.checkout(original());
            assertThat(legacy.sellers()).containsExactly(SELLER_A, SELLER_B);
        }
    }

    @Test void concurrentOriginalReplaysOnlyRepairOnceAndPreserveFunding() throws Exception {
        try (var legacy = new Legacy(); var pool = Executors.newFixedThreadPool(2)) {
            var before = legacy.financialSnapshot();
            var start = new CountDownLatch(1);
            var one = pool.submit(() -> { start.await(); legacy.checkout.checkout(original()); return true; });
            var two = pool.submit(() -> { start.await(); legacy.checkout.checkout(original()); return true; });
            start.countDown();
            assertThat(one.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(two.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(legacy.marker(REPAIR_CONSUMER)).isEqualTo(1);
            assertThat(legacy.sellers()).containsExactly(SELLER_A, SELLER_B);
            assertThat(legacy.financialSnapshot()).isEqualTo(before);
        }
    }

    @Test void completedEventCannotSupplyMissingSellerAndClosedOrdersNeverReopenOnRepair() throws Exception {
        try (var legacy = new Legacy()) {
            legacy.payouts.completed(completed(UUID.randomUUID()));
            legacy.checkout.checkout(original());
            legacy.payouts.retry(ORDER_A);
            assertThat(legacy.eligible()).isZero();
            assertThat(legacy.sellers()).containsExactly(SELLER_A, SELLER_B);
        }
        try (var legacy = new Legacy()) {
            legacy.jdbc.update("UPDATE payment_sessions SET status='CANCELED' WHERE id=?", SESSION);
            legacy.jdbc.update("UPDATE payment_instructions SET status='CANCELED',canceled_at=now()");
            var before = legacy.financialSnapshot();
            legacy.checkout.checkout(original());
            legacy.payouts.completed(completed(SELLER_A));
            legacy.payouts.retry(ORDER_A);
            assertThat(legacy.eligible()).isZero();
            assertThat(legacy.financialSnapshot()).isEqualTo(before);
            verifyNoInteractions(legacy.context.getBean(RefundService.class));
        }
    }

    @Test
    void equivalentBusinessReplayAndItsDuplicatePreserveFinancialState() throws Exception {
        try (var legacy = new Legacy()) {
            var before = legacy.financialSnapshot();
            legacy.checkout.checkout(original());
            var first = original();
            var equivalent = new EventEnvelope<>(UUID.randomUUID(), first.eventType(), first.aggregateId(),
                    first.occurredAt(), first.traceId(), first.payload());
            legacy.checkout.checkout(equivalent);
            legacy.checkout.checkout(equivalent);
            assertThat(legacy.sellers()).containsExactly(SELLER_A, SELLER_B);
            assertThat(legacy.financialSnapshot()).isEqualTo(before);
            assertThat(legacy.marker(REPAIR_CONSUMER)).isEqualTo(1);
        }
    }

    private static UUID id(int suffix) {
        return UUID.fromString("68220000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private static EventEnvelope<CheckoutPaymentRequested> original() {
        var manifest = new CheckoutPaymentRequested(GROUP, BUYER, "VND", new BigDecimal("200000.00"), DUE, CREATED, 2,
                List.of(new CheckoutPaymentRequested.SellerOrder(ORDER_A, SELLER_A, new BigDecimal("125000.00"), DUE, 0L),
                        new CheckoutPaymentRequested.SellerOrder(ORDER_B, SELLER_B, new BigDecimal("75000.00"), DUE, 0L)));
        return new EventEnvelope<>(EVENT, "CheckoutPaymentRequested", GROUP.toString(), CREATED, "legacy-upgrade", manifest);
    }

    private static EventEnvelope<OrderCompleted> completed(UUID seller) {
        var time = CREATED.plusSeconds(172800);
        return new EventEnvelope<>(UUID.randomUUID(), "OrderCompleted", ORDER_A.toString(), time, "legacy-completion",
                new OrderCompleted(ORDER_A, GROUP, BUYER, seller, "STANDARD", null, 4, time, "NX-LEGACY-V4"));
    }

    // Frozen V4 canonical format: seller ownership is independently retained in the original manifest digest.
    private static String originalV4Hash() throws Exception {
        String canonical = GROUP + "|" + BUYER + "|VND|200000.00|" + DUE
                + "|" + ORDER_A + "|" + SELLER_A + "|125000.00|" + DUE + "|0"
                + "|" + ORDER_B + "|" + SELLER_B + "|75000.00|" + DUE + "|0";
        return "checkout:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class Legacy implements AutoCloseable {
        final AnnotationConfigApplicationContext context;
        final JdbcTemplate jdbc;
        final PaymentSessionEventHandler checkout;
        final PayoutEligibilityService payouts;
        final PostgreSQLContainer container;

        Legacy() throws Exception {
            String base = System.getenv("PAYMENT_TEST_DB_URL");
            String user = System.getenv().getOrDefault("PAYMENT_TEST_DB_USERNAME", "postgres");
            String password = System.getenv().getOrDefault("PAYMENT_TEST_DB_PASSWORD", "");
            if ("true".equals(System.getenv("PAYMENT_DB_TEST"))) container = null;
            else {
                container = new PostgreSQLContainer("postgres:18-alpine");
                container.start();
                base = container.getJdbcUrl(); user = container.getUsername(); password = container.getPassword();
            }
            String database = "seller_upgrade_" + UUID.randomUUID().toString().replace("-", "");
            try (var connection = DriverManager.getConnection(base, user, password); var statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE " + database);
            }
            DataSource source = new DriverManagerDataSource(base.substring(0, base.lastIndexOf('/') + 1) + database, user, password);
            var flyway = Flyway.configure().dataSource(source).target("4").load();
            flyway.migrate();
            jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='payment_instructions' AND column_name='seller_id'", Long.class)).isZero();
            jdbc.update("""
                    INSERT INTO payment_sessions(id,order_group_id,buyer_id,total_amount,currency,memo_code,due_at,status,source_event_id,idempotency_key,paid_at)
                    VALUES (?,?,?,200000,'VND','NXLEGACYV4',?,'PAID',?,?,?)
                    """, SESSION, GROUP, BUYER, DUE.atOffset(ZoneOffset.UTC), EVENT, originalV4Hash(), PAID.atOffset(ZoneOffset.UTC));
            jdbc.update("""
                    INSERT INTO payment_transactions(id,session_id,provider,txn_reference,amount,currency,status,type,idempotency_key,received_at)
                    VALUES (?,?,'SEPAY','legacy-bank-reference',200000,'VND','SUCCESS','NORMAL','legacy-payment',?)
                    """, PAYMENT, SESSION, PAID.atOffset(ZoneOffset.UTC));
            for (var order : original().payload().orders()) jdbc.update("""
                    INSERT INTO payment_instructions(order_id,session_id,funded_transaction_id,buyer_id,amount,currency,due_at,status,paid_at,last_order_version,last_order_event_at)
                    VALUES (?,?,?,?,?,'VND',?,'PAID',?,2,?)
                    """, order.orderId(), SESSION, PAYMENT, BUYER, order.amount(), DUE.atOffset(ZoneOffset.UTC),
                    PAID.atOffset(ZoneOffset.UTC), PAID.atOffset(ZoneOffset.UTC));
            jdbc.update("INSERT INTO processed_events(event_id,consumer) VALUES (?,?)", EVENT, PaymentSessionEventHandler.CHECKOUT_CONSUMER);
            Flyway.configure().dataSource(source).load().migrate();

            context = new AnnotationConfigApplicationContext();
            context.registerBean(DataSource.class, () -> source);
            context.register(Config.class); context.refresh();
            checkout = context.getBean(PaymentSessionEventHandler.class);
            payouts = context.getBean(PayoutEligibilityService.class);
        }

        List<String> financialSnapshot() {
            var rows = new java.util.ArrayList<String>();
            rows.addAll(jdbc.queryForList("SELECT row_to_json(s)::text FROM payment_sessions s ORDER BY id", String.class));
            rows.addAll(jdbc.queryForList("SELECT (to_jsonb(i)-'seller_id'-'version'-'updated_at')::text FROM payment_instructions i ORDER BY order_id", String.class));
            rows.addAll(jdbc.queryForList("SELECT row_to_json(t)::text FROM payment_transactions t ORDER BY id", String.class));
            rows.add("refunds=" + jdbc.queryForObject("SELECT count(*) FROM refunds", Long.class));
            rows.add("outbox=" + jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class));
            return rows;
        }
        List<UUID> sellers() { return jdbc.queryForList("SELECT seller_id FROM payment_instructions ORDER BY order_id", UUID.class); }
        long marker(String consumer) { return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id=? AND consumer=?", Long.class, EVENT, consumer); }
        long eligible() { return jdbc.queryForObject("SELECT count(*) FROM payout_eligibility WHERE order_id=?", Long.class, ORDER_A); }
        @Override public void close() { context.close(); if (container != null) container.stop(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = PaymentSessionRepository.class)
    @Import({PaymentSessionEventHandler.class, PayoutEligibilityService.class})
    static class Config {
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean Validator validator() { return Validation.buildDefaultValidatorFactory().getValidator(); }
        // Recovery must never call cancellation/refund workflows.
        @Bean RefundService refunds() { return mock(RefundService.class); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source); factory.setPackagesToScan(PaymentInstruction.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate")); return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean IdempotentConsumer consumer(EntityManagerFactory factory, PlatformTransactionManager transactions) {
            return new IdempotentConsumer(SharedEntityManagerCreator.createSharedEntityManager(factory), new TransactionTemplate(transactions));
        }
    }
}
