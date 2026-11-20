package com.group5.userservice.password;

import com.group5.userservice.shared.KeycloakTestServer;
import com.group5.userservice.shared.PostgresTestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:file:./password-reset-test-none.properties",
        "spring.cloud.config.enabled=false",
        "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.kafka.admin.auto-create=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "nexus.security.jwt.audience=nexus-services",
        "nexus.security.public-endpoints[0].method=POST",
        "nexus.security.public-endpoints[0].path=/api/v1/auth/register",
        "nexus.security.public-endpoints[1].method=POST",
        "nexus.security.public-endpoints[1].path=/api/v1/auth/password-reset",
        "nexus.security.public-endpoints[2].method=POST",
        "nexus.security.public-endpoints[2].path=/api/v1/auth/password-reset/confirm",
        "nexus.iam.default-user-role=BUYER",
        "nexus.iam.allow-self-registration=true",
        "nexus.password-reset.web-base-url=http://localhost:3000",
        "nexus.outbox.relay.enabled=false",
        "management.tracing.export.enabled=false"
})
class PasswordResetIntegrationTest {
    private static final String REQUEST = "/api/v1/auth/password-reset";
    private static final String CONFIRM = "/api/v1/auth/password-reset/confirm";
    private static final String PASSWORD = "registered-password-123!";
    private static final String NEW_PASSWORD = "brand-new-password-456!";
    private static final Pattern LINK = Pattern.compile("http://localhost:3000/reset-password\\?token=([A-Za-z0-9_-]{43})");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private Environment environment;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
        registry.add("nexus.keycloak.server-url", KeycloakTestServer::baseUrl);
        registry.add("nexus.keycloak.client-secret", () -> KeycloakTestServer.CLIENT_SECRET);
        registry.add("nexus.security.jwt.issuer", KeycloakTestServer::issuer);
        registry.add("nexus.security.jwt.jwk-set-uri", KeycloakTestServer::jwkSetUri);
        registry.add("spring.mail.host", KeycloakTestServer::smtpHost);
        registry.add("spring.mail.port", KeycloakTestServer::smtpPort);
    }

    @BeforeEach
    void clean() {
        jdbc.update("delete from password_reset_links");
        jdbc.update("delete from user_addresses");
        jdbc.update("delete from phone_verifications");
        jdbc.update("delete from verification_email_sends");
        jdbc.update("delete from seller");
        jdbc.update("delete from users");
        jdbc.update("delete from outbox");
        KeycloakTestServer.deleteRegisteredUsers();
        KeycloakTestServer.deleteAllMail();
    }

    @Test
    void theEmailedLinkSetsANewPasswordOnceSignsOutEverywhereAndIsAudited() throws Exception {
        UUID id = register("forgot@nexus.test");
        KeycloakTestServer.signIn("forgot@nexus.test", PASSWORD);
        assertThat(KeycloakTestServer.sessionCount(id)).isPositive();

        HttpResponse<String> requested = post(REQUEST, Map.of("email", "Forgot@Nexus.Test"));
        assertThat(requested.statusCode()).isEqualTo(204);
        assertThat(requested.headers().firstValue("Cache-Control")).contains("no-store");
        String token = token("forgot@nexus.test");
        assertThat(jdbc.queryForObject("select token_hash from password_reset_links", String.class))
                .isEqualTo(PasswordResetService.hash(token)).doesNotContain(token);

        assertThat(post(CONFIRM, Map.of("token", token, "newPassword", NEW_PASSWORD)).statusCode()).isEqualTo(204);

        assertThat(KeycloakTestServer.sessionCount(id)).isZero();
        assertThat(KeycloakTestServer.signIn("forgot@nexus.test", NEW_PASSWORD)).isNotBlank();
        assertThatThrownBy(() -> KeycloakTestServer.signIn("forgot@nexus.test", PASSWORD))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select used_at is not null from password_reset_links", Boolean.class)).isTrue();
        Map<String, Object> audit = jdbc.queryForMap("select event_type, payload::text as payload from outbox "
                + "where event_type = 'UserPasswordChanged'");
        assertThat(audit.get("payload").toString()).contains("\"method\": \"RESET_LINK\"")
                .contains(id.toString()).doesNotContain(NEW_PASSWORD);

        HttpResponse<String> again = post(CONFIRM, Map.of("token", token, "newPassword", "another-password-789!"));
        assertThat(again.statusCode()).isEqualTo(400);
        assertThat(code(again)).isEqualTo("USER_PASSWORD_RESET_LINK_INVALID");
    }

    @Test
    void anUnknownAddressGetsTheSameAnswerAndOneLinkIsSentPerMinute() throws Exception {
        assertThat(post(REQUEST, Map.of("email", "nobody@nexus.test")).statusCode()).isEqualTo(204);
        assertThat(KeycloakTestServer.mailTo("nobody@nexus.test")).isEmpty();
        register("minute@nexus.test");

        assertThat(post(REQUEST, Map.of("email", "minute@nexus.test")).statusCode()).isEqualTo(204);
        assertThat(post(REQUEST, Map.of("email", "minute@nexus.test")).statusCode()).isEqualTo(204);
        assertThat(resetMail("minute@nexus.test")).hasSize(1);
        String first = token("minute@nexus.test");

        jdbc.update("update password_reset_links set created_at = created_at - interval '61 seconds'");
        assertThat(post(REQUEST, Map.of("email", "minute@nexus.test")).statusCode()).isEqualTo(204);
        assertThat(resetMail("minute@nexus.test")).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from password_reset_links", Integer.class)).isEqualTo(1);
        assertThat(code(post(CONFIRM, Map.of("token", first, "newPassword", NEW_PASSWORD))))
                .isEqualTo("USER_PASSWORD_RESET_LINK_INVALID");
        assertThat(post(CONFIRM, Map.of("token", token("minute@nexus.test"), "newPassword", NEW_PASSWORD))
                .statusCode()).isEqualTo(204);
    }

    @Test
    void theCurrentPasswordAndAShortOneAreRejectedAndTheLinkStaysUsableUntilItExpires() throws Exception {
        register("policy@nexus.test");
        post(REQUEST, Map.of("email", "policy@nexus.test"));
        String token = token("policy@nexus.test");

        HttpResponse<String> reused = post(CONFIRM, Map.of("token", token, "newPassword", PASSWORD));
        assertThat(reused.statusCode()).isEqualTo(400);
        assertThat(code(reused)).isEqualTo("USER_PASSWORD_REUSED");
        HttpResponse<String> shortOne = post(CONFIRM, Map.of("token", token, "newPassword", "short"));
        assertThat(shortOne.statusCode()).isEqualTo(400);
        assertThat(code(shortOne)).isEqualTo("VALIDATION_FAILED");
        assertThat(code(post(CONFIRM, Map.of("token", "x".repeat(43), "newPassword", NEW_PASSWORD))))
                .isEqualTo("USER_PASSWORD_RESET_LINK_INVALID");
        assertThat(code(post(CONFIRM, Map.of("token", "not a token", "newPassword", NEW_PASSWORD))))
                .isEqualTo("VALIDATION_FAILED");
        assertThat(jdbc.queryForObject("select count(*) from outbox where event_type = 'UserPasswordChanged'",
                Integer.class)).isZero();

        jdbc.update("update password_reset_links set created_at = created_at - interval '31 minutes', "
                + "expires_at = expires_at - interval '31 minutes'");
        assertThat(code(post(CONFIRM, Map.of("token", token, "newPassword", NEW_PASSWORD))))
                .isEqualTo("USER_PASSWORD_RESET_LINK_INVALID");
        assertThat(KeycloakTestServer.signIn("policy@nexus.test", PASSWORD)).isNotBlank();
    }

    private UUID register(String email) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("phone", "0901234567");
        body.put("password", PASSWORD);
        body.put("fullName", "Forgetful Buyer");
        body.put("identityNumber", "001234");
        body.put("gender", "OTHER");
        body.put("address", "Test address");
        body.put("dateOfBirth", "2000-01-01");
        assertThat(post("/api/v1/auth/register", body).statusCode()).isEqualTo(201);
        UUID id = KeycloakTestServer.userIdByEmail(email).orElseThrow();
        KeycloakTestServer.markEmailVerified(id);
        return id;
    }

    private static List<String> resetMail(String email) {
        return KeycloakTestServer.mailTo(email).stream().filter(text -> text.contains("/reset-password?token=")).toList();
    }

    private static String token(String email) {
        Matcher link = LINK.matcher(resetMail(email).getFirst());
        assertThat(link.find()).isTrue();
        return link.group(1);
    }

    private static String code(HttpResponse<String> response) {
        return JSON.readTree(response.body()).get("code").asString();
    }

    private HttpResponse<String> post(String path, Map<?, ?> body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + environment.getRequiredProperty("local.server.port") + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
