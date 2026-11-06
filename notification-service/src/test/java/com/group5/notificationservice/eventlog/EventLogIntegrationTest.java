package com.group5.notificationservice.eventlog;

import com.group5.common_events.Topics;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

/** Real broker -> production consumer -> migrated PostgreSQL -> HTTP + RS256/JWKS.
 * Identities are test keys/claims, not a live Keycloak login. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/event-log-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "spring.kafka.admin.auto-create=false",
        "spring.kafka.consumer.auto-offset-reset=earliest", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false", "nexus.notification.group-id=notification-domain-test",
        "nexus.event-log.group-id=event-log-integration", "nexus.outbox.relay.enabled=false",
        "nexus.security.jwt.issuer=event-log-test", "nexus.security.jwt.audience=nexus-services",
        "management.tracing.enabled=false"
})
class EventLogIntegrationTest {
    private static final List<String> TOPICS = List.of(Topics.USER, Topics.CATALOG, Topics.COMMERCE,
            Topics.PAYMENT, Topics.INVENTORY, Topics.SHIPMENT, Topics.RATING, Topics.AUCTION);
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");
    private static final UUID ACTOR = UUID.randomUUID();
    private static final RSAKey KEY;
    private static final HttpServer JWKS;
    static {
        try {
            POSTGRES.start(); KAFKA.start();
            try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
                admin.createTopics(TOPICS.stream().map(t -> new NewTopic(t, 1, (short) 1)).toList()).all().get(30, TimeUnit.SECONDS);
            }
            KEY = new RSAKeyGenerator(2048).keyID("event-log-test").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body); exchange.close();
            });
            JWKS.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("nexus.security.jwt.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @MockitoSpyBean EventLogRecorder recorder;
    @Autowired PlatformTransactionManager transactions;
    @Autowired KafkaListenerEndpointRegistry listeners;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach void ready() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (listeners.getListenerContainers().stream().filter(c -> c.getGroupId().equals("event-log-integration"))
                .anyMatch(c -> c.getAssignedPartitions() == null || c.getAssignedPartitions().size() != 8)) {
            if (System.nanoTime() > deadline) throw new AssertionError("Event log listener did not acquire all 8 topics");
            Thread.sleep(50);
        }
        jdbc.update("DELETE FROM event_logs");
        jdbc.update("DELETE FROM event_log_rejections");
    }

    @AfterAll static void stop() { JWKS.stop(0); }

    @Test void handPublishedCategoryIsVisibleWithinFiveSecondsAndDuplicateKeepsOriginalEnvelope() throws Exception {
        String raw = envelope("CategoryCreated", "category-192", "{\"name\":\"Bàn 192\",\"custom\":{\"price\":\"1.20\"}}");
        String eventId = mapper.readTree(raw).get("eventId").asText();
        long started = System.nanoTime();
        publish(Topics.CATALOG, raw);
        JsonNode list = awaitEvent(eventId, started + Duration.ofSeconds(5).toNanos());
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsed).isLessThan(5000);
        assertThat(list.get("page").get("totalElements").asInt()).isEqualTo(1);
        String id = list.get("data").get(0).get("id").asText();
        assertThat(list.get("data").get(0).get("eventId").asText()).isEqualTo(eventId);
        publish(Topics.CATALOG, raw);
        // A later marker in the same partition proves the duplicate has been consumed.
        String marker = envelope("ProbeCompleted", "category-192", "{}");
        publish(Topics.CATALOG, marker);
        awaitEvent(mapper.readTree(marker).get("eventId").asText(), System.nanoTime() + Duration.ofSeconds(5).toNanos());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_logs WHERE event_id = ?", Long.class, UUID.fromString(eventId))).isEqualTo(1);
        JsonNode detail = json(get("/api/v1/event-logs/" + id, "support", "EVENT_LOG.VIEW_DETAIL"));
        assertThat(detail.get("data").get("rawEnvelope").asText()).isEqualTo(raw);
        assertThat(detail.get("data").get("envelope")).isEqualTo(mapper.readTree(raw));
        assertThat(detail.get("data").get("payload")).isEqualTo(mapper.readTree(raw).get("payload"));
        System.out.println("ISSUE192 CategoryCreated visible in " + elapsed + "ms; duplicate count=1; envelope preserved");
    }

    @Test void allEightTopicsIncludingUnknownTypesAreRecorded() throws Exception {
        for (String topic : TOPICS) {
            String raw = envelope("FutureEvent", topic, "{\"flag\":true}");
            publish(topic, raw);
            awaitEvent(mapper.readTree(raw).get("eventId").asText(), System.nanoTime() + Duration.ofSeconds(5).toNanos());
        }
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT source_topic) FROM event_logs", Integer.class)).isEqualTo(8);
    }

    @Test void invalidRecordsAreRetainedAndDoNotBlockTheNextRecordOrOffsetCommit() throws Exception {
        String valid = envelope("CategoryCreated", "after-invalid", "{}");
        var invalid = new ArrayList<String>();
        invalid.add(valid.replace("\"traceId\":\"issue192-trace\",", ""));
        invalid.add("{broken-json");
        invalid.add(valid.replace(mapper.readTree(valid).get("eventId").asText(), "invalid-uuid"));
        invalid.add(valid.replace("2026-11-04T00:00:00Z", "invalid-time"));
        invalid.add(valid.replace("\"payload\":{}", "\"payload\":null"));
        invalid.add(valid.replace("issue192-trace", "x".repeat(65)));
        invalid.add(null); // Tombstones must not block later events either.
        var positions = new ArrayList<RecordMetadata>();
        for (String raw : invalid) positions.add(publish(Topics.CATALOG, raw));
        RecordMetadata last = publish(Topics.CATALOG, valid);
        awaitEvent(mapper.readTree(valid).get("eventId").asText(), System.nanoTime() + Duration.ofSeconds(5).toNanos());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log_rejections", Integer.class)).isEqualTo(invalid.size());
        for (int i = 0; i < invalid.size(); i++) {
            var position = positions.get(i);
            var rejected = jdbc.queryForMap("""
                    SELECT record_key, raw_envelope, failure_type FROM event_log_rejections
                    WHERE source_topic = ? AND source_partition = ? AND source_offset = ?
                    """, position.topic(), position.partition(), position.offset());
            assertThat(rejected.get("raw_envelope")).isEqualTo(invalid.get(i));
            assertThat(rejected.get("record_key")).isEqualTo("issue192");
            assertThat(rejected.get("failure_type")).isNotNull();
            // A recovery repeated after an offset-commit crash preserves the first row.
            recorder.reject(position.topic(), position.partition(), position.offset(), "different-key", "changed", "changed");
            assertThat(jdbc.queryForMap("SELECT record_key, raw_envelope, failure_type FROM event_log_rejections WHERE source_topic = ? AND source_partition = ? AND source_offset = ?",
                    position.topic(), position.partition(), position.offset())).isEqualTo(rejected);
        }
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            var partition = new org.apache.kafka.common.TopicPartition(last.topic(), last.partition());
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                var offset = admin.listConsumerGroupOffsets("event-log-integration").partitionsToOffsetAndMetadata().get(2, TimeUnit.SECONDS).get(partition);
                assertThat(offset).isNotNull();
                assertThat(offset.offset()).isGreaterThan(last.offset());
            });
        }
        System.out.println("ISSUE192_REVIEW invalid JSON/envelopes retained; next valid record visible and Kafka offset advanced");
    }

    @Test void databaseFailureRetriesInsteadOfRejectingAValidEvent() throws Exception {
        String raw = envelope("CategoryCreated", "database-retry", "{}");
        doThrow(new TransientDataAccessResourceException("simulated database outage"))
                .doThrow(new TransientDataAccessResourceException("simulated database outage"))
                .doCallRealMethod().when(recorder).recordRaw(raw, Topics.CATALOG);
        publish(Topics.CATALOG, raw);
        awaitEvent(mapper.readTree(raw).get("eventId").asText(), System.nanoTime() + Duration.ofSeconds(10).toNanos());
        verify(recorder, atLeast(3)).recordRaw(raw, Topics.CATALOG);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log_rejections", Integer.class)).isZero();
    }

    @Test void failedRejectionWriteMustRecoverBeforeThePartitionContinues() throws Exception {
        String invalid = envelope("CategoryCreated", "reject-write-failure", "{}").replace("\"traceId\":\"issue192-trace\",", "");
        doThrow(new TransientDataAccessResourceException("simulated rejection storage outage"))
                .doCallRealMethod().when(recorder).reject(eq(Topics.CATALOG), anyInt(), anyLong(), eq("issue192"), eq(invalid), anyString());
        RecordMetadata position = publish(Topics.CATALOG, invalid);
        String valid = envelope("CategoryCreated", "after-rejection-retry", "{}");
        publish(Topics.CATALOG, valid);
        awaitEvent(mapper.readTree(valid).get("eventId").asText(), System.nanoTime() + Duration.ofSeconds(10).toNanos());
        verify(recorder, atLeast(2)).reject(eq(Topics.CATALOG), eq(position.partition()), eq(position.offset()), eq("issue192"), eq(invalid), anyString());
        assertThat(jdbc.queryForObject("SELECT raw_envelope FROM event_log_rejections WHERE source_topic = ? AND source_partition = ? AND source_offset = ?",
                String.class, position.topic(), position.partition(), position.offset())).isEqualTo(invalid);
    }

    @Test void criticalActionsUseActorAndOriginalEntityAndConfigCanBeRecordedWithoutKafka() throws Exception {
        for (String type : List.of("UserUpdated", "UserDeleted", "UserRoleAssigned", "OrderRefundRequested", "OrderRefundCompleted", "SystemConfigUpdated")) {
            if (type.equals("SystemConfigUpdated")) {
                recorder.record(new com.group5.common_events.EventEnvelope<>(UUID.randomUUID(), type,
                        "entity-192", Instant.parse("2026-11-04T00:00:00Z"), "issue192-trace",
                        mapper.readTree("{\"actorId\":\"" + ACTOR + "\",\"newValue\":false}")));
            } else {
                recorder.recordRaw(envelope(type, "entity-192", "{\"actorId\":\"" + ACTOR + "\"}"), null);
            }
        }
        recorder.recordRaw(envelope("CategoryCreated", "entity-192", "{}"), Topics.CATALOG);
        JsonNode all = json(get("/api/v1/audit-logs", "admin", "AUDIT_LOG.VIEW"));
        assertThat(all.get("page").get("totalElements").asInt()).isEqualTo(6);
        for (String[] filter : List.of(new String[]{"actorId", ACTOR.toString()},new String[]{"entityId","entity-192"},
                new String[]{"from","2020-01-01T00:00:00Z"}, new String[]{"to","2099-01-01T00:00:00Z"})) {
            assertThat(json(get("/api/v1/audit-logs?"+filter[0]+"="+filter[1],"admin","AUDIT_LOG.VIEW")).get("page").get("totalElements").asInt()).isEqualTo(6);
        }
        JsonNode filtered = json(get("/api/v1/audit-logs?action=OrderRefundCompleted&entityType=Order&actorId="+ACTOR, "admin", "AUDIT_LOG.VIEW"));
        assertThat(filtered.get("data").size()).isEqualTo(1);
        assertThat(filtered.get("data").get(0).get("entityId").asText()).isEqualTo("entity-192");
    }

    @Test void rollbackAndConcurrentDuplicateCannotLeavePartialOrDuplicateRows() throws Exception {
        String raw = envelope("UserUpdated", "rollback-192", "{}");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            recorder.recordRaw(raw, null); status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_logs", Integer.class)).isZero();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = new ArrayList<Future<Boolean>>();
            for (int i=0;i<10;i++) jobs.add(executor.submit(() -> recorder.recordRaw(raw, null)));
            int writes = 0;
            for (var job : jobs) if (job.get(10, TimeUnit.SECONDS)) writes++;
            assertThat(writes).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isEqualTo(1);
    }

    @Test void searchFiltersPagingAndValidationAreAppliedByRealHttpEndpoints() throws Exception {
        recorder.recordRaw(envelope("UserUpdated", "a", "{\"actorId\":\""+ACTOR+"\",\"note\":\"literal_%\"}"),Topics.USER);
        recorder.recordRaw(envelope("UserDeleted", "b", "{}"),Topics.USER);
        for (String filter : List.of("q=literal_%25","eventType=UserUpdated","aggregateId=a","operatorId="+ACTOR)) {
            assertThat(json(get("/api/v1/event-logs?"+filter,"support","EVENT_LOG.VIEW")).get("data").size()).isEqualTo(1);
        }
        assertThat(json(get("/api/v1/event-logs?aggregateType=User&size=1&sort=eventType,asc","support","EVENT_LOG.VIEW")).get("data").get(0).get("eventType").asText()).isEqualTo("UserDeleted");
        var beyond = json(get("/api/v1/event-logs?page=5&size=1","support","EVENT_LOG.VIEW"));
        assertThat(beyond.get("data").size()).isZero();
        assertThat(beyond.get("page").get("totalElements").asInt()).isEqualTo(2);
        for (String query : List.of("page=-1","size=101","size=0","sort=payload,asc","from=bad","operatorId=bad","from=2099-01-01T00:00:00Z&to=2020-01-01T00:00:00Z")) {
            var response = get("/api/v1/event-logs?"+query,"support","EVENT_LOG.VIEW");
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json(response).get("code").asText()).isEqualTo("NOTIFICATION_DATA_INVALID");
        }
        var missing = get("/api/v1/event-logs/"+UUID.randomUUID(),"support","EVENT_LOG.VIEW_DETAIL");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(missing).get("code").asText()).isEqualTo("NOTIFICATION_EVENT_LOG_NOT_FOUND");
    }

    @Test void buyerSupportAndDetailPrivilegesAreEnforcedAtServiceToo() throws Exception {
        for (String path : List.of("/api/v1/event-logs","/api/v1/audit-logs","/api/v1/event-logs/"+UUID.randomUUID())) {
            assertThat(get(path,"buyer").statusCode()).isEqualTo(403);
        }
        assertThat(get("/api/v1/event-logs","support","EVENT_LOG.VIEW").statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/audit-logs","support","EVENT_LOG.VIEW","EVENT_LOG.VIEW_DETAIL").statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/event-logs/"+UUID.randomUUID(),"support","EVENT_LOG.VIEW").statusCode()).isEqualTo(403);
    }

    private String envelope(String type, String aggregateId, String payload) {
        return "{ \"eventId\":\""+UUID.randomUUID()+"\",\"eventType\":\""+type+"\",\"aggregateId\":\""+aggregateId+
                "\",\"occurredAt\":\"2026-11-04T00:00:00Z\",\"traceId\":\"issue192-trace\",\"futureField\":7,\"payload\":"+payload+" }";
    }
    private RecordMetadata publish(String topic, String raw) throws Exception {
        try (var producer = new KafkaProducer<String,String>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            return producer.send(new ProducerRecord<>(topic,"issue192",raw)).get(10,TimeUnit.SECONDS);
        }
    }
    private JsonNode awaitEvent(String eventId, long deadline) throws Exception {
        do {
            var response = get("/api/v1/event-logs?q="+eventId,"support","EVENT_LOG.VIEW");
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode body = json(response);
            if (body.get("data").size() == 1) return body;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Event not visible before deadline: " + eventId);
    }
    private HttpResponse<String> get(String path, String role, String... privileges) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("event-log-test").audience("nexus-services")
                .subject(ACTOR.toString()).claim("roles",List.of(role.equals("support") ? "SUPPORT_STAFF" : role.toUpperCase(Locale.ROOT)))
                .claim("privileges",List.of(privileges)).expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),claims);
        jwt.sign(new RSASSASigner(KEY));
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Authorization","Bearer "+jwt.serialize()).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
}
