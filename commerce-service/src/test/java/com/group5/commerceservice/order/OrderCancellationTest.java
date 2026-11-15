package com.group5.commerceservice.order;

import java.math.BigDecimal;

import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.entity.Cart;
import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.order.dto.request.CancelOrderRequest;
import com.group5.commerceservice.order.dto.response.OrderGroupResponse;
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
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.group5.common_security.CurrentUserAuditor;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(OrderCancellationTest.JpaConfig.class)
class OrderCancellationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    private static final AtomicInteger ORDER_NUMBERS = new AtomicInteger();
    private static final CancelOrderRequest CHANGED_MIND = new CancelOrderRequest("Changed my mind");

    static {
        POSTGRES.start();
    }

    @Autowired private OrderService orderService;
    @Autowired private DataSource dataSource;

    @Test
    void theBuyerCancelsTheirPendingOrder() {
        CurrentUser buyer = buyer();
        UUID groupId = insertGroup(buyer.id(), "PENDING");
        UUID orderId = insertOrder(groupId, buyer.id(), OrderStatus.PENDING, "150000.00");
        insertItem(orderId, 1, "150000.00");

        OrderGroupResponse group = orderService.cancel(buyer, orderId, CHANGED_MIND);

        assertThat(group.status()).isEqualTo("CANCELED");
        assertThat(group.orders()).extracting(OrderResponse::status).containsExactly(OrderStatus.CANCELED);
        assertThat(cartLines(buyer.id())).isEmpty();
        assertThat(status(orderId)).isEqualTo("CANCELED");
        assertThat(historyRows(orderId)).containsExactly("PENDING -> CANCELED: Changed my mind");
        List<JsonNode> cancelled = events("OrderCancelled", orderId);
        assertThat(cancelled).hasSize(1);
        JsonNode payload = cancelled.getFirst().get("payload");
        assertThat(payload.get("orderGroupId").asString()).isEqualTo(groupId.toString());
        assertThat(payload.get("buyerId").asString()).isEqualTo(buyer.id().toString());
        assertThat(payload.get("orderType").asString()).isEqualTo("STANDARD");
        assertThat(payload.has("referenceId") && payload.get("referenceId").isNull()).isTrue();
        assertThat(payload.get("paidAmount").asString()).isEqualTo("0.00");
        assertThat(payload.get("currency").asString()).isEqualTo("VND");
        assertThat(payload.get("orderVersion").asLong()).isEqualTo(1);
        assertThat(payload.get("actorId").asString()).isEqualTo(buyer.id().toString());
        assertThat(payload.get("cancelledAt").asString()).endsWith("Z");
        assertThat(events("OrderGroupCancelled", groupId)).hasSize(1);
    }

    @Test
    void cancellingOnePendingOrderOfATwoShopGroupCancelsTheWholeGroup() {
        CurrentUser buyer = buyer();
        UUID groupId = insertGroup(buyer.id(), "PENDING");
        UUID first = insertOrder(groupId, buyer.id(), OrderStatus.PENDING, "200000.00");
        UUID second = insertOrder(groupId, buyer.id(), OrderStatus.PENDING, "150000.00");

        OrderGroupResponse group = orderService.cancel(buyer, first, CHANGED_MIND);

        assertThat(group.status()).isEqualTo("CANCELED");
        assertThat(group.orders()).extracting(OrderResponse::status)
                .containsExactly(OrderStatus.CANCELED, OrderStatus.CANCELED);
        assertThat(status(first)).isEqualTo("CANCELED");
        assertThat(status(second)).isEqualTo("CANCELED");
        assertThat(groupStatus(groupId)).isEqualTo("CANCELED");
        assertThat(historyRows(first)).hasSize(1);
        assertThat(historyRows(second)).hasSize(1);
        assertThat(events("OrderCancelled", first)).hasSize(1);
        assertThat(events("OrderCancelled", second)).hasSize(1);
        List<JsonNode> groupEvents = events("OrderGroupCancelled", groupId);
        assertThat(groupEvents).hasSize(1);
        JsonNode payload = groupEvents.getFirst().get("payload");
        assertThat(payload.get("groupVersion").asLong()).isEqualTo(1);
        assertThat(payload.get("reason").asString()).isEqualTo("Changed my mind");
        assertThat(payload.get("orders")).hasSize(2);
        assertThat(payload.get("orders").valueStream().map(order -> order.get("orderId").asString()).toList())
                .containsExactlyInAnyOrder(first.toString(), second.toString());
    }

    @Test
    void theOtherShopsItemsGoBackToTheBuyersCart() {
        CurrentUser buyer = buyer();
        UUID groupId = insertGroup(buyer.id(), "PENDING");
        UUID first = insertOrder(groupId, buyer.id(), OrderStatus.PENDING, "200000.00");
        UUID second = insertOrder(groupId, buyer.id(), OrderStatus.PENDING, "150000.00");
        insertItem(first, 1, "200000.00");
        UUID cup = insertItem(second, 2, "50000.00");
        UUID pen = insertItem(second, 1, "50000.00");

        orderService.cancel(buyer, first, CHANGED_MIND);

        assertThat(cartLines(buyer.id()))
                .containsExactlyInAnyOrder(cup + " x2 @ 50000.00", pen + " x1 @ 50000.00");
    }

    @Test
    void aReturnedItemAddsUpWithTheSameSkuAlreadyInTheCart() {
        CurrentUser buyer = buyer();
        UUID groupId = insertGroup(buyer.id(), "PENDING");
        UUID first = insertOrder(groupId, buyer.id(), OrderStatus.PENDING, "200000.00");
        UUID second = insertOrder(groupId, buyer.id(), OrderStatus.PENDING, "150000.00");
        insertItem(first, 1, "200000.00");
        UUID cup = insertItem(second, 2, "75000.00");
        UUID cartId = insertActiveCart(buyer.id(), cup, 1, "70000.00");

        orderService.cancel(buyer, first, CHANGED_MIND);

        assertThat(cartLines(buyer.id())).containsExactly(cup + " x3 @ 75000.00");
        assertThat(activeCartId(buyer.id())).isEqualTo(cartId);
    }

    @Test
    void cancellingAPaidOrderCarriesTheAmountPaidAndKeepsItsSiblingPaid() {
        CurrentUser buyer = buyer();
        UUID groupId = insertGroup(buyer.id(), "PAID");
        UUID paid = insertOrder(groupId, buyer.id(), OrderStatus.PAID, "200000.50");
        UUID sibling = insertOrder(groupId, buyer.id(), OrderStatus.PAID, "150000.00");
        insertItem(paid, 1, "200000.50");

        OrderGroupResponse group = orderService.cancel(buyer, paid,
                new CancelOrderRequest("Buyer requested cancellation before carrier handover"));

        assertThat(group.status()).isEqualTo("PAID");
        assertThat(status(paid)).isEqualTo("CANCELED");
        assertThat(status(sibling)).isEqualTo("PAID");
        assertThat(groupStatus(groupId)).isEqualTo("PAID");
        assertThat(historyRows(paid)).containsExactly(
                "PAID -> CANCELED: Buyer requested cancellation before carrier handover");
        assertThat(historyRows(sibling)).isEmpty();
        List<JsonNode> cancelled = events("OrderCancelled", paid);
        assertThat(cancelled).hasSize(1);
        assertThat(cancelled.getFirst().get("payload").get("paidAmount").asString()).isEqualTo("200000.50");
        assertThat(events("OrderCancelled", sibling)).isEmpty();
        assertThat(events("OrderGroupCancelled", groupId)).isEmpty();
        assertThat(cartLines(buyer.id())).isEmpty();
    }

    @Test
    void anotherBuyerGets403AndNothingChanges() {
        CurrentUser owner = buyer();
        UUID groupId = insertGroup(owner.id(), "PENDING");
        UUID orderId = insertOrder(groupId, owner.id(), OrderStatus.PENDING, "150000.00");

        assertThatThrownBy(() -> orderService.cancel(buyer(), orderId, CHANGED_MIND))
                .isInstanceOf(DomainException.class)
                .extracting("code", "errorCode").containsExactly(403, "COMMERCE_FORBIDDEN");

        assertThat(status(orderId)).isEqualTo("PENDING");
        assertThat(groupStatus(groupId)).isEqualTo("PENDING");
        assertThat(historyRows(orderId)).isEmpty();
        assertThat(events("OrderCancelled", orderId)).isEmpty();
        assertThat(events("OrderGroupCancelled", groupId)).isEmpty();
    }

    @Test
    void shippedAndCompletedOrdersReturn409() {
        CurrentUser buyer = buyer();
        for (OrderStatus status : List.of(OrderStatus.SHIPPED, OrderStatus.COMPLETED)) {
            UUID groupId = insertGroup(buyer.id(), "PAID");
            UUID orderId = insertOrder(groupId, buyer.id(), status, "150000.00");

            assertThatThrownBy(() -> orderService.cancel(buyer, orderId, CHANGED_MIND))
                    .isInstanceOf(DomainException.class)
                    .extracting("code", "errorCode").containsExactly(409, "COMMERCE_ORDER_NOT_CANCELLABLE");

            assertThat(status(orderId)).isEqualTo(status.name());
            assertThat(historyRows(orderId)).isEmpty();
            assertThat(events("OrderCancelled", orderId)).isEmpty();
        }
    }

    @Test
    void supportStaffCancelsAnyBuyersOrder() {
        CurrentUser owner = buyer();
        UUID groupId = insertGroup(owner.id(), "PENDING");
        UUID orderId = insertOrder(groupId, owner.id(), OrderStatus.PENDING, "150000.00");
        UUID other = insertOrder(groupId, owner.id(), OrderStatus.PENDING, "200000.00");
        UUID cup = insertItem(other, 1, "200000.00");
        CurrentUser support = new CurrentUser(UUID.randomUUID(), Set.of("SUPPORT_STAFF"), Set.of("ORDER.CANCEL"));

        orderService.cancel(support, orderId, new CancelOrderRequest("Buyer asked by phone"));

        assertThat(status(orderId)).isEqualTo("CANCELED");
        assertThat(events("OrderCancelled", orderId).getFirst().get("payload").get("actorId").asString())
                .isEqualTo(support.id().toString());
        assertThat(cartLines(owner.id())).containsExactly(cup + " x1 @ 200000.00");
        assertThat(cartLines(support.id())).isEmpty();
    }

    @Test
    void theSystemReasonsAreReservedAndAnUnknownOrderIs404() {
        CurrentUser buyer = buyer();
        UUID groupId = insertGroup(buyer.id(), "PENDING");
        UUID orderId = insertOrder(groupId, buyer.id(), OrderStatus.PENDING, "150000.00");

        assertThatThrownBy(() -> orderService.cancel(buyer, orderId, new CancelOrderRequest("PAYMENT_DEADLINE_EXPIRED")))
                .isInstanceOf(DomainException.class)
                .extracting("code", "errorCode").containsExactly(400, "COMMERCE_RESERVED_CANCEL_REASON");
        assertThatThrownBy(() -> orderService.cancel(buyer, orderId, new CancelOrderRequest("STOCK_UNAVAILABLE")))
                .isInstanceOf(DomainException.class)
                .extracting("code", "errorCode").containsExactly(400, "COMMERCE_RESERVED_CANCEL_REASON");
        assertThatThrownBy(() -> orderService.cancel(buyer, UUID.randomUUID(), CHANGED_MIND))
                .isInstanceOf(DomainException.class)
                .extracting("code", "errorCode").containsExactly(404, "COMMERCE_ORDER_NOT_FOUND");
        assertThat(status(orderId)).isEqualTo("PENDING");
    }

    private static CurrentUser buyer() {
        return new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of("ORDER.CANCEL", "ORDER.VIEW"));
    }

    private UUID insertGroup(UUID buyerId, String status) {
        return jdbc().queryForObject("""
                INSERT INTO order_groups (buyer_id, idempotency_key, status, total_amount, due_at)
                VALUES (?, ?, ?, 350000.00, now() + interval '15 minutes') RETURNING id
                """, UUID.class, buyerId, UUID.randomUUID().toString(), status);
    }

    private UUID insertOrder(UUID groupId, UUID buyerId, OrderStatus status, String total) {
        return jdbc().queryForObject("""
                INSERT INTO orders (order_group_id, user_id, seller_id, order_number, order_type, status,
                                    subtotal, total_amount, idempotency_key)
                VALUES (?, ?, ?, ?, 'STANDARD', CAST(? AS order_status), CAST(? AS numeric), CAST(? AS numeric), ?)
                RETURNING id
                """, UUID.class, groupId, buyerId, UUID.randomUUID(),
                "NX-CANCEL-%08d".formatted(ORDER_NUMBERS.incrementAndGet()), status.name(), total, total,
                UUID.randomUUID().toString());
    }

    private UUID insertItem(UUID orderId, int quantity, String unitPrice) {
        UUID skuId = UUID.randomUUID();
        jdbc().update("""
                INSERT INTO order_items (order_id, product_id, sku_id, product_name, quantity, unit_price, total_price)
                VALUES (?, ?, ?, 'Item', ?, CAST(? AS numeric), CAST(? AS numeric) * ?)
                """, orderId, UUID.randomUUID(), skuId, quantity, unitPrice, unitPrice, quantity);
        return skuId;
    }

    private UUID insertActiveCart(UUID buyerId, UUID skuId, int quantity, String price) {
        UUID cartId = jdbc().queryForObject(
                "INSERT INTO carts (user_id, status) VALUES (?, 'ACTIVE') RETURNING id", UUID.class, buyerId);
        jdbc().update("""
                INSERT INTO cart_items (cart_id, product_id, sku_id, quantity, price)
                VALUES (?, ?, ?, ?, CAST(? AS numeric))
                """, cartId, UUID.randomUUID(), skuId, quantity, price);
        return cartId;
    }

    private List<String> cartLines(UUID buyerId) {
        return jdbc().queryForList("""
                SELECT i.sku_id || ' x' || i.quantity || ' @ ' || i.price
                FROM cart_items i JOIN carts c ON c.id = i.cart_id
                WHERE c.user_id = ? AND c.status = 'ACTIVE'
                """, String.class, buyerId);
    }

    private UUID activeCartId(UUID buyerId) {
        return jdbc().queryForObject("SELECT id FROM carts WHERE user_id = ? AND status = 'ACTIVE'",
                UUID.class, buyerId);
    }

    private String status(UUID orderId) {
        return jdbc().queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, orderId);
    }

    private String groupStatus(UUID groupId) {
        return jdbc().queryForObject("SELECT status FROM order_groups WHERE id = ?", String.class, groupId);
    }

    private List<String> historyRows(UUID orderId) {
        return jdbc().queryForList("""
                SELECT previous_status::text || ' -> ' || new_status::text || ': ' || reason
                FROM order_status_history WHERE order_id = ? ORDER BY created_at
                """, String.class, orderId);
    }

    private List<JsonNode> events(String eventType, UUID aggregateId) {
        return jdbc().queryForList("""
                        SELECT payload::text FROM outbox
                        WHERE event_type = ? AND aggregate_id = ? AND topic = 'nexus.commerce.events'
                        """, String.class, eventType, aggregateId.toString()).stream()
                .map(JsonMapper.shared()::readTree)
                .toList();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {CartRepository.class, OrderRepository.class})
    @EnableJpaAuditing(auditorAwareRef = "auditor")
    @Import({PromotionTestConfig.class, OrderService.class, OrderStateMachine.class, OrderEventPublisher.class, PricingService.class})
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
            DataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=commerce_cancel",
                    POSTGRES.getUsername(), POSTGRES.getPassword());
            Flyway.configure().dataSource(source).schemas("commerce_cancel")
                    .defaultSchema("commerce_cancel").load().migrate();
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
        OutboxWriter outboxWriter(DataSource source, PlatformTransactionManager transactionManager) {
            return new OutboxWriter(new JdbcTemplate(source), JsonMapper.shared(), transactionManager);
        }

        @Bean
        EventEnvelopeFactory eventEnvelopeFactory() {
            Tracer tracer = Mockito.mock(Tracer.class, Mockito.RETURNS_DEEP_STUBS);
            when(tracer.currentSpan().context().traceId()).thenReturn("189cancel");
            return new EventEnvelopeFactory(tracer, Clock.systemUTC());
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
