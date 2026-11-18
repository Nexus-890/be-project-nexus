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
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false"
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
        reset(decoder);
        when(decoder.decode("user-a")).thenReturn(token("user-a", USER_A, List.of("NOTIFICATION.VIEW")));
        when(decoder.decode("user-b")).thenReturn(token("user-b", USER_B, List.of("NOTIFICATION.VIEW")));
        when(decoder.decode("no-privilege")).thenReturn(token("no-privilege", USER_A, List.of()));
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
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

    private UUID insert(UUID owner, OffsetDateTime readAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, user_id, type, title, content, read_at)
                VALUES (?, ?, 'ORDER_CREATED', 'Order placed', 'Fixture notification', ?)
                """, id, owner, readAt);
        return id;
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
