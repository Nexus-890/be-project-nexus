package com.group5.commerceservice.order;

import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.cart.entity.Cart;
import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.order.dto.request.ChangeOrderStatusRequest;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest;
import com.group5.commerceservice.order.dto.response.OrderStatusHistoryResponse;
import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.service.InventoryClient;
import com.group5.commerceservice.order.service.OrderService;
import com.group5.commerceservice.order.service.PricingService;
import com.group5.commerceservice.order.service.OrderShippingService;
import com.group5.commerceservice.order.service.OrderStateMachine;
import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.common_security.CurrentUserAuditor;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import com.group5.commerceservice.promotion.PromotionTestConfig;
import org.springframework.data.domain.AuditorAware;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(OrderStatusFlowTest.JpaConfig.class)
class OrderStatusFlowTest {
    private static final PostgreSQLContainer POSTGRES = new com.group5.commerceservice.PostgresTestDatabase("postgres:18-alpine");
    private static final AtomicInteger ORDER_NUMBERS = new AtomicInteger();
    private static final CurrentUser ADMIN = new CurrentUser(UUID.randomUUID(), Set.of("ADMIN"), Set.of("ORDER.VIEW"));

    static {
        POSTGRES.start();
    }

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orders;
    @Autowired private CatalogClient catalogClient;
    @Autowired private InventoryClient inventoryClient;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    @org.junit.jupiter.api.BeforeEach
    void privateCostFixture() {
        org.mockito.Mockito.doAnswer(call -> {
            CatalogClient.CostRequest request=call.getArgument(0);
            return ApiResponse.list(request.skuIds().stream().map(id -> {
                var sku=catalogClient.getSkuById(id).data();
                var product=catalogClient.getProductById(sku.getProductId()).data();
                return new CatalogClient.Cost(id,sku.getProductId(),product.sellerId(),null);
            }).toList());
        }).when(catalogClient).getCosts(any());
    }

    static Stream<Arguments> everyPair() {
        return Arrays.stream(OrderStatus.values())
                .flatMap(from -> Arrays.stream(OrderStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void everyPairFollowsTheTransitionTable(OrderStatus from, OrderStatus to) {
        UUID orderId = insertOrder(from);
        ChangeOrderStatusRequest request = new ChangeOrderStatusRequest(to, "Manual reconciliation");

        if (from.canMoveTo(to) && to != OrderStatus.COMPLETED) {
            assertThat(orderService.changeStatus(ADMIN, orderId, request).status()).isEqualTo(to);
            assertThat(statusOf(orderId)).isEqualTo(to.name());
            assertThat(jdbc().queryForList("""
                    SELECT previous_status::text || '->' || new_status::text || ' by ' || changed_by
                    FROM order_status_history WHERE order_id = ?
                    """, String.class, orderId)).containsExactly(from + "->" + to + " by " + ADMIN.id());
        } else {
            assertThatThrownBy(() -> orderService.changeStatus(ADMIN, orderId, request))
                    .isInstanceOf(DomainException.class)
                    .extracting("code").isEqualTo(409);
            assertThat(statusOf(orderId)).isEqualTo(from.name());
            assertThat(historyCount(orderId)).isZero();
        }
    }

    @Test
    void aCanceledOrderStaysCanceledWhenPaymentArrivesLate() {
        UUID orderId = insertOrder(OrderStatus.CANCELED);

        assertThatThrownBy(() -> orderService.changeStatus(ADMIN, orderId,
                new ChangeOrderStatusRequest(OrderStatus.PAID, "Late payment")))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo(409);
        assertThat(statusOf(orderId)).isEqualTo("CANCELED");
        assertThat(historyCount(orderId)).isZero();
    }

    @Test
    void aMoveFromAStaleStatusUpdatesNoRow() {
        UUID orderId = insertOrder(OrderStatus.CANCELED);

        Integer updated = new TransactionTemplate(transactionManager).execute(status ->
                orders.moveStatus(orderId, "PENDING", "PAID", ADMIN.id()));

        assertThat(updated).isZero();
        assertThat(statusOf(orderId)).isEqualTo("CANCELED");
    }

    @Test
    void onlyAnAdminChangesTheStatus() {
        UUID orderId = insertOrder(OrderStatus.PENDING);
        CurrentUser buyer = new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of("ORDER.VIEW"));

        assertThatThrownBy(() -> orderService.changeStatus(buyer, orderId,
                new ChangeOrderStatusRequest(OrderStatus.PAID, "Paid")))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo(403);
        assertThat(statusOf(orderId)).isEqualTo("PENDING");
        assertThat(historyCount(orderId)).isZero();
    }

    @Test
    void creationWritesThePendingRowAndHistoryReadsOldestFirst() {
        CurrentUser buyer = new CurrentUser(UUID.randomUUID(), Set.of("BUYER"),
                Set.of("CHECKOUT.START", "ORDER.CREATE", "ORDER.VIEW"));
        UUID orderId = placeOrder(buyer);

        orderService.changeStatus(ADMIN, orderId, new ChangeOrderStatusRequest(OrderStatus.PAID, "Paid at the bank"));
        orderService.changeStatus(ADMIN, orderId, new ChangeOrderStatusRequest(OrderStatus.SHIPPED, "Picked up"));

        List<OrderStatusHistoryResponse> history = orderService.statusHistory(buyer, orderId);
        assertThat(history).extracting(OrderStatusHistoryResponse::previousStatus)
                .containsExactly(null, OrderStatus.PENDING, OrderStatus.PAID);
        assertThat(history).extracting(OrderStatusHistoryResponse::newStatus)
                .containsExactly(OrderStatus.PENDING, OrderStatus.PAID, OrderStatus.SHIPPED);
        assertThat(history).extracting(OrderStatusHistoryResponse::changedBy)
                .containsExactly(buyer.id(), ADMIN.id(), ADMIN.id());
        assertThat(history.get(0).createdAt()).isBeforeOrEqualTo(history.get(1).createdAt());
        assertThat(history.get(1).createdAt()).isBeforeOrEqualTo(history.get(2).createdAt());
        assertThat(orderService.statusHistory(ADMIN, orderId)).hasSize(3);
    }

    @Test
    void anotherUserCannotReadTheHistory() {
        CurrentUser buyer = new CurrentUser(UUID.randomUUID(), Set.of("BUYER"),
                Set.of("CHECKOUT.START", "ORDER.CREATE", "ORDER.VIEW"));
        UUID orderId = placeOrder(buyer);
        CurrentUser stranger = new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of("ORDER.VIEW"));

        assertThatThrownBy(() -> orderService.statusHistory(stranger, orderId))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo(403);
        assertThatThrownBy(() -> orderService.statusHistory(stranger, UUID.randomUUID()))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo(404);
    }

    private UUID placeOrder(CurrentUser buyer) {
        UUID productId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID cartId = jdbc().queryForObject("INSERT INTO carts (user_id) VALUES (?) RETURNING id", UUID.class, buyer.id());
        jdbc().update("INSERT INTO cart_items (cart_id, product_id, sku_id, quantity, price) VALUES (?, ?, ?, 2, 150000.00)",
                cartId, productId, skuId);

        CatalogSkuResponse sku = new CatalogSkuResponse();
        sku.setId(skuId);
        sku.setProductId(productId);
        sku.setSkuCode("SKU-" + skuId.toString().substring(0, 8));
        sku.setPrice(new BigDecimal("150000.00"));
        sku.setStatus("ACTIVE");
        when(catalogClient.getSkuById(skuId)).thenReturn(ApiResponse.of(sku));
        when(catalogClient.getProductById(productId)).thenReturn(ApiResponse.of(new CatalogClient.ProductResponse(
                productId, UUID.randomUUID(), "Phone", "ACTIVE", List.of())));
        when(inventoryClient.reserve(any(), any())).thenReturn(new InventoryClient.Reservation(
                List.of(UUID.randomUUID()), UUID.randomUUID(), java.time.OffsetDateTime.parse("2031-02-03T04:05:06.123456Z")));

        CreateOrderRequest request = new CreateOrderRequest(new CreateOrderRequest.ShippingAddress(
                "Buyer", "0900000000", "1 Main Street", "1003646", "1000001", "VN"), "SEPAY");
        return orderService.create(buyer, UUID.randomUUID().toString(), request)
                .response().orders().getFirst().orderId();
    }

    private UUID insertOrder(OrderStatus status) {
        UUID buyerId = UUID.randomUUID();
        UUID groupId = jdbc().queryForObject("""
                INSERT INTO order_groups (buyer_id, idempotency_key, total_amount, due_at)
                VALUES (?, ?, 100.00, now() + interval '15 minutes') RETURNING id
                """, UUID.class, buyerId, UUID.randomUUID().toString());
        return jdbc().queryForObject("""
                INSERT INTO orders (order_group_id, user_id, seller_id, order_number, order_type, status,
                                    subtotal, total_amount, idempotency_key)
                VALUES (?, ?, ?, ?, 'STANDARD', CAST(? AS order_status), 100.00, 100.00, ?) RETURNING id
                """, UUID.class, groupId, buyerId, UUID.randomUUID(),
                "NX-TEST-%08d".formatted(ORDER_NUMBERS.incrementAndGet()), status.name(), UUID.randomUUID().toString());
    }

    private String statusOf(UUID orderId) {
        return jdbc().queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, orderId);
    }

    private int historyCount(UUID orderId) {
        return jdbc().queryForObject("SELECT count(*) FROM order_status_history WHERE order_id = ?", Integer.class, orderId);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {CartRepository.class, OrderRepository.class})
    @EnableJpaAuditing(auditorAwareRef = "auditor")
    @Import({PromotionTestConfig.class, OrderService.class, OrderStateMachine.class, PricingService.class})
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
            DataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=commerce_orders",
                    POSTGRES.getUsername(), POSTGRES.getPassword());
            Flyway.configure().dataSource(source).schemas("commerce_orders")
                    .defaultSchema("commerce_orders").load().migrate();
            return source;
        }

        @Bean
        AuditorAware<UUID> auditor() {
            return new CurrentUserAuditor();
        }

        @Bean
        CatalogClient catalogClient() {
            return Mockito.mock(CatalogClient.class);
        }

        @Bean
        InventoryClient inventoryClient() {
            return Mockito.mock(InventoryClient.class);
        }

        @Bean
        OrderEventPublisher orderEventPublisher() {
            return Mockito.mock(OrderEventPublisher.class);
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(Cart.class.getPackageName(), Order.class.getPackageName());
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
