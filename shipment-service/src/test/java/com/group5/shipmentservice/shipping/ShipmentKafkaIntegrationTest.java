package com.group5.shipmentservice.shipping;

import com.group5.shipmentservice.shipping.event.OrderPaidShipmentConsumer;

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

/** Exercises the actual JSON listener, local transaction, scheduled relay and broker key. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = ShipmentServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.config.name=shipment-kafka-test", "spring.config.import=",
        "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.kafka.consumer.auto-offset-reset=earliest", "spring.kafka.consumer.enable-auto-commit=false",
        "spring.kafka.listener.ack-mode=record", "spring.kafka.listener.missing-topics-fatal=true",
        "spring.kafka.admin.auto-create=false", "spring.kafka.producer.acks=all",
        "nexus.shipping.carrier=FAKE", "nexus.shipping.order-paid-consumer-enabled=true",
        "nexus.outbox.relay.enabled=true", "nexus.outbox.relay.initial-delay=100", "nexus.outbox.relay.fixed-delay=100",
        "nexus.shipping.tracking-retry-poll-ms=3600000", "management.tracing.export.zipkin.enabled=false"
})
class ShipmentKafkaIntegrationTest {
    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18-alpine");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) throws Exception {
        // Topics exist before Spring starts its listener; no broker auto-create dependency.
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
    void rawOrderPaidDeliveredTwiceCreatesOneTrackedShipmentAndPublishesOneCreationEvent() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        String handPublishedJson = orderPaidJson(orderId, sourceId);
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
                     ConsumerConfig.GROUP_ID_CONFIG, "shipment-evidence-" + UUID.randomUUID(),
                     ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                     ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                     ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                     ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                     ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000,
                     ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000));
             AdminClient admin = admin()) {
            events.subscribe(List.of(Topics.SHIPMENT));
            publisher.send(new ProducerRecord<>(Topics.COMMERCE, orderId.toString(), handPublishedJson))
                    .get(10, TimeUnit.SECONDS);
            var duplicate = publisher.send(new ProducerRecord<>(Topics.COMMERCE, orderId.toString(), handPublishedJson))
                    .get(10, TimeUnit.SECONDS);
            TopicPartition input = new TopicPartition(Topics.COMMERCE, duplicate.partition());
            boolean completed = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                events.poll(Duration.ofMillis(250)).forEach(received::add);
                var offset = admin.listConsumerGroupOffsets(OrderPaidShipmentConsumer.CONSUMER)
                        .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).get(input);
                // Committed input offset proves that the real listener finished both deliveries.
                if (!received.isEmpty() && offset != null && offset.offset() > duplicate.offset()
                        && count("SELECT count(*) FROM outbox WHERE published_at IS NOT NULL") == 1) {
                    completed = true;
                    break;
                }
            }
            assertThat(completed).as("both source deliveries committed and ShipmentCreated reached Kafka").isTrue();
            events.poll(Duration.ofMillis(500)).forEach(received::add);
        }

        assertThat(count("SELECT count(*) FROM shipments WHERE order_id=?", orderId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM processed_events WHERE event_id=? AND consumer=?",
                sourceId, OrderPaidShipmentConsumer.CONSUMER)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox WHERE event_type='ShipmentCreated'")).isEqualTo(1);
        assertThat(received).hasSize(1);
        UUID shipmentId = jdbc.queryForObject("SELECT id FROM shipments WHERE order_id=?", UUID.class, orderId);
        String tracking = jdbc.queryForObject("SELECT tracking_code FROM shipments WHERE order_id=?", String.class, orderId);
        assertThat(tracking).isEqualTo("FAKE-" + shipmentId);
        var record = received.getFirst();
        JsonNode event = mapper.readTree(record.value());
        assertThat(record.key()).isEqualTo(orderId.toString());
        assertThat(event.path("eventType").asString()).isEqualTo("ShipmentCreated");
        assertThat(event.path("aggregateId").asString()).isEqualTo(shipmentId.toString());
        assertThat(event.path("payload").path("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(event.path("payload").path("shipmentId").asString()).isEqualTo(shipmentId.toString());
        assertThat(event.path("payload").path("trackingCode").isNull()).isTrue();
        assertThat(event.path("payload").path("status").asString()).isEqualTo("PENDING_TRACKING");
        assertThat(event.path("eventId").asString()).isEqualTo(
                jdbc.queryForObject("SELECT id::text FROM outbox", String.class));
    }

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private static AdminClient admin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000, AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000));
    }

    private static String orderPaidJson(UUID orderId, UUID eventId) {
        return """
                {
                  "eventId":"%s", "eventType":"OrderPaid", "aggregateId":"%s",
                  "occurredAt":"2026-11-11T00:00:00Z", "traceId":"4bf92f3577b34da6a3ce929d0e0e4736",
                  "payload": {
                    "orderId":"%s", "buyerId":"00000000-0000-4000-8000-000000000002",
                    "orderNumber":"NX-KAFKA-202", "orderType":"STANDARD", "referenceId":null,
                    "paidAt":"2026-11-11T00:00:00Z", "orderVersion":1,
                    "orderGroupId":"00000000-0000-4000-8000-000000000020",
                    "sellerId":"00000000-0000-4000-8000-000000000004",
                    "sessionId":"00000000-0000-4000-8000-000000000021",
                    "instructionId":"00000000-0000-4000-8000-000000000022",
                    "transactionId":"00000000-0000-4000-8000-000000000023",
                    "paidAmount":"200000.00", "currency":"VND",
                    "warehouseId":"00000000-0000-4000-8000-000000000010", "shippingFee":"30000.00",
                    "shippingInfo": {
                      "carrier":"FAKE", "serviceType":"STANDARD",
                      "recipient": {"name":"Buyer", "phone":"0900000000", "address": {
                        "line1":"1 Test Street", "provinceCode":"1000001", "wardCode":"1003646"
                      }},
                      "items":[{"skuId":"00000000-0000-4000-8000-000000000030", "productName":"Demo item", "quantity":1}],
                      "parcel":{"weightGrams":1000, "lengthCm":20, "widthCm":15, "heightCm":10},
                      "pickupAddress":{"line1":"2 Pickup Street", "provinceCode":"1000001", "wardCode":"1003646"}
                    }
                  }
                }
                """.formatted(eventId, orderId, orderId);
    }
}
