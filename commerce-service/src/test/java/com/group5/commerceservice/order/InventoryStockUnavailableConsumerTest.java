package com.group5.commerceservice.order;

import com.group5.commerceservice.order.event.InventoryStockUnavailable;
import com.group5.common_events.EventEnvelope;
import com.group5.common_outbox.IdempotentConsumer;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(InventoryStockUnavailableConsumerTest.Config.class)
class InventoryStockUnavailableConsumerTest {
    private static final AtomicInteger ORDER_NUMBERS = new AtomicInteger();

    @Autowired private InventoryStockUnavailableConsumer consumer;
    @Autowired private DataSource dataSource;

    @Test
    void aPaidOrderWithoutStockIsCancelledAndItsPaidAmountGoesToTheRefund() {
        UUID buyer = UUID.randomUUID();
        UUID groupId = insertGroup(buyer);
        UUID orderId = insertOrder(groupId, buyer, "PAID");
        UUID sibling = insertOrder(groupId, buyer, "PAID");

        consumer.handle(event(orderId));

        assertThat(status(orderId)).isEqualTo("CANCELED");
        assertThat(status(sibling)).isEqualTo("PAID");
        assertThat(historyRows(orderId)).containsExactly("PAID -> CANCELED: STOCK_UNAVAILABLE");
        List<JsonNode> cancelled = events("OrderCancelled", orderId);
        assertThat(cancelled).hasSize(1);
        JsonNode payload = cancelled.getFirst().get("payload");
        assertThat(payload.get("reason").asString()).isEqualTo("STOCK_UNAVAILABLE");
        assertThat(payload.get("paidAmount").asString()).isEqualTo("150000.00");
        assertThat(events("OrderGroupCancelled", groupId)).isEmpty();
        assertThat(events("OrderCancelled", sibling)).isEmpty();
    }

    @Test
    void theSameEventDeliveredTwiceCancelsOnce() {
        UUID buyer = UUID.randomUUID();
        UUID orderId = insertOrder(insertGroup(buyer), buyer, "PAID");
        EventEnvelope<InventoryStockUnavailable> event = event(orderId);

        consumer.handle(event);
        consumer.handle(event);

        assertThat(historyRows(orderId)).hasSize(1);
        assertThat(events("OrderCancelled", orderId)).hasSize(1);
    }

    @Test
    void aShippedOrderIsLeftAloneAndTheEventIsStillMarkedProcessed() {
        UUID buyer = UUID.randomUUID();
        UUID orderId = insertOrder(insertGroup(buyer), buyer, "SHIPPED");
        EventEnvelope<InventoryStockUnavailable> event = event(orderId);

        consumer.handle(event);

        assertThat(status(orderId)).isEqualTo("SHIPPED");
        assertThat(historyRows(orderId)).isEmpty();
        assertThat(events("OrderCancelled", orderId)).isEmpty();
        assertThat(processed(event.eventId())).isEqualTo(1);
    }

    @Test
    void anAggregateThatIsNotTheOrderIsRejected() {
        UUID buyer = UUID.randomUUID();
        UUID orderId = insertOrder(insertGroup(buyer), buyer, "PAID");
        EventEnvelope<InventoryStockUnavailable> event = event(orderId);

        assertThatThrownBy(() -> consumer.handle(new EventEnvelope<>(event.eventId(), event.eventType(),
                UUID.randomUUID().toString(), event.occurredAt(), event.traceId(), event.payload())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(status(orderId)).isEqualTo("PAID");
    }

    private static EventEnvelope<InventoryStockUnavailable> event(UUID orderId) {
        return new EventEnvelope<>(UUID.randomUUID(), "InventoryStockUnavailable", orderId.toString(), Instant.now(),
                "157stock", new InventoryStockUnavailable(orderId, List.of(
                new InventoryStockUnavailable.Line(UUID.randomUUID(), UUID.randomUUID(), 2))));
    }

    private UUID insertGroup(UUID buyerId) {
        return jdbc().queryForObject("""
                INSERT INTO order_groups (buyer_id, idempotency_key, status, total_amount, due_at)
                VALUES (?, ?, 'PAID', 300000.00, now() - interval '5 minutes') RETURNING id
                """, UUID.class, buyerId, UUID.randomUUID().toString());
    }

    private UUID insertOrder(UUID groupId, UUID buyerId, String status) {
        return jdbc().queryForObject("""
                INSERT INTO orders (order_group_id, user_id, seller_id, order_number, order_type, status, subtotal,
                                    total_amount, idempotency_key)
                VALUES (?, ?, ?, ?, 'STANDARD', CAST(? AS order_status), 150000.00, 150000.00, ?)
                RETURNING id
                """, UUID.class, groupId, buyerId, UUID.randomUUID(),
                "NX-STOCK-%08d".formatted(ORDER_NUMBERS.incrementAndGet()), status, UUID.randomUUID().toString());
    }

    private String status(UUID orderId) {
        return jdbc().queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, orderId);
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

    private int processed(UUID eventId) {
        return jdbc().queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class,
                eventId);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({OrderExpiryJobTest.JpaConfig.class, InventoryStockUnavailableConsumer.class})
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

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.shared();
        }

        @Bean
        Tracer tracer() {
            return Mockito.mock(Tracer.class);
        }
    }
}
