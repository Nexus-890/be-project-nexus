package com.group5.commerceservice.order;

import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.event.OrderRefundCompleted;
import com.group5.commerceservice.order.event.OrderRefundRequested;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.service.OrderStateMachine;
import com.group5.commerceservice.order.service.RefundStatusHandler;
import com.group5.common_events.EventEnvelope;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.common_security.CurrentUserAuditor;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(RefundStatusHandlerTest.JpaConfig.class)
class RefundStatusHandlerTest {
    private static final PostgreSQLContainer POSTGRES = new com.group5.commerceservice.PostgresTestDatabase("postgres:18-alpine");
    private static final AtomicInteger ORDER_NUMBERS = new AtomicInteger();
    private static final UUID SUPPORT = UUID.randomUUID();

    static {
        POSTGRES.start();
    }

    @Autowired private RefundStatusHandler handler;
    @Autowired private DataSource dataSource;

    @Test
    void requestedThenCompletedEndsRefundedWithTwoHistoryRows() {
        UUID orderId = insertOrder(OrderStatus.CANCELED);

        handler.requested(requested(orderId));
        assertThat(status(orderId)).isEqualTo("REFUND_REQUESTED");
        handler.completed(completed(orderId));

        assertThat(status(orderId)).isEqualTo("REFUNDED");
        assertThat(refundVersion(orderId)).isEqualTo(1);
        assertThat(history(orderId)).containsExactly("CANCELED -> REFUND_REQUESTED", "REFUND_REQUESTED -> REFUNDED");
        assertThat(jdbc().queryForObject("SELECT DISTINCT changed_by FROM order_status_history WHERE order_id = ?",
                UUID.class, orderId)).isEqualTo(SUPPORT);
    }

    @Test
    void onlyTheCompletionStillWalksBothSteps() {
        UUID orderId = insertOrder(OrderStatus.CANCELED);

        handler.completed(completed(orderId));

        assertThat(status(orderId)).isEqualTo("REFUNDED");
        assertThat(history(orderId)).containsExactly("CANCELED -> REFUND_REQUESTED", "REFUND_REQUESTED -> REFUNDED");
    }

    @Test
    void aLateRequestAfterTheCompletionChangesNothing() {
        UUID orderId = insertOrder(OrderStatus.CANCELED);
        handler.completed(completed(orderId));

        handler.requested(requested(orderId));

        assertThat(status(orderId)).isEqualTo("REFUNDED");
        assertThat(refundVersion(orderId)).isEqualTo(1);
        assertThat(history(orderId)).hasSize(2);
    }

    @Test
    void theSameEventDeliveredTwiceIsAppliedOnce() {
        UUID orderId = insertOrder(OrderStatus.CANCELED);
        EventEnvelope<OrderRefundRequested> event = requested(orderId);

        handler.requested(event);
        handler.requested(event);

        assertThat(history(orderId)).containsExactly("CANCELED -> REFUND_REQUESTED");
        assertThat(processed(event.eventId())).isEqualTo(1);
    }

    @Test
    void aPaidOrderIsRetriedAndNotMarkedHandled() {
        UUID orderId = insertOrder(OrderStatus.PAID);
        EventEnvelope<OrderRefundRequested> event = requested(orderId);

        assertThatThrownBy(() -> handler.requested(event)).isInstanceOf(IllegalStateException.class);

        assertThat(status(orderId)).isEqualTo("PAID");
        assertThat(history(orderId)).isEmpty();
        assertThat(processed(event.eventId())).isZero();
    }

    private static EventEnvelope<OrderRefundRequested> requested(UUID orderId) {
        return new EventEnvelope<>(UUID.randomUUID(), "OrderRefundRequested", orderId.toString(), Instant.now(),
                "247refund", new OrderRefundRequested(UUID.randomUUID(), orderId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "300000.00", "VND", "REQUESTED",
                "Seller out of stock", SUPPORT, 0, 1, Instant.now().toString(), null));
    }

    private static EventEnvelope<OrderRefundCompleted> completed(UUID orderId) {
        return new EventEnvelope<>(UUID.randomUUID(), "OrderRefundCompleted", orderId.toString(), Instant.now(),
                "247refund", new OrderRefundCompleted(UUID.randomUUID(), orderId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "300000.00", "VND", "COMPLETED",
                "Seller out of stock", SUPPORT, 1, 1, Instant.now().toString(), UUID.randomUUID(),
                Instant.now().toString(), null));
    }

    private UUID insertOrder(OrderStatus status) {
        UUID buyerId = UUID.randomUUID();
        UUID groupId = jdbc().queryForObject("""
                INSERT INTO order_groups (buyer_id, idempotency_key, status, total_amount, due_at)
                VALUES (?, ?, 'CANCELED', 300000.00, now() + interval '15 minutes') RETURNING id
                """, UUID.class, buyerId, UUID.randomUUID().toString());
        return jdbc().queryForObject("""
                INSERT INTO orders (order_group_id, user_id, seller_id, order_number, order_type, status,
                                    subtotal, total_amount, idempotency_key)
                VALUES (?, ?, ?, ?, 'STANDARD', CAST(? AS order_status), 300000.00, 300000.00, ?)
                RETURNING id
                """, UUID.class, groupId, buyerId, UUID.randomUUID(),
                "NX-REFUND-%08d".formatted(ORDER_NUMBERS.incrementAndGet()), status.name(),
                UUID.randomUUID().toString());
    }

    private String status(UUID orderId) {
        return jdbc().queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, orderId);
    }

    private long refundVersion(UUID orderId) {
        return jdbc().queryForObject("SELECT last_refund_version FROM orders WHERE id = ?", Long.class, orderId);
    }

    private List<String> history(UUID orderId) {
        return jdbc().queryForList("""
                SELECT previous_status::text || ' -> ' || new_status::text
                FROM order_status_history WHERE order_id = ? ORDER BY created_at, id
                """, String.class, orderId);
    }

    private long processed(UUID eventId) {
        return jdbc().queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer = ?",
                Long.class, eventId, RefundStatusHandler.CONSUMER);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class)
    @EnableJpaAuditing(auditorAwareRef = "auditor")
    @Import({com.group5.commerceservice.promotion.PromotionTestConfig.class, OrderStateMachine.class, RefundStatusHandler.class})
    static class JpaConfig {
        @Bean
        com.group5.commerceservice.order.event.OrderEventPublisher orderEvents() {
            return org.mockito.Mockito.mock(com.group5.commerceservice.order.event.OrderEventPublisher.class);
        }

        @Bean
        AuditorAware<UUID> auditor() {
            return new CurrentUserAuditor();
        }

        @Bean
        DataSource dataSource() {
            DataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=commerce_refund",
                    POSTGRES.getUsername(), POSTGRES.getPassword());
            Flyway.configure().dataSource(source).schemas("commerce_refund")
                    .defaultSchema("commerce_refund").load().migrate();
            return source;
        }

        @Bean
        Validator validator() {
            return Validation.buildDefaultValidatorFactory().getValidator();
        }

        @Bean
        IdempotentConsumer idempotentConsumer(EntityManagerFactory factory, PlatformTransactionManager transactions) {
            return new IdempotentConsumer(SharedEntityManagerCreator.createSharedEntityManager(factory),
                    new TransactionTemplate(transactions));
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(Order.class.getPackageName());
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
