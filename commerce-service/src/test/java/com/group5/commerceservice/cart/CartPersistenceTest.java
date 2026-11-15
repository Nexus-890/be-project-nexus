package com.group5.commerceservice.cart;

import com.group5.commerceservice.cart.entity.Cart;
import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.cart.service.CartPricingService;
import com.group5.commerceservice.shared.config.CheckoutProperties;
import com.group5.common_core.model.ApiResponse;
import com.group5.commerceservice.cart.repository.CartItemRepository;
import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.shared.config.CartProperties;
import com.group5.common_security.CurrentUser;
import com.group5.common_security.CurrentUserAuditor;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Production migrations and Hibernate validate against real PostgreSQL 18. */
@SpringJUnitConfig(CartPersistenceTest.JpaConfig.class)
class CartPersistenceTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired private CartRepository carts;
    @Autowired private CartItemRepository items;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private CartPricingService pricing;
    @Autowired private CatalogClient catalog;

    @Test
    void pricingValidationAndPreviewLeaveEveryDatabaseRowAndAuditFieldUnchanged() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID userId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        jdbc.update("INSERT INTO carts (id, user_id) VALUES (?, ?)", cartId, userId);
        jdbc.update("""
                INSERT INTO cart_items (id, cart_id, product_id, sku_id, quantity, price)
                VALUES (?, ?, ?, ?, 2, 150000)
                """, itemId, cartId, productId, skuId);
        CatalogSkuResponse sku = new CatalogSkuResponse();
        sku.setId(skuId);
        sku.setProductId(productId);
        sku.setStatus("ACTIVE");
        sku.setPrice(new BigDecimal("160000"));
        when(catalog.getSkuById(skuId)).thenReturn(ApiResponse.of(sku));
        when(catalog.getProductById(productId)).thenReturn(ApiResponse.of(
                new CatalogClient.ProductResponse(productId, UUID.randomUUID(), "Product", "ACTIVE", List.of())));
        Map<String, String> before = databaseSnapshot(jdbc);
        CurrentUser user = new CurrentUser(userId, Set.of("BUYER"), Set.of());
        assertThat(pricing.pricing(user, null, null).subtotal()).isEqualByComparingTo("320000");
        assertThat(pricing.validate(user).valid()).isFalse();
        assertThat(pricing.preview(user, "D01").items()).hasSize(1);

        CurrentUser noCart = new CurrentUser(UUID.randomUUID(), Set.of(), Set.of());
        assertThat(pricing.pricing(noCart, null, null).subtotal()).isZero();
        assertThat(pricing.validate(noCart).valid()).isTrue();
        assertThatThrownBy(() -> pricing.preview(noCart, null))
                .isInstanceOf(com.group5.common_core.exception.DomainException.class)
                .hasMessage("Cart is empty");
        assertThat(databaseSnapshot(jdbc)).isEqualTo(before);
    }

    private static Map<String, String> databaseSnapshot(JdbcTemplate jdbc) {
        Map<String, String> snapshot = new java.util.TreeMap<>();
        jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = current_schema()", String.class)
                .forEach(table -> snapshot.put(table, jdbc.queryForObject(
                        "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text), '[]'::jsonb)::text FROM \""
                                + table.replace("\"", "\"\"") + "\" t", String.class)));
        return snapshot;
    }

    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void namedEnumRoundTripsAndAuditingKeepsTheCreator() {
        UUID creator = UUID.randomUUID();
        authenticate(creator);
        Cart saved = new TransactionTemplate(transactionManager).execute(status -> {
            Cart cart = new Cart();
            cart.setUserId(creator);
            return carts.saveAndFlush(cart);
        });
        assertThat(saved.getStatus()).isEqualTo(CartStatus.ACTIVE);
        assertThat(saved.getCreatedBy()).isEqualTo(creator);
        assertThat(saved.getUpdatedBy()).isEqualTo(creator);

        UUID modifier = UUID.randomUUID();
        authenticate(modifier);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Cart cart = carts.findByUserIdAndStatus(creator, CartStatus.ACTIVE).orElseThrow();
            cart.setStatus(CartStatus.CHECKED_OUT);
            carts.flush();
        });
        Cart changed = carts.findById(saved.getId()).orElseThrow();
        assertThat(changed.getStatus()).isEqualTo(CartStatus.CHECKED_OUT);
        assertThat(changed.getCreatedBy()).isEqualTo(creator);
        assertThat(changed.getUpdatedBy()).isEqualTo(modifier);

        SecurityContextHolder.clearContext();
        Cart system = new TransactionTemplate(transactionManager).execute(status -> {
            Cart cart = new Cart();
            cart.setGuestToken(UUID.randomUUID().toString());
            return carts.saveAndFlush(cart);
        });
        assertThat(system.getCreatedBy()).isEqualTo(CurrentUserAuditor.SYSTEM_ID);
        assertThat(system.getUpdatedBy()).isEqualTo(CurrentUserAuditor.SYSTEM_ID);
    }

    @Test
    void nativeCartCreationTouchAndItemUpsertRecordActors() {
        UUID creator = UUID.randomUUID();
        UUID modifier = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            carts.createActiveCartIfAbsent(cartId, creator, creator);
            carts.createActiveCartIfAbsent(UUID.randomUUID(), creator, modifier);
            assertThat(carts.findByUserIdAndStatus(creator, CartStatus.ACTIVE)).isPresent();
            assertThat(carts.touchActiveCart(cartId, modifier)).isEqualTo(1);
            items.addOrIncreaseQuantity(UUID.randomUUID(), cartId, UUID.randomUUID(), skuId,
                    2, new BigDecimal("10.00"), creator);
            items.addOrIncreaseQuantity(UUID.randomUUID(), cartId, UUID.randomUUID(), skuId,
                    3, new BigDecimal("12.00"), modifier);
        });
        Cart cart = carts.findById(cartId).orElseThrow();
        assertThat(cart.getCreatedBy()).isEqualTo(creator);
        assertThat(cart.getUpdatedBy()).isEqualTo(modifier);
        var item = items.findByCart_Id(cartId).getFirst();
        assertThat(item.getQuantity()).isEqualTo(5);
        assertThat(item.getUnitPrice()).isEqualByComparingTo("12.00");
        assertThat(item.getCreatedBy()).isEqualTo(creator);
        assertThat(item.getUpdatedBy()).isEqualTo(modifier);
    }

    @Test
    void expiryJobRecordsSystemActorAndOnlyExpiresInactiveActiveCarts() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID creator = UUID.randomUUID();
        UUID editor = UUID.randomUUID();
        UUID expiredId = UUID.randomUUID();
        UUID recentId = UUID.randomUUID();
        UUID checkedOutId = UUID.randomUUID();
        UUID mergedId = UUID.randomUUID();
        for (UUID id : List.of(expiredId, recentId, checkedOutId, mergedId)) {
            jdbc.update("""
                    INSERT INTO carts (id, guest_token, updated_at, created_by, updated_by)
                    VALUES (?, ?, now() - interval '8 days', ?, ?)
                    """, id, id.toString(), creator, editor);
        }
        jdbc.update("UPDATE carts SET updated_at = now() WHERE id = ?", recentId);
        jdbc.update("UPDATE carts SET status = 'CHECKED_OUT' WHERE id = ?", checkedOutId);
        jdbc.update("UPDATE carts SET status = 'MERGED' WHERE id = ?", mergedId);

        CartProperties properties = new CartProperties();
        properties.setExpirationDays(7);
        CartExpiryJob job = new CartExpiryJob(carts, properties);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> job.expireInactiveCarts());

        Cart expired = carts.findById(expiredId).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(CartStatus.EXPIRED);
        assertThat(expired.getCreatedBy()).isEqualTo(creator);
        assertThat(expired.getUpdatedBy()).isEqualTo(CurrentUserAuditor.SYSTEM_ID);
        assertThat(jdbc.queryForObject(
                "SELECT updated_at > now() - interval '1 minute' FROM carts WHERE id = ?",
                Boolean.class, expiredId)).isTrue();
        assertThat(carts.findById(recentId).orElseThrow().getStatus()).isEqualTo(CartStatus.ACTIVE);
        assertThat(carts.findById(checkedOutId).orElseThrow().getStatus()).isEqualTo(CartStatus.CHECKED_OUT);
        assertThat(carts.findById(mergedId).orElseThrow().getStatus()).isEqualTo(CartStatus.MERGED);
        for (UUID id : List.of(recentId, checkedOutId, mergedId)) {
            assertThat(carts.findById(id).orElseThrow().getUpdatedBy()).isEqualTo(editor);
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertThat(carts.expireInactiveActiveCarts(7, CurrentUserAuditor.SYSTEM_ID)).isZero());
    }

    @Test
    void freshSchemaHasNamedEnumsAndRejectsAnUnknownStatus() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND data_type = 'USER-DEFINED'
                """, Integer.class)).isEqualTo(11);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO carts (guest_token, status) VALUES ('invalid-status', 'UNKNOWN_STATUS')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("invalid input value for enum cart_status");
    }

    @Test
    void upgradePreservesExistingStatusValuesAndPromotionAuthors() {
        String schema = "commerce_upgrade";
        DataSource upgrade = source(schema);
        // Include the split's V3 when available; '?' also permits the reserved gap.
        Flyway.configure().dataSource(upgrade).schemas(schema)
                .defaultSchema(schema).target("3?").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(upgrade);
        for (CartStatus status : CartStatus.values()) {
            jdbc.update("INSERT INTO carts (guest_token, status) VALUES (?, ?)", status.name(), status.name());
        }
        UUID author = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO promo_mega_sale_events
                    (name, registration_start_at, registration_end_at, start_at, end_at, status, created_by)
                VALUES ('Sale', now(), now() + interval '1 day', now() + interval '2 days',
                        now() + interval '3 days', 'DRAFT', ?)
                """, author);
        Flyway.configure().dataSource(upgrade).schemas(schema)
                .defaultSchema(schema).load().migrate();
        assertThat(jdbc.queryForList("SELECT status::text FROM carts ORDER BY status::text", String.class))
                .containsExactly("ACTIVE", "CHECKED_OUT", "EXPIRED", "MERGED");
        assertThat(jdbc.queryForObject("SELECT status::text FROM promo_mega_sale_events", String.class))
                .isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT created_by FROM promo_mega_sale_events", UUID.class)).isEqualTo(author);
        assertThat(jdbc.queryForObject("SELECT updated_by FROM promo_mega_sale_events", UUID.class)).isEqualTo(author);
        assertThat(jdbc.queryForList("""
                SELECT version FROM flyway_schema_history
                WHERE success AND version IS NOT NULL ORDER BY installed_rank
                """, String.class)).containsSubsequence("1", "2", "3", "4", "5", "6");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                WHERE n.nspname = current_schema()
                  AND t.typname IN ('payment_transaction_status', 'payment_transaction_type')
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'payment_transactions'
                  AND column_name IN ('created_by', 'updated_by')
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT to_regclass('payment_transactions')::text", String.class)).isNull();
    }

    @Test
    void existingIndexesSupportCartLookupAndOutboxRelay() {
        DataSource explain = source("commerce_explain");
        Flyway.configure().dataSource(explain).schemas("commerce_explain")
                .defaultSchema("commerce_explain").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(explain);
        jdbc.update("INSERT INTO carts (user_id) SELECT uuidv7() FROM generate_series(1, 10000)");
        UUID userId = jdbc.queryForObject("SELECT user_id FROM carts LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO outbox (aggregate_type, aggregate_id, event_type, topic, payload, published_at)
                SELECT 'Cart', n::text, 'Changed', 'cart-events', '{}'::jsonb,
                       CASE WHEN n % 100 = 0 THEN NULL ELSE now() END
                FROM generate_series(1, 10000) AS n
                """);
        jdbc.execute("ANALYZE carts");
        jdbc.execute("ANALYZE outbox");
        String cartPlan = String.join("\n", jdbc.queryForList("""
                EXPLAIN SELECT * FROM carts WHERE user_id = ? AND status = 'ACTIVE'
                """, String.class, userId));
        String relayPlan = String.join("\n", jdbc.queryForList("""
                EXPLAIN SELECT * FROM outbox WHERE published_at IS NULL
                ORDER BY occurred_at LIMIT 20 FOR UPDATE SKIP LOCKED
                """, String.class));
        assertThat(cartPlan).contains("Index Scan", "idx_carts_one_active");
        assertThat(relayPlan).contains("Index Scan", "idx_outbox_unpublished");
    }

    private static void authenticate(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new CurrentUser(userId, Set.of(), Set.of()), "", List.of()));
    }

    private static DataSource source(String schema) {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = CartRepository.class)
    @EnableJpaAuditing(auditorAwareRef = "auditor")
    static class JpaConfig {
        @Bean
        com.group5.commerceservice.shared.config.CartProperties cartProperties() {
            var properties = new com.group5.commerceservice.shared.config.CartProperties();
            properties.setAllowGuestCart(true);
            properties.setGuestTokenSecret(java.util.Base64.getEncoder().encodeToString(new byte[32]));
            return properties;
        }

        @Bean
        com.group5.commerceservice.cart.service.GuestCartTokenService guestTokens(
                com.group5.commerceservice.shared.config.CartProperties properties) {
            return new com.group5.commerceservice.cart.service.GuestCartTokenService(properties);
        }

        @Bean
        com.group5.commerceservice.cart.service.CartService cartService(CartRepository carts,
                CartItemRepository items, CatalogClient catalog,
                com.group5.commerceservice.shared.config.CartProperties properties,
                com.group5.commerceservice.cart.service.GuestCartTokenService tokens, CartPricingService pricing) {
            return new com.group5.commerceservice.cart.service.CartService(carts, items, catalog, properties, tokens, pricing);
        }
        @Bean
        CatalogClient catalogClient() { return mock(CatalogClient.class); }

        @Bean
        CartPricingService pricing(CartRepository carts, CartItemRepository items, CatalogClient catalog) {
            return new CartPricingService(carts, items, catalog, new CheckoutProperties(),
                    mock(com.group5.commerceservice.order.service.PricingService.class),
                    mock(com.group5.commerceservice.promotion.VoucherCheckoutService.class));
        }

        @Bean
        DataSource dataSource() {
            DataSource source = source("commerce_mapping");
            Flyway.configure().dataSource(source).schemas("commerce_mapping")
                    .defaultSchema("commerce_mapping").load().migrate();
            return source;
        }

        @Bean
        AuditorAware<UUID> auditor() {
            return new CurrentUserAuditor();
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(Cart.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
