package com.group5.commerceservice.order;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_events.*;
import com.group5.common_outbox.*;
import com.group5.common_security.CurrentUser;
import com.group5.commerceservice.order.client.*;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest.ShippingAddress;
import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.service.*;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** PostgreSQL/Flyway thật: transaction, replay, concurrent envelopes và rollback outbox. */
@SpringJUnitConfig(AuctionSettlementTest.Config.class)
class AuctionSettlementTest {
    static final JsonMapper JSON = JsonMapper.shared();
    static final ShippingAddress ADDRESS = new ShippingAddress("Winner", "0900000000", "1 Main Street", "1003646", "1000001", "VN");
    @Autowired AuctionWonConsumer consumer;
    @Autowired AuctionShippingService shipping;
    @Autowired DataSource source;
    @Autowired OrderEventPublisher events;
    @Autowired InventoryReservationHttpClient inventory;
    @Autowired PricingService pricing;
    @Autowired CheckoutPaymentConfirmedConsumer payments;
    UUID warehouse;

    @BeforeEach void prepare() {
        reset(events, inventory, pricing);
        warehouse = UUID.randomUUID();
        when(inventory.linked(any())).thenReturn(ApiResponse.of(List.of(new InventoryReservationHttpClient.LinkedReservation(UUID.randomUUID(), warehouse, "HELD"))));
        when(pricing.auctionShippingQuote(any(), any(), any(), any(), any())).thenReturn(new OrderShippingService.Quote(
                new BigDecimal("30000.00"), new OrderShippingService.Parcel(1200,30,20,15), "GHN", "STANDARD", OffsetDateTime.now().plusDays(1)));
    }

    @Test void createsSnapshotAndOneGroupWithoutPaymentThenConfirmsExactlyOnce() {
        var won = won(); var record = record(won);
        consumer.receive(record); consumer.receive(record); consumer.receive(record(won));
        UUID order = order(won);
        assertThat(count("SELECT count(*) FROM orders WHERE reference_id=?", won.auctionId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox WHERE event_type='CheckoutPaymentRequested' AND aggregate_id=(SELECT order_group_id::text FROM orders WHERE id=?)", order)).isZero();
        assertThat(jdbc().queryForObject("SELECT order_number FROM orders WHERE id=?", String.class, order)).startsWith("AUC-");
        assertThat(jdbc().queryForObject("SELECT payment_due_at FROM orders WHERE id=?", OffsetDateTime.class, order).toInstant()).isEqualTo(won.paymentDueAt());
        assertThat(jdbc().queryForObject("SELECT product_name FROM order_items WHERE order_id=?", String.class, order)).isEqualTo(won.title());
        var user = buyer(won);
        var confirmed = shipping.confirm(user, order, ADDRESS);
        assertThat(confirmed.totalAmount()).isEqualByComparingTo("930000.00");
        assertThat(shipping.confirm(user, order, ADDRESS)).isEqualTo(confirmed);
        assertThat(count("SELECT count(*) FROM outbox WHERE event_type='CheckoutPaymentRequested' AND aggregate_id=?", confirmed.orderGroupId().toString())).isEqualTo(1);
        var snapshot = JSON.readTree(jdbc().queryForObject("SELECT shipping_info::text FROM orders WHERE id=?", String.class, order));
        assertThat(snapshot.path("warehouseId").asString()).isEqualTo(warehouse.toString());
        assertThat(snapshot.path("recipient").path("address").path("line1").asString()).isEqualTo(ADDRESS.addressLine());
        assertThat(snapshot.path("recipient").path("address").has("districtCode")).isFalse();
        assertThat(jdbc().queryForObject("SELECT total_amount FROM order_groups WHERE id=?", BigDecimal.class, confirmed.orderGroupId())).isEqualByComparingTo(confirmed.totalAmount());
        verify(inventory, times(1)).linked(order);
        verify(inventory, never()).reserve(any());
    }

    @Test void rejectsNonWinnerChangedAddressAndMissingHoldWithoutManifest() {
        var won = won(); consumer.receive(record(won)); var order = order(won);
        assertThatThrownBy(() -> shipping.confirm(new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of()), order, ADDRESS)).hasMessageContaining("permission");
        when(inventory.linked(order)).thenReturn(ApiResponse.of(List.of()));
        assertThatThrownBy(() -> shipping.confirm(buyer(won), order, ADDRESS)).hasMessageContaining("not linked");
        assertThat(jdbc().queryForObject("SELECT shipping_info IS NULL FROM orders WHERE id=?", Boolean.class, order)).isTrue();
        when(inventory.linked(order)).thenReturn(ApiResponse.of(List.of(new InventoryReservationHttpClient.LinkedReservation(UUID.randomUUID(), warehouse, "HELD"))));
        shipping.confirm(buyer(won), order, ADDRESS);
        var other = new ShippingAddress("Other", ADDRESS.phoneNumber(), ADDRESS.addressLine(), ADDRESS.wardCode(), ADDRESS.provinceCode(), "VN");
        assertThatThrownBy(() -> shipping.confirm(buyer(won), order, other)).hasMessageContaining("cannot confirm");
        jdbc().update("UPDATE orders SET status='PAID' WHERE id=?", order);
        assertThatThrownBy(() -> shipping.confirm(buyer(won), order, ADDRESS)).hasMessageContaining("cannot confirm");
    }

    @Test void outboxFailureRollsBackMarkerOrderAndGroupThenRetryWorks() {
        var won = won(); var message = record(won);
        doThrow(new IllegalStateException("outbox failure")).when(events).orderCreated(any());
        assertThatThrownBy(() -> consumer.receive(message)).hasMessageContaining("outbox failure");
        assertThat(count("SELECT count(*) FROM orders WHERE reference_id=?", won.auctionId())).isZero();
        assertThat(count("SELECT count(*) FROM order_groups WHERE buyer_id=?", won.winnerId())).isZero();
        assertThat(count("SELECT count(*) FROM processed_events WHERE event_id=?", message.value().eventId())).isZero();
        doCallRealMethod().when(events).orderCreated(any()); consumer.receive(message);
        assertThat(order(won)).isNotNull();
    }

    @Test void paymentManifestFailureRollsBackAddressAndTotals() {
        var won = won(); consumer.receive(record(won)); UUID order = order(won);
        doThrow(new IllegalStateException("manifest failure")).when(events).checkoutPaymentRequested(any());
        assertThatThrownBy(() -> shipping.confirm(buyer(won), order, ADDRESS)).hasMessageContaining("manifest failure");
        assertThat(jdbc().queryForObject("SELECT shipping_info IS NULL FROM orders WHERE id=?", Boolean.class, order)).isTrue();
        assertThat(jdbc().queryForObject("SELECT total_amount FROM orders WHERE id=?", BigDecimal.class, order)).isEqualByComparingTo(won.finalPrice());
    }

    @Test void unavailableDependenciesLeaveOrderUnconfirmedAndRetryable() {
        var won=won();consumer.receive(record(won));UUID order=order(won);
        when(inventory.linked(order)).thenReturn(null);
        assertThatThrownBy(() -> shipping.confirm(buyer(won),order,ADDRESS))
                .isInstanceOfSatisfying(com.group5.common_core.exception.DomainException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("COMMERCE_INVENTORY_UNAVAILABLE"));
        when(inventory.linked(order)).thenReturn(ApiResponse.of(List.of(new InventoryReservationHttpClient.LinkedReservation(UUID.randomUUID(),warehouse,"HELD"))));
        when(pricing.auctionShippingQuote(any(),any(),any(),any(),any())).thenThrow(new com.group5.common_core.exception.DomainException(com.group5.commerceservice.shared.exception.CommerceErrorCode.SHIPPING_UNAVAILABLE));
        assertThatThrownBy(() -> shipping.confirm(buyer(won),order,ADDRESS))
                .isInstanceOfSatisfying(com.group5.common_core.exception.DomainException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("COMMERCE_SHIPPING_UNAVAILABLE"));
        assertThat(jdbc().queryForObject("SELECT shipping_info IS NULL FROM orders WHERE id=?",Boolean.class,order)).isTrue();
        assertThat(count("SELECT count(*) FROM outbox WHERE event_type='CheckoutPaymentRequested' AND aggregate_id=(SELECT order_group_id::text FROM orders WHERE id=?)",order)).isZero();
    }

    @Test void concurrentDifferentEnvelopesAndConfirmationsHaveOneBusinessEffect() throws Exception {
        var won = won();
        parallel(() -> consumer.receive(record(won)));
        UUID order = order(won);
        parallel(() -> shipping.confirm(buyer(won), order, ADDRESS));
        assertThat(count("SELECT count(*) FROM orders WHERE reference_id=?", won.auctionId())).isEqualTo(1);
        String group = jdbc().queryForObject("SELECT order_group_id::text FROM orders WHERE id=?", String.class, order);
        assertThat(count("SELECT count(*) FROM outbox WHERE event_type='CheckoutPaymentRequested' AND aggregate_id=?", group)).isEqualTo(1);
    }

    @Test void confirmedAuctionCanBePaidWithSchemaValidOrderPaid() throws Exception {
        var won=won();consumer.receive(record(won));UUID order=order(won);
        var steps = new ArrayList<Map<String, Object>>();
        steps.add(paymentState(order, "AuctionWon consumed"));
        var confirmation=shipping.confirm(buyer(won),order,ADDRESS);
        steps.add(paymentState(order, "Shipping confirmed"));
        var payment=new com.group5.commerceservice.order.event.CheckoutPaymentConfirmed(UUID.randomUUID(),confirmation.orderGroupId(),UUID.randomUUID(),won.winnerId(),"VND",
                confirmation.totalAmount().toPlainString(),"2031-01-01T12:00:00Z",1L,List.of(new com.group5.commerceservice.order.event.CheckoutPaymentConfirmed.Instruction(UUID.randomUUID(),order,confirmation.totalAmount().toPlainString())));
        var paidEvent = new EventEnvelope<>(UUID.randomUUID(),"CheckoutPaymentConfirmed",payment.sessionId().toString(),Instant.now(),"172paid",payment);
        payments.receive(confirmation.orderGroupId().toString(),paidEvent);
        String serialized=jdbc().queryForObject("SELECT payload::text FROM outbox WHERE aggregate_id=? AND event_type='OrderPaid'",String.class,order.toString());
        var doc=java.nio.file.Files.readString(java.nio.file.Path.of("../docs/api/events.md"));
        var section=java.util.regex.Pattern.compile("(?ms)^### OrderPaid\\s*\\n(.*?)(?=^### |\\z)").matcher(doc);assertThat(section.find()).isTrue();
        var schema=java.util.regex.Pattern.compile("(?s)```json\\s*\\n(.*?)```").matcher(section.group(1));assertThat(schema.find()).isTrue();
        var validator=com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_7).getSchema(schema.group(1));
        assertThat(validator.validate(JSON.readTree(serialized).get("payload").toString(), com.networknt.schema.InputFormat.JSON)).isEmpty();
        assertThat(jdbc().queryForObject("SELECT status::text FROM orders WHERE id=?",String.class,order)).isEqualTo("PAID");
        steps.add(paymentState(order, "CheckoutPaymentConfirmed consumed"));
        payments.receive(confirmation.orderGroupId().toString(),paidEvent);
        assertThat(count("SELECT count(*) FROM outbox WHERE aggregate_id=? AND event_type='OrderPaid'",order.toString())).isEqualTo(1);
        steps.add(paymentState(order, "Same confirmation replayed"));
        if (Boolean.getBoolean("settlement.capture")) {
            var path = java.nio.file.Path.of("target/settlement-evidence/payment-states.json");
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(steps));
        }
    }

    private Map<String, Object> paymentState(UUID order, String step) {
        Map<String, Object> state = new LinkedHashMap<>(jdbc().queryForMap("""
                SELECT o.id::text AS order_id, g.id::text AS group_id, o.status::text AS order_status,
                       g.status AS group_status, o.total_amount::text AS order_total,
                       g.total_amount::text AS group_total, o.shipping_fee::text AS shipping_fee,
                       (SELECT count(*) FROM outbox WHERE aggregate_id=g.id::text AND event_type='CheckoutPaymentRequested') AS payment_requests,
                       (SELECT count(*) FROM outbox WHERE aggregate_id=o.id::text AND event_type='OrderPaid') AS paid_events
                FROM orders o JOIN order_groups g ON g.id=o.order_group_id WHERE o.id=?
                """, order));
        state.put("step", step);
        return state;
    }

    @Test void referenceLookupUsesExistingIndexWithAThousandOrders() {
        var won=won();consumer.receive(record(won));UUID order=order(won);
        jdbc().update("""
                INSERT INTO orders(order_group_id,user_id,seller_id,order_number,order_type,reference_id,status,subtotal,total_amount,idempotency_key,payment_due_at)
                SELECT order_group_id,user_id,uuidv7(),'IDX-'||uuidv7(),'STANDARD',uuidv7(),'PENDING',subtotal,total_amount,uuidv7()::text,payment_due_at
                FROM orders CROSS JOIN generate_series(1,1000) WHERE id=?
                """,order);
        jdbc().execute("ANALYZE orders");
        String plan=String.join("\n",jdbc().queryForList("EXPLAIN SELECT * FROM orders WHERE reference_id=?",String.class,won.auctionId()));
        assertThat(plan).contains("idx_orders_reference");
    }

    private static void parallel(Runnable task) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i=0;i<2;i++) futures.add(pool.submit(() -> { try { start.await(); task.run(); } catch (InterruptedException e) { throw new RuntimeException(e); } }));
            start.countDown(); for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
    }
    static AuctionWon won() { return new AuctionWon(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "900000.00", "VND", "Immutable auction title", "https://example.com/item.jpg", 1, Instant.parse("2031-01-01T00:00:00Z"), Instant.parse("2031-01-02T00:00:00Z")); }
    static ConsumerRecord<String, EventEnvelope<JsonNode>> record(AuctionWon won) {
        return new ConsumerRecord<>(Topics.AUCTION,0,0,won.auctionId().toString(),new EventEnvelope<>(UUID.randomUUID(),"AuctionWon",won.auctionId().toString(),Instant.now(),"172settlement",JSON.valueToTree(won)));
    }
    CurrentUser buyer(AuctionWon won) { return new CurrentUser(won.winnerId(), Set.of("BUYER"), Set.of("CHECKOUT.START")); }
    UUID order(AuctionWon won) { return jdbc().queryForObject("SELECT id FROM orders WHERE reference_id=?", UUID.class, won.auctionId()); }
    long count(String sql, Object... args) { return jdbc().queryForObject(sql, Long.class, args); }
    JdbcTemplate jdbc() { return new JdbcTemplate(source); }

    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement
    @org.springframework.data.jpa.repository.config.EnableJpaAuditing(auditorAwareRef="auditor")
    @EnableJpaRepositories(basePackageClasses=OrderRepository.class)
    @Import({AuctionOrderService.class,AuctionWonConsumer.class,AuctionShippingService.class,CheckoutPaymentConfirmedConsumer.class,OrderStateMachine.class})
    static class Config {
        @Bean org.springframework.data.domain.AuditorAware<UUID> auditor() { return new com.group5.common_security.CurrentUserAuditor(); }
        @Bean DataSource dataSource() {
            String url = System.getProperty("settlement.test.jdbc-url");
            String user="postgres", password="";
            if (url == null) {
                var pg = new PostgreSQLContainer("postgres:18-alpine"); pg.start();
                url=pg.getJdbcUrl(); user=pg.getUsername(); password=pg.getPassword();
            }
            String schema="settlement_"+UUID.randomUUID().toString().replace("-", "");
            var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate(); return source;
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory=new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(source);
            factory.setPackagesToScan(Order.class.getPackageName()); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","validate")); return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean IdempotentConsumer idempotentConsumer(EntityManagerFactory factory,PlatformTransactionManager tx) { return new IdempotentConsumer(SharedEntityManagerCreator.createSharedEntityManager(factory),new TransactionTemplate(tx)); }
        @Bean JsonMapper mapper() { return JSON; }
        @Bean Validator validator() { return Validation.buildDefaultValidatorFactory().getValidator(); }
        @Bean InventoryReservationHttpClient inventory() { return mock(InventoryReservationHttpClient.class); }
        @Bean OrderRemoteExecutor executor() { return new OrderRemoteExecutor(5,30000); }
        @Bean PricingService pricing() { return mock(PricingService.class); }
        @Bean Tracer tracer() {
            var tracer=mock(Tracer.class,RETURNS_DEEP_STUBS);
            when(tracer.currentSpan().context().traceId()).thenReturn("172settlement"); return tracer;
        }
        @Bean OrderEventPublisher events(DataSource source,PlatformTransactionManager tx,Tracer tracer) {
            return spy(new OrderEventPublisher(new OutboxWriter(new JdbcTemplate(source),JSON,tx),new EventEnvelopeFactory(tracer,Clock.systemUTC())));
        }
    }
}
