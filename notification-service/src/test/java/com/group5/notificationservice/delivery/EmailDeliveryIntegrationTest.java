package com.group5.notificationservice.delivery;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.notificationservice.eventlog.EventLogRepository;
import com.group5.notificationservice.health.BrokerHealthJob;
import com.group5.notificationservice.health.HealthEventWriter;
import com.group5.notificationservice.health.KafkaBrokerProbe;
import com.group5.notificationservice.notification.DomainEventConsumer;
import com.group5.notificationservice.notification.NotificationChannel;
import com.group5.notificationservice.notification.NotificationRepository;
import com.group5.notificationservice.shared.config.DeliveryProperties;
import jakarta.servlet.Filter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

// Bài test email không chạy health job hay kết nối Redis/Kafka của luồng giám sát broker.
@MockitoBean(types = {BrokerHealthJob.class, HealthEventWriter.class, KafkaBrokerProbe.class})
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
        "nexus.notification.frontend-base-url=http://localhost:3000",
        "nexus.notification.delivery.retry-limit=3",
        "nexus.notification.delivery.base-delay=0ms", "nexus.notification.delivery.max-delay=0ms",
        "nexus.notification.delivery.poll-interval=1h",
        "spring.mail.properties.mail.smtp.connectiontimeout=10000",
        "spring.mail.properties.mail.smtp.timeout=10000"
})
class EmailDeliveryIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new com.group5.common_test.PostgresTestDatabase("postgres:18");
    private static final GenericContainer<?> MAILPIT =
            new com.group5.common_test.MailpitTestContainer("axllent/mailpit:v1.31").withExposedPorts(1025, 8025);
    static {
        POSTGRES.start();
        MAILPIT.start();
    }

    private static final UUID BUYER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final UUID SELLER = UUID.randomUUID();
    private static final UUID SECOND_BIDDER = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID ORDER_GROUP = UUID.randomUUID();
    private static final UUID AUCTION = UUID.randomUUID();
    private static final String BUYER_EMAIL = "buyer.one@example.com";
    private static final String SELLER_EMAIL = "seller.one@example.com";
    private static final String SECOND_BIDDER_EMAIL = "bidder.two@example.com";
    private static final int CLOSED_PORT = 1;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DomainEventConsumer consumer;
    @Autowired private DeliveryRetryJob job;
    @Autowired private JavaMailSender mailSender;
    @Autowired private DeliveryService deliveryService;
    @Autowired private DeliveryProperties properties;
    @Autowired private EmailSender sender;
    @Autowired private EventLogRepository eventLogs;
    @Autowired private IdempotentConsumer idempotentConsumer;
    @Autowired private NotificationRepository notifications;
    @Autowired private EmailChannel emailChannel;
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
        jdbc.update("DELETE FROM notification_preferences");
        mailpit("DELETE", "/api/v1/messages");
        smtpPort(MAILPIT.getMappedPort(1025));
        reset(recipients, decoder);
        when(recipients.emailOf(BUYER)).thenReturn(BUYER_EMAIL);
        when(recipients.emailOf(SELLER)).thenReturn(SELLER_EMAIL);
        when(recipients.emailOf(SECOND_BIDDER)).thenReturn(SECOND_BIDDER_EMAIL);
        when(decoder.decode("buyer")).thenReturn(token("buyer", BUYER, List.of("NOTIFICATION.VIEW")));
        when(decoder.decode("stranger")).thenReturn(token("stranger", STRANGER, List.of("NOTIFICATION.VIEW")));
        when(decoder.decode("no-privilege")).thenReturn(token("no-privilege", BUYER, List.of()));
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @ParameterizedTest(name = "{0}: một email cho buyer dù event được giao lại")
    @MethodSource("singleRecipientEvents")
    void newBuyerEventsSendTheirContentOnceThroughMailpit(String eventType, String topic, String type,
                                                         String subject, String expectedContent) throws Exception {
        // Dùng consumer, processed_events, DB delivery và SMTP Mailpit thật; chỉ việc tra email là fixture.
        ConsumerRecord<String, EventEnvelope<JsonNode>> record = domainRecord(topic, eventType, sample(eventType));

        consumer.receive(record);
        consumer.receive(record);

        assertThat(jdbc.queryForList("SELECT user_id FROM notifications WHERE type = ?", UUID.class, type))
                .containsExactly(BUYER);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_deliveries", Long.class)).isEqualTo(1);
        assertThat(processedCount(record)).isEqualTo(1);
        assertThat(job.runOnce()).isEqualTo(1);
        List<JsonNode> inbox = messages();
        assertThat(inbox).hasSize(1);
        JsonNode email = message(inbox.getFirst());
        assertThat(email.get("To").get(0).get("Address").asString()).isEqualTo(BUYER_EMAIL);
        assertThat(email.get("Subject").asString()).isEqualTo(subject);
        assertThat(email.get("Text").asString()).contains(expectedContent);
        assertThat(jdbc.queryForList("SELECT status::text FROM notification_deliveries", String.class))
                .containsExactly("SENT");

        // Replay sau khi SMTP thành công cũng không tạo notification hay email mới.
        consumer.receive(record);
        assertThat(job.runOnce()).isZero();
        assertThat(messages()).hasSize(1);
        assertThat(processedCount(record)).isEqualTo(1);
    }

    @Test
    void cancelledAuctionSendsOnceToEveryDistinctBidderAndSeller() throws Exception {
        ConsumerRecord<String, EventEnvelope<JsonNode>> record = domainRecord(Topics.AUCTION, "AuctionCancelled",
                sample("AuctionCancelled"));
        // BUYER xuất hiện hai lần trong bidderIds nhưng chỉ được tạo một notification/delivery.
        consumer.receive(record);
        consumer.receive(record);

        assertThat(jdbc.queryForList("SELECT user_id FROM notifications", UUID.class))
                .containsExactlyInAnyOrder(BUYER, SECOND_BIDDER, SELLER);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_deliveries", Long.class)).isEqualTo(3);
        assertThat(processedCount(record)).isEqualTo(1);
        assertThat(job.runOnce()).isEqualTo(3);

        List<JsonNode> inbox = messages();
        assertThat(inbox).hasSize(3);
        assertThat(inbox.stream().map(email -> email.get("To").get(0).get("Address").asString()).toList())
                .containsExactlyInAnyOrder(BUYER_EMAIL, SECOND_BIDDER_EMAIL, SELLER_EMAIL);
        for (JsonNode summary : inbox) {
            JsonNode email = message(summary);
            assertThat(email.get("Subject").asString()).isEqualTo("Auction cancelled");
            assertThat(email.get("Text").asString()).contains(AUCTION.toString(), "ADMIN_CANCELLED");
        }
        consumer.receive(record);
        assertThat(job.runOnce()).isZero();
        assertThat(messages()).hasSize(3);
    }

    @Test
    void auctionTimeoutSendsOnlyToWinnerAndSeller() throws Exception {
        ConsumerRecord<String, EventEnvelope<JsonNode>> record = domainRecord(Topics.AUCTION, "AuctionPaymentTimeout",
                sample("AuctionPaymentTimeout"));
        // Bidder thua có mặt trong dữ liệu bổ sung nhưng không phải người nhận của timeout.
        consumer.receive(record);
        consumer.receive(record);

        assertThat(jdbc.queryForList("SELECT user_id FROM notifications", UUID.class))
                .containsExactlyInAnyOrder(BUYER, SELLER);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_deliveries", Long.class)).isEqualTo(2);
        assertThat(processedCount(record)).isEqualTo(1);
        assertThat(job.runOnce()).isEqualTo(2);
        List<JsonNode> inbox = messages();
        assertThat(inbox).hasSize(2);
        assertThat(inbox.stream().map(email -> email.get("To").get(0).get("Address").asString()).toList())
                .containsExactlyInAnyOrder(BUYER_EMAIL, SELLER_EMAIL);
        for (JsonNode summary : inbox) {
            JsonNode email = message(summary);
            assertThat(email.get("Subject").asString()).isEqualTo("Auction payment deadline passed");
            assertThat(email.get("Text").asString()).contains(AUCTION.toString(), ORDER.toString(), "deadline");
        }
        assertThat(job.runOnce()).isZero();
        assertThat(messages()).hasSize(2);
    }

    @Test
    void oneBidderOptingOutDoesNotSuppressOtherAuctionRecipients() throws Exception {
        preference(BUYER, "AUCTION_CANCELLED", "EMAIL", false);
        ConsumerRecord<String, EventEnvelope<JsonNode>> record = domainRecord(Topics.AUCTION, "AuctionCancelled",
                sample("AuctionCancelled"));
        // Preference thuộc từng người nhận: cả ba vẫn có in-app, chỉ buyer tắt bị SKIPPED.
        consumer.receive(record);
        consumer.receive(record);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT d.status::text FROM notification_deliveries d JOIN notifications n ON n.id = d.notification_id
                WHERE n.user_id = ?
                """, String.class, BUYER)).isEqualTo("SKIPPED");
        assertThat(job.runOnce()).isEqualTo(2);
        assertThat(messages().stream().map(email -> email.get("To").get(0).get("Address").asString()).toList())
                .containsExactlyInAnyOrder(SECOND_BIDDER_EMAIL, SELLER_EMAIL);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_delivery_attempts a
                JOIN notification_deliveries d ON d.id = a.delivery_id JOIN notifications n ON n.id = d.notification_id
                WHERE n.user_id = ?
                """, Long.class, BUYER)).isZero();
        assertThat(processedCount(record)).isEqualTo(1);
    }

    @Test
    void fanoutFailureRollsBackEveryRecipientAndMarkerThenTheSameEventCanRetry() throws Exception {
        AtomicInteger recipientsSeen = new AtomicInteger();
        NotificationChannel failOnSecondRecipient = notification -> {
            // Lỗi sau khi đã ghi delivery đầu và notification thứ hai phải rollback toàn transaction.
            if (recipientsSeen.incrementAndGet() == 2) {
                throw new IllegalStateException("Fanout fixture failure");
            }
        };
        DomainEventConsumer failingConsumer = new DomainEventConsumer(idempotentConsumer, notifications,
                List.of(emailChannel, failOnSecondRecipient), "http://localhost:3000");
        ConsumerRecord<String, EventEnvelope<JsonNode>> record = domainRecord(Topics.AUCTION, "AuctionCancelled",
                sample("AuctionCancelled"));

        assertThatThrownBy(() -> failingConsumer.receive(record))
                .isInstanceOf(IllegalStateException.class).hasMessage("Fanout fixture failure");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_deliveries", Long.class)).isZero();
        assertThat(processedCount(record)).isZero();
        assertThat(messages()).isEmpty();
        consumer.receive(record);
        consumer.receive(record);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_deliveries", Long.class)).isEqualTo(3);
        assertThat(processedCount(record)).isEqualTo(1);
        assertThat(job.runOnce()).isEqualTo(3);
        assertThat(messages()).hasSize(3);
    }

    @Test
    void disabledEmailPreferenceKeepsTheNotificationButSkipsDeliveryWithoutAnAttempt() throws Exception {
        // Tắt đúng loại EMAIL trước khi nhận event: vẫn có thông báo trong ứng dụng để người dùng đọc.
        preference(BUYER, "ORDER_CREATED", "EMAIL", false);

        UUID notificationId = receiveOrderCreated("NX-259-DISABLED");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE id = ?", Long.class,
                notificationId)).isEqualTo(1);
        assertThat(delivery(notificationId).get("status")).isEqualTo("SKIPPED");
        assertThat(delivery(notificationId).get("next_attempt_at")).isNull();
        assertThat(delivery(notificationId).get("attempt_count")).isEqualTo(0);
        assertThat(job.runOnce()).isZero();
        assertThat(attempts(notificationId)).isEmpty();
        assertThat(messages()).isEmpty();
        verifyNoInteractions(recipients);
    }

    @Test
    void preferencesForAnotherUserTypeOrChannelDoNotDisableEmail() throws Exception {
        // Mặc định vẫn bật khi không có preference khớp cả ba chiều người dùng, loại và kênh.
        preference(STRANGER, "ORDER_CREATED", "EMAIL", false);
        preference(BUYER, "ORDER_COMPLETED", "EMAIL", false);
        preference(BUYER, "ORDER_CREATED", "PUSH", false);
        UUID notificationId = receiveOrderCreated("NX-259-DEFAULT");

        assertThat(delivery(notificationId).get("status")).isEqualTo("PENDING");
        assertThat(job.runOnce()).isEqualTo(1);
        assertThat(delivery(notificationId).get("status")).isEqualTo("SENT");
        assertThat(messages()).hasSize(1);
    }

    @Test
    void explicitlyEnabledPreferenceStillDeliversEmail() throws Exception {
        // Một preference bật phải giữ nguyên đường gửi SMTP hiện có.
        preference(BUYER, "ORDER_CREATED", "EMAIL", true);
        UUID notificationId = receiveOrderCreated("NX-259-ENABLED");

        assertThat(job.runOnce()).isEqualTo(1);
        assertThat(delivery(notificationId).get("status")).isEqualTo("SENT");
        assertThat(messages()).hasSize(1);
    }

    @Test
    void disablingAfterQueueingSkipsTheEmailBeforeStartingAnAttempt() throws Exception {
        UUID notificationId = receiveOrderCreated("NX-259-QUEUED");
        // Preference thay đổi lúc email còn chờ; worker phải đọc lại thay vì dùng quyết định lúc nhận event.
        preference(BUYER, "ORDER_CREATED", "EMAIL", false);

        assertThat(job.runOnce()).isZero();

        assertThat(delivery(notificationId).get("status")).isEqualTo("SKIPPED");
        assertThat(delivery(notificationId).get("attempt_count")).isEqualTo(0);
        assertThat(attempts(notificationId)).isEmpty();
        assertThat(messages()).isEmpty();
        verifyNoInteractions(recipients);
    }

    @Test
    void disablingAfterSmtpFailureStopsFurtherRetries() throws Exception {
        UUID notificationId = receiveOrderCreated("NX-259-RETRY");
        smtpPort(CLOSED_PORT);
        assertThat(job.runOnce()).isEqualTo(1);
        // Giữ lịch sử lần lỗi trước đó nhưng không gửi lại khi người dùng đã tắt loại email này.
        preference(BUYER, "ORDER_CREATED", "EMAIL", false);
        smtpPort(MAILPIT.getMappedPort(1025));

        assertThat(job.runOnce()).isZero();

        assertThat(delivery(notificationId).get("status")).isEqualTo("SKIPPED");
        assertThat(delivery(notificationId).get("attempt_count")).isEqualTo(1);
        assertThat(delivery(notificationId).get("next_attempt_at")).isNull();
        assertThat(attempts(notificationId)).extracting(row -> row.get("error_code"))
                .containsExactly("SMTP_UNAVAILABLE");
        assertThat(messages()).isEmpty();
    }

    @Test
    void disablingDuringRecipientLookupClosesTheClaimWithoutSending() throws Exception {
        UUID notificationId = receiveOrderCreated("NX-259-LOOKUP");
        // Mô phỏng preference bị tắt trong lúc gọi Keycloak; kiểm tra cuối trước SMTP phải ngăn email.
        when(recipients.emailOf(BUYER)).thenAnswer(invocation -> {
            preference(BUYER, "ORDER_CREATED", "EMAIL", false);
            return BUYER_EMAIL;
        });

        assertThat(job.runOnce()).isEqualTo(1);

        assertThat(delivery(notificationId).get("status")).isEqualTo("SKIPPED");
        assertThat(delivery(notificationId).get("next_attempt_at")).isNull();
        assertThat(attempts(notificationId)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("error_code")).isEqualTo("PREFERENCE_DISABLED");
            assertThat(row.get("retryable")).isEqualTo(false);
        });
        assertThat(messages()).isEmpty();
        assertThat(job.runOnce()).isZero();
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

        smtpPort(CLOSED_PORT);
        job.runOnce();
        assertThat(delivery(notificationId).get("status")).isEqualTo("PENDING");
        Map<String, Object> first = attempts(notificationId).getFirst();
        assertThat(first.get("status")).isEqualTo("FAILED");
        assertThat(first.get("error_code")).isEqualTo("SMTP_UNAVAILABLE");
        assertThat(first.get("retryable")).isEqualTo(true);

        smtpPort(MAILPIT.getMappedPort(1025));
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
        smtpPort(CLOSED_PORT);

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
        smtpPort(MAILPIT.getMappedPort(1025));
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
        smtpPort(CLOSED_PORT);

        for (int i = 0; i < 3; i++) {
            job.runOnce();
        }
        Map<String, Object> failed = delivery(notificationId);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("attempt_count")).isEqualTo(3);
        assertThat(failed.get("next_attempt_at")).isNull();

        smtpPort(MAILPIT.getMappedPort(1025));
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
        smtpPort(CLOSED_PORT);
        job.runOnce();
        smtpPort(MAILPIT.getMappedPort(1025));
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

    // Từng dòng kiểm tra một loại buyer event, subject và phần nội dung đặc trưng tới SMTP.
    private static Stream<Arguments> singleRecipientEvents() {
        return Stream.of(
                Arguments.of("CheckoutPaymentConfirmed", Topics.PAYMENT, "CHECKOUT_PAYMENT_CONFIRMED",
                        "Payment received", "Payment received for order group " + ORDER_GROUP + ": 125000.00 VND"),
                Arguments.of("ShipmentPickedUp", Topics.SHIPMENT, "SHIPMENT_CREATED", "Order shipped",
                        "has shipped with GHN. Tracking code: TRACK-259"),
                Arguments.of("ShipmentDelivered", Topics.SHIPMENT, "SHIPMENT_DELIVERED", "Order delivered",
                        "Rate your order: http://localhost:3000/order/" + ORDER),
                Arguments.of("ShipmentFailed", Topics.SHIPMENT, "SHIPMENT_FAILED", "Delivery problem",
                        "Delivery failed for order " + ORDER + ". Please contact Support"),
                Arguments.of("ShipmentReturned", Topics.SHIPMENT, "SHIPMENT_RETURNED", "Delivery problem",
                        "Your order " + ORDER + " was returned. Please contact Support"),
                Arguments.of("OrderRefundRequested", Topics.PAYMENT, "ORDER_REFUND_REQUESTED", "Refund requested",
                        "Refund requested for order " + ORDER + ": 125000.00 VND. Reason: BUYER_REQUESTED"),
                Arguments.of("OrderRefundCompleted", Topics.PAYMENT, "ORDER_REFUND_COMPLETED", "Refund sent",
                        "Refund sent for order " + ORDER + ": 125000.00 VND"),
                Arguments.of("OrderCancelled", Topics.COMMERCE, "ORDER_CANCELLED", "Order cancelled",
                        "Your order NX-259-CANCELLED was cancelled"));
    }

    // Chỉ cung cấp dữ kiện notification dùng từ event; không gọi payment/carrier thật trong bài test này.
    private static JsonNode sample(String eventType) {
        Map<String, ?> payload = switch (eventType) {
            case "CheckoutPaymentConfirmed" -> Map.of("sessionId", UUID.randomUUID(), "orderGroupId", ORDER_GROUP,
                    "buyerId", BUYER, "totalAmount", "125000.00", "currency", "VND");
            case "ShipmentPickedUp", "ShipmentDelivered", "ShipmentFailed", "ShipmentReturned" ->
                    Map.of("shipmentId", UUID.randomUUID(), "orderId", ORDER, "buyerId", BUYER,
                            "carrier", "GHN", "trackingCode", "TRACK-259");
            case "OrderRefundRequested", "OrderRefundCompleted" -> Map.of("refundId", UUID.randomUUID(),
                    "orderId", ORDER, "buyerId", BUYER, "amount", "125000.00", "currency", "VND",
                    "reason", "BUYER_REQUESTED");
            case "OrderCancelled" -> Map.of("orderId", ORDER, "orderNumber", "NX-259-CANCELLED", "buyerId", BUYER,
                    "reason", "PAYMENT_DEADLINE_EXPIRED");
            case "AuctionCancelled" -> Map.of("auctionId", AUCTION, "sellerId", SELLER,
                    "bidderIds", List.of(BUYER, BUYER, SECOND_BIDDER), "reason", "ADMIN_CANCELLED");
            case "AuctionPaymentTimeout" -> Map.of("auctionId", AUCTION, "orderId", ORDER, "winnerId", BUYER,
                    "sellerId", SELLER, "bidderIds", List.of(SECOND_BIDDER));
            default -> throw new IllegalArgumentException("No notification fixture for " + eventType);
        };
        return JsonMapper.shared().valueToTree(payload);
    }

    private static ConsumerRecord<String, EventEnvelope<JsonNode>> domainRecord(String topic, String eventType,
                                                                               JsonNode payload) {
        // Payment sở hữu cả session và refund; refund vẫn dùng orderId theo contract publisher hiện có.
        String aggregateField = switch (eventType) {
            case "CheckoutPaymentConfirmed" -> "sessionId";
            case "OrderRefundRequested", "OrderRefundCompleted" -> "orderId";
            case "AuctionCancelled", "AuctionPaymentTimeout" -> "auctionId";
            case "ShipmentPickedUp", "ShipmentDelivered", "ShipmentFailed", "ShipmentReturned" -> "shipmentId";
            default -> "orderId";
        };
        String aggregateId = payload.path(aggregateField).asString();
        EventEnvelope<JsonNode> event = new EventEnvelope<>(UUID.randomUUID(), eventType, aggregateId,
                Instant.now(), "4bf92f3577b34da6a3ce929d0e0e4736", payload);
        String key = topic.equals(Topics.SHIPMENT) ? ORDER.toString() : aggregateId;
        return new ConsumerRecord<>(topic, 0, 0L, key, event);
    }

    private int processedCount(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer = ?",
                Integer.class, record.value().eventId(), "notification-domain-events");
    }

    private static JsonNode message(JsonNode summary) throws Exception {
        return JsonMapper.shared().readTree(mailpit("GET", "/api/v1/message/" + summary.get("ID").asString()));
    }

    // Chỉ ghi preference của DB notification trong test; không thay cấu hình hay dữ liệu dịch vụ người dùng.
    private void preference(UUID userId, String type, String channel, boolean enabled) {
        jdbc.update("""
                INSERT INTO notification_preferences (user_id, notification_type, channel, enabled)
                VALUES (?, ?, CAST(? AS notification_channel), ?)
                ON CONFLICT (user_id, notification_type, channel) DO UPDATE SET enabled = EXCLUDED.enabled
                """, userId, type, channel, enabled);
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

    // Inject interface do Spring công bố; chỉ đổi cổng trên SMTP implementation của bài test.
    private void smtpPort(int port) {
        assertThat(mailSender).isInstanceOfSatisfying(JavaMailSenderImpl.class, smtp -> smtp.setPort(port));
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
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("Mailpit %s %s", method, path).isBetween(200, 299);
        return response.body();
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
