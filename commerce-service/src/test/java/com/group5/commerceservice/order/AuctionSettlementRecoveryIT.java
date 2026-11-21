package com.group5.commerceservice.order;

import com.group5.common_events.AuctionWon;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.Topics;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the packaged production applications in separate JVMs, with a real Kafka broker
 * and independent disposable databases. No application consumer or publisher is mocked.
 * Package auction-service before commerce-service verify (see the recovery runbook).
 */
class AuctionSettlementRecoveryIT {
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final PostgreSQLContainer COMMERCE_DB = postgres("commerce_recovery");
    private static final PostgreSQLContainer AUCTION_DB = postgres("auction_recovery");
    private static final KafkaContainer KAFKA = new KafkaContainer(
            System.getProperty("settlement.test.kafka-image", "apache/kafka:4.3.1"))
            .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m")
            .withStartupTimeout(Duration.ofSeconds(120));
    private static final Path BACKEND = Path.of("..").toAbsolutePath().normalize();
    private static final Path EVIDENCE = Path.of("target", "settlement-recovery-evidence").toAbsolutePath();
    private static AdminClient admin;
    private static KafkaProducer<String, String> producer;
    private final List<Process> processes = new ArrayList<>();
    private final List<Map<String, Object>> steps = new ArrayList<>();
    private JdbcTemplate commerce;
    private JdbcTemplate auction;
    private String schema;
    private Process commerceProcess;
    private Process auctionProcess;
    private AuctionWon won;

    private static PostgreSQLContainer postgres(String database) {
        return new PostgreSQLContainer("postgres:18-alpine").withDatabaseName(database)
                .withEnv("TZ", "UTC").withUrlParam("options", "-c timezone=UTC");
    }

    @BeforeAll
    static void infrastructure() throws Exception {
        Files.createDirectories(EVIDENCE);
        assertPackaged("auction-service");
        assertPackaged("commerce-service");
        COMMERCE_DB.start();
        AUCTION_DB.start();
        KAFKA.start();
        admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()));
        var topics = List.of(Topics.USER, Topics.CATALOG, Topics.COMMERCE, Topics.AUCTION,
                Topics.AUCTION_BIDS, Topics.INVENTORY, Topics.PAYMENT, Topics.SHIPMENT, Topics.RATING);
        admin.createTopics(topics.stream().map(topic -> new NewTopic(topic, 1, (short) 1)).toList())
                .all().get(30, TimeUnit.SECONDS);
        producer = new KafkaProducer<>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "acks", "all"), new StringSerializer(), new StringSerializer());
    }

    @BeforeEach
    void independentFixture() throws Exception {
        schema = "recovery_" + UUID.randomUUID().toString().replace("-", "");
        commerce = jdbc(COMMERCE_DB);
        auction = jdbc(AUCTION_DB);
        won = new AuctionWon(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "900000.00", "VND", "Recovery fixture",
                "https://example.invalid/recovery.png", 1, Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(86400));
        auctionProcess = start("auction-service", false);
        auction.update("""
                INSERT INTO auctions(id,product_id,sku_id,seller_id,title,starting_price,bid_increment,
                  current_price,start_at,end_at,original_end_at,status,winner_id,final_price,payment_due_at)
                VALUES(?,?,?,?,?,100,10,900000,now()-interval '1 hour',now()-interval '1 minute',
                  now()-interval '1 minute','WON',?,900000,now()+interval '1 day')
                """, won.auctionId(), won.productId(), won.skuId(), won.sellerId(), won.title(), won.winnerId());
        steps.add(Map.of("step", "fixture", "auctionId", won.auctionId().toString(), "schema", schema));
    }

    @Test
    void committedOrderSurvivesCommerceKillBeforeOrderCreatedPublication() throws Exception {
        // A one-hour initial relay delay is a deterministic gate, not a mock of publication.
        commerceProcess = start("commerce-service", true);
        sendWon();
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            assertThat(orderCount()).isEqualTo(1);
            assertThat(outboxCount("published_at IS NULL")).isEqualTo(1);
        });
        assertThat(orderCreatedRecords()).isZero();
        assertThat(status()).isEqualTo("WON");
        snapshot("committed-before-kill");
        kill(commerceProcess);
        assertThat(commerceProcess.isAlive()).isFalse();
        assertThat(orderCount()).isEqualTo(1);
        snapshot("commerce-forcefully-terminated");

        commerceProcess = start("commerce-service", false);
        assertRecovered();
        writeEvidence("commerce-crash-before-publish");
    }

    @Test
    void auctionWonDeliveredThreeTimesCreatesOneOrderAndOneOrderCreated() throws Exception {
        commerceProcess = start("commerce-service", false);
        EventEnvelope<AuctionWon> envelope = envelope();
        long lastOffset = -1;
        for (int delivery = 0; delivery < 3; delivery++) {
            lastOffset = send(Topics.AUCTION, won.auctionId().toString(), envelope);
        }
        awaitCommitted("commerce-auction-won", Topics.AUCTION, lastOffset + 1);
        assertRecovered();
        assertThat(commerce.queryForObject("SELECT count(*) FROM processed_events WHERE event_id=? AND consumer=?",
                Long.class, envelope.eventId(), "commerce-auction-won")).isEqualTo(1);
        steps.add(Map.of("step", "three-broker-deliveries-consumed", "eventId", envelope.eventId().toString(),
                "lastKafkaOffset", lastOffset));
        writeEvidence("auction-won-delivered-three-times");
    }

    @Test
    void auctionRestartConsumesOrderCreatedPublishedWhileItWasStopped() throws Exception {
        // Establish a real committed offset for the durable settlement consumer before stopping it.
        UUID warmupOrder = UUID.randomUUID();
        long warmup = send(Topics.COMMERCE, warmupOrder.toString(), new EventEnvelope<>(UUID.randomUUID(),
                "OrderCreated", warmupOrder.toString(), Instant.now(), "recovery-warmup",
                Map.of("orderType", "STANDARD")));
        awaitCommitted("auction-order-created-settlement", Topics.COMMERCE, warmup + 1);
        kill(auctionProcess);
        commerceProcess = start("commerce-service", false);
        sendWon();
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            assertThat(orderCount()).isEqualTo(1);
            assertThat(outboxCount("published_at IS NOT NULL")).isEqualTo(1);
        });
        assertThat(orderCreatedRecords()).isEqualTo(1);
        assertThat(status()).isEqualTo("WON");
        assertThat(auctionProcess.isAlive()).isFalse();
        snapshot("order-created-on-broker-auction-offline");

        auctionProcess = start("auction-service", false);
        assertRecovered();
        writeEvidence("auction-offline-during-order-created");
    }

    private void assertRecovered() throws Exception {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(status()).isEqualTo("SETTLED");
            assertThat(orderCount()).isEqualTo(1);
            assertThat(outboxCount("published_at IS NOT NULL")).isEqualTo(1);
        });
        UUID order = commerce.queryForObject("SELECT id FROM orders WHERE reference_id=?", UUID.class, won.auctionId());
        assertThat(auction.queryForObject("SELECT order_id FROM auctions WHERE id=?", UUID.class, won.auctionId()))
                .isEqualTo(order);
        assertThat(auction.queryForObject("SELECT count(*) FROM outbox WHERE event_type='AuctionSettled' AND aggregate_id=?",
                Long.class, won.auctionId().toString())).isEqualTo(1);
        assertThat(orderCreatedRecords()).isEqualTo(1);
        snapshot("recovered");
    }

    private JdbcTemplate jdbc(PostgreSQLContainer database) {
        return new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl() + "&currentSchema=" + schema,
                database.getUsername(), database.getPassword()));
    }

    private Process start(String service, boolean gateRelay) throws Exception {
        var database = service.equals("commerce-service") ? COMMERCE_DB : AUCTION_DB;
        Path log = EVIDENCE.resolve(schema + "-" + service + "-" + processes.size() + ".log");
        Path config = BACKEND.resolve("config-service/src/main/resources/config");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString(),
                "-Xms64m", "-Xmx384m", "-XX:ActiveProcessorCount=2", "-Duser.timezone=UTC", "-jar",
                artifact(service).toString(),
                "--spring.application.name=" + service,
                "--spring.config.location=" + config.resolve("application.yml").toUri() + "," + config.resolve(service + ".yml").toUri(),
                "--server.port=0", "--server.shutdown=immediate",
                "--spring.datasource.url=" + database.getJdbcUrl() + "&currentSchema=" + schema,
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword(),
                "--spring.flyway.schemas=" + schema, "--spring.flyway.default-schema=" + schema,
                "--spring.cloud.config.enabled=false", "--spring.cloud.config.import-check.enabled=false",
                "--spring.cloud.discovery.enabled=false", "--eureka.client.enabled=false",
                "--spring.cloud.compatibility-verifier.enabled=false",
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--spring.kafka.consumer.auto-offset-reset=earliest", "--spring.kafka.listener.ack-mode=record",
                "--spring.datasource.hikari.maximum-pool-size=4",
                "--management.tracing.export.zipkin.enabled=false",
                "--nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/certs",
                "--nexus.auction.scheduler.enabled=false", "--nexus.outbox.relay.enabled=true",
                "--nexus.outbox.relay.initial-delay=" + (gateRelay ? "3600000" : "50"),
                "--nexus.outbox.relay.fixed-delay=100"));
        Process process = new ProcessBuilder(command).directory(BACKEND.resolve(service).toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        processes.add(process);
        String started = service.equals("commerce-service") ? "Started CommerceServiceApplication" : "Started AuctionServiceApplication";
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            assertThat(process.isAlive()).as("%s exited; inspect %s", service, log).isTrue();
            assertThat(Files.readString(log)).as("startup log %s", log).contains(started);
        });
        steps.add(Map.of("step", "started", "service", service, "pid", process.pid(),
                "relayInitiallyGated", gateRelay, "log", log.getFileName().toString()));
        return process;
    }

    private static boolean isWindows() { return File.separatorChar == '\\'; }

    private static Path artifact(String service) {
        return BACKEND.resolve(service).resolve("target/" + service + "-0.0.1-SNAPSHOT.jar");
    }

    private static void assertPackaged(String service) throws Exception {
        Path artifact = artifact(service);
        assertThat(Files.isRegularFile(artifact)).as("Package %s first with ./mvnw -DskipTests package; this test starts production JARs", service).isTrue();
        try (var files = Files.walk(BACKEND.resolve(service).resolve("src/main"))) {
            long newestSource = files.filter(Files::isRegularFile).mapToLong(path -> {
                try { return Files.getLastModifiedTime(path).toMillis(); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }).max().orElse(0);
            assertThat(Files.getLastModifiedTime(artifact).toMillis())
                    .as("%s JAR must include the current production sources; repackage before this test", service)
                    .isGreaterThanOrEqualTo(newestSource);
        }
    }

    private EventEnvelope<AuctionWon> envelope() {
        return new EventEnvelope<>(UUID.randomUUID(), "AuctionWon", won.auctionId().toString(),
                Instant.now(), "settlement-recovery-166", won);
    }

    private void sendWon() throws Exception { send(Topics.AUCTION, won.auctionId().toString(), envelope()); }

    private long send(String topic, String key, Object envelope) throws Exception {
        return producer.send(new ProducerRecord<>(topic, key, JSON.writeValueAsString(envelope)))
                .get(20, TimeUnit.SECONDS).offset();
    }

    private void awaitCommitted(String group, String topic, long nextOffset) {
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            assertThat(offsets.get(new TopicPartition(topic, 0))).isNotNull();
            assertThat(offsets.get(new TopicPartition(topic, 0)).offset()).isGreaterThanOrEqualTo(nextOffset);
        });
    }

    private long orderCreatedRecords() {
        try (var consumer = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "enable.auto.commit", "false"), new StringDeserializer(), new StringDeserializer())) {
            TopicPartition partition = new TopicPartition(Topics.COMMERCE, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long end = consumer.endOffsets(List.of(partition)).get(partition);
            long found = 0;
            Instant deadline = Instant.now().plusSeconds(20);
            while (consumer.position(partition) < end && Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    var event = JSON.readTree(record.value());
                    if ("OrderCreated".equals(event.path("eventType").asString())
                            && won.auctionId().toString().equals(event.path("payload").path("referenceId").asString())) found++;
                }
            }
            assertThat(consumer.position(partition)).as("Broker evidence must read through the captured end offset").isGreaterThanOrEqualTo(end);
            return found;
        }
    }

    private long orderCount() {
        return commerce.queryForObject("SELECT count(*) FROM orders WHERE reference_id=?", Long.class, won.auctionId());
    }

    private long outboxCount(String publicationPredicate) {
        return commerce.queryForObject("SELECT count(*) FROM outbox WHERE event_type='OrderCreated' AND "
                + publicationPredicate + " AND payload->'payload'->>'referenceId'=?", Long.class, won.auctionId().toString());
    }

    private String status() {
        return auction.queryForObject("SELECT status::text FROM auctions WHERE id=?", String.class, won.auctionId());
    }

    private void snapshot(String step) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("step", step);
        result.put("auctionId", won.auctionId().toString());
        result.put("orderCount", orderCount());
        result.put("pendingOrderCreated", outboxCount("published_at IS NULL"));
        result.put("publishedOrderCreated", outboxCount("published_at IS NOT NULL"));
        result.put("auction", auction.queryForMap("SELECT id::text,status::text,order_id::text,settled_at::text FROM auctions WHERE id=?", won.auctionId()));
        steps.add(result);
    }

    private void writeEvidence(String scenario) throws Exception {
        Files.writeString(EVIDENCE.resolve(scenario + ".json"), JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("scenario", scenario, "steps", steps)));
    }

    private void kill(Process process) throws Exception {
        process.destroyForcibly();
        assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("Force-killed application exits").isTrue();
        steps.add(Map.of("step", "forcefully-killed", "pid", process.pid(), "exitCode", process.exitValue()));
    }

    @AfterEach
    void stopApplications() throws Exception {
        for (Process process : processes) if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(20, TimeUnit.SECONDS);
        }
        // Retain partial steps on failure as well; no local project database was touched.
        if (schema != null) Files.writeString(EVIDENCE.resolve(schema + "-steps.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(steps));
    }

    @AfterAll
    static void stopInfrastructure() {
        if (producer != null) producer.close(Duration.ofSeconds(5));
        if (admin != null) admin.close(Duration.ofSeconds(5));
        KAFKA.stop();
        AUCTION_DB.stop();
        COMMERCE_DB.stop();
    }
}
