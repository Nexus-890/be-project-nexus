package com.group5.shipmentservice.shipping;

import com.group5.shipmentservice.shipping.event.OrderCancelledConsumer;

import com.group5.common_events.Topics;
import com.group5.shipmentservice.ShipmentServiceApplication;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = ShipmentServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.config.name=shipment-status-kafka-test", "spring.config.import=",
        "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.kafka.consumer.auto-offset-reset=earliest", "spring.kafka.consumer.enable-auto-commit=false",
        "spring.kafka.listener.ack-mode=record", "spring.kafka.listener.missing-topics-fatal=true",
        "spring.kafka.admin.auto-create=false", "spring.kafka.producer.acks=all",
        "nexus.shipping.carrier=FAKE", "nexus.shipping.order-paid-consumer-enabled=false",
        "nexus.shipping.order-cancelled-consumer-enabled=true", "nexus.shipping.status-poll-enabled=false",
        "nexus.outbox.relay.enabled=true", "nexus.outbox.relay.initial-delay=100", "nexus.outbox.relay.fixed-delay=100",
        "nexus.shipping.tracking-retry-poll-ms=3600000", "management.tracing.export.zipkin.enabled=false"
})
class ShipmentStatusKafkaIntegrationTest {
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18-alpine");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) throws Exception {
        try (AdminClient admin = admin()) {
            admin.createTopics(List.of(new NewTopic(Topics.COMMERCE, 1, (short) 1),
                    new NewTopic(Topics.SHIPMENT, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;

    @Test
    @Timeout(60)
    void duplicateRawCancellationProducesOneCancelledShipmentAndOneKeyedBrokerEvent() throws Exception {
        UUID shipmentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO shipments(id,order_id,buyer_id,warehouse_id,carrier,tracking_code,shipment_status,shipping_info)
                VALUES (?,?,?,?,'FAKE',?,'CREATED'::shipment_status,'{}'::jsonb)
                """, shipmentId, orderId, UUID.randomUUID(), UUID.randomUUID(), "FAKE-" + shipmentId);
        String cancellation = cancellationJson(orderId, sourceId);
        List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> received = new ArrayList<>();

        try (KafkaProducer<String, String> publisher = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000));
             KafkaConsumer<String, String> events = new KafkaConsumer<>(Map.of(
                     ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                     ConsumerConfig.GROUP_ID_CONFIG, "shipment-status-evidence-" + UUID.randomUUID(),
                     ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                     ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                     ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                     ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                     ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000,
                     ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000));
             AdminClient admin = admin()) {
            events.subscribe(List.of(Topics.SHIPMENT));
            publisher.send(new ProducerRecord<>(Topics.COMMERCE, orderId.toString(), cancellation))
                    .get(10, TimeUnit.SECONDS);
            var duplicate = publisher.send(new ProducerRecord<>(Topics.COMMERCE, orderId.toString(), cancellation))
                    .get(10, TimeUnit.SECONDS);
            TopicPartition input = new TopicPartition(Topics.COMMERCE, duplicate.partition());
            boolean completed = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                events.poll(Duration.ofMillis(250)).forEach(received::add);
                var offset = admin.listConsumerGroupOffsets(OrderCancelledConsumer.CONSUMER)
                        .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).get(input);
                if (!received.isEmpty() && offset != null && offset.offset() > duplicate.offset()
                        && count("SELECT count(*) FROM outbox WHERE published_at IS NOT NULL") == 1) {
                    completed = true;
                    break;
                }
            }
            assertThat(completed).as("both cancellation deliveries committed and ShipmentCancelled reached Kafka").isTrue();
            events.poll(Duration.ofMillis(500)).forEach(received::add);
        }

        assertThat(jdbc.queryForObject("SELECT shipment_status::text FROM shipments WHERE id=?", String.class, shipmentId))
                .isEqualTo("CANCELLED");
        assertThat(count("SELECT count(*) FROM processed_events WHERE event_id=? AND consumer=?",
                sourceId, OrderCancelledConsumer.CONSUMER)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM shipment_history WHERE shipment_id=?", shipmentId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox WHERE event_type='ShipmentCancelled'")).isEqualTo(1);
        assertThat(received).hasSize(1);

        var record = received.getFirst();
        JsonNode event = mapper.readTree(record.value());
        assertThat(record.key()).isEqualTo(orderId.toString());
        assertThat(event.path("eventType").asString()).isEqualTo("ShipmentCancelled");
        assertThat(event.path("aggregateId").asString()).isEqualTo(shipmentId.toString());
        assertThat(event.path("traceId").asString()).isEqualTo(TRACE);
        assertThat(event.path("payload").path("shipmentId").asString()).isEqualTo(shipmentId.toString());
        assertThat(event.path("payload").path("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(event.path("payload").path("status").asString()).isEqualTo("CANCELLED");
        assertThat(event.path("payload").path("source").asString()).isEqualTo("SYSTEM");
        assertThat(event.path("payload").path("historyId").asString())
                .isEqualTo(jdbc.queryForObject("SELECT id::text FROM shipment_history WHERE shipment_id=?", String.class, shipmentId));
        assertThat(event.path("payload").path("shipmentVersion").asLong())
                .isEqualTo(jdbc.queryForObject("SELECT version FROM shipments WHERE id=?", Long.class, shipmentId));
        assertThat(event.path("payload").path("reason").asString()).isEqualTo("Buyer requested cancellation");
        assertThat(event.path("eventId").asString())
                .isEqualTo(jdbc.queryForObject("SELECT id::text FROM outbox", String.class));
        assertThat(jdbc.queryForObject("SELECT trace_id FROM outbox", String.class)).isEqualTo(TRACE);
    }

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private static AdminClient admin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000, AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000));
    }

    private static String cancellationJson(UUID orderId, UUID eventId) {
        return """
                {
                  "eventId":"%s", "eventType":"OrderCancelled", "aggregateId":"%s",
                  "occurredAt":"2026-11-11T00:00:00Z", "traceId":"%s",
                  "payload": {
                    "orderId":"%s", "buyerId":"00000000-0000-4000-8000-000000000002",
                    "orderNumber":"NX-KAFKA-203", "orderType":"STANDARD", "referenceId":null,
                    "cancelledAt":"2026-11-11T00:00:00Z", "reason":"Buyer requested cancellation",
                    "orderGroupId":"00000000-0000-4000-8000-000000000020",
                    "sellerId":"00000000-0000-4000-8000-000000000004", "orderVersion":2,
                    "paidAmount":"200000.00", "currency":"VND",
                    "actorId":"00000000-0000-4000-8000-000000000002"
                  }
                }
                """.formatted(eventId, orderId, TRACE, orderId);
    }
}
