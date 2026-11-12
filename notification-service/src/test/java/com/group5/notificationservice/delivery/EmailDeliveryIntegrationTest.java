package com.group5.notificationservice.delivery;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.Topics;
import com.group5.notificationservice.eventlog.EventLogRepository;
import com.group5.notificationservice.notification.DomainEventConsumer;
import com.group5.notificationservice.shared.config.DeliveryProperties;
import jakarta.servlet.Filter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/notification-email-delivery-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "nexus.notification.group-id=notification-email-delivery-test", "nexus.outbox.relay.enabled=false",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.export.enabled=false",
        "nexus.notification.email-enabled=true",
        "nexus.notification.delivery.retry-limit=3",
        "nexus.notification.delivery.base-delay=0ms", "nexus.notification.delivery.max-delay=0ms",
        "nexus.notification.delivery.poll-interval=1h",
        "spring.mail.properties.mail.smtp.connectiontimeout=10000",
        "spring.mail.properties.mail.smtp.timeout=10000"
})
class EmailDeliveryIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    private static final GenericContainer<?> MAILPIT =
            new GenericContainer<>("axllent/mailpit:v1.31").withExposedPorts(1025, 8025);
    static {
        POSTGRES.start();
        MAILPIT.start();
    }

    private static final UUID BUYER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final String BUYER_EMAIL = "buyer.one@example.com";
    private static final int CLOSED_PORT = 1;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DomainEventConsumer consumer;
    @Autowired private DeliveryRetryJob job;
    @Autowired private JavaMailSenderImpl mailSender;
    @Autowired private DeliveryService deliveryService;
    @Autowired private DeliveryProperties properties;
    @Autowired private EmailSender sender;
    @Autowired private EventLogRepository eventLogs;
    @MockitoBean private RecipientDirectory recipients;
    @MockitoBean private JwtDecoder decoder;
    private MockMvc mvc;

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    }

    @BeforeEach
    void setup() throws Exception {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM event_logs");
        mailpit("DELETE", "/api/v1/messages");
        mailSender.setPort(MAILPIT.getMappedPort(1025));
        reset(recipients, decoder);
        when(recipients.emailOf(BUYER)).thenReturn(BUYER_EMAIL);
        when(decoder.decode("buyer")).thenReturn(token("buyer", BUYER, List.of("NOTIFICATION.VIEW")));
        when(decoder.decode("stranger")).thenReturn(token("stranger", STRANGER, List.of("NOTIFICATION.VIEW")));
        when(decoder.decode("no-privilege")).thenReturn(token("no-privilege", BUYER, List.of()));
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void orderCreatedQueuesTheEmailWithoutSmtpAndTheJobDeliversItToMailpit() throws Exception {
        UUID notificationId = receiveOrderCreated("NX-20261104-0001");

        Map<String, Object> queued = delivery(notificationId);
        assertThat(queued.get("status")).isEqualTo("PENDING");
        assertThat(queued.get("attempt_count")).isEqualTo(0);
        assertThat(messages()).isEmpty();

        assertThat(job.runOnce()).isEqualTo(1);

        List<JsonNode> inbox = messages();
        assertThat(inbox).hasSize(1);
        assertThat(inbox.getFirst().get("To").get(0).get("Address").asString()).isEqualTo(BUYER_EMAIL);
        assertThat(inbox.getFirst().get("Subject").asString()).isEqualTo("Order placed");
        assertThat(inbox.getFirst().get("Snippet").asString()).contains("NX-20261104-0001");

        Map<String, Object> sent = delivery(notificationId);
        assertThat(sent.get("status")).isEqualTo("SENT");
        assertThat(sent.get("next_attempt_at")).isNull();
        assertThat(attempts(notificationId)).extracting(row -> row.get("status")).containsExactly("SUCCEEDED");
        assertThat(attempts(notificationId).getFirst().get("provider_message_id")).isNotNull();
        assertThat(eventTypes()).containsExactly("NotificationSent");
        EventLogRepository.EventLogDetail detail = eventDetail("NotificationSent");
        assertThat(detail.payload().get("deliveryId").asString()).isEqualTo(sent.get("id").toString());
        assertThat(detail.payload().get("notificationId").asString()).isEqualTo(notificationId.toString());
        assertThat(detail.envelope().get("eventType").asString()).isEqualTo("NotificationSent");
        assertThat(detail.envelope().get("aggregateId").asString()).isEqualTo(sent.get("id").toString());
        assertThat(detail.rawEnvelope()).contains("\"eventType\":\"NotificationSent\"");
        assertThat(job.runOnce()).isZero();
        assertThat(messages()).hasSize(1);
    }

    @Test
    void smtpDownThenBackRetriesOnceAndSendsExactlyOneEmail() throws Exception {
        UUID notificationId = receiveOrderCreated("NX-20261104-0002");

        mailSender.setPort(CLOSED_PORT);
        job.runOnce();
        assertThat(delivery(notificationId).get("status")).isEqualTo("PENDING");
        Map<String, Object> first = attempts(notificationId).getFirst();
        assertThat(first.get("status")).isEqualTo("FAILED");
        assertThat(first.get("error_code")).isEqualTo("SMTP_UNAVAILABLE");
        assertThat(first.get("retryable")).isEqualTo(true);

        mailSender.setPort(MAILPIT.getMappedPort(1025));
        job.runOnce();
        assertThat(delivery(notificationId).get("status")).isEqualTo("SENT");
        assertThat(attempts(notificationId)).extracting(row -> row.get("status"))
                .containsExactly("FAILED", "SUCCEEDED");
        assertThat(messages()).hasSize(1);
        assertThat(eventTypes()).containsExactly("NotificationFailed", "NotificationSent");
        EventLogRepository.EventLogDetail failedEvent = eventDetail("NotificationFailed");
        assertThat(failedEvent.payload().get("errorCode").asString()).isEqualTo("SMTP_UNAVAILABLE");
        assertThat(failedEvent.payload().get("deliveryStatus").asString()).isEqualTo("PENDING");
        assertThat(failedEvent.envelope().get("eventType").asString()).isEqualTo("NotificationFailed");
        assertThat(failedEvent.rawEnvelope()).isNotNull();
    }

    @Test
    void completedOrderReceiptSurvivesDuplicateDeliveryAndSmtpRetryAsHtml() throws Exception {
        UUID orderId = UUID.randomUUID();
        String html = "<!doctype html><html><head><meta charset=\"UTF-8\"></head><body>"
                + "<h1>Receipt NX-204-0001</h1><p>Cà phê &amp; tea</p><p>Total: 125000.00 VND</p></body></html>";
        JsonNode payload = JsonMapper.shared().valueToTree(Map.of("orderId", orderId, "buyerId", BUYER,
                "orderNumber", "NX-204-0001", "receiptHtml", html));
        EventEnvelope<JsonNode> envelope = new EventEnvelope<>(UUID.randomUUID(), "OrderCompleted", orderId.toString(),
                Instant.now(), "4bf92f3577b34da6a3ce929d0e0e4736", payload);
        ConsumerRecord<String, EventEnvelope<JsonNode>> record =
                new ConsumerRecord<>(Topics.COMMERCE, 0, 0L, orderId.toString(), envelope);
        mailSender.setPort(CLOSED_PORT);

        consumer.receive(record);
        consumer.receive(record);

        UUID notificationId = jdbc.queryForObject("SELECT id FROM notifications", UUID.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_deliveries", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT email_html FROM notifications WHERE id = ?", String.class,
                notificationId)).isEqualTo(html);
        assertThat(delivery(notificationId).get("attempt_count")).isEqualTo(0);
        assertThat(messages()).isEmpty();
        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer buyer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].content").value(
                        "Your order NX-204-0001 is complete. Your receipt is ready."))
                .andExpect(jsonPath("$.data[0].emailHtml").doesNotExist());

        assertThat(job.runOnce()).isEqualTo(1);
        assertThat(attempts(notificationId)).extracting(row -> row.get("error_code"))
                .containsExactly("SMTP_UNAVAILABLE");
        mailSender.setPort(MAILPIT.getMappedPort(1025));
        assertThat(job.runOnce()).isEqualTo(1);

        List<JsonNode> inbox = messages();
        assertThat(inbox).hasSize(1);
        JsonNode message = JsonMapper.shared().readTree(mailpit("GET", "/api/v1/message/"
                + inbox.getFirst().get("ID").asString()));
        assertThat(message.get("To").get(0).get("Address").asString()).isEqualTo(BUYER_EMAIL);
        assertThat(message.get("Subject").asString()).isEqualTo("Order receipt");
        // SMTP terminates body lines with CRLF; Mailpit retains that final transport newline.
        assertThat(message.get("HTML").asString().stripTrailing()).isEqualTo(html);
        assertThat(attempts(notificationId)).extracting(row -> row.get("status"))
                .containsExactly("FAILED", "SUCCEEDED");
        assertThat(eventTypes()).containsExactly("NotificationFailed", "NotificationSent");
        assertThat(job.runOnce()).isZero();
        assertThat(messages()).hasSize(1);
    }

    @Test
    void afterTheRetryLimitTheDeliveryIsFailedAndNeverSentAgain() throws Exception {
        UUID notificationId = receiveOrderCreated("NX-20261104-0003");
        mailSender.setPort(CLOSED_PORT);

        for (int i = 0; i < 3; i++) {
            job.runOnce();
        }
        Map<String, Object> failed = delivery(notificationId);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("attempt_count")).isEqualTo(3);
        assertThat(failed.get("next_attempt_at")).isNull();

        mailSender.setPort(MAILPIT.getMappedPort(1025));
        assertThat(job.runOnce()).isZero();
        assertThat(attempts(notificationId)).hasSize(3)
                .allSatisfy(row -> assertThat(row.get("status")).isEqualTo("FAILED"));
        assertThat(messages()).isEmpty();
        assertThat(eventTypes()).containsExactly("NotificationFailed", "NotificationFailed", "NotificationFailed");
    }

    @Test
    void aPermanentRecipientErrorFailsOnTheFirstAttempt() throws Exception {
        when(recipients.emailOf(BUYER)).thenThrow(new DeliveryFailure("RECIPIENT_NOT_FOUND", false));
        UUID notificationId = receiveOrderCreated("NX-20261104-0004");

        job.runOnce();

        assertThat(delivery(notificationId).get("status")).isEqualTo("FAILED");
        assertThat(attempts(notificationId)).singleElement().satisfies(row -> {
            assertThat(row.get("error_code")).isEqualTo("RECIPIENT_NOT_FOUND");
            assertThat(row.get("retryable")).isEqualTo(false);
        });
        assertThat(job.runOnce()).isZero();
    }

    @Test
    void anUnexpectedErrorIsRetriedAndTheStoredMessageHasNoFullEmail() throws Exception {
        when(recipients.emailOf(any())).thenThrow(new IllegalStateException("lookup broke for " + BUYER_EMAIL));
        UUID notificationId = receiveOrderCreated("NX-20261104-0005");

        job.runOnce();

        Map<String, Object> attempt = attempts(notificationId).getFirst();
        assertThat(attempt.get("error_code")).isEqualTo("DELIVERY_ERROR");
        assertThat(attempt.get("retryable")).isEqualTo(true);
        assertThat((String) attempt.get("error_message")).contains("b***@example.com").doesNotContain(BUYER_EMAIL);
        assertThat(delivery(notificationId).get("status")).isEqualTo("PENDING");
    }

    @Test
    void anAttemptLeftStartedPastItsLeaseIsClosedAndSentAgain() throws Exception {
        UUID notificationId = receiveOrderCreated("NX-20261104-0006");
        UUID deliveryId = (UUID) delivery(notificationId).get("id");
        jdbc.update("UPDATE notification_deliveries SET attempt_count = 1, next_attempt_at = now() - interval '1 minute'"
                + " WHERE id = ?", deliveryId);
        jdbc.update("INSERT INTO notification_delivery_attempts (delivery_id, attempt_number, status)"
                + " VALUES (?, 1, 'STARTED')", deliveryId);

        job.runOnce();

        assertThat(attempts(notificationId)).extracting(row -> row.get("error_code")).containsExactly(
                "ATTEMPT_TIMED_OUT", null);
        assertThat(attempts(notificationId)).extracting(row -> row.get("status")).containsExactly(
                "FAILED", "SUCCEEDED");
        assertThat(delivery(notificationId).get("status")).isEqualTo("SENT");
        assertThat(messages()).hasSize(1);
    }

    @Test
    void aWorkerStillSendingAfterTheFirstLeaseWouldEndNeverSendsAnEmailTwice() {
        for (int i = 0; i < 20; i++) {
            receiveOrderCreated("NX-20261106-%04d".formatted(i));
        }
        FakeClock clock = new FakeClock(Instant.now());
        Map<String, Integer> sends = new ConcurrentHashMap<>();
        AtomicInteger sent = new AtomicInteger();
        AtomicReference<DeliveryRetryJob> otherInstance = new AtomicReference<>();
        EmailSender sevenSecondsPerEmail = new EmailSender(mailSender, properties) {
            @Override
            public String send(String to, String subject, String text) {
                clock.advance(Duration.ofSeconds(7));
                sends.merge(text, 1, Integer::sum);
                if (sent.incrementAndGet() == 18) {
                    otherInstance.get().runOnce();
                }
                return "<" + UUID.randomUUID() + "@nexus.test>";
            }
        };
        DeliveryRetryJob first = new DeliveryRetryJob(deliveryService, recipients, sevenSecondsPerEmail, clock,
                properties);
        otherInstance.set(new DeliveryRetryJob(deliveryService, recipients, sevenSecondsPerEmail, clock, properties));

        first.runOnce();

        assertThat(sent).hasValue(20);
        assertThat(sends).hasSize(20).allSatisfy((text, count) -> assertThat(count).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_deliveries WHERE status = 'SENT'",
                Long.class)).isEqualTo(20);
        assertThat(jdbc.queryForList("SELECT status::text FROM notification_delivery_attempts", String.class))
                .hasSize(20).containsOnly("SUCCEEDED");
    }

    @Test
    void aWorkerWhoseLeaseRanOutBeforeSendingLeavesTheEmailToTheNextClaim() throws Exception {
        UUID notificationId = receiveOrderCreated("NX-20261106-0100");
        FakeClock clock = new FakeClock(Instant.now());
        when(recipients.emailOf(BUYER)).thenAnswer(invocation -> {
            clock.advance(properties.getAttemptTimeout().plusSeconds(1));
            return BUYER_EMAIL;
        });
        DeliveryRetryJob slowLookup = new DeliveryRetryJob(deliveryService, recipients, sender, clock, properties);

        assertThat(slowLookup.runOnce()).isEqualTo(1);
        assertThat(messages()).isEmpty();
        assertThat(attempts(notificationId)).extracting(row -> row.get("status")).containsExactly("STARTED");

        when(recipients.emailOf(BUYER)).thenReturn(BUYER_EMAIL);
        assertThat(slowLookup.runOnce()).isEqualTo(1);
        assertThat(attempts(notificationId)).extracting(row -> row.get("error_code"))
                .containsExactly("ATTEMPT_TIMED_OUT", null);
        assertThat(attempts(notificationId)).extracting(row -> row.get("status"))
                .containsExactly("FAILED", "SUCCEEDED");
        assertThat(messages()).hasSize(1);
    }

    @Test
    void theOwnerReadsTheAttemptsWithoutTheEmailAndOthersAreRefused() throws Exception {
        UUID notificationId = receiveOrderCreated("NX-20261104-0007");
        mailSender.setPort(CLOSED_PORT);
        job.runOnce();
        mailSender.setPort(MAILPIT.getMappedPort(1025));
        job.runOnce();

        mvc.perform(get("/api/v1/notifications/{id}/deliveries", notificationId).header("Authorization", "Bearer buyer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$.data[0].status").value("SENT"))
                .andExpect(jsonPath("$.data[0].attemptCount").value(2))
                .andExpect(jsonPath("$.data[0].attempts[0].attemptNumber").value(1))
                .andExpect(jsonPath("$.data[0].attempts[0].errorCode").value("SMTP_UNAVAILABLE"))
                .andExpect(jsonPath("$.data[0].attempts[0].retryable").value(true))
                .andExpect(jsonPath("$.data[0].attempts[1].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].attempts[0].errorMessage").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(BUYER_EMAIL))));

        mvc.perform(get("/api/v1/notifications/{id}/deliveries", notificationId).header("Authorization", "Bearer stranger"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_OWNER"));
        mvc.perform(get("/api/v1/notifications/{id}/deliveries", UUID.randomUUID()).header("Authorization", "Bearer buyer"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
        mvc.perform(get("/api/v1/notifications/{id}/deliveries", notificationId).header("Authorization", "Bearer no-privilege"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/notifications/{id}/deliveries", notificationId))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications/not-a-uuid/deliveries").header("Authorization", "Bearer buyer"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    private UUID receiveOrderCreated(String orderNumber) {
        UUID orderId = UUID.randomUUID();
        JsonNode payload = JsonMapper.shared().readTree("""
                {"orderId":"%s","buyerId":"%s","orderNumber":"%s","orderType":"STANDARD","referenceId":null}
                """.formatted(orderId, BUYER, orderNumber));
        EventEnvelope<JsonNode> envelope = new EventEnvelope<>(UUID.randomUUID(), "OrderCreated", orderId.toString(),
                Instant.now(), "4bf92f3577b34da6a3ce929d0e0e4736", payload);
        consumer.receive(new ConsumerRecord<>(Topics.COMMERCE, 0, 0L, orderId.toString(), envelope));
        return jdbc.queryForObject("SELECT id FROM notifications WHERE content LIKE ?", UUID.class,
                "%" + orderNumber + "%");
    }

    private Map<String, Object> delivery(UUID notificationId) {
        return jdbc.queryForMap("""
                SELECT id, status::text AS status, attempt_count, next_attempt_at
                FROM notification_deliveries WHERE notification_id = ? AND channel = 'EMAIL'
                """, notificationId);
    }

    private List<Map<String, Object>> attempts(UUID notificationId) {
        return jdbc.queryForList("""
                SELECT a.attempt_number, a.status::text AS status, a.error_code, a.error_message, a.retryable,
                       a.provider_message_id
                FROM notification_delivery_attempts a
                JOIN notification_deliveries d ON d.id = a.delivery_id
                WHERE d.notification_id = ?
                ORDER BY a.attempt_number
                """, notificationId);
    }

    private List<String> eventTypes() {
        return jdbc.queryForList("SELECT event_type FROM event_logs WHERE aggregate_type = 'Notification'"
                + " ORDER BY occurred_at, received_at", String.class);
    }

    private EventLogRepository.EventLogDetail eventDetail(String eventType) {
        UUID id = jdbc.queryForObject("SELECT id FROM event_logs WHERE event_type = ? ORDER BY occurred_at LIMIT 1",
                UUID.class, eventType);
        return eventLogs.detail(id.toString());
    }

    private static List<JsonNode> messages() throws Exception {
        JsonNode body = JsonMapper.shared().readTree(mailpit("GET", "/api/v1/messages"));
        return body.get("messages").valueStream().toList();
    }

    private static String mailpit(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + MAILPIT.getHost() + ":"
                        + MAILPIT.getMappedPort(8025) + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static final class FakeClock extends Clock {
        private final AtomicReference<Instant> now;

        private FakeClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private static Jwt token(String value, UUID userId, List<String> privileges) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(userId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of("BUYER")).claim("privileges", privileges).build();
    }
}
