package com.group5.inventoryservice.inventory;

import com.group5.common_events.Topics;
import com.group5.inventoryservice.reservation.service.ReservationService;
import com.group5.inventoryservice.shared.config.InventoryKafkaConfiguration;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/inventory-order-paid-dlt-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "spring.kafka.admin.auto-create=false",
        "spring.kafka.consumer.auto-offset-reset=earliest", "nexus.outbox.relay.enabled=false",
        "nexus.inventory.consumer-group=inventory-order-paid-dlt-test",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "nexus.inventory.default-warehouse-code=WH_DLT",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false"
})
class OrderPaidDeadLetterIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");
    private static final Duration NO_RETRY_DEADLINE = Duration.ofSeconds(5);
    private static KafkaConsumer<String, String> deadLetters;
    private static KafkaProducer<String, String> producer;

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @Autowired private CommonErrorHandler inventoryEventErrorHandler;
    @MockitoSpyBean private ReservationService reservations;
    @MockitoBean private JwtDecoder decoder;
    private UUID warehouse;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeAll
    static void topics() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(Topics.COMMERCE, 1, (short) 1),
                    new NewTopic(InventoryKafkaConfiguration.DEAD_LETTER_TOPIC, 1, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        }
        producer = new KafkaProducer<>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()),
                new StringSerializer(), new StringSerializer());
        deadLetters = new KafkaConsumer<>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader", ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer());
        deadLetters.subscribe(List.of(InventoryKafkaConfiguration.DEAD_LETTER_TOPIC));
    }

    @AfterAll
    static void close() {
        deadLetters.close();
        producer.close();
    }

    @BeforeEach
    void setup() {
        MessageListenerContainer container = listeners.getListenerContainer(OrderEventConsumer.LISTENER_ID);
        await().atMost(Duration.ofSeconds(30)).until(() -> container.getAssignedPartitions() != null
                && !container.getAssignedPartitions().isEmpty());
        jdbc.update("DELETE FROM processed_events");
        jdbc.update("DELETE FROM inventory_movements");
        jdbc.update("DELETE FROM inventory_reservations");
        jdbc.update("DELETE FROM inventory_balances");
        jdbc.update("INSERT INTO warehouses (code, name) VALUES ('WH_DLT', 'Dead letter test') ON CONFLICT (code) DO NOTHING");
        warehouse = jdbc.queryForObject("SELECT id FROM warehouses WHERE code = 'WH_DLT'", UUID.class);
    }

    @Test
    void theOrderEventListenerUsesTheInventoryRetryAndDeadLetterHandler() {
        MessageListenerContainer container = listeners.getListenerContainer(OrderEventConsumer.LISTENER_ID);

        assertThat(((AbstractMessageListenerContainer<?, ?>) container).getCommonErrorHandler())
                .isSameAs(inventoryEventErrorHandler);
    }

    @Test
    void aPaidOrderPublishedOnKafkaConfirmsItsReservation() throws Exception {
        UUID order = UUID.randomUUID();
        UUID sku = stock(10, 2);
        UUID reservation = hold(order, sku, 2);
        UUID eventId = UUID.randomUUID();

        publish(order.toString(), orderPaid(eventId, order.toString(), order));

        await().atMost(Duration.ofSeconds(10)).until(() -> "CONFIRMED".equals(status(reservation)));
        assertThat(processed(eventId)).isEqualTo(1);
    }

    @Test
    void anOrderPaidWithoutOrderIdGoesToTheDeadLetterTopicWithoutRetryAndConfirmsNothing() throws Exception {
        UUID sku = stock(10, 3);
        UUID unattached = hold(null, sku, 3);
        UUID eventId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        long started = System.nanoTime();

        publish(key, orderPaid(eventId, key, null));

        ConsumerRecord<String, String> dead = deadLetter(eventId);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(NO_RETRY_DEADLINE);
        assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(Topics.COMMERCE);
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_FQCN) + header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .contains(InvalidOrderEventException.class.getName());
        assertThat(status(unattached)).isEqualTo("HELD");
        assertThat(processed(eventId)).isZero();
        verify(reservations, never()).confirmOrder(any());
    }

    @Test
    void anOrderPaidForAReleasedReservationGoesToTheDeadLetterTopicWithoutRetry() throws Exception {
        UUID order = UUID.randomUUID();
        UUID sku = stock(10, 0);
        UUID released = jdbc.queryForObject("""
                INSERT INTO inventory_reservations (sku_id, warehouse_id, order_id, quantity, status, expires_at,
                                                    released_at)
                VALUES (?, ?, ?, 2, 'RELEASED', ?, now()) RETURNING id
                """, UUID.class, sku, warehouse, order, OffsetDateTime.now().plusMinutes(15));
        UUID eventId = UUID.randomUUID();
        long started = System.nanoTime();

        publish(order.toString(), orderPaid(eventId, order.toString(), order));

        ConsumerRecord<String, String> dead = deadLetter(eventId);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(NO_RETRY_DEADLINE);
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_FQCN) + header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .contains("DomainException");
        assertThat(status(released)).isEqualTo("RELEASED");
        assertThat(processed(eventId)).isZero();
        verify(reservations, times(1)).confirmOrder(order);
    }

    @Test
    void aDeadlockIsRetriedAndTheOrderIsThenConfirmedWithoutADeadLetter() throws Exception {
        UUID order = UUID.randomUUID();
        UUID sku = stock(10, 2);
        UUID reservation = hold(order, sku, 2);
        UUID eventId = UUID.randomUUID();
        doThrow(new CannotAcquireLockException("deadlock detected")).doCallRealMethod()
                .when(reservations).confirmOrder(order);

        publish(order.toString(), orderPaid(eventId, order.toString(), order));

        await().atMost(Duration.ofSeconds(15)).until(() -> "CONFIRMED".equals(status(reservation)));
        verify(reservations, times(2)).confirmOrder(order);
        assertThat(processed(eventId)).isEqualTo(1);
        assertThat(deadLettersFor(eventId, Duration.ofSeconds(2))).isEmpty();
    }

    private void publish(String key, String value) throws Exception {
        producer.send(new ProducerRecord<>(Topics.COMMERCE, key, value)).get(10, TimeUnit.SECONDS);
    }

    private String orderPaid(UUID eventId, String aggregateId, UUID order) {
        String orderId = order == null ? "" : "\"orderId\":\"" + order + "\",";
        return """
                {"eventId":"%s","eventType":"OrderPaid","aggregateId":"%s","occurredAt":"%s",
                 "traceId":"trace-dlt","payload":{%s"orderNumber":"NX-DLT-0001","orderType":"STANDARD",
                 "referenceId":null,"warehouseId":"%s"}}
                """.formatted(eventId, aggregateId, Instant.now(), orderId, warehouse);
    }

    private ConsumerRecord<String, String> deadLetter(UUID eventId) {
        List<ConsumerRecord<String, String>> found = deadLettersFor(eventId, NO_RETRY_DEADLINE);
        assertThat(found).as("dead letter for event %s", eventId).hasSize(1);
        return found.getFirst();
    }

    private List<ConsumerRecord<String, String>> deadLettersFor(UUID eventId, Duration wait) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        long deadline = System.nanoTime() + wait.toNanos();
        while (found.isEmpty() && System.nanoTime() < deadline) {
            deadLetters.poll(Duration.ofMillis(200)).forEach(record -> {
                if (record.value() != null && record.value().contains(eventId.toString())) {
                    found.add(record);
                }
            });
        }
        return found;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }

    private UUID stock(int total, int reserved) {
        UUID sku = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_balances (sku_id, warehouse_id, total_quantity, available_quantity,
                                                reserved_quantity)
                VALUES (?, ?, ?, ?, ?)
                """, sku, warehouse, total, total - reserved, reserved);
        return sku;
    }

    private UUID hold(UUID order, UUID sku, int quantity) {
        return jdbc.queryForObject("""
                INSERT INTO inventory_reservations (sku_id, warehouse_id, order_id, quantity, status, expires_at)
                VALUES (?, ?, ?, ?, 'HELD', ?) RETURNING id
                """, UUID.class, sku, warehouse, order, quantity, OffsetDateTime.now().plusMinutes(15));
    }

    private String status(UUID reservation) {
        return jdbc.queryForObject("SELECT status::text FROM inventory_reservations WHERE id = ?", String.class,
                reservation);
    }

    private int processed(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer = ?",
                Integer.class, eventId, OrderEventConsumer.ORDER_PAID_CONSUMER);
    }
}
