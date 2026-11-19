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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:file:./password-change-test-none.properties",
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
        "nexus.iam.default-user-role=BUYER",
        "nexus.iam.allow-self-registration=true",
        "nexus.outbox.relay.enabled=false",
        "management.tracing.export.enabled=false"
})
class PasswordChangeIntegrationTest {
    private static final String PATH = "/api/v1/users/me/password";
    private static final String EMAIL = "changer@nexus.test";
    private static final String PASSWORD = "registered-password-123!";
    private static final String NEW_PASSWORD = "brand-new-password-456!";
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
    void theCurrentPasswordIsCheckedThenTheNewOneWorksAndOnlyThisSessionStays() throws Exception {
        UUID id = register();
        String current = KeycloakTestServer.signIn(EMAIL, PASSWORD);
        KeycloakTestServer.signIn(EMAIL, PASSWORD);
        assertThat(sessionId(current)).isNotBlank();
        assertThat(KeycloakTestServer.sessionCount(id)).isEqualTo(2);

        HttpResponse<String> wrong = post(current, Map.of("currentPassword", "not-my-password", "newPassword", NEW_PASSWORD));
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(code(wrong)).isEqualTo("USER_CURRENT_PASSWORD_INCORRECT");
        HttpResponse<String> same = post(current, Map.of("currentPassword", PASSWORD, "newPassword", PASSWORD));
        assertThat(code(same)).isEqualTo("USER_PASSWORD_REUSED");
        HttpResponse<String> shortOne = post(current, Map.of("currentPassword", PASSWORD, "newPassword", "short"));
        assertThat(code(shortOne)).isEqualTo("VALIDATION_FAILED");
        assertThat(KeycloakTestServer.sessionCount(id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from outbox where event_type = 'UserPasswordChanged'",
                Integer.class)).isZero();

        HttpResponse<String> changed = post(current, Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD));
        assertThat(changed.statusCode()).isEqualTo(204);
        assertThat(changed.headers().firstValue("Cache-Control")).contains("no-store");

        assertThat(KeycloakTestServer.sessionCount(id)).isEqualTo(1);
        assertThat(get(current).statusCode()).isEqualTo(200);
        String payload = jdbc.queryForObject("select payload::text from outbox where event_type = 'UserPasswordChanged'",
                String.class);
        assertThat(payload).contains("\"method\": \"SELF\"").contains(id.toString())
                .doesNotContain(NEW_PASSWORD).doesNotContain(PASSWORD);
        assertThat(KeycloakTestServer.signIn(EMAIL, NEW_PASSWORD)).isNotBlank();
        assertThatThrownBy(() -> KeycloakTestServer.signIn(EMAIL, PASSWORD)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anonymousCallersCannotChangeAPassword() throws Exception {
        HttpResponse<String> response = HTTP.send(request(PATH)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                        Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD)))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    private UUID register() throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("email", EMAIL);
        body.put("phone", "0901234567");
        body.put("password", PASSWORD);
        body.put("fullName", "Careful Buyer");
        body.put("identityNumber", "001235");
        body.put("gender", "OTHER");
        body.put("address", "Test address");
        body.put("dateOfBirth", "2000-01-01");
        HttpResponse<String> registered = HTTP.send(request("/api/v1/auth/register")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(registered.statusCode()).isEqualTo(201);
        UUID id = KeycloakTestServer.userIdByEmail(EMAIL).orElseThrow();
        KeycloakTestServer.markEmailVerified(id);
        return id;
    }

    private static String sessionId(String token) {
        String claims = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        return JSON.readTree(claims).path("sid").asString();
    }

    private static String code(HttpResponse<String> response) {
        return JSON.readTree(response.body()).get("code").asString();
    }

    private HttpResponse<String> post(String token, Map<String, String> body) throws Exception {
        return HTTP.send(request(PATH).header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String token) throws Exception {
        return HTTP.send(request("/api/v1/users/me").header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + environment.getRequiredProperty("local.server.port") + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
    }
}
