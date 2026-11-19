package com.group5.catalogservice.product;

import com.group5.catalogservice.category.CategoryRepository;
import com.group5.catalogservice.product.dto.request.ProductRequest;
import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.entity.Skus;
import com.group5.catalogservice.product.enums.ProductStatus;
import com.group5.catalogservice.product.enums.SkuStatus;
import com.group5.catalogservice.product.repository.ProductRepository;
import com.group5.catalogservice.product.repository.SkuRepository;
import com.group5.catalogservice.product.service.ProductService;
import com.group5.catalogservice.shared.config.CatalogProperties;
import com.group5.catalogservice.shared.util.ProductSlugGenerator;
import com.group5.common_security.CurrentUser;
import com.group5.common_security.CurrentUserAuditor;
import com.group5.common_security.autoconfigure.JpaAuditingAutoConfiguration;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import com.group5.catalogservice.PostgresTestDatabase;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogPersistenceTest {
    private static final UUID SELLER = UUID.fromString("68000000-0000-4000-8000-000000000002");
    private static final UUID CATEGORY = UUID.fromString("69000000-0000-4000-8000-000000000102");
    private static TimeZone originalTimeZone;
    private static final PostgresTestDatabase POSTGRES = new PostgresTestDatabase("postgres:18");

    @BeforeAll
    static void startPostgres() {
        originalTimeZone = TimeZone.getDefault();
        // PostgreSQL 18 images need a canonical timezone rather than the Windows Asia/Saigon alias.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        try {
            POSTGRES.stop();
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    private DataSource database() {
        return new DriverManagerDataSource(POSTGRES.createDatabase("catalog_"), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void migrate(DataSource dataSource) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @Test
    void freshDatabaseHasNativeEnumsAndRejectsInvalidValues() throws Exception {
        DataSource dataSource = database();
        migrate(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForList("""
                SELECT table_name, column_name, data_type, udt_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND data_type = 'USER-DEFINED'
                ORDER BY table_name, column_name
                """))
                .containsExactly(
                        Map.of("table_name", "products", "column_name", "status",
                                "data_type", "USER-DEFINED", "udt_name", "product_status"),
                        Map.of("table_name", "skus", "column_name", "status",
                                "data_type", "USER-DEFINED", "udt_name", "sku_status"));

        try (var connection = dataSource.getConnection()) {
            for (String sql : List.of("""
                    INSERT INTO products (category_id, seller_id, name, slug, status)
                    VALUES ('69000000-0000-4000-8000-000000000102',
                            '68000000-0000-4000-8000-000000000002',
                            'Invalid product', 'invalid-product', 'INVALID')
                    """, """
                    INSERT INTO skus (product_id, sku_code, price, status)
                    VALUES ('69000000-0000-4000-8000-000000000201', 'INVALID-SKU', 100, 'INVALID')
                    """)) {
                try (var statement = connection.createStatement()) {
                    assertThatThrownBy(() -> statement.execute(sql))
                            .isInstanceOfSatisfying(SQLException.class,
                                    exception -> assertThat(exception.getSQLState()).isEqualTo("22P02"));
                }
            }
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name IN ('categories', 'products', 'skus', 'product_images', 'product_stats')
                  AND column_name IN ('created_by', 'updated_by') AND udt_name = 'uuid'
                  AND is_nullable = 'NO'
                """, Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT created_by FROM products LIMIT 1", UUID.class)).isEqualTo(SELLER);

        UUID productId = jdbc.queryForObject("""
                INSERT INTO products (category_id, seller_id, name, slug)
                VALUES (?, ?, 'Default status product', 'default-status-product') RETURNING id
                """, UUID.class, CATEGORY, SELLER);
        assertThat(jdbc.queryForObject("SELECT status::text FROM products WHERE id = ?", String.class, productId))
                .isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("""
                INSERT INTO skus (product_id, sku_code, price) VALUES (?, 'DEFAULT-SKU', 100)
                RETURNING status::text
                """, String.class, productId)).isEqualTo("ACTIVE");
    }

    @Test
    void upgradeKeepsEveryExistingStatusAndSeedRow() {
        DataSource dataSource = database();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("4").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE products SET status = 'DRAFT' WHERE slug LIKE 'iphone%'");
        jdbc.update("UPDATE products SET status = 'INACTIVE' WHERE slug LIKE 'samsung%'");
        jdbc.update("UPDATE skus SET status = 'DRAFT' WHERE color = 'Red'");
        jdbc.update("UPDATE skus SET status = 'INACTIVE' WHERE color = 'Blue'");
        List<Map<String, Object>> products = jdbc.queryForList("SELECT id, status::text FROM products ORDER BY id");
        List<Map<String, Object>> skus = jdbc.queryForList("SELECT id, status::text FROM skus ORDER BY id");
        migrate(dataSource);
        assertThat(jdbc.queryForList("SELECT id, status::text FROM products ORDER BY id")).isEqualTo(products);
        assertThat(jdbc.queryForList("SELECT id, status::text FROM skus ORDER BY id")).isEqualTo(skus);
        assertThat(products).hasSize(5);
        assertThat(skus).hasSize(10);
    }

    @Test
    void shippingPackageUpgradePreservesLegacyRowsAndRejectsPartialOrInvalidMeasurements() throws Exception {
        DataSource dataSource = database();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("7").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        int before = jdbc.queryForObject("SELECT count(*) FROM skus", Integer.class);

        migrate(dataSource);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM skus WHERE shipping_weight_grams IS NULL
                  AND shipping_length_cm IS NULL AND shipping_width_cm IS NULL AND shipping_height_cm IS NULL
                """, Integer.class)).isEqualTo(before);
        UUID skuId = jdbc.queryForObject("SELECT id FROM skus ORDER BY id LIMIT 1", UUID.class);
        jdbc.update("""
                UPDATE skus SET shipping_weight_grams = 50000, shipping_length_cm = 200,
                  shipping_width_cm = 200, shipping_height_cm = 200 WHERE id = ?
                """, skuId);
        try (var connection = dataSource.getConnection()) {
            // NULL không được làm CHECK thành UNKNOWN rồi cho qua một kiện thiếu số đo.
            for (String invalid : List.of("shipping_weight_grams = NULL", "shipping_length_cm = NULL",
                    "shipping_width_cm = NULL", "shipping_height_cm = NULL", "shipping_weight_grams = 0",
                    "shipping_weight_grams = 50001", "shipping_length_cm = 0", "shipping_length_cm = 201",
                    "shipping_width_cm = 0", "shipping_width_cm = 201", "shipping_height_cm = 0",
                    "shipping_height_cm = 201")) {
                try (var statement = connection.prepareStatement("UPDATE skus SET " + invalid + " WHERE id = ?")) {
                    statement.setObject(1, skuId);
                    assertThatThrownBy(statement::executeUpdate)
                            .isInstanceOfSatisfying(SQLException.class,
                                    exception -> assertThat(exception.getSQLState()).isEqualTo("23514"));
                }
            }
        }
        jdbc.update("""
                UPDATE skus SET shipping_weight_grams = 1, shipping_length_cm = 1,
                  shipping_width_cm = 1, shipping_height_cm = 1 WHERE id = ?
                """, skuId);
        assertThat(jdbc.queryForObject("SELECT shipping_weight_grams FROM skus WHERE id = ?",
                Integer.class, skuId)).isEqualTo(1);
    }

    @Test
    void hibernateValidatesAndWritesEnumsWithCurrentActorAuditing() {
        DataSource dataSource = database();
        migrate(dataSource);
        new ApplicationContextRunner()
                .withBean(DataSource.class, () -> dataSource)
                .withUserConfiguration(JpaConfig.class)
                .withConfiguration(AutoConfigurations.of(JpaAuditingAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CurrentUserAuditor.class);
                    CurrentUser seller = new CurrentUser(SELLER, Set.of("SELLER"), Set.of("PRODUCT.CREATE"));
                    authenticate(seller);
                    try {
                        ProductRequest request = new ProductRequest();
                        request.setCategoryId(CATEGORY);
                        request.setName("Seller audited product");
                        UUID productId = context.getBean(ProductService.class).create(request, seller).getId();
                        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                        assertThat(jdbc.queryForObject("SELECT created_by FROM products WHERE id = ?",
                                UUID.class, productId)).isEqualTo(SELLER);
                        assertThat(jdbc.queryForObject("SELECT updated_by FROM products WHERE id = ?",
                                UUID.class, productId)).isEqualTo(SELLER);

                        UUID editor = UUID.randomUUID();
                        authenticate(new CurrentUser(editor, Set.of("ADMIN"), Set.of()));
                        ProductRepository products = context.getBean(ProductRepository.class);
                        SkuRepository skus = context.getBean(SkuRepository.class);
                        TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                        transaction.executeWithoutResult(status -> {
                            Product product = products.findById(productId).orElseThrow();
                            product.setStatus(ProductStatus.ACTIVE);
                            for (SkuStatus skuStatus : SkuStatus.values()) {
                                Skus sku = new Skus();
                                sku.setProduct(product);
                                sku.setSkuCode("ENUM-" + skuStatus);
                                sku.setColor(skuStatus.name());
                                sku.setPrice(BigDecimal.TEN);
                                sku.setStatus(skuStatus);
                                sku.setShippingWeightGrams(1200);
                                sku.setShippingLengthCm(30);
                                sku.setShippingWidthCm(20);
                                sku.setShippingHeightCm(15);
                                skus.saveAndFlush(sku);
                            }
                        });
                        assertThat(jdbc.queryForObject("SELECT status::text FROM products WHERE id = ?",
                                String.class, productId)).isEqualTo("ACTIVE");
                        assertThat(jdbc.queryForObject("SELECT created_by FROM products WHERE id = ?",
                                UUID.class, productId)).isEqualTo(SELLER);
                        assertThat(jdbc.queryForObject("SELECT updated_by FROM products WHERE id = ?",
                                UUID.class, productId)).isEqualTo(editor);
                        assertThat(skus.findAllByProduct_Id(productId)).extracting(Skus::getStatus)
                                .containsExactlyInAnyOrder(SkuStatus.values());
                        assertThat(skus.findAllByProduct_Id(productId)).allSatisfy(sku -> {
                            assertThat(sku.getShippingWeightGrams()).isEqualTo(1200);
                            assertThat(sku.getShippingLengthCm()).isEqualTo(30);
                            assertThat(sku.getShippingWidthCm()).isEqualTo(20);
                            assertThat(sku.getShippingHeightCm()).isEqualTo(15);
                        });

                        SecurityContextHolder.clearContext();
                        transaction.executeWithoutResult(status -> {
                            Product product = products.findById(productId).orElseThrow();
                            product.setDescription("Updated by a consumer");
                        });
                        assertThat(jdbc.queryForObject("SELECT updated_by FROM products WHERE id = ?",
                                UUID.class, productId)).isEqualTo(CurrentUserAuditor.SYSTEM_ID);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
    }

    @Test
    void categoryQueryUsesTheExistingCategoryIndex() {
        DataSource dataSource = database();
        migrate(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO products (category_id, seller_id, name, slug, status)
                SELECT '69000000-0000-4000-8000-000000000103', ?, 'Plan product', 'plan-' || n, 'ACTIVE'
                FROM generate_series(1, 10000) n
                """, SELLER);
        jdbc.execute("ANALYZE products");
        jdbc.execute("ANALYZE skus");
        String plan = String.join("\n", jdbc.queryForList("""
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT p.id, p.name, p.slug, p.status, p.category_id, p.seller_id, min(s.price)
                FROM products p LEFT JOIN skus s ON s.product_id = p.id
                WHERE p.category_id = ? AND p.status = CAST(? AS product_status) AND p.deleted_at IS NULL
                GROUP BY p.id, p.name, p.slug, p.status, p.category_id, p.seller_id
                """, String.class, CATEGORY, ProductStatus.ACTIVE.name()));
        System.out.println("Catalog products by category EXPLAIN:\n" + plan);
        assertThat(plan).contains("idx_products_category");
    }

    private static void authenticate(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "", List.of()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {ProductRepository.class, CategoryRepository.class})
    static class JpaConfig {
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.group5.catalogservice");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        ProductService productService(ProductRepository products, CategoryRepository categories) {
            return new ProductService(products, categories, new ProductSlugGenerator(), id -> false,
                    new CatalogProperties(), org.mockito.Mockito.mock(com.group5.catalogservice.reputation.SellingEligibilityClient.class));
        }
    }
}
