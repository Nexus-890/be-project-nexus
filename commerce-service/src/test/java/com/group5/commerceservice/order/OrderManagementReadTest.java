package com.group5.commerceservice.order;

import java.math.BigDecimal;

import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.entity.Cart;
import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.order.dto.response.OrderResponse;
import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.service.InventoryClient;
import com.group5.commerceservice.order.service.OrderService;
import com.group5.commerceservice.order.service.OrderStateMachine;
import com.group5.commerceservice.order.service.PricingService;
import com.group5.commerceservice.order.service.OrderShippingService;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.common_security.CurrentUserAuditor;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
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

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(OrderManagementReadTest.JpaConfig.class)
class OrderManagementReadTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-03T10:00:00Z");
    private static final CurrentUser ADMIN = user("ADMIN");
    private static final CurrentUser SUPPORT = user("SUPPORT_STAFF");

    static {
        POSTGRES.start();
    }

    @Autowired private OrderService orderService;
    @Autowired private DataSource dataSource;
    @Autowired private EntityManagerFactory entityManagerFactory;
    private UUID buyerA;
    private UUID buyerB;
    private CurrentUser sellerA;
    private CurrentUser sellerB;
    private UUID oldest;
    private UUID middle;
    private UUID auction;
    private UUID tiedAuction;
    private UUID tiedPaid;
    private UUID sellerAsBuyer;

    @BeforeEach
    void insertOrdersAcrossBuyersSellersTypesAndTimes() {
        jdbc().execute("TRUNCATE order_groups CASCADE");
        buyerA = UUID.randomUUID();
        buyerB = UUID.randomUUID();
        sellerA = user("SELLER");
        sellerB = user("SELLER");
        oldest = order(1, buyerA, sellerA.id(), OrderStatus.PENDING, OrderType.STANDARD, AT.minusHours(2));
        middle = order(2, buyerB, sellerA.id(), OrderStatus.PAID, OrderType.STANDARD, AT.minusHours(1));
        auction = order(3, buyerA, sellerB.id(), OrderStatus.COMPLETED, OrderType.AUCTION, AT);
        tiedAuction = order(4, buyerB, sellerB.id(), OrderStatus.PENDING, OrderType.AUCTION, AT);
        tiedPaid = order(5, buyerA, sellerA.id(), OrderStatus.PAID, OrderType.STANDARD, AT);
        sellerAsBuyer = order(6, sellerA.id(), sellerB.id(), OrderStatus.PENDING, OrderType.STANDARD, AT.plusHours(1));
    }

    @Test
    void adminAndSupportListEveryOrderWithStableNewestFirstSorting() {
        for (CurrentUser manager : List.of(ADMIN, SUPPORT)) {
            Page<OrderResponse> result = managed(manager, null, null, null, null, null, null, 0, 10);

            assertIds(result, sellerAsBuyer, tiedPaid, tiedAuction, auction, middle, oldest);
            assertThat(result.getTotalElements()).isEqualTo(6);
            assertThat(result.getContent()).extracting(OrderResponse::sellerId)
                    .containsOnly(sellerA.id(), sellerB.id());
            assertThat(result.getContent()).allSatisfy(response -> assertThat(response.items()).hasSize(1));
        }
        assertThat(orderService.listOwn(ADMIN, null, 0, 10).getContent()).isEmpty();
    }

    @Test
    void managementFiltersCanBeUsedSeparatelyOrCombinedWithAnd() {
        for (CurrentUser manager : List.of(ADMIN, SUPPORT)) {
            assertIds(managed(manager, buyerA, null, null, null, null, null, 0, 10), tiedPaid, auction, oldest);
            assertIds(managed(manager, null, sellerA.id(), null, null, null, null, 0, 10), tiedPaid, middle, oldest);
            assertIds(managed(manager, null, null, OrderStatus.PAID, null, null, null, 0, 10), tiedPaid, middle);
            assertIds(managed(manager, null, null, null, OrderType.AUCTION, null, null, 0, 10), tiedAuction, auction);
            assertIds(managed(manager, buyerA, sellerA.id(), OrderStatus.PAID, OrderType.STANDARD,
                    AT.minusMinutes(1), AT.plusMinutes(1), 0, 10), tiedPaid);
            assertThat(managed(manager, buyerA, sellerA.id(), OrderStatus.COMPLETED, null,
                    null, null, 0, 10).getContent()).isEmpty();
            assertThat(managed(manager, UUID.randomUUID(), null, null, null, null, null, 0, 10).getTotalElements())
                    .isZero();
        }
    }

    @Test
    void managementResponsesIncludeThePersistedBuyerTypeAndCreationTime() {
        for (CurrentUser manager : List.of(ADMIN, SUPPORT)) {
            Page<OrderResponse> result = managed(manager, buyerA, sellerB.id(), OrderStatus.COMPLETED,
                    OrderType.AUCTION, AT, AT, 0, 10);

            assertIds(result, auction);
            OrderResponse response = result.getContent().getFirst();
            assertThat(response.userId()).isEqualTo(buyerA);
            assertThat(response.orderType()).isEqualTo(OrderType.AUCTION);
            assertThat(response.createdAt()).isEqualTo(AT);
        }
    }

    @Test
    void dateBoundsAreInclusiveAndCompareInstantsAcrossOffsets() {
        OffsetDateTime sameInstantInVietnam = OffsetDateTime.parse("2026-11-03T17:00:00+07:00");

        assertIds(managed(ADMIN, null, null, null, null, sameInstantInVietnam, sameInstantInVietnam, 0, 10),
                tiedPaid, tiedAuction, auction);
        assertIds(managed(SUPPORT, null, null, null, null, AT.minusHours(1), AT, 0, 10),
                tiedPaid, tiedAuction, auction, middle);
        assertIds(managed(ADMIN, null, null, null, null, AT, null, 0, 10),
                sellerAsBuyer, tiedPaid, tiedAuction, auction);
        assertIds(managed(ADMIN, null, null, null, null, null, AT.minusHours(1), 0, 10), middle, oldest);
    }

    @Test
    void paginationKeepsTotalsAndReturnsAnEmptyPageBeyondTheEnd() {
        Page<OrderResponse> first = managed(ADMIN, null, null, null, null, null, null, 0, 2);
        Page<OrderResponse> second = managed(ADMIN, null, null, null, null, null, null, 1, 2);
        Page<OrderResponse> last = managed(ADMIN, null, null, null, null, null, null, 2, 2);
        Page<OrderResponse> beyond = managed(ADMIN, null, null, null, null, null, null, 20, 2);

        assertIds(first, sellerAsBuyer, tiedPaid);
        assertIds(second, tiedAuction, auction);
        assertIds(last, middle, oldest);
        assertThat(first.getTotalElements()).isEqualTo(6);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getNumber()).isEqualTo(20);
        assertThat(beyond.getTotalElements()).isEqualTo(6);
        assertThat(beyond.getTotalPages()).isEqualTo(3);
        assertThat(managed(ADMIN, null, null, null, null, null, null, 0, 100).getSize()).isEqualTo(100);
    }

    @Test
    void fullManagementAndSalesPagesLoadItemsInABoundedNumberOfQueries() {
        OffsetDateTime from = AT.plusHours(2);
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 105; i++) {
            UUID id = order(10 + i, buyerA, sellerA.id(), OrderStatus.PAID, OrderType.STANDARD,
                    from.plusMinutes(i));
            jdbc().update("""
                    INSERT INTO order_items (order_id, product_id, sku_id, product_name, quantity, unit_price, total_price)
                    VALUES (?, ?, ?, ?, 2, 50.00, 100.00)
                    """, id, UUID.randomUUID(), UUID.randomUUID(), "Batch item " + id);
            expected.add(id);
        }
        Collections.reverse(expected);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        List<Supplier<Page<OrderResponse>>> firstPages = List.of(
                () -> managed(ADMIN, buyerA, sellerA.id(), OrderStatus.PAID, OrderType.STANDARD,
                        from, null, 0, 100),
                () -> orderService.listSales(sellerA, OrderStatus.PAID, from, null, 0, 100));

        for (Supplier<Page<OrderResponse>> read : firstPages) {
            statistics.clear();
            Page<OrderResponse> result = read.get();

            assertIds(result, expected.subList(0, 100).toArray(UUID[]::new));
            assertThat(result.getTotalElements()).isEqualTo(105);
            assertThat(result.getTotalPages()).isEqualTo(2);
            assertThat(result.getContent()).allSatisfy(response -> {
                assertThat(response.sellerId()).isEqualTo(sellerA.id());
                assertThat(response.items()).hasSize(2);
                assertThat(response.items()).extracting("productName")
                        .containsExactlyInAnyOrder("Management snapshot", "Batch item " + response.orderId());
            });
            assertThat(statistics.getPrepareStatementCount())
                    .as("100 orders need only the page, count and one batched items query")
                    .isLessThanOrEqualTo(3);
        }

        assertIds(managed(ADMIN, buyerA, sellerA.id(), OrderStatus.PAID, OrderType.STANDARD,
                from, null, 1, 100), expected.subList(100, 105).toArray(UUID[]::new));
        assertIds(orderService.listSales(sellerA, OrderStatus.PAID, from, null, 1, 100),
                expected.subList(100, 105).toArray(UUID[]::new));
    }

    @Test
    void salesAreScopedBySellerIdEvenWhenTheSellerAlsoBoughtAnotherSellersOrder() {
        Page<OrderResponse> salesA = orderService.listSales(sellerA, null, null, null, 0, 10);
        Page<OrderResponse> salesB = orderService.listSales(sellerB, null, null, null, 0, 10);

        assertIds(salesA, tiedPaid, middle, oldest);
        assertThat(salesA.getContent()).extracting(OrderResponse::sellerId).containsOnly(sellerA.id());
        assertIds(salesB, sellerAsBuyer, tiedAuction, auction);
        assertThat(salesB.getContent()).extracting(OrderResponse::sellerId).containsOnly(sellerB.id());
        assertIds(orderService.listOwn(sellerA, null, 0, 10), sellerAsBuyer);
        assertIds(orderService.listSales(sellerA, OrderStatus.PAID, AT.minusHours(1), AT, 0, 10), tiedPaid, middle);
        assertIds(orderService.listSales(sellerA, OrderStatus.PENDING, AT.minusHours(2), AT.minusHours(2), 0, 10), oldest);
        assertIds(orderService.listSales(sellerA, null, null, null, 1, 2), oldest);
        Page<OrderResponse> absentSeller = orderService.listSales(user("SELLER"), null, null, null, 0, 10);
        assertThat(absentSeller.getContent()).isEmpty();
        assertThat(absentSeller.getTotalElements()).isZero();
    }

    @Test
    void invalidPaginationAndReversedDateRangesUseTheCommerceFilterError() {
        for (int[] invalid : new int[][]{{-1, 10}, {0, 0}, {0, 101}}) {
            assertFilterError(() -> managed(ADMIN, null, null, null, null, null, null, invalid[0], invalid[1]));
            assertFilterError(() -> orderService.listSales(sellerA, null, null, null, invalid[0], invalid[1]));
        }
        assertFilterError(() -> managed(SUPPORT, null, null, null, null, AT.plusSeconds(1), AT, 0, 10));
        assertFilterError(() -> orderService.listSales(sellerA, null, AT.plusSeconds(1), AT, 0, 10));
    }

    @Test
    void serviceRejectsBuyerAndWrongRolesEvenWithOrderListPrivilege() {
        for (CurrentUser caller : List.of(user("BUYER"), sellerA)) {
            assertForbidden(() -> managed(caller, null, null, null, null, null, null, 0, 10));
        }
        for (CurrentUser caller : List.of(user("BUYER"), ADMIN, SUPPORT)) {
            assertForbidden(() -> orderService.listSales(caller, null, null, null, 0, 10));
        }
        CurrentUser adminWithoutPrivilege = new CurrentUser(UUID.randomUUID(), Set.of("ADMIN"), Set.of());
        CurrentUser sellerWithoutPrivilege = new CurrentUser(UUID.randomUUID(), Set.of("SELLER"), Set.of());
        assertForbidden(() -> managed(adminWithoutPrivilege, null, null, null, null, null, null, 0, 10));
        assertForbidden(() -> orderService.listSales(sellerWithoutPrivilege, null, null, null, 0, 10));
    }

    @Test
    void serviceRejectsMissingUserBeforeQuerying() {
        assertThatThrownBy(() -> managed(null, null, null, null, null, null, null, 0, 10))
                .isInstanceOf(DomainException.class).extracting("code", "errorCode")
                .containsExactly(401, "COMMERCE_UNAUTHENTICATED");
        assertThatThrownBy(() -> orderService.listSales(null, null, null, null, 0, 10))
                .isInstanceOf(DomainException.class).extracting("code", "errorCode")
                .containsExactly(401, "COMMERCE_UNAUTHENTICATED");
    }

    private Page<OrderResponse> managed(CurrentUser caller, UUID userId, UUID sellerId, OrderStatus status,
                                        OrderType type, OffsetDateTime from, OffsetDateTime to, int page, int size) {
        return orderService.listManaged(caller, userId, sellerId, status, type, from, to, page, size);
    }

    private static void assertIds(Page<OrderResponse> page, UUID... expected) {
        assertThat(page.getContent()).extracting(OrderResponse::orderId).containsExactly(expected);
    }

    private static void assertFilterError(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(DomainException.class).extracting("code", "errorCode")
                .containsExactly(400, "COMMERCE_INVALID_ORDER_FILTER");
    }

    private static void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(DomainException.class).extracting("code", "errorCode")
                .containsExactly(403, "COMMERCE_FORBIDDEN");
    }

    private static CurrentUser user(String role) {
        return new CurrentUser(UUID.randomUUID(), Set.of(role), Set.of("ORDER.LIST"));
    }

    private UUID order(int number, UUID buyerId, UUID sellerId, OrderStatus status, OrderType type,
                       OffsetDateTime createdAt) {
        UUID groupId = jdbc().queryForObject("""
                INSERT INTO order_groups (buyer_id, idempotency_key, total_amount, due_at)
                VALUES (?, ?, 100.00, ?) RETURNING id
                """, UUID.class, buyerId, UUID.randomUUID().toString(), createdAt.plusMinutes(15));
        UUID id = jdbc().queryForObject("""
                INSERT INTO orders (order_group_id, user_id, seller_id, order_number, order_type, reference_id,
                                    status, subtotal, total_amount, idempotency_key, payment_due_at, created_at)
                VALUES (?, ?, ?, ?, CAST(? AS order_type), ?, CAST(? AS order_status), 100.00, 100.00, ?, ?, ?)
                RETURNING id
                """, UUID.class, groupId, buyerId, sellerId, "NX-MANAGE-%04d".formatted(number), type.name(),
                type == OrderType.AUCTION ? UUID.randomUUID() : null, status.name(), UUID.randomUUID().toString(),
                createdAt.plusMinutes(15), createdAt);
        jdbc().update("""
                INSERT INTO order_items (order_id, product_id, sku_id, product_name, quantity, unit_price, total_price)
                VALUES (?, ?, ?, 'Management snapshot', 1, 100.00, 100.00)
                """, id, UUID.randomUUID(), UUID.randomUUID());
        return id;
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {CartRepository.class, OrderRepository.class})
    @EnableJpaAuditing(auditorAwareRef = "auditor")
    @Import({OrderService.class, OrderStateMachine.class, PricingService.class})
    static class JpaConfig {
        // Fixture báo giá tách riêng khỏi transport Shipping; các test này kiểm nghiệp vụ Order hiện hữu.
        @Bean
        OrderShippingService shipping() {
            OrderShippingService shipping = Mockito.mock(OrderShippingService.class);
            Mockito.when(shipping.quote(Mockito.any(), Mockito.any())).thenReturn(new OrderShippingService.Quote(
                    BigDecimal.ZERO, new OrderShippingService.Parcel(1200, 30, 20, 15), "FAKE", "STANDARD",
                    java.time.OffsetDateTime.parse("2031-02-04T04:05:06Z")));
            return shipping;
        }
        @Bean
        DataSource dataSource() {
            DataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=commerce_management_reads",
                    POSTGRES.getUsername(), POSTGRES.getPassword());
            Flyway.configure().dataSource(source).schemas("commerce_management_reads")
                    .defaultSchema("commerce_management_reads").load().migrate();
            return source;
        }

        @Bean AuditorAware<UUID> auditor() { return new CurrentUserAuditor(); }
        @Bean CatalogClient catalogClient() { return Mockito.mock(CatalogClient.class); }
        @Bean InventoryClient inventoryClient() { return Mockito.mock(InventoryClient.class); }
        @Bean OrderEventPublisher orderEventPublisher() { return Mockito.mock(OrderEventPublisher.class); }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(Cart.class.getPackageName(), Order.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate",
                    "hibernate.generate_statistics", "true",
                    "hibernate.query.fail_on_pagination_over_collection_fetch", "true"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
