package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUserAuditor;
import com.group5.common_security.autoconfigure.JpaAuditingAutoConfiguration;
import com.group5.userservice.shared.KeycloakTestServer;
import com.group5.userservice.shared.config.IamProperties;
import com.group5.userservice.shared.PostgresTestDatabase;
import com.group5.userservice.shared.exception.UserErrorCode;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

/**
 * HTTP tests against disposable PostgreSQL 18 and Keycloak 26.7 Testcontainers instances.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:file:${nexus.registration.test-config}",
        "spring.cloud.config.enabled=false",
        "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.refresh.additional-property-sources-to-retain=Inlined Test Properties,Dynamic Test Properties",
        "spring.cloud.discovery.enabled=false",
        "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.kafka.admin.auto-create=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "nexus.security.jwt.audience=nexus-services",
        "nexus.security.public-endpoints[0].method=POST",
        "nexus.security.public-endpoints[0].path=/api/v1/auth/register",
        "management.endpoints.web.exposure.include=health,refresh",
        "management.tracing.enabled=true",
        "management.tracing.export.enabled=false",
        "nexus.outbox.relay.enabled=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.group5.userservice.iam.RegistrationIntegrationTest$SqlCapture"
})
class RegistrationIntegrationTest {
    private static final String REGISTER = "/api/v1/auth/register";
    private static final String PASSWORD = "registered-password-123!";
    private static final Path TEMP = createTestFiles();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private Environment environment;
    @Autowired private IamProperties iamProperties;
    @Autowired private ApplicationContext context;
    @MockitoSpyBean private KeycloakAdminClient keycloak;
    private boolean configChanged;

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
    void prepareFixtures() {
        // These rows exist only in the disposable Testcontainers database.
        jdbc.update("delete from users");
        jdbc.update("delete from outbox");
        KeycloakTestServer.deleteRegisteredUsers();
        SqlCapture.STATEMENTS.clear();
    }

    @AfterEach
    void restoreConfig() throws Exception {
        if (configChanged) {
            writeConfig("BUYER", true);
            assertThat(post("/actuator/refresh", Map.of()).statusCode()).isEqualTo(200);
        }
    }

    @AfterAll
    static void removeTestFiles() throws Exception {
        Files.deleteIfExists(TEMP.resolve("registration.properties"));
        Files.deleteIfExists(TEMP);
        System.clearProperty("nexus.registration.test-config");
    }

    @Test
    void productionAutoConfigurationAuditsRegistrationWithoutTestAuditingConfiguration() throws Exception {
        assertThat(context.getBeansOfType(JpaAuditingAutoConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(CurrentUserAuditor.class)).containsOnlyKeys("currentUserAuditor");
        assertThat(context.containsBean("testAuditor")).isFalse();

        assertThat(post(REGISTER, request("audited@nexus.test", "0901234567", "001234"))
                .statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select created_by from users", UUID.class))
                .isEqualTo(CurrentUserAuditor.SYSTEM_ID);
        assertThat(jdbc.queryForObject("select updated_by from users", UUID.class))
                .isEqualTo(CurrentUserAuditor.SYSTEM_ID);
    }

    @Test
    void registrationReturns201WithTheKeycloakUserIdAsProfileId() throws Exception {
        HttpResponse<String> response = post(REGISTER, request("buyer@nexus.test", "0901234567", "001234"));

        assertThat(response.statusCode()).isEqualTo(201);
        UUID keycloakId = KeycloakTestServer.userIdByEmail("buyer@nexus.test").orElseThrow();
        assertThat(JSON.readTree(response.body()).get("data").get("id").asString()).isEqualTo(keycloakId.toString());
        assertThat(jdbc.queryForObject("select id from users", UUID.class)).isEqualTo(keycloakId);
        assertThat(KeycloakTestServer.realmRoles(keycloakId)).contains("BUYER");
    }

    @Test
    void newAccountSignsInThroughKeycloakAndReadsItsOwnProfile() throws Exception {
        assertThat(post(REGISTER, request("login@nexus.test", "0901234567", "001234")).statusCode()).isEqualTo(201);
        UUID keycloakId = KeycloakTestServer.userIdByEmail("login@nexus.test").orElseThrow();

        String token = KeycloakTestServer.signIn("LOGIN@NEXUS.TEST", PASSWORD);
        HttpResponse<String> profile = get("/api/v1/users/me", token);

        assertThat(profile.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(profile.body()).get("data");
        assertThat(body.get("id").asString()).isEqualTo(keycloakId.toString());
        assertThat(body.get("email").asString()).isEqualTo("login@nexus.test");
        assertThat(body.get("fullName").asString()).isEqualTo("Registered Buyer");
        assertThat(body.get("roles").toString()).isEqualTo("[\"BUYER\"]");
    }

    @Test
    void profileEmailChangeMovesTheKeycloakSignIn() throws Exception {
        assertThat(post(REGISTER, request("before@nexus.test", "0901234567", "001234")).statusCode()).isEqualTo(201);
        UUID keycloakId = KeycloakTestServer.userIdByEmail("before@nexus.test").orElseThrow();
        String token = KeycloakTestServer.signIn("before@nexus.test", PASSWORD);

        HttpResponse<String> updated = put("/api/v1/users/me", Map.of("email", "After@Nexus.Test"), token);

        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(updated.body()).get("data").get("email").asString()).isEqualTo("after@nexus.test");
        assertThat(KeycloakTestServer.userIdByEmail("after@nexus.test")).contains(keycloakId);
        assertThat(KeycloakTestServer.userIdByEmail("before@nexus.test")).isEmpty();
        assertThat(KeycloakTestServer.signIn("after@nexus.test", PASSWORD)).isNotBlank();
    }

    @Test
    void emailChangeKeepsATemporaryKeycloakLockout() throws Exception {
        assertThat(post(REGISTER, request("locked@nexus.test", "0901234567", "001234")).statusCode()).isEqualTo(201);
        UUID keycloakId = KeycloakTestServer.userIdByEmail("locked@nexus.test").orElseThrow();
        String token = KeycloakTestServer.signIn("locked@nexus.test", PASSWORD);
        for (int attempt = 0; attempt < 5; attempt++) {
            KeycloakTestServer.failSignIn("locked@nexus.test");
        }
        assertThat(KeycloakTestServer.bruteForceLocked(keycloakId)).isTrue();

        HttpResponse<String> updated = put("/api/v1/users/me", Map.of("email", "moved@nexus.test"), token);

        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(KeycloakTestServer.userIdByEmail("moved@nexus.test")).contains(keycloakId);
        assertThat(KeycloakTestServer.bruteForceLocked(keycloakId)).isTrue();
        assertThatThrownBy(() -> KeycloakTestServer.signIn("moved@nexus.test", PASSWORD))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lostKeycloakResponseAfterTheEmailChangeRestoresTheOldEmail() throws Exception {
        assertThat(post(REGISTER, request("kept@nexus.test", "0901234567", "001234")).statusCode()).isEqualTo(201);
        UUID keycloakId = KeycloakTestServer.userIdByEmail("kept@nexus.test").orElseThrow();
        KeycloakTestServer.markEmailVerified(keycloakId);
        String token = KeycloakTestServer.signIn("kept@nexus.test", PASSWORD);
        doAnswer(call -> {
            call.callRealMethod();
            throw new DomainException(UserErrorCode.USER_IDENTITY_PROVIDER_UNAVAILABLE);
        }).when(keycloak).updateEmail(keycloakId, "lost@nexus.test", false);

        HttpResponse<String> updated = put("/api/v1/users/me", Map.of("email", "lost@nexus.test"), token);

        assertThat(updated.statusCode()).isEqualTo(503);
        assertThat(JSON.readTree(updated.body()).get("code").asString()).isEqualTo("USER_IDENTITY_PROVIDER_UNAVAILABLE");
        assertThat(jdbc.queryForObject("select email from users", String.class)).isEqualTo("kept@nexus.test");
        assertThat(KeycloakTestServer.userIdByEmail("lost@nexus.test")).isEmpty();
        assertThat(KeycloakTestServer.userIdByEmail("kept@nexus.test")).contains(keycloakId);
        assertThat(KeycloakTestServer.emailVerified(keycloakId)).isTrue();
        assertThat(KeycloakTestServer.signIn("kept@nexus.test", PASSWORD)).isNotBlank();
    }

    @Test
    void profileEmailHeldByAnotherKeycloakAccountReturns409AndKeepsBothRecords() throws Exception {
        assertThat(post(REGISTER, request("keep@nexus.test", "0901234567", "001234")).statusCode()).isEqualTo(201);
        UUID keycloakId = KeycloakTestServer.userIdByEmail("keep@nexus.test").orElseThrow();
        String token = KeycloakTestServer.signIn("keep@nexus.test", PASSWORD);

        HttpResponse<String> rejected = put("/api/v1/users/me", Map.of("email", KeycloakTestServer.DEMO_ADMIN), token);

        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(rejected.body()).get("code").asString()).isEqualTo("USER_EMAIL_ALREADY_USED");
        assertThat(jdbc.queryForObject("select email from users", String.class)).isEqualTo("keep@nexus.test");
        assertThat(KeycloakTestServer.userIdByEmail("keep@nexus.test")).contains(keycloakId);
    }

    @Test
    void adminListFiltersByKeycloakRoleAndShowsKeycloakRoles() throws Exception {
        assertThat(post(REGISTER, request("buyer@nexus.test", "0901234567", "001234")).statusCode()).isEqualTo(201);
        configChanged = true;
        writeConfig("SELLER", true);
        assertThat(post("/actuator/refresh", Map.of()).statusCode()).isEqualTo(200);
        assertThat(post(REGISTER, request("seller@nexus.test", "0901234568", "001235")).statusCode()).isEqualTo(201);
        String admin = KeycloakTestServer.signIn(KeycloakTestServer.DEMO_ADMIN, KeycloakTestServer.DEMO_ADMIN_PASSWORD);

        HttpResponse<String> sellers = get("/api/v1/users?role=seller", admin);
        HttpResponse<String> everyone = get("/api/v1/users?sort=email", admin);

        assertThat(sellers.statusCode()).isEqualTo(200);
        JsonNode sellerPage = JSON.readTree(sellers.body()).get("data");
        assertThat(sellerPage.size()).isEqualTo(1);
        assertThat(sellerPage.get(0).get("email").asString()).isEqualTo("seller@nexus.test");
        assertThat(sellerPage.get(0).get("roles").toString()).isEqualTo("[\"SELLER\"]");
        assertThat(everyone.statusCode()).isEqualTo(200);
        JsonNode everyonePage = JSON.readTree(everyone.body()).get("data");
        assertThat(everyonePage.size()).isEqualTo(2);
        assertThat(everyonePage.get(0).get("roles").toString()).isEqualTo("[\"BUYER\"]");
    }

    @Test
    void createsOneBuyerRoleAndNeverReturnsSecrets() throws Exception {
        HttpResponse<String> response = post(REGISTER, request("buyer@nexus.test", "0901234567", "001234"));
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = JSON.readTree(response.body()).get("data");
        assertThat(body.get("roles").get(0).asString()).isEqualTo("BUYER");
        assertThat(body.get("roles").size()).isEqualTo(1);
        assertThat(body.has("passwordHash")).isFalse();
        assertThat(body.has("identityNumber")).isFalse();
        assertThat(body.has("password")).isFalse();
        assertThat(count("users")).isEqualTo(1);
        assertThat(KeycloakTestServer.registeredUserCount()).isEqualTo(1);
    }

    @Test
    void storesNormalizedEmailAndPhone() throws Exception {
        assertThat(post(REGISTER, request("  Buyer@Nexus.Test  ", " (090) 123-4567 ", "001234"))
                .statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select email from users", String.class)).isEqualTo("buyer@nexus.test");
        assertThat(jdbc.queryForObject("select phone from users", String.class)).isEqualTo("0901234567");
        assertThat(KeycloakTestServer.userIdByEmail("buyer@nexus.test")).isPresent();
    }

    @Test
    void duplicateEmailReturns409AndWritesNoRow() throws Exception {
        duplicate("first@nexus.test", "0901234568", "001235", "email");
    }

    @Test
    void duplicatePhoneReturns409AndWritesNoRow() throws Exception {
        duplicate("second@nexus.test", "(090) 123-4567", "001235", "phone");
    }

    @Test
    void duplicateIdentityReturns409AndWritesNoRow() throws Exception {
        duplicate("second@nexus.test", "0901234568", "001234", "identityNumber");
    }

    @Test
    void simultaneousEmailRegistrationsReturnExactly201And409() throws Exception {
        concurrent(request("race@nexus.test", "0901234567", "001234"),
                request("race@nexus.test", "0901234568", "001235"));
        assertThat(KeycloakTestServer.registeredUserCount()).isEqualTo(1);
    }

    @Test
    void simultaneousPhoneRegistrationsReturnExactly201And409() throws Exception {
        concurrent(request("first@nexus.test", "0901234567", "001234"),
                request("second@nexus.test", "0901234567", "001235"));
        assertThat(KeycloakTestServer.registeredUserCount()).isEqualTo(1);
    }

    @Test
    void simultaneousIdentityRegistrationsReturnExactly201And409WithoutSchemaChanges() throws Exception {
        concurrent(request("first@nexus.test", "0901234567", "001234"),
                request("second@nexus.test", "0901234568", "001234"));
        assertThat(KeycloakTestServer.registeredUserCount()).isEqualTo(1);
    }

    @Test
    void failedProfileInsertRemovesTheKeycloakAccount() throws Exception {
        jdbc.execute("""
                create function reject_registration() returns trigger language plpgsql as $$
                begin
                    raise exception 'registration rejected by test trigger';
                end $$
                """);
        jdbc.execute("create trigger reject_registration before insert on users "
                + "for each row execute function reject_registration()");
        HttpResponse<String> response;
        try {
            response = post(REGISTER, request("orphan@nexus.test", "0901234567", "001234"));
        } finally {
            jdbc.execute("drop trigger reject_registration on users");
            jdbc.execute("drop function reject_registration()");
        }
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(count("users")).isZero();
        assertThat(KeycloakTestServer.userIdByEmail("orphan@nexus.test")).isEmpty();
        assertThat(KeycloakTestServer.registeredUserCount()).isZero();
    }

    @Test
    void invalidEmailNamesTheFieldAndProducesNoInsert() throws Exception {
        invalid(request("not-an-email", "0901234567", "001234"), "email");
    }

    @Test
    void missingIdentityNamesTheFieldAndProducesNoInsert() throws Exception {
        Map<String, String> request = request("buyer@nexus.test", "0901234567", "001234");
        request.remove("identityNumber");
        invalid(request, "identityNumber");
    }

    @Test
    void missingAddressNamesTheFieldAndProducesNoInsert() throws Exception {
        Map<String, String> request = request("buyer@nexus.test", "0901234567", "001234");
        request.remove("address");
        invalid(request, "address");
    }

    @Test
    void malformedDateNamesTheFieldAndProducesNoInsert() throws Exception {
        Map<String, String> request = request("buyer@nexus.test", "0901234567", "001234");
        request.put("dateOfBirth", "2000-02-30");
        invalid(request, "dateOfBirth");
    }

    @Test
    void missingConfiguredRoleLeavesNoAccount() throws Exception {
        configChanged = true;
        writeConfig("MISSING_ROLE", true);
        assertThat(post("/actuator/refresh", Map.of()).statusCode()).isEqualTo(200);
        HttpResponse<String> response = post(REGISTER, request("buyer@nexus.test", "0901234567", "001234"));
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(JSON.readTree(response.body()).get("code").asString())
                .isEqualTo("USER_REGISTRATION_ROLE_UNAVAILABLE");
        assertThat(count("users")).isZero();
        assertThat(KeycloakTestServer.registeredUserCount()).isZero();
        assertNoInsert();
    }

    @Test
    void refreshedDefaultRoleIsAssignedInKeycloakInsteadOfHardcodedBuyer() throws Exception {
        configChanged = true;
        writeConfig("SELLER", true);
        assertThat(post("/actuator/refresh", Map.of()).statusCode()).isEqualTo(200);
        assertThat(iamProperties.getDefaultUserRole()).isEqualTo("SELLER");
        HttpResponse<String> response = post(REGISTER, request("seller@nexus.test", "0901234567", "001234"));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(JSON.readTree(response.body()).get("data").get("roles").get(0).asString()).isEqualTo("SELLER");
        UUID keycloakId = KeycloakTestServer.userIdByEmail("seller@nexus.test").orElseThrow();
        assertThat(KeycloakTestServer.realmRoles(keycloakId)).contains("SELLER").doesNotContain("BUYER");
    }

    @Test
    void actuatorRefreshDisablesRegistrationWithoutRestartAndCanEnableItAgain() throws Exception {
        UserService original = context.getBean(UserService.class);
        long started = context.getStartupDate();
        configChanged = true;
        writeConfig("BUYER", false);
        assertThat(post("/actuator/refresh", Map.of()).statusCode()).isEqualTo(200);
        assertThat(iamProperties.isAllowSelfRegistration()).isFalse();
        assertThat(post(REGISTER, request("buyer@nexus.test", "0901234567", "001234"))
                .statusCode()).isEqualTo(403);
        assertThat(count("users")).isZero();
        assertThat(KeycloakTestServer.registeredUserCount()).isZero();
        assertNoInsert();
        assertThat(context.getBean(UserService.class)).isSameAs(original);
        assertThat(context.getStartupDate()).isEqualTo(started);

        writeConfig("BUYER", true);
        assertThat(post("/actuator/refresh", Map.of()).statusCode()).isEqualTo(200);
        assertThat(iamProperties.isAllowSelfRegistration()).isTrue();
        assertThat(post(REGISTER, request("buyer@nexus.test", "0901234567", "001234"))
                .statusCode()).isEqualTo(201);
    }

    /** Kiểm chứng toàn bộ thao tác Admin qua HTTP, profile PostgreSQL, Keycloak thật và audit outbox. */
    @Test
    void adminEndpointsCoordinateRealProfilesCredentialsRolesAndDeletion() throws Exception {
        Map<String, Object> create = new LinkedHashMap<>(request("managed@nexus.test", "0901234567", "ADMIN-211"));
        create.put("roleCodes", List.of("BUYER", "SELLER"));
        HttpResponse<String> response = admin("POST", "/api/v1/users", create);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        UUID id = UUID.fromString(JSON.readTree(response.body()).get("data").get("id").asString());
        assertThat(KeycloakTestServer.realmRoles(id)).contains("BUYER", "SELLER");
        assertThat(count("outbox")).isEqualTo(2);
        assertThat(admin("GET", "/api/v1/roles", Map.of()).statusCode()).isEqualTo(200);
        JsonNode permissions = JSON.readTree(admin("GET", "/api/v1/permissions", Map.of()).body());
        assertThat(permissions.get("data").size()).isEqualTo(65);
        assertThat(admin("PUT", "/api/v1/users/" + id, Map.of("email", "managed-new@nexus.test", "address", "New address")).statusCode()).isEqualTo(200);
        assertThat(KeycloakTestServer.signIn("managed-new@nexus.test", PASSWORD)).isNotBlank();
        assertThat(admin("PUT", "/api/v1/users/" + id + "/roles", Map.of("roleCodes", List.of("SELLER"))).statusCode()).isEqualTo(200);
        assertThat(KeycloakTestServer.realmRoles(id)).contains("SELLER").doesNotContain("BUYER");
        HttpResponse<String> samePassword = admin("PUT", "/api/v1/users/" + id + "/password", Map.of("newPassword", PASSWORD));
        assertThat(samePassword.statusCode()).as(samePassword.body()).isEqualTo(400);
        assertThat(JSON.readTree(samePassword.body()).get("code").asString()).isEqualTo("USER_IDENTITY_REJECTED");
        String changedPassword = "managed-new-password-211!";
        assertThat(admin("PUT", "/api/v1/users/" + id + "/password", Map.of("newPassword", changedPassword)).statusCode()).isEqualTo(204);
        assertThat(KeycloakTestServer.signIn("managed-new@nexus.test", changedPassword)).isNotBlank();
        assertThat(admin("DELETE", "/api/v1/users/" + id, Map.of()).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("select status::text from users where id=?", String.class, id)).isEqualTo("DELETED");
        assertThatThrownBy(() -> KeycloakTestServer.signIn("managed-new@nexus.test", changedPassword))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sign-in failed");
        assertThat(count("outbox")).isEqualTo(6);
    }

    /** Endpoint unlock xóa khóa brute-force thật, cho đăng nhập lại và trả 409 khi mở khóa lần nữa. */
    @Test
    void adminUnlockEndpointRestoresActualKeycloakSignIn() throws Exception {
        Map<String, Object> create = new LinkedHashMap<>(request("unlock@nexus.test", "0901234567", "UNLOCK-211"));
        create.put("roleCodes", List.of("BUYER"));
        HttpResponse<String> response = admin("POST", "/api/v1/users", create);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        UUID id = UUID.fromString(JSON.readTree(response.body()).get("data").get("id").asString());
        for (int i = 0; i < 5; i++) KeycloakTestServer.failSignIn("unlock@nexus.test");
        assertThat(KeycloakTestServer.bruteForceLocked(id)).isTrue();
        assertThat(admin("POST", "/api/v1/users/" + id + "/unlock", Map.of()).statusCode()).isEqualTo(204);
        assertThat(KeycloakTestServer.bruteForceLocked(id)).isFalse();
        assertThat(KeycloakTestServer.signIn("unlock@nexus.test", PASSWORD)).isNotBlank();
        assertThat(admin("POST", "/api/v1/users/" + id + "/unlock", Map.of()).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from outbox where event_type='UserUnlocked'", Integer.class)).isEqualTo(1);
    }

    /** Gửi request quản trị bằng token lấy qua đăng nhập PKCE thật của demo Admin. */
    private HttpResponse<String> admin(String method, String path, Map<?, ?> body) throws Exception {
        String token = KeycloakTestServer.signIn(KeycloakTestServer.DEMO_ADMIN, KeycloakTestServer.DEMO_ADMIN_PASSWORD);
        HttpRequest.BodyPublisher publisher = method.equals("GET") || method.equals("DELETE")
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        return HTTP.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void duplicate(String email, String phone, String identity, String field) throws Exception {
        assertThat(post(REGISTER, request("first@nexus.test", "0901234567", "001234"))
                .statusCode()).isEqualTo(201);
        SqlCapture.STATEMENTS.clear();
        HttpResponse<String> duplicate = post(REGISTER, request(email, phone, identity));
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(duplicate.body()).get("detail").asString()).contains(field);
        assertThat(count("users")).isEqualTo(1);
        assertThat(KeycloakTestServer.registeredUserCount()).isEqualTo(1);
        assertNoInsert();
    }

    private void invalid(Map<String, String> request, String field) throws Exception {
        HttpResponse<String> response = post(REGISTER, request);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).get("errors").toString()).contains("\"field\":\"" + field + "\"");
        assertThat(count("users")).isZero();
        assertThat(KeycloakTestServer.registeredUserCount()).isZero();
        assertNoInsert();
    }

    private void concurrent(Map<String, String> first, Map<String, String> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { ready.countDown(); start.await(); return post(REGISTER, first).statusCode(); });
            var b = pool.submit(() -> { ready.countDown(); start.await(); return post(REGISTER, second).statusCode(); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(count("users")).isEqualTo(1);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static void assertNoInsert() {
        assertThat(SqlCapture.STATEMENTS).noneMatch(sql -> sql.stripLeading().toLowerCase(java.util.Locale.ROOT)
                .startsWith("insert"));
    }

    /** Refresh trong fixture cũng dùng token Admin thật; đăng ký public vẫn gửi request không token. */
    private HttpResponse<String> post(String path, Map<?, ?> body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (path.equals("/actuator/refresh")) {
            request.header("Authorization", "Bearer " + KeycloakTestServer.signIn(
                    KeycloakTestServer.DEMO_ADMIN, KeycloakTestServer.DEMO_ADMIN_PASSWORD));
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, Map<?, ?> body, String token) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path);
    }

    private static Map<String, String> request(String email, String phone, String identity) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("email", email);
        values.put("phone", phone);
        values.put("password", PASSWORD);
        values.put("fullName", "Registered Buyer");
        values.put("identityNumber", identity);
        values.put("gender", "OTHER");
        values.put("address", "Test address");
        values.put("dateOfBirth", "2000-01-01");
        return values;
    }

    private static void writeConfig(String role, boolean enabled) throws Exception {
        Files.writeString(TEMP.resolve("registration.properties"),
                "nexus.iam.default-user-role=" + role + "\nnexus.iam.allow-self-registration=" + enabled + "\n");
    }

    private static Path createTestFiles() {
        try {
            Path directory = Files.createTempDirectory("nexus-registration-test-");
            Files.writeString(directory.resolve("registration.properties"),
                    "nexus.iam.default-user-role=BUYER\nnexus.iam.allow-self-registration=true\n");
            System.setProperty("nexus.registration.test-config", directory.resolve("registration.properties").toString());
            return directory;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create isolated test configuration", exception);
        }
    }

    public static class SqlCapture implements StatementInspector {
        static final ConcurrentLinkedQueue<String> STATEMENTS = new ConcurrentLinkedQueue<>();

        @Override
        public String inspect(String sql) {
            STATEMENTS.add(sql);
            return sql;
        }
    }
}
