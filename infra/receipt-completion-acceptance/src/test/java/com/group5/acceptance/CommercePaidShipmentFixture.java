package com.group5.acceptance;

import com.group5.commerceservice.order.CheckoutPaymentConfirmedConsumer;
import com.group5.commerceservice.order.event.CheckoutPaymentConfirmed;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.repository.OrderGroupRepository;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.service.OrderStateMachine;
import com.group5.commerceservice.promotion.VoucherLifecycleService;
import com.group5.common_events.EventEnvelope;
import com.group5.common_outbox.IdempotentConsumer;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Calls the real payment-confirmation consumer and returns its actual serialized OrderPaid outbox. */
final class CommercePaidShipmentFixture implements AutoCloseable {
    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class);
    final JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
    private final JsonMapper mapper = JsonMapper.shared();

    EventEnvelope<JsonNode> pay(UUID warehouseId, String recipientLine) {
        return pay(warehouseId, recipientLine, "1000001", "1000002");
    }

    EventEnvelope<JsonNode> pay(UUID warehouseId, String recipientLine, String provinceCode, String wardCode) {
        UUID buyer = UUID.randomUUID(), seller = UUID.randomUUID();
        BigDecimal total = new BigDecimal("345678.00");
        UUID group = jdbc.queryForObject("""
                INSERT INTO order_groups(buyer_id,idempotency_key,total_amount,due_at)
                VALUES (?,?,?,now()+interval '15 minutes') RETURNING id
                """, UUID.class, buyer, UUID.randomUUID().toString(), total);
        // Shape persisted by checkout: measurements and quote are present, pickup is absent.
        var snapshot = Map.of("warehouseId", warehouseId.toString(), "carrier", "GHN", "serviceType", "STANDARD",
                "recipient", Map.of("name", "Nexus Sandbox Test", "phone", "0987654321", "address",
                        Map.of("line1", recipientLine, "provinceCode", provinceCode, "wardCode", wardCode)),
                "parcel", Map.of("weightGrams", 500, "lengthCm", 20, "widthCm", 15, "heightCm", 10));
        UUID order = jdbc.queryForObject("""
                INSERT INTO orders(order_group_id,user_id,seller_id,order_number,order_type,status,
                    subtotal,discount_amount,shipping_fee,total_amount,idempotency_key,shipping_info)
                VALUES (?,?,?,?,'STANDARD','PENDING',300000,0,45678,?,?,?::jsonb) RETURNING id
                """, UUID.class, group, buyer, seller, "NX-258-" + UUID.randomUUID().toString().substring(0, 20),
                total, UUID.randomUUID().toString(), mapper.writeValueAsString(snapshot));
        jdbc.update("""
                INSERT INTO order_items(order_id,product_id,sku_id,product_name,sku_code,quantity,unit_price,total_price)
                VALUES (?,?,?,'Sample item','GHN-258-SKU',2,150000,300000)
                """, order, UUID.randomUUID(), UUID.randomUUID());
        var paid = new CheckoutPaymentConfirmed(UUID.randomUUID(), group, UUID.randomUUID(), buyer, "VND",
                total.toPlainString(), Instant.now().toString(), 1L,
                List.of(new CheckoutPaymentConfirmed.Instruction(UUID.randomUUID(), order, total.toPlainString())));
        var event = new EventEnvelope<>(UUID.randomUUID(), "CheckoutPaymentConfirmed", paid.sessionId().toString(),
                Instant.now(), "4bf92f3577b34da6a3ce929d0e0e4736", paid);
        var consumer = context.getBean(CheckoutPaymentConfirmedConsumer.class);
        consumer.receive(group.toString(), event);
        consumer.receive(group.toString(), event);
        consumer.receive(group.toString(), new EventEnvelope<>(UUID.randomUUID(), event.eventType(),
                event.aggregateId(), event.occurredAt(), event.traceId(), event.payload()));
        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id=?", String.class, order)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE event_type='OrderPaid' AND aggregate_id=?",
                Long.class, order.toString())).isEqualTo(1);
        JsonNode stored = mapper.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM outbox WHERE event_type='OrderPaid' AND aggregate_id=?", String.class, order.toString()));
        JsonNode info = stored.path("payload").path("shippingInfo");
        assertThat(info.has("serviceType")).as("producer does not emit a service selection").isFalse();
        assertThat(info.has("carrier")).as("producer does not emit a quote carrier").isFalse();
        assertThat(info.path("pickupAddress").isNull()).as("producer preserves missing pickup").isTrue();
        return new EventEnvelope<>(UUID.fromString(stored.path("eventId").asString()), stored.path("eventType").asString(),
                stored.path("aggregateId").asString(), Instant.parse(stored.path("occurredAt").asString()),
                stored.path("traceId").asString(), stored.path("payload"));
    }

    @Override public void close() { context.close(); }

    @Configuration(proxyBeanMethods = false)
    @Import(CommerceReceiptFixture.Config.class)
    static class Config {
        @Bean JsonMapper mapper() { return JsonMapper.shared(); }
        @Bean Validator validator() { return Validation.buildDefaultValidatorFactory().getValidator(); }
        @Bean VoucherLifecycleService voucherLifecycle() { return mock(VoucherLifecycleService.class); }
        @Bean IdempotentConsumer idempotency(EntityManagerFactory factory, PlatformTransactionManager transactions) {
            return new IdempotentConsumer(SharedEntityManagerCreator.createSharedEntityManager(factory), new TransactionTemplate(transactions));
        }
        @Bean CheckoutPaymentConfirmedConsumer payments(IdempotentConsumer idempotency, OrderGroupRepository groups,
                OrderRepository orders, OrderStateMachine stateMachine, OrderEventPublisher events,
                EntityManagerFactory factory, Validator validator, JsonMapper mapper, Tracer tracer, VoucherLifecycleService vouchers) {
            return new CheckoutPaymentConfirmedConsumer(idempotency, groups, orders, stateMachine, events,
                    SharedEntityManagerCreator.createSharedEntityManager(factory), validator, mapper, tracer, vouchers);
        }
    }
}
