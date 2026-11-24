package com.group5.notificationservice.notification;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.Filter;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Real filters, controller, service and PostgreSQL; only JWT signature verification is a fixture. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/notification-mark-read-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false", "spring.jpa.properties.hibernate.generate_statistics=true",
        "nexus.notification.group-id=notification-mark-read-test", "nexus.outbox.relay.enabled=false",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false",
        "nexus.notification.delivery.poll-interval=1d"
})
class NotificationMarkReadIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new com.group5.common_test.PostgresTestDatabase("postgres:18");
    static { POSTGRES.start(); }

    private static final UUID USER_A = UUID.randomUUID();
    private static final UUID USER_B = UUID.randomUUID();
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @MockitoBean private JwtDecoder decoder;
    @Autowired private com.group5.notificationservice.stream.NotificationInboxQuery inbox;
    @Autowired private com.group5.notificationservice.preference.NotificationPreferenceConsumer preferenceConsumer;
    @Autowired private DomainEventConsumer domain;
    @Autowired private com.group5.notificationservice.delivery.DeliveryRepository deliveries;
    @Autowired private com.group5.notificationservice.delivery.DeliveryService worker;
    private MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM notification_preferences");
        jdbc.update("DELETE FROM notification_inbox_versions");
        reset(decoder);
        when(decoder.decode("user-a")).thenReturn(token("user-a", USER_A, List.of("NOTIFICATION.VIEW", "NOTIFICATION.MANAGE_PREFERENCE")));
        when(decoder.decode("user-b")).thenReturn(token("user-b", USER_B, List.of("NOTIFICATION.VIEW")));
        when(decoder.decode("no-privilege")).thenReturn(token("no-privilege", USER_A, List.of()));
        when(decoder.decode("admin")).thenReturn(Jwt.withTokenValue("admin").header("alg", "RS256").subject(USER_B.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of("ADMIN")).claim("privileges", List.of("NOTIFICATION.VIEW")).build());
        when(decoder.decode("support")).thenReturn(Jwt.withTokenValue("support").header("alg", "RS256").subject(USER_B.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of("SUPPORT_STAFF")).claim("privileges", List.of("NOTIFICATION.VIEW")).build());
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void viewingNotificationsDoesNotGrantPreferenceWritePermission() throws Exception {
        mvc.perform(get("/api/v1/notifications/preferences").header("Authorization", "Bearer user-b"))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/notifications/preferences")
                .header("Authorization", "Bearer user-b").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"preferences\":[{\"notificationType\":\"BID_OUTBID\",\"channel\":\"EMAIL\",\"enabled\":false}]}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT enabled FROM notification_preferences WHERE user_id=? AND notification_type='BID_OUTBID' AND channel='EMAIL'", Boolean.class, USER_B)).isTrue();
    }

    @Test
    void marksOwnNotificationAndUnreadCountDropsByOne() throws Exception {
        UUID id = insert(USER_A, null);
        insert(USER_A, null);
        mvc.perform(patch("/api/v1/notifications/{id}/read", id).header("Authorization", "Bearer user-a"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(row(id).get("read_at")).isNotNull();
        assertThat(row(id).get("updated_by")).isEqualTo(USER_A);
        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", "Bearer user-a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.count").value(1));
    }

    @Test
    void anotherUsersNotificationReturns403AndEveryColumnIsUnchanged() throws Exception {
        UUID id = insert(USER_A, null);
        Map<String, Object> before = row(id);
        mvc.perform(patch("/api/v1/notifications/{id}/read", id).header("Authorization", "Bearer user-b"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_OWNER"));
        assertThat(row(id)).isEqualTo(before);
    }

    @Test
    void readAllUsesOneUpdateAndDoesNotTouchOtherUsersOrAlreadyReadRows() throws Exception {
        insert(USER_A, null); insert(USER_A, null); insert(USER_A, null);
        UUID other = insert(USER_B, null);
        UUID alreadyRead = insert(USER_A, OffsetDateTime.parse("2026-10-01T00:00:00Z"));
        Map<String, Object> otherBefore = row(other);
        Map<String, Object> readBefore = row(alreadyRead);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mvc.perform(patch("/api/v1/notifications/read-all").header("Authorization", "Bearer user-a"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(row(other)).isEqualTo(otherBefore);
        assertThat(row(alreadyRead)).isEqualTo(readBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND updated_by = ?",
                Integer.class, USER_A, USER_A)).isEqualTo(3);
        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", "Bearer user-a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.count").value(0));
    }

    @Test
    void markingAlreadyReadNotificationDoesNotChangeAnyColumn() throws Exception {
        UUID id = insert(USER_A, OffsetDateTime.parse("2026-10-01T00:00:00Z"));
        Map<String, Object> before = row(id);
        mvc.perform(patch("/api/v1/notifications/{id}/read", id).header("Authorization", "Bearer user-a"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(row(id)).isEqualTo(before);
    }

    @Test
    void missingNotificationReturnsNamed404() throws Exception {
        mvc.perform(patch("/api/v1/notifications/{id}/read", UUID.randomUUID())
                        .header("Authorization", "Bearer user-a"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    void emptyAndRepeatedReadAllReturn204WithoutChangingTimestamps() throws Exception {
        mvc.perform(patch("/api/v1/notifications/read-all").header("Authorization", "Bearer user-a"))
                .andExpect(status().isNoContent());
        UUID id = insert(USER_A, null);
        mvc.perform(patch("/api/v1/notifications/read-all").header("Authorization", "Bearer user-a"))
                .andExpect(status().isNoContent());
        Map<String, Object> before = row(id);
        mvc.perform(patch("/api/v1/notifications/read-all").header("Authorization", "Bearer user-a"))
                .andExpect(status().isNoContent());
        assertThat(row(id)).isEqualTo(before);
    }

    @Test
    void bothWritesRequireAuthenticationAndNotificationPrivilege() throws Exception {
        UUID id = insert(USER_A, null);
        for (String path : List.of("/api/v1/notifications/" + id + "/read", "/api/v1/notifications/read-all")) {
            mvc.perform(patch(path)).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"));
            mvc.perform(patch(path).header("Authorization", "Bearer no-privilege"))
                    .andExpect(status().isForbidden());
        }
        assertThat(row(id).get("read_at")).isNull();
    }

    @Test
    void malformedIdReturnsProblemDetails() throws Exception {
        mvc.perform(patch("/api/v1/notifications/not-a-uuid/read").header("Authorization", "Bearer user-a"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void preferenceMatrixDefaultsAndUpdatesAreScopedAtomicAndMandatory() throws Exception {
        mvc.perform(get("/api/v1/notifications/preferences").header("Authorization", "Bearer user-a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.preferences").isNotEmpty());
        String changes = """
                {"preferences":[{"notificationType":"BID_OUTBID","channel":"IN_APP","enabled":false},
                {"notificationType":"BID_OUTBID","channel":"EMAIL","enabled":false}]}
                """;
        mvc.perform(put("/api/v1/notifications/preferences").header("Authorization", "Bearer user-a")
                .contentType("application/json").content(changes)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_preferences WHERE user_id=? AND NOT enabled", Long.class, USER_A)).isEqualTo(2);
        mvc.perform(get("/api/v1/notifications/preferences").header("Authorization", "Bearer user-b")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_preferences WHERE user_id=? AND NOT enabled", Long.class, USER_B)).isZero();
        mvc.perform(put("/api/v1/notifications/preferences").header("Authorization", "Bearer user-a").contentType("application/json")
                .content("""
                        {"preferences":[{"notificationType":"BID_OUTBID","channel":"EMAIL","enabled":true},
                        {"notificationType":"ORDER_CREATED","channel":"EMAIL","enabled":false}]}
                        """)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT enabled FROM notification_preferences WHERE user_id=? AND notification_type='BID_OUTBID' AND channel='EMAIL'", Boolean.class, USER_A)).isFalse();
        mvc.perform(get("/api/v1/notifications/preferences")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/notifications/preferences").header("Authorization", "Bearer no-privilege")
                .contentType("application/json").content(changes)).andExpect(status().isForbidden());
    }

    @Test
    void registrationDefaultsDoNotResetExistingChoicesAndDuplicateEventIsHarmless() {
        var json = tools.jackson.databind.json.JsonMapper.shared().valueToTree(Map.of("userId", USER_A.toString()));
        var event = new com.group5.common_events.EventEnvelope<>(UUID.randomUUID(), "UserRegistered", USER_A.toString(), Instant.now(), "test", json);
        var record = new org.apache.kafka.clients.consumer.ConsumerRecord<>(com.group5.common_events.Topics.USER, 0, 0L, USER_A.toString(), event);
        preferenceConsumer.receive(record);
        long cells = jdbc.queryForObject("SELECT count(*) FROM notification_preferences WHERE user_id=?", Long.class, USER_A);
        assertThat(cells).isEqualTo(NotificationTemplate.values().length * 2L);
        jdbc.update("UPDATE notification_preferences SET enabled=false WHERE user_id=? AND notification_type='BID_OUTBID'", USER_A);
        preferenceConsumer.receive(record);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_preferences WHERE user_id=?", Long.class, USER_A)).isEqualTo(cells);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_preferences WHERE user_id=? AND NOT enabled", Long.class, USER_A)).isEqualTo(2);
    }

    @Test
    void optedOutInboxIsHiddenAndDurableCursorIsIsolatedAndAdvancesOnReads() throws Exception {
        mvc.perform(put("/api/v1/notifications/preferences").header("Authorization", "Bearer user-a").contentType("application/json")
                .content("""
                        {"preferences":[{"notificationType":"BID_OUTBID","channel":"IN_APP","enabled":false}]}
                        """)).andExpect(status().isOk());
        UUID auction = UUID.randomUUID();
        var json = tools.jackson.databind.json.JsonMapper.shared().valueToTree(Map.of("auctionId", auction.toString(),
                "outbidUserId", USER_A.toString(), "amount", "100000.00", "currency", "VND"));
        var event = new com.group5.common_events.EventEnvelope<>(UUID.randomUUID(), "BidOutbid", auction.toString(), Instant.now(), "test", json);
        domain.receive(new org.apache.kafka.clients.consumer.ConsumerRecord<>(com.group5.common_events.Topics.AUCTION, 0, 0L, auction.toString(), event));
        assertThat(inbox.snapshot(USER_A).unreadCount()).isZero();
        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer user-a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        UUID visible = insert(USER_A, null);
        assertThat(inbox.snapshot(USER_A).unreadCount()).isEqualTo(1);
        String before = inbox.snapshot(USER_A).revision();
        assertThat(inbox.snapshot(USER_B).revision()).isEqualTo("0");
        mvc.perform(patch("/api/v1/notifications/{id}/read", visible).header("Authorization", "Bearer user-a")).andExpect(status().isNoContent());
        assertThat(inbox.snapshot(USER_A).revision()).isNotEqualTo(before);
        assertThat(inbox.snapshot(USER_A).unreadCount()).isZero();
    }

    @Test
    void staffDeliveryFiltersAndDetailRequireAdminOrSupportWhileOwnerEndpointStaysScoped() throws Exception {
        UUID n = insert(USER_A, null);
        deliveries.insertEmail(n, Instant.now());
        UUID id = jdbc.queryForObject("SELECT id FROM notification_deliveries WHERE notification_id=?", UUID.class, n);
        jdbc.update("INSERT INTO notification_delivery_attempts(delivery_id,attempt_number,status,error_code) VALUES (?,1,'FAILED','SMTP_UNAVAILABLE')", id);
        jdbc.update("UPDATE notification_deliveries SET status='FAILED',attempt_count=1 WHERE id=?", id);
        mvc.perform(get("/api/v1/notifications/deliveries").header("Authorization", "Bearer user-a")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/notifications/deliveries").header("Authorization", "Bearer admin")
                .param("status", "FAILED").param("channel", "EMAIL").param("errorCode", "SMTP_UNAVAILABLE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].userId").value(USER_A.toString()));
        mvc.perform(get("/api/v1/notifications/deliveries/{id}", id).header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.attempts[0].errorCode").value("SMTP_UNAVAILABLE"));
        mvc.perform(get("/api/v1/notifications/deliveries").header("Authorization", "Bearer support"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1));
        mvc.perform(get("/api/v1/notifications/deliveries/{id}", id).header("Authorization", "Bearer support"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/notifications/deliveries").header("Authorization", "Bearer admin").param("status", "BOGUS"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/notifications/{id}/deliveries", n).header("Authorization", "Bearer admin"))
                .andExpect(status().isForbidden());
    }

    @Test
    void transactionalEmailCannotBeDisabledEvenByHistoricalPreference() {
        UUID n = insert(USER_A, null);
        jdbc.update("INSERT INTO notification_preferences(user_id,notification_type,channel,enabled) VALUES (?,'ORDER_CREATED','EMAIL',false)", USER_A);
        deliveries.insertEmail(n, Instant.now());
        UUID id = jdbc.queryForObject("SELECT id FROM notification_deliveries WHERE notification_id=?", UUID.class, n);
        assertThat(jdbc.queryForObject("SELECT status::text FROM notification_deliveries WHERE id=?", String.class, id)).isEqualTo("PENDING");
        assertThat(deliveries.skipDisabledEmail(id, Instant.now())).isFalse();
        var claim = worker.claimNext(Instant.now().plusSeconds(1), java.util.Set.of()).orElseThrow();
        assertThat(claim.notificationId()).isEqualTo(n);
        assertThat(worker.stillOwns(claim, Instant.now().plusSeconds(1))).isTrue();
    }

    @Test
    void optionalEmailOptOutAfterClaimStopsWorkerBeforeSendingAndKeepsHistory() {
        UUID n = insert(USER_A, null);
        jdbc.update("UPDATE notifications SET type='BID_OUTBID' WHERE id=?", n);
        deliveries.insertEmail(n, Instant.now());
        var claim = worker.claimNext(Instant.now().plusSeconds(1), java.util.Set.of()).orElseThrow();
        jdbc.update("INSERT INTO notification_preferences(user_id,notification_type,channel,enabled) VALUES (?,'BID_OUTBID','EMAIL',false)", USER_A);
        assertThat(worker.stillOwns(claim, Instant.now().plusSeconds(1))).isFalse();
        assertThat(jdbc.queryForObject("SELECT status::text FROM notification_deliveries WHERE id=?", String.class, claim.deliveryId())).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM notification_delivery_attempts WHERE id=?", String.class, claim.attemptId())).isEqualTo("PREFERENCE_DISABLED");
    }

    private UUID insert(UUID owner, OffsetDateTime readAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, user_id, type, title, content, read_at)
                VALUES (?, ?, 'ORDER_CREATED', 'Order placed', 'Fixture notification', ?)
                """, id, owner, readAt);
        return id;
    }

    @Test
    void privateSseStartsWithCurrentUserSnapshotAndRequiresPrivilege() throws Exception {
        insert(USER_A, null); insert(USER_B, null); insert(USER_B, null);
        mvc.perform(get("/api/v1/notifications/stream")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications/stream").header("Authorization", "Bearer no-privilege"))
                .andExpect(status().isForbidden());
        var result = mvc.perform(get("/api/v1/notifications/stream").header("Authorization", "Bearer user-a")
                .header("Last-Event-ID", "stale-cursor")).andExpect(status().isOk()).andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no")).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("event:notifications", "\"unreadCount\":1", "\"revision\":");
        result.getRequest().getAsyncContext().complete();
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM notifications WHERE id = ?", id);
    }

    private static Jwt token(String value, UUID userId, List<String> privileges) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(userId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of("BUYER")).claim("privileges", privileges).build();
    }
}
