package com.group5.common_outbox;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class OutboxIT {

    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static ConfigurableApplicationContext application;
    private static JdbcTemplate jdbc;
    private static OutboxWriter writer;
    private static OutboxRelay relay;
    private static TransactionTemplate transaction;
    private static AdminClient admin;
    private String topic;

    @BeforeAll
    static void startApplicationAndLoadTheActualBaseline() throws Exception {
        application = startApplication(Map.of());
        jdbc = application.getBean(JdbcTemplate.class);
        writer = application.getBean(OutboxWriter.class);
        relay = application.getBean(OutboxRelay.class);
        PlatformTransactionManager manager = application.getBean(PlatformTransactionManager.class);
        assertThat(manager).isInstanceOf(JpaTransactionManager.class);
        transaction = new TransactionTemplate(manager);

        for (String statement : outboxDdl("catalog").split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }
        jdbc.execute("CREATE TABLE outbox_test_business (id uuid PRIMARY KEY)");
        jdbc.execute("CREATE TABLE publication_audit (event_id uuid NOT NULL)");
        jdbc.execute("""
                CREATE FUNCTION audit_outbox_publication() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN INSERT INTO publication_audit VALUES (NEW.id); RETURN NEW; END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER audit_outbox_publication AFTER UPDATE OF published_at ON outbox
                FOR EACH ROW WHEN (NEW.published_at IS NOT NULL) EXECUTE FUNCTION audit_outbox_publication()
                """);
        admin = AdminClient.create(Map.of("bootstrap.servers", requiredEnv("OUTBOX_TEST_KAFKA_BOOTSTRAP_SERVERS"),
                "request.timeout.ms", "1000", "default.api.timeout.ms", "3000"));
    }

    @BeforeEach
    void prepareAnIsolatedTopic() throws Exception {
        jdbc.execute("TRUNCATE outbox, outbox_test_business, publication_audit");
        topic = "outbox-test-" + UUID.randomUUID();
        admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void deleteTopic() throws Exception {
        admin.deleteTopics(List.of(topic)).all().get(10, TimeUnit.SECONDS);
    }

    @AfterAll
    static void closeApplication() {
        if (admin != null) {
            admin.close(Duration.ofSeconds(5));
        }
        if (application != null) {
            application.close();
        }
    }

    @Test
    void allSixBaselinesHaveTheSameOutboxContract() throws Exception {
        String expected = outboxDdl("catalog");
        for (String service : List.of("user", "commerce", "auction", "inventory", "notification")) {
            assertThat(outboxDdl(service)).as(service + " outbox DDL").isEqualTo(expected);
        }
    }

    @Test
    void refusesToWriteOutsideATransactionWithoutPersistingARow() {
        assertThatThrownBy(() -> writer.write("Product", topic, event(0)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("outbox")).isZero();
    }

    @Test
    void rollsBackTheOutboxWithTheJpaBusinessWrite() {
        EventEnvelope<?> event = event(0);
        EntityManager entityManager = SharedEntityManagerCreator.createSharedEntityManager(
                application.getBean(EntityManagerFactory.class));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            entityManager.persist(new BusinessRecord(event.eventId()));
            entityManager.flush();
            writer.write("Product", topic, event);
            throw new IllegalStateException("business operation failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("business operation failed");

        assertThat(count("outbox")).isZero();
        assertThat(count("outbox_test_business")).isZero();
    }

    @Test
    void commitsWhileKafkaIsStoppedAndPublishesTheSameEventAfterRestart() throws Exception {
        EventEnvelope<?> event = event(0);
        docker("stop");
        try {
            transaction.executeWithoutResult(status -> {
                EntityManager entityManager = SharedEntityManagerCreator.createSharedEntityManager(
                        application.getBean(EntityManagerFactory.class));
                entityManager.persist(new BusinessRecord(event.eventId()));
                writer.write("Product", topic, event);
            });
            assertThat(count("outbox_test_business")).isEqualTo(1);
            assertThat(unpublished()).isEqualTo(1);

            relay.publishPending();
            assertThat(unpublished()).isEqualTo(1);
            assertThat(count("publication_audit")).isZero();
        } finally {
            docker("start");
            awaitBroker();
        }

        relay.publishPending();

        assertThat(unpublished()).isZero();
        List<ConsumerRecord<String, String>> records = consumeAll();
        assertThat(records).hasSize(1);
        assertThat(MAPPER.readTree(records.getFirst().value())).isEqualTo(MAPPER.valueToTree(event));
        assertThat(records.getFirst().key()).isEqualTo(event.aggregateId());
        assertThat(count("publication_audit")).isEqualTo(1);
    }

    @Test
    void twoInstancesClaimDisjointBatchesAndPublishExactly200RecordsWithoutFailures() throws Exception {
        List<EventEnvelope<?>> events = writeEvents(200);
        try (ConfigurableApplicationContext second = startApplication(Map.of());
             var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch bothClaimed = new CountDownLatch(2);
            AtomicInteger firstSends = new AtomicInteger();
            AtomicInteger secondSends = new AtomicInteger();
            OutboxRelay firstRelay = synchronizedRelay(application, bothClaimed, firstSends);
            OutboxRelay secondRelay = synchronizedRelay(second, bothClaimed, secondSends);

            var firstRun = pool.submit(firstRelay::publishPending);
            var secondRun = pool.submit(secondRelay::publishPending);
            firstRun.get(45, TimeUnit.SECONDS);
            secondRun.get(45, TimeUnit.SECONDS);

            assertThat(firstSends.get()).isEqualTo(100);
            assertThat(secondSends.get()).isEqualTo(100);
        }

        assertThat(unpublished()).isZero();
        assertThat(count("publication_audit")).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT event_id FROM publication_audit GROUP BY event_id HAVING count(*) <> 1"))
                .isEmpty();
        relay.publishPending();
        List<ConsumerRecord<String, String>> records = consumeAll();
        assertThat(records).hasSize(200);
        assertThat(records.stream().map(record -> MAPPER.readTree(record.value()).get("eventId").asString()))
                .containsExactlyInAnyOrderElementsOf(events.stream().map(event -> event.eventId().toString()).toList());
    }

    @Test
    void commitsEarlierAcknowledgementsWhenALaterSendFailsAndRetriesOnlyTheRemainingRows() {
        List<EventEnvelope<?>> events = writeEvents(3);
        KafkaTemplate<String, EventEnvelope<?>> kafka = spy(eventTemplate(application));
        AtomicInteger sends = new AtomicInteger();
        doAnswer(invocation -> sends.incrementAndGet() == 2
                ? CompletableFuture.failedFuture(new IllegalStateException("simulated publish failure"))
                : invocation.callRealMethod()).when(kafka).send(anyString(), anyString(), any(EventEnvelope.class));
        OutboxRelay failingRelay = new OutboxRelay(jdbc, MAPPER, kafka,
                application.getBean(PlatformTransactionManager.class), Duration.ofSeconds(5));

        failingRelay.publishPending();

        assertThat(unpublished()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox WHERE id = ?",
                Boolean.class, events.getFirst().eventId())).isTrue();
        assertThat(count("publication_audit")).isEqualTo(1);

        relay.publishPending();
        assertThat(unpublished()).isZero();
        assertThat(count("publication_audit")).isEqualTo(3);
        assertThat(consumeAll()).hasSize(3);
    }

    @Test
    void deliveryCanRepeatAfterADatabaseFailureButKeepsTheOriginalEventId() {
        EventEnvelope<?> event = writeEvents(1).getFirst();
        JdbcTemplate brokenDatabase = spy(jdbc);
        doThrow(new DataAccessResourceFailureException("simulated failure after Kafka acknowledgement"))
                .when(brokenDatabase).update(eq(OutboxRelay.MARK_PUBLISHED_SQL), any(UUID.class));
        OutboxRelay failingRelay = new OutboxRelay(brokenDatabase, MAPPER, eventTemplate(application),
                application.getBean(PlatformTransactionManager.class), Duration.ofSeconds(5));

        assertThatThrownBy(failingRelay::publishPending).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(unpublished()).isEqualTo(1);

        relay.publishPending();

        assertThat(unpublished()).isZero();
        assertThat(count("publication_audit")).isEqualTo(1);
        List<ConsumerRecord<String, String>> records = consumeAll();
        assertThat(records).hasSize(2);
        assertThat(records).allSatisfy(record -> assertThat(MAPPER.readTree(record.value()).get("eventId").asString())
                .isEqualTo(event.eventId().toString()));
    }

    @Test
    void actualScheduledTaskPublishesWithoutAnExplicitRelayCall() throws Exception {
        writeEvents(1);
        try (ConfigurableApplicationContext scheduled = startApplication(Map.of(
                "nexus.outbox.relay.initial-delay", "10", "nexus.outbox.relay.fixed-delay", "100"))) {
            assertThat(scheduled.getBean(OutboxRelay.class)).isNotNull();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (unpublished() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(unpublished()).isZero();
        }
        assertThat(consumeAll()).hasSize(1);
    }

    @Test
    void explainUsesThePartialIndexForFiveUnpublishedRowsOutOfTenThousand() throws Exception {
        jdbc.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, topic, payload, published_at)
                SELECT uuidv7(), 'Product', 'old-' || n, 'ProductCreated', ?, '{}'::jsonb, now()
                FROM generate_series(1, 9995) AS n
                """, topic);
        writeEvents(5);
        jdbc.execute("ANALYZE outbox");

        String plan = transaction.execute(status -> jdbc.queryForObject(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + OutboxRelay.CLAIM_SQL, String.class));

        assertThat(count("outbox")).isEqualTo(10000);
        assertThat(unpublished()).isEqualTo(5);
        assertThat(plan).contains("idx_outbox_unpublished", "LockRows", "Index Scan");
        Files.writeString(Path.of("target/outbox-explain.json"), plan);
        System.out.println("OUTBOX_EXPLAIN: " + plan);
    }

    private List<EventEnvelope<?>> writeEvents(int count) {
        List<? extends EventEnvelope<?>> events = IntStream.range(0, count).mapToObj(this::event).toList();
        transaction.executeWithoutResult(status -> events.forEach(event
                -> writer.write("Product", topic, event)));
        return (List<EventEnvelope<?>>) events;
    }

    private EventEnvelope<?> event(int sequence) {
        return new EventEnvelope<>(UUID.randomUUID(), "ProductCreated", "product-" + sequence,
                Instant.parse("2026-10-20T08:00:00Z").plusMillis(sequence),
                "4bf92f3577b34da6a3ce929d0e0e4736", Map.of("name", "Phone " + sequence));
    }

    private long unpublished() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private List<ConsumerRecord<String, String>> consumeAll() {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", requiredEnv("OUTBOX_TEST_KAFKA_BOOTSTRAP_SERVERS"),
                "enable.auto.commit", "false"), new StringDeserializer(), new StringDeserializer())) {
            List<TopicPartition> partitions = IntStream.range(0, 3).mapToObj(n -> new TopicPartition(topic, n)).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, Duration.ofSeconds(10));
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (partitions.stream().anyMatch(partition -> consumer.position(partition) < ends.get(partition))) {
                assertThat(System.nanoTime()).as("consumer must reach the actual end offsets").isLessThan(deadline);
                consumer.poll(Duration.ofMillis(100)).forEach(records::add);
            }
            return records;
        }
    }

    private static OutboxRelay synchronizedRelay(ConfigurableApplicationContext context, CountDownLatch bothClaimed,
                                                 AtomicInteger sends) {
        KafkaTemplate<String, EventEnvelope<?>> kafka = spy(eventTemplate(context));
        doAnswer(invocation -> {
            if (sends.incrementAndGet() == 1) {
                bothClaimed.countDown();
                assertThat(bothClaimed.await(10, TimeUnit.SECONDS))
                        .as("both instances must hold their database row claims before sending").isTrue();
            }
            return invocation.callRealMethod();
        }).when(kafka).send(anyString(), anyString(), any(EventEnvelope.class));
        return new OutboxRelay(context.getBean(JdbcTemplate.class), MAPPER, kafka,
                context.getBean(PlatformTransactionManager.class), Duration.ofSeconds(5));
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, EventEnvelope<?>> eventTemplate(ConfigurableApplicationContext context) {
        return context.getBean(EventKafka.TEMPLATE, KafkaTemplate.class);
    }

    private static ConfigurableApplicationContext startApplication(Map<String, Object> overrides) {
        Map<String, Object> properties = new HashMap<>(Map.of(
                "spring.datasource.url", requiredEnv("OUTBOX_TEST_JDBC_URL"),
                "spring.datasource.username", requiredEnv("OUTBOX_TEST_DB_USERNAME"),
                "spring.datasource.password", requiredEnv("OUTBOX_TEST_DB_PASSWORD"),
                "spring.kafka.bootstrap-servers", requiredEnv("OUTBOX_TEST_KAFKA_BOOTSTRAP_SERVERS"),
                "spring.kafka.producer.acks", "all",
                "spring.kafka.producer.properties[enable.idempotence]", "true",
                "spring.kafka.producer.properties[request.timeout.ms]", "1000",
                "spring.kafka.producer.properties[delivery.timeout.ms]", "3000",
                "spring.kafka.producer.properties[max.block.ms]", "1000",
                "nexus.outbox.relay.initial-delay", "3600000"));
        properties.put("nexus.outbox.relay.send-timeout", "5s");
        properties.put("spring.jpa.hibernate.ddl-auto", "none");
        properties.put("spring.main.banner-mode", "off");
        properties.put("management.tracing.enabled", "false");
        properties.put("management.tracing.export.enabled", "false");
        properties.put("logging.level.root", "WARN");
        properties.putAll(overrides);
        return new SpringApplicationBuilder(TestApplication.class).web(WebApplicationType.NONE).properties(properties).run();
    }

    private static String outboxDdl(String service) throws Exception {
        String migration = Files.readString(Path.of("../../" + service + "-service/src/main/resources/db/migration/V1__init.sql"));
        return migration.substring(migration.indexOf("CREATE TABLE outbox ("), migration.indexOf("CREATE TABLE processed_events ("))
                .strip();
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing " + name + "; run common-outbox/verify-integration.sh");
        }
        return value;
    }

    private static void docker(String action) throws Exception {
        String container = requiredEnv("OUTBOX_TEST_KAFKA_CONTAINER");
        assertThat(container).startsWith("nexus-outbox-test-");
        Process process = new ProcessBuilder("docker", action, container).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("docker " + action).isTrue();
            assertThat(process.exitValue()).as("docker " + action + " exit code").isZero();
        } finally {
            process.destroyForcibly();
        }
    }

    private static void awaitBroker() throws Exception {
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                admin.describeCluster().nodes().get(2, TimeUnit.SECONDS);
                return;
            } catch (Exception ignored) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("Disposable Kafka did not restart");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = BusinessRecord.class)
    static class TestApplication {
    }

    @Entity
    @Table(name = "outbox_test_business")
    static class BusinessRecord {
        @Id
        private UUID id;

        protected BusinessRecord() {
        }

        BusinessRecord(UUID id) {
            this.id = id;
        }
    }
}
