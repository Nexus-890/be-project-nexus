package com.group5.commerceservice.order;

import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.entity.Cart;
import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.service.InventoryClient;
import com.group5.commerceservice.order.service.OrderService;
import com.group5.commerceservice.order.service.OrderShippingService;
import com.group5.commerceservice.order.service.OrderStateMachine;
import com.group5.commerceservice.order.service.PricingService;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUserAuditor;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(OrderExpiryJobTest.JpaConfig.class)
class OrderExpiryJobTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    private static final AtomicInteger ORDER_NUMBERS = new AtomicInteger();

    static {
        POSTGRES.start();
    }

    @Autowired private OrderExpiryJob job;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void noOtherGroupIsOverdue() {
        jdbc().update("UPDATE order_groups SET status = 'CANCELED' WHERE status = 'PENDING' AND due_at < now()");
    }

    @Test
    void aPendingGroupOfTwoOrdersOneMinutePastItsDeadlineIsCancelled() {
        UUID buyer = UUID.randomUUID();
        UUID groupId = insertGroup(buyer, "PENDING", "-1 minute");
        UUID first = insertOrder(groupId, buyer, "STANDARD", null, OrderStatus.PENDING);
        UUID second = insertOrder(groupId, buyer, "STANDARD", null, OrderStatus.PENDING);
        insertItem(second);

        assertThat(job.runOnce()).isEqualTo(1);

        assertThat(groupStatus(groupId)).isEqualTo("CANCELED");
        assertThat(status(first)).isEqualTo("CANCELED");
        assertThat(status(second)).isEqualTo("CANCELED");
        assertThat(historyRows(first)).containsExactly("PENDING -> CANCELED: PAYMENT_DEADLINE_EXPIRED");
        assertThat(historyRows(second)).containsExactly("PENDING -> CANCELED: PAYMENT_DEADLINE_EXPIRED");
        List<JsonNode> group = events("OrderGroupCancelled", groupId);
        assertThat(group).hasSize(1);
        assertThat(group.getFirst().get("payload").get("reason").asString()).isEqualTo("PAYMENT_DEADLINE_EXPIRED");
        assertThat(group.getFirst().get("payload").get("orders")).hasSize(2);
        assertThat(events("OrderCancelled", first)).hasSize(1);
        JsonNode payload = events("OrderCancelled", second).getFirst().get("payload");
        assertThat(payload.get("reason").asString()).isEqualTo("PAYMENT_DEADLINE_EXPIRED");
        assertThat(payload.get("actorId").asString()).isEqualTo(CurrentUserAuditor.SYSTEM_ID.toString());
        assertThat(payload.get("paidAmount").asString()).isEqualTo("0.00");
        assertThat(cartLines(buyer)).isEmpty();
    }

    @Test
    void aPendingAuctionOrderPastItsPaymentDeadlineIsCancelledTheSameWay() {
        UUID buyer = UUID.randomUUID();
        UUID auctionId = UUID.randomUUID();
        UUID groupId = insertGroup(buyer, "PENDING", "-1 minute");
        UUID orderId = insertOrder(groupId, buyer, "AUCTION", auctionId, OrderStatus.PENDING);

        job.runOnce();

        assertThat(status(orderId)).isEqualTo("CANCELED");
        JsonNode payload = events("OrderCancelled", orderId).getFirst().get("payload");
        assertThat(payload.get("orderType").asString()).isEqualTo("AUCTION");
        assertThat(payload.get("referenceId").asString()).isEqualTo(auctionId.toString());
        assertThat(payload.get("reason").asString()).isEqualTo("PAYMENT_DEADLINE_EXPIRED");
    }

    @Test
    void aPendingOrderTenMinutesFromItsDeadlineIsUnchanged() {
        UUID buyer = UUID.randomUUID();
        UUID groupId = insertGroup(buyer, "PENDING", "10 minutes");
        UUID orderId = insertOrder(groupId, buyer, "STANDARD", null, OrderStatus.PENDING);

        assertThat(job.runOnce()).isZero();

        assertThat(status(orderId)).isEqualTo("PENDING");
        assertThat(groupStatus(groupId)).isEqualTo("PENDING");
        assertThat(historyRows(orderId)).isEmpty();
        assertThat(events("OrderCancelled", orderId)).isEmpty();
    }

    @Test
    void aPaidOrderPastItsDeadlineStaysPaid() {
        UUID buyer = UUID.randomUUID();
        UUID groupId = insertGroup(buyer, "PAID", "-1 hour");
        UUID orderId = insertOrder(groupId, buyer, "STANDARD", null, OrderStatus.PAID);

        assertThat(job.runOnce()).isZero();

        assertThat(status(orderId)).isEqualTo("PAID");
        assertThat(groupStatus(groupId)).isEqualTo("PAID");
        assertThat(historyRows(orderId)).isEmpty();
        assertThat(events("OrderCancelled", orderId)).isEmpty();
    }

    @Test
    void aSecondRunChangesNothingAndAddsNoSecondEvent() {
        UUID buyer = UUID.randomUUID();
        UUID groupId = insertGroup(buyer, "PENDING", "-1 minute");
        UUID orderId = insertOrder(groupId, buyer, "STANDARD", null, OrderStatus.PENDING);

        assertThat(job.runOnce()).isEqualTo(1);
        assertThat(job.runOnce()).isZero();

        assertThat(historyRows(orderId)).hasSize(1);
        assertThat(events("OrderCancelled", orderId)).hasSize(1);
        assertThat(events("OrderGroupCancelled", groupId)).hasSize(1);
    }

    private UUID insertGroup(UUID buyerId, String status, String dueIn) {
        return jdbc().queryForObject("""
                INSERT INTO order_groups (buyer_id, idempotency_key, status, total_amount, due_at)
                VALUES (?, ?, ?, 150000.00, now() + CAST(? AS interval)) RETURNING id
                """, UUID.class, buyerId, UUID.randomUUID().toString(), status, dueIn);
    }

    private UUID insertOrder(UUID groupId, UUID buyerId, String type, UUID referenceId, OrderStatus status) {
        return jdbc().queryForObject("""
                INSERT INTO orders (order_group_id, user_id, seller_id, order_number, order_type, reference_id,
                                    status, subtotal, total_amount, idempotency_key)
                VALUES (?, ?, ?, ?, CAST(? AS order_type), ?, CAST(? AS order_status), 150000.00, 150000.00, ?)
                RETURNING id
                """, UUID.class, groupId, buyerId, UUID.randomUUID(),
                "NX-EXPIRE-%08d".formatted(ORDER_NUMBERS.incrementAndGet()), type, referenceId, status.name(),
                UUID.randomUUID().toString());
    }

    private void insertItem(UUID orderId) {
        jdbc().update("""
                INSERT INTO order_items (order_id, product_id, sku_id, product_name, quantity, unit_price, total_price)
                VALUES (?, ?, ?, 'Item', 1, 150000.00, 150000.00)
                """, orderId, UUID.randomUUID(), UUID.randomUUID());
    }

    private List<String> cartLines(UUID buyerId) {
        return jdbc().queryForList("""
                SELECT i.sku_id::text FROM cart_items i JOIN carts c ON c.id = i.cart_id WHERE c.user_id = ?
                """, String.class, buyerId);
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
    @Import({PromotionTestConfig.class, OrderExpiryJob.class, OrderService.class, OrderStateMachine.class, OrderEventPublisher.class,
            PricingService.class})
    static class JpaConfig {
        @Bean
        OrderShippingService shipping() {
            return Mockito.mock(OrderShippingService.class);
        }

        @Bean
        DataSource dataSource() {
            DataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=commerce_expiry",
                    POSTGRES.getUsername(), POSTGRES.getPassword());
            Flyway.configure().dataSource(source).schemas("commerce_expiry")
                    .defaultSchema("commerce_expiry").load().migrate();
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
        Tracer tracer() {
            Tracer tracer = Mockito.mock(Tracer.class, Mockito.RETURNS_DEEP_STUBS);
            when(tracer.currentSpan().context().traceId()).thenReturn("159expiry");
            return tracer;
        }

        @Bean
        EventEnvelopeFactory eventEnvelopeFactory(Tracer tracer) {
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
