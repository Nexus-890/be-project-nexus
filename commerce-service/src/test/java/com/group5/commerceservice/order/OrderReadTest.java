package com.group5.commerceservice.order;

import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.cart.entity.Cart;
import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest;
import com.group5.commerceservice.order.dto.response.OrderItemResponse;
import com.group5.commerceservice.order.dto.response.OrderResponse;
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
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(OrderReadTest.JpaConfig.class)
class OrderReadTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    private static final AtomicInteger ORDER_NUMBERS = new AtomicInteger();
    private static final CurrentUser ADMIN = new CurrentUser(UUID.randomUUID(), Set.of("ADMIN"),
            Set.of("ORDER.VIEW", "ORDER.LIST"));

    static {
        POSTGRES.start();
    }

    @Autowired private OrderService orderService;
    @Autowired private CatalogClient catalogClient;
    @Autowired private InventoryClient inventoryClient;
    @Autowired private DataSource dataSource;

    @Autowired private OrderShippingService shipping;

    @org.junit.jupiter.api.BeforeEach
    void restoreShippingFixture() {
        // Context dùng chung với test #138: mỗi test đọc đơn tự khôi phục fixture phí bằng 0.
        Mockito.reset(shipping);
        when(shipping.quote(any(), any())).thenReturn(new OrderShippingService.Quote(BigDecimal.ZERO,
                new OrderShippingService.Parcel(1200, 30, 20, 15), "FAKE", "STANDARD",
                java.time.OffsetDateTime.parse("2031-02-04T04:05:06Z")));
    }

    @Test
    void theOwnerGetsEveryItemWithItsSnapshotPrice() {
        CurrentUser buyer = buyer();
        UUID orderId = placeOrder(buyer, new BigDecimal("150000.00"), new BigDecimal("89000.00"));

        OrderResponse order = orderService.getOrder(buyer, orderId);

        assertThat(order.orderId()).isEqualTo(orderId);
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.items()).hasSize(2);
        assertThat(order.items()).extracting(OrderItemResponse::unitPrice)
                .containsExactlyInAnyOrder(new BigDecimal("150000.00"), new BigDecimal("89000.00"));
        assertThat(order.items()).extracting(OrderItemResponse::totalPrice)
                .containsExactlyInAnyOrder(new BigDecimal("300000.00"), new BigDecimal("178000.00"));
        assertThat(order.totalAmount()).isEqualByComparingTo("478000.00");
    }

    @Test
    void anotherUsersOrderIs403AndAnUnknownIdIs404() {
        UUID orderId = placeOrder(buyer(), new BigDecimal("150000.00"));

        assertThatThrownBy(() -> orderService.getOrder(buyer(), orderId))
                .isInstanceOf(DomainException.class)
                .extracting("code", "errorCode").containsExactly(403, "COMMERCE_FORBIDDEN");
        assertThatThrownBy(() -> orderService.getOrder(buyer(), UUID.randomUUID()))
                .isInstanceOf(DomainException.class)
                .extracting("code", "errorCode").containsExactly(404, "COMMERCE_ORDER_NOT_FOUND");
    }

    @Test
    void anAdminReadsTheSameOrder() {
        UUID orderId = placeOrder(buyer(), new BigDecimal("150000.00"));

        assertThat(orderService.getOrder(ADMIN, orderId).orderId()).isEqualTo(orderId);
    }

    @Test
    void theListNeverReturnsAnotherUsersOrderAndIsNewestFirst() {
        CurrentUser buyer = buyer();
        UUID older = insertOrder(buyer.id(), OrderStatus.PENDING, 30);
        UUID newer = insertOrder(buyer.id(), OrderStatus.PAID, 10);
        UUID foreign = insertOrder(UUID.randomUUID(), OrderStatus.PENDING, 5);

        Page<OrderResponse> page = orderService.listOwn(buyer, null, 0, 10);

        assertThat(page.getContent()).extracting(OrderResponse::orderId).containsExactly(newer, older);
        assertThat(page.getContent()).extracting(OrderResponse::orderId).doesNotContain(foreign);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void anAdminAlsoListsOnlyTheirOwnOrders() {
        UUID foreign = insertOrder(UUID.randomUUID(), OrderStatus.PENDING, 5);

        assertThat(orderService.listOwn(ADMIN, null, 0, 100).getContent())
                .extracting(OrderResponse::orderId).doesNotContain(foreign);
    }

    @Test
    void statusPendingReturnsOnlyPendingOrders() {
        CurrentUser buyer = buyer();
        UUID first = insertOrder(buyer.id(), OrderStatus.PENDING, 40);
        insertOrder(buyer.id(), OrderStatus.PAID, 30);
        insertOrder(buyer.id(), OrderStatus.CANCELED, 20);
        UUID second = insertOrder(buyer.id(), OrderStatus.PENDING, 10);

        Page<OrderResponse> page = orderService.listOwn(buyer, OrderStatus.PENDING, 0, 10);

        assertThat(page.getContent()).extracting(OrderResponse::orderId).containsExactly(second, first);
        assertThat(page.getContent()).extracting(OrderResponse::status).containsOnly(OrderStatus.PENDING);
    }

    @Test
    void pagesSplitTheListAndRejectASizeOutsideOneToHundred() {
        CurrentUser buyer = buyer();
        UUID oldest = insertOrder(buyer.id(), OrderStatus.PENDING, 30);
        UUID middle = insertOrder(buyer.id(), OrderStatus.PENDING, 20);
        UUID newest = insertOrder(buyer.id(), OrderStatus.PENDING, 10);

        Page<OrderResponse> first = orderService.listOwn(buyer, null, 0, 2);
        Page<OrderResponse> second = orderService.listOwn(buyer, null, 1, 2);

        assertThat(first.getContent()).extracting(OrderResponse::orderId).containsExactly(newest, middle);
        assertThat(second.getContent()).extracting(OrderResponse::orderId).containsExactly(oldest);
        assertThat(first.getTotalPages()).isEqualTo(2);
        for (int[] invalid : new int[][]{{0, 0}, {0, 101}, {-1, 10}}) {
            assertThatThrownBy(() -> orderService.listOwn(buyer, null, invalid[0], invalid[1]))
                    .isInstanceOf(DomainException.class)
                    .extracting("code", "errorCode").containsExactly(400, "INVALID_PARAMETER");
        }
    }

    private static CurrentUser buyer() {
        return new CurrentUser(UUID.randomUUID(), Set.of("BUYER"),
                Set.of("CHECKOUT.START", "ORDER.CREATE", "ORDER.VIEW", "ORDER.LIST"));
    }

    private UUID placeOrder(CurrentUser buyer, BigDecimal... prices) {
        UUID sellerId = UUID.randomUUID();
        UUID cartId = jdbc().queryForObject("INSERT INTO carts (user_id) VALUES (?) RETURNING id", UUID.class, buyer.id());
        for (BigDecimal price : prices) {
            UUID productId = UUID.randomUUID();
            UUID skuId = UUID.randomUUID();
            jdbc().update("INSERT INTO cart_items (cart_id, product_id, sku_id, quantity, price) VALUES (?, ?, ?, 2, ?)",
                    cartId, productId, skuId, price);
            CatalogSkuResponse sku = new CatalogSkuResponse();
            sku.setId(skuId);
            sku.setProductId(productId);
            sku.setSkuCode("SKU-" + skuId.toString().substring(0, 8));
            sku.setPrice(price);
            sku.setStatus("ACTIVE");
            when(catalogClient.getSkuById(skuId)).thenReturn(ApiResponse.of(sku));
            when(catalogClient.getProductById(productId)).thenReturn(ApiResponse.of(new CatalogClient.ProductResponse(
                    productId, sellerId, "Product " + price, "ACTIVE", List.of())));
        }
        when(inventoryClient.reserve(any(), any())).thenReturn(new InventoryClient.Reservation(
                List.of(UUID.randomUUID()), UUID.randomUUID(), java.time.OffsetDateTime.parse("2031-02-03T04:05:06.123456Z")));

        CreateOrderRequest request = new CreateOrderRequest(new CreateOrderRequest.ShippingAddress(
                "Buyer", "0900000000", "1 Main Street", "1003646", "1000001", "VN"), "SEPAY");
        return orderService.create(buyer, UUID.randomUUID().toString(), request)
                .response().orders().getFirst().orderId();
    }

    private UUID insertOrder(UUID buyerId, OrderStatus status, int minutesAgo) {
        UUID groupId = jdbc().queryForObject("""
                INSERT INTO order_groups (buyer_id, idempotency_key, total_amount, due_at)
                VALUES (?, ?, 100.00, now() + interval '15 minutes') RETURNING id
                """, UUID.class, buyerId, UUID.randomUUID().toString());
        return jdbc().queryForObject("""
                INSERT INTO orders (order_group_id, user_id, seller_id, order_number, order_type, status,
                                    subtotal, total_amount, idempotency_key, created_at)
                VALUES (?, ?, ?, ?, 'STANDARD', CAST(? AS order_status), 100.00, 100.00, ?,
                        now() - make_interval(mins => ?)) RETURNING id
                """, UUID.class, groupId, buyerId, UUID.randomUUID(),
                "NX-READ-%08d".formatted(ORDER_NUMBERS.incrementAndGet()), status.name(),
                UUID.randomUUID().toString(), minutesAgo);
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
            DataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=commerce_reads",
                    POSTGRES.getUsername(), POSTGRES.getPassword());
            Flyway.configure().dataSource(source).schemas("commerce_reads")
                    .defaultSchema("commerce_reads").load().migrate();
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
