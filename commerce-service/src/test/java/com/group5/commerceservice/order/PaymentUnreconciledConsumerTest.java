package com.group5.commerceservice.order;

import com.group5.commerceservice.order.event.PaymentUnreconciled;
import com.group5.commerceservice.order.service.OrderService;
import com.group5.common_events.EventEnvelope;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.common_security.CurrentUser;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(PaymentUnreconciledConsumerTest.Config.class)
class PaymentUnreconciledConsumerTest {
    private static final AtomicInteger ORDER_NUMBERS = new AtomicInteger();
    private static final String LATE = "Payment arrived after the payment deadline. Support will contact you about a refund.";

    @Autowired private PaymentUnreconciledConsumer consumer;
    @Autowired private OrderService orderService;
    @Autowired private DataSource dataSource;

    @Test
    void aLatePaymentPutsTheReasonOnEveryOrderOfTheGroupWithoutMovingItsStatus() {
        UUID buyer = UUID.randomUUID();
        UUID groupId = insertGroup(buyer);
        UUID first = insertOrder(groupId, buyer, "CANCELED");
        UUID second = insertOrder(groupId, buyer, "CANCELED");

        consumer.receive(groupId.toString(), event(UUID.randomUUID(), groupId, List.of(first, second)));

        assertThat(note(first)).isEqualTo(LATE);
        assertThat(note(second)).isEqualTo(LATE);
        assertThat(status(first)).isEqualTo("CANCELED");
        assertThat(status(second)).isEqualTo("CANCELED");
        assertThat(historyRows(first)).isZero();
        var buyerView = orderService.getOrder(new CurrentUser(buyer, Set.of("BUYER"), Set.of()), first);
        assertThat(buyerView.paymentNote()).isEqualTo(LATE);
        assertThat(buyerView.status()).isEqualTo(OrderStatus.CANCELED);
    }

    @Test
    void theSameEventDeliveredTwiceIsProcessedOnce() {
        UUID buyer = UUID.randomUUID();
        UUID groupId = insertGroup(buyer);
        UUID orderId = insertOrder(groupId, buyer, "PENDING");
        EventEnvelope<PaymentUnreconciled> event = event(UUID.randomUUID(), groupId, List.of(orderId));

        consumer.receive(groupId.toString(), event);
        long version = version(orderId);
        consumer.receive(groupId.toString(), event);

        assertThat(version(orderId)).isEqualTo(version);
        assertThat(note(orderId)).isEqualTo(LATE);
    }

    @Test
    void anOrderOutsideTheGroupIsNotTouched() {
        UUID buyer = UUID.randomUUID();
        UUID groupId = insertGroup(buyer);
        UUID inGroup = insertOrder(groupId, buyer, "PENDING");
        UUID otherGroup = insertGroup(buyer);
        UUID outside = insertOrder(otherGroup, buyer, "PENDING");

        consumer.receive(groupId.toString(), event(UUID.randomUUID(), groupId, List.of(inGroup, outside)));

        assertThat(note(inGroup)).isEqualTo(LATE);
        assertThat(note(outside)).isNull();
    }

    @Test
    void aKafkaKeyThatIsNotTheGroupIsRejected() {
        UUID buyer = UUID.randomUUID();
        UUID groupId = insertGroup(buyer);
        UUID orderId = insertOrder(groupId, buyer, "PENDING");

        assertThatThrownBy(() -> consumer.receive(UUID.randomUUID().toString(),
                event(UUID.randomUUID(), groupId, List.of(orderId)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(note(orderId)).isNull();
    }

    private static EventEnvelope<PaymentUnreconciled> event(UUID sessionId, UUID groupId, List<UUID> orders) {
        return new EventEnvelope<>(UUID.randomUUID(), "PaymentUnreconciled", sessionId.toString(), Instant.now(),
                "157unreconciled", new PaymentUnreconciled(sessionId, groupId, UUID.randomUUID(), "156007", orders,
                "LATE_PAYMENT", LATE));
    }

    private UUID insertGroup(UUID buyerId) {
        return jdbc().queryForObject("""
                INSERT INTO order_groups (buyer_id, idempotency_key, status, total_amount, due_at)
                VALUES (?, ?, 'CANCELED', 150000.00, now() + interval '15 minutes') RETURNING id
                """, UUID.class, buyerId, UUID.randomUUID().toString());
    }

    private UUID insertOrder(UUID groupId, UUID buyerId, String status) {
        return jdbc().queryForObject("""
                INSERT INTO orders (order_group_id, user_id, seller_id, order_number, order_type, status, subtotal,
                                    total_amount, idempotency_key)
                VALUES (?, ?, ?, ?, 'STANDARD', CAST(? AS order_status), 150000.00, 150000.00, ?)
                RETURNING id
                """, UUID.class, groupId, buyerId, UUID.randomUUID(),
                "NX-UNREC-%08d".formatted(ORDER_NUMBERS.incrementAndGet()), status, UUID.randomUUID().toString());
    }

    private String note(UUID orderId) {
        return jdbc().queryForObject("SELECT payment_note FROM orders WHERE id = ?", String.class, orderId);
    }

    private String status(UUID orderId) {
        return jdbc().queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, orderId);
    }

    private long version(UUID orderId) {
        return jdbc().queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, orderId);
    }

    private int historyRows(UUID orderId) {
        return jdbc().queryForObject("SELECT count(*) FROM order_status_history WHERE order_id = ?", Integer.class,
                orderId);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({OrderExpiryJobTest.JpaConfig.class, PaymentUnreconciledConsumer.class})
    static class Config {
        @Bean
        IdempotentConsumer idempotentConsumer(EntityManagerFactory factory, PlatformTransactionManager transactions) {
            return new IdempotentConsumer(SharedEntityManagerCreator.createSharedEntityManager(factory),
                    new TransactionTemplate(transactions));
        }

        @Bean
        Validator validator() {
            return Validation.buildDefaultValidatorFactory().getValidator();
        }
    }
}
