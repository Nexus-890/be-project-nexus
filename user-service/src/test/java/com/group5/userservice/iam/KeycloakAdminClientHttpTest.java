package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.shared.config.KeycloakProperties;
import com.group5.userservice.shared.exception.UserErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Kiểm tra hợp đồng HTTP thật của client, không thay RestClient bằng mock phương thức. */
class KeycloakAdminClientHttpTest {
    private static final UUID USER = UUID.fromString("35754f22-99b9-4a70-b8ea-612b876ba5ba");
    private static final UUID ROLE = UUID.fromString("45754f22-99b9-4a70-b8ea-612b876ba5ba");
    private static final String USER_PATH = "/admin/realms/nexus/users/" + USER;
    private static final String LOCK_PATH = "/admin/realms/nexus/attack-detection/brute-force/users/" + USER;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Queue<Reply> replies = new ArrayDeque<>();
    private final List<Request> requests = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private HttpServer server;
    private KeycloakAdminClient client;
    private int tokens;
    private Map<UUID, JsonNode> realmUsers;

    /** Tạo Admin API cục bộ và client thật với thời gian cố định để quan sát token cache. */
    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        KeycloakProperties properties = new KeycloakProperties();
        properties.setServerUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setClientSecret("test-service-secret");
        properties.setTimeout(Duration.ofSeconds(2));
        client = new KeycloakAdminClient(properties,
                Clock.fixed(Instant.parse("2026-11-14T01:00:00Z"), ZoneOffset.UTC),
                new DefaultListableBeanFactory().getBeanProvider(ObservationRegistry.class));
    }

    /** Dừng server và xác nhận client không thiếu hoặc gửi thừa bước HTTP trong kịch bản. */
    @AfterEach
    void tearDown() {
        server.stop(0);
        assertThat(failures).isEmpty();
        assertThat(replies).isEmpty();
    }

    /** Giữ snapshot thuộc tính Keycloak nhưng chỉ ghi lại trường có thể sửa khi hoàn tác. */
    @Test
    void snapshotAndRestoreKeepWritableAttributesWithoutSendingCredentials() {
        Map<String, Object> state = account();
        state.put("credentials", List.of(Map.of("type", "password", "secretData", "never-resend")));
        state.put("realmRoles", List.of("ADMIN"));
        state.put("id", USER.toString());
        expect("GET", USER_PATH, 200, state);
        expect("PUT", USER_PATH, 204, null);

        Map<String, Object> snapshot = client.userState(USER);
        client.restoreUser(USER, snapshot);

        JsonNode body = body(1);
        assertThat(body.get("attributes").get("phone").get(0).asString()).isEqualTo("0900000001");
        assertThat(body.get("requiredActions").get(0).asString()).isEqualTo("VERIFY_EMAIL");
        assertThat(body.get("emailVerified").asBoolean()).isTrue();
        assertThat(body.has("credentials")).isFalse();
        assertThat(body.has("realmRoles")).isFalse();
        assertThat(body.has("id")).isFalse();
        assertThat(tokens).isEqualTo(1);
        assertThat(requests).allSatisfy(request -> assertThat(request.authorization()).isEqualTo("Bearer token-1"));
    }

    /** Đổi email phải bỏ xác minh cũ, đồng bộ tên và enabled, đồng thời giữ attribute/action. */
    @Test
    void updateUserChangesEmailNameAndEnabledWithoutLosingOtherFields() {
        expect("GET", USER_PATH, 200, account());
        expect("PUT", USER_PATH, 204, null);

        client.updateUser(USER, "changed@nexus.local", "Nguyen Van An", false);

        JsonNode body = body(1);
        assertThat(body.get("firstName").asString()).isEqualTo("Nguyen Van");
        assertThat(body.get("lastName").asString()).isEqualTo("An");
        assertThat(body.get("email").asString()).isEqualTo("changed@nexus.local");
        assertThat(body.get("emailVerified").asBoolean()).isFalse();
        assertThat(body.get("enabled").asBoolean()).isFalse();
        assertThat(body.get("username").asString()).isEqualTo("original-login");
        assertThat(body.get("attributes").get("phone").get(0).asString()).isEqualTo("0900000001");
    }

    /** Realm giả lập giữ uniqueness để chuỗi tạo, đổi email và tạo lại thất bại nếu còn username cũ. */
    @Test
    void changedEmailFreesTheOriginalUsernameForAnotherAccount() {
        realmUsers = new LinkedHashMap<>();
        UUID first = client.createUser("original@nexus.local", "test-password", "First Account");
        assertError(() -> client.createUser("original@nexus.local", "test-password", "Duplicate Account"),
                UserErrorCode.USER_EMAIL_ALREADY_USED);

        client.updateUser(first, "changed@nexus.local", "First Account", true);
        UUID second = client.createUser("original@nexus.local", "test-password", "Second Account");

        assertThat(second).isNotEqualTo(first);
        assertThat(client.userState(first)).containsEntry("email", "changed@nexus.local")
                .containsEntry("username", "changed@nexus.local");
        assertThat(client.userState(second)).containsEntry("email", "original@nexus.local")
                .containsEntry("username", "original@nexus.local");
    }

    /** Keycloak có thể chuẩn hóa username; email username vẫn phải đổi dù khác chữ hoa/thường. */
    @ParameterizedTest
    @CsvSource({"original@nexus.local,original@nexus.local", "original@nexus.local,Original@Nexus.Local"})
    void emailUsernamesFollowEmailChangesDespiteCaseNormalization(String username, String previousEmail) {
        Map<String, Object> state = account();
        state.put("username", username);
        state.put("email", previousEmail);
        expect("GET", USER_PATH, 200, state);
        expect("PUT", USER_PATH, 204, null);

        client.updateUser(USER, "changed@nexus.local", "An", true);

        assertThat(body(1).get("username").asString()).isEqualTo("changed@nexus.local");
    }

    /** Đổi email qua self-service cũng giải phóng username email, không chỉ endpoint quản trị. */
    @Test
    void selfServiceEmailChangeAlsoFreesTheOriginalUsername() {
        realmUsers = new LinkedHashMap<>();
        UUID first = client.createUser("original@nexus.local", "test-password", "First Account");

        client.updateEmail(first, "changed@nexus.local", false);
        UUID second = client.createUser("original@nexus.local", "test-password", "Second Account");

        assertThat(second).isNotEqualTo(first);
        assertThat(client.userState(first)).containsEntry("username", "changed@nexus.local");
    }

    /** Cập nhật tên đơn từ không được mất trạng thái xác minh khi email vẫn giữ nguyên. */
    @Test
    void unchangedEmailRetainsVerificationForSingleWordNames() {
        expect("GET", USER_PATH, 200, account());
        expect("PUT", USER_PATH, 204, null);

        client.updateUser(USER, "original@nexus.local", "An", true);

        assertThat(body(1).get("firstName").asString()).isEqualTo("An");
        assertThat(body(1).get("lastName").asString()).isEmpty();
        assertThat(body(1).get("emailVerified").asBoolean()).isTrue();
    }

    /** Thay role chỉ xóa các role nghiệp vụ, không xóa role mặc định hoặc built-in của Keycloak. */
    @Test
    void replacingRolesPreservesBuiltInsAndSendsDeleteBody() {
        String path = USER_PATH + "/role-mappings/realm";
        expect("GET", path, 200, List.of(role("old", "BUYER", false), role("default", "default-roles-nexus", false),
                role("offline", "offline_access", false), role("uma", "uma_authorization", false)));
        expect("DELETE", path, 204, null);
        expect("POST", path, 204, null);

        client.replaceRealmRoles(USER, List.of(new KeycloakAdminClient.RealmRole("new", "SELLER")));

        assertThat(body(1).size()).isEqualTo(1);
        assertThat(body(1).get(0).get("name").asString()).isEqualTo("BUYER");
        assertThat(body(2).size()).isEqualTo(1);
        assertThat(body(2).get(0).get("name").asString()).isEqualTo("SELLER");
    }

    /** Role thay thế rỗng chỉ được dùng cho hoàn tác và phải bỏ qua POST role không cần thiết. */
    @Test
    void restoringNoApplicationRolePreservesSystemRolesAndSkipsEmptyPost() {
        String path = USER_PATH + "/role-mappings/realm";
        expect("GET", path, 200, List.of(role("default", "default-roles-nexus", false)));

        client.replaceRealmRoles(USER, List.of());

        assertThat(requests).hasSize(1);
    }

    /** Đặt mật khẩu gọi đúng API credential và không đánh dấu mật khẩu mới là tạm thời. */
    @Test
    void resetPasswordUsesKeycloakCredentialContract() {
        expect("PUT", USER_PATH + "/reset-password", 204, null);

        client.resetPassword(USER, "a-new-password");

        assertThat(body(0).get("type").asString()).isEqualTo("password");
        assertThat(body(0).get("value").asString()).isEqualTo("a-new-password");
        assertThat(body(0).get("temporary").asBoolean()).isFalse();
        assertThat(new KeycloakAdminClient.Credential("password", "a-new-password", false).toString())
                .doesNotContain("a-new-password");
    }

    /** Lỗi policy của Keycloak phải thành lỗi nghiệp vụ 400 thay vì rò nội dung provider. */
    @Test
    void rejectedPasswordMapsToStableBusinessError() {
        expect("PUT", USER_PATH + "/reset-password", 400, Map.of("error", "invalidPasswordHistoryMessage"));

        assertError(() -> client.resetPassword(USER, "same-password"), UserErrorCode.USER_IDENTITY_REJECTED);
    }

    /** Đọc khóa và xóa counter sử dụng brute-force API, không sửa cờ enabled của tài khoản. */
    @Test
    void unlockUsesBruteForceEndpointAndClearsLock() {
        expect("GET", LOCK_PATH, 200, Map.of("disabled", true, "numFailures", 5));
        expect("DELETE", LOCK_PATH, 204, null);
        expect("GET", LOCK_PATH, 200, Map.of("disabled", false, "numFailures", 0));

        assertThat(client.isBruteForceLocked(USER)).isTrue();
        client.unlock(USER);
        assertThat(client.isBruteForceLocked(USER)).isFalse();

        assertThat(requests).hasSize(3);
        assertThat(tokens).isEqualTo(1);
    }

    /** Các built-in role không được lộ ra danh mục bốn role nghiệp vụ được gán cho user. */
    @Test
    void listsOnlyTheFixedApplicationRealmRoles() {
        expect("GET", "/admin/realms/nexus/roles?first=0&max=500", 200,
                List.of(role("buyer", "BUYER", false), role("admin", "ADMIN", false),
                        role("support", "SUPPORT_STAFF", false), role("seller", "SELLER", false),
                        role("offline", "offline_access", false), role("other", "SUPERUSER", false)));

        assertThat(client.availableRealmRoles()).extracting(KeycloakAdminClient.RoleDetails::name)
                .containsExactly("BUYER", "ADMIN", "SUPPORT_STAFF", "SELLER");
    }

    /** Xem role bằng UUID phải lấy dữ liệu Keycloak và giữ mô tả của role. */
    @Test
    void readsApplicationRoleByKeycloakId() {
        expect("GET", "/admin/realms/nexus/roles-by-id/" + ROLE, 200, role(ROLE.toString(), "ADMIN", false));

        KeycloakAdminClient.RoleDetails result = client.realmRoleById(ROLE);

        assertThat(result.id()).isEqualTo(ROLE.toString());
        assertThat(result.description()).isEqualTo("description ADMIN");
    }

    /** Role built-in, role client và role không tồn tại đều không được dùng làm role nghiệp vụ. */
    @Test
    void roleByIdRejectsBuiltInsClientRolesAndMissingRoles() {
        String path = "/admin/realms/nexus/roles-by-id/" + ROLE;
        expect("GET", path, 200, role(ROLE.toString(), "offline_access", false));
        expect("GET", path, 200, role(ROLE.toString(), "ADMIN", true));
        expect("GET", path, 404, Map.of("error", "not found"));

        assertError(() -> client.realmRoleById(ROLE), UserErrorCode.USER_ROLE_NOT_FOUND);
        assertError(() -> client.realmRoleById(ROLE), UserErrorCode.USER_ROLE_NOT_FOUND);
        assertError(() -> client.realmRoleById(ROLE), UserErrorCode.USER_ROLE_NOT_FOUND);
    }

    /** Quyền trong role được đọc từ composite client roles, không lấy realm/built-in roles. */
    @Test
    void rolePrivilegesExcludeRealmAndTechnicalRoles() {
        expect("GET", "/admin/realms/nexus/roles/ADMIN/composites", 200,
                List.of(role("user", "USER.UPDATE", true), role("realm", "BUYER", false),
                        role("offline", "offline_access", true), role("view", "ROLE.VIEW", true)));

        assertThat(client.rolePermissions("ADMIN")).extracting(KeycloakAdminClient.RoleDetails::name)
                .containsExactly("USER.UPDATE", "ROLE.VIEW");
    }

    /** Danh sách quyền phải đọc qua nexus-web và lấy tiếp trang sau khi đã đủ 500 phần tử. */
    @Test
    void readsAllClientRolePagesInsteadOfTruncatingPermissions() {
        expect("GET", "/admin/realms/nexus/clients?clientId=nexus-web", 200,
                List.of(Map.of("id", "web-id", "clientId", "nexus-web")));
        List<Map<String, Object>> page = IntStream.range(0, 500)
                .mapToObj(index -> role("id-" + index, "PRIVILEGE." + index, true)).toList();
        expect("GET", "/admin/realms/nexus/clients/web-id/roles?first=0&max=500", 200, page);
        expect("GET", "/admin/realms/nexus/clients/web-id/roles?first=500&max=500", 200,
                List.of(role("last", "ROLE.VIEW", true), role("builtin", "offline_access", true)));

        assertThat(client.permissions()).hasSize(501).last()
                .extracting(KeycloakAdminClient.RoleDetails::name).isEqualTo("ROLE.VIEW");
    }

    /** Client nexus-web bị thiếu là lỗi provider rõ ràng, không giả vờ trả danh sách quyền rỗng. */
    @Test
    void missingPrivilegeClientReturnsProviderUnavailable() {
        expect("GET", "/admin/realms/nexus/clients?clientId=nexus-web", 200, List.of());

        assertError(client::permissions, UserErrorCode.USER_IDENTITY_PROVIDER_UNAVAILABLE);
    }

    /** HTTP 401 chỉ làm refresh token một lần, các lần gọi sau dùng token đã được thay mới. */
    @Test
    void retriesExpiredServiceTokenOnceAndCachesReplacement() {
        expect("GET", USER_PATH, 401, Map.of("error", "expired token"));
        expect("GET", USER_PATH, 200, account());
        expect("GET", USER_PATH, 200, account());

        client.userState(USER);
        client.userState(USER);

        assertThat(tokens).isEqualTo(2);
        assertThat(requests).extracting(Request::authorization)
                .containsExactly("Bearer token-1", "Bearer token-2", "Bearer token-2");
    }

    /** HTTP 401 lặp lại phải dừng retry để tránh vòng lặp và trả mã provider ổn định. */
    @Test
    void repeatedUnauthorizedResponseDoesNotLoop() {
        expect("GET", USER_PATH, 401, Map.of("error", "unauthorized"));
        expect("GET", USER_PATH, 401, Map.of("error", "unauthorized"));

        assertError(() -> client.userState(USER), UserErrorCode.USER_IDENTITY_PROVIDER_UNAVAILABLE);
        assertThat(tokens).isEqualTo(2);
        assertThat(requests).hasSize(2);
    }

    /** Chuẩn hóa lỗi HTTP thành mã USER_* theo trách nhiệm identity thay vì trả exception provider. */
    @ParameterizedTest
    @CsvSource({"404,USER_IDENTITY_NOT_FOUND", "409,USER_EMAIL_ALREADY_USED", "400,USER_IDENTITY_REJECTED",
            "500,USER_IDENTITY_PROVIDER_UNAVAILABLE", "403,USER_IDENTITY_PROVIDER_UNAVAILABLE"})
    void adminTransportErrorsHaveNamedCodes(int status, UserErrorCode expected) {
        expect("GET", USER_PATH, status, Map.of("error", "provider internals"));

        assertError(() -> client.userState(USER), expected);
    }

    /** Tạo snapshot đầy đủ để kiểm tra client bảo toàn dữ liệu ngoài trường đang sửa. */
    private static Map<String, Object> account() {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("username", "original-login");
        account.put("email", "original@nexus.local");
        account.put("emailVerified", true);
        account.put("firstName", "Original");
        account.put("lastName", "Name");
        account.put("enabled", true);
        account.put("attributes", Map.of("phone", List.of("0900000001")));
        account.put("requiredActions", List.of("VERIFY_EMAIL"));
        return account;
    }

    /** Tạo biểu diễn role đúng cấu trúc HTTP của Keycloak. */
    private static Map<String, Object> role(String id, String name, boolean clientRole) {
        return Map.of("id", id, "name", name, "description", "description " + name, "clientRole", clientRole);
    }

    /** Đăng ký bước HTTP kế tiếp để phát hiện URI, thứ tự và phương thức gọi sai. */
    private void expect(String method, String path, int status, Object body) {
        replies.add(new Reply(method, path, status, body == null ? "" : JSON.writeValueAsString(body)));
    }

    /** Đọc JSON request đã gửi trên mạng cho các kiểm tra contract quan trọng. */
    private JsonNode body(int request) {
        return JSON.readTree(requests.get(request).body());
    }

    /** Kiểm tra HTTP status và mã enum mà không phụ thuộc câu thông báo có thể thay đổi. */
    private static void assertError(Runnable action, UserErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo(expected.status().value());
            assertThat(exception.getErrorCode()).isEqualTo(expected.code());
            assertThat(exception.getMessage()).doesNotContain("provider internals");
        });
    }

    /** Phục vụ token và từng phản hồi giả lập, đồng thời ghi nhận request thật của RestClient. */
    private void respond(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().toString();
        if (path.equals("/realms/nexus/protocol/openid-connect/token")) {
            if (!exchange.getRequestMethod().equals("POST") || !body.contains("grant_type=client_credentials")
                    || !body.contains("client_id=user-service") || !body.contains("client_secret=test-service-secret")) {
                failures.add("Invalid service token request");
            }
            write(exchange, 200, "{\"access_token\":\"token-" + (++tokens) + "\",\"expires_in\":300}");
            return;
        }
        requests.add(new Request(exchange.getRequestMethod(), path, body,
                exchange.getRequestHeaders().getFirst("Authorization")));
        if (realmUsers != null) {
            respondRealm(exchange, path, body);
            return;
        }
        Reply reply = replies.poll();
        if (reply == null || !reply.method().equals(exchange.getRequestMethod()) || !reply.path().equals(path)) {
            failures.add("Unexpected " + exchange.getRequestMethod() + " " + path + "; expected " + reply);
            write(exchange, 500, "{}");
            return;
        }
        write(exchange, reply.status(), reply.body());
    }

    /** Áp dụng request lên realm có trạng thái, kiểm unique email/username độc lập với client. */
    private void respondRealm(HttpExchange exchange, String path, String body) throws IOException {
        String usersPath = "/admin/realms/nexus/users";
        boolean creating = path.equals(usersPath) && exchange.getRequestMethod().equals("POST");
        UUID id = creating ? UUID.randomUUID() : UUID.fromString(path.substring(usersPath.length() + 1));
        if (exchange.getRequestMethod().equals("GET")) {
            write(exchange, 200, JSON.writeValueAsString(realmUsers.get(id)));
            return;
        }
        JsonNode account = JSON.readTree(body);
        boolean conflict = realmUsers.entrySet().stream().anyMatch(entry -> !entry.getKey().equals(id)
                && (entry.getValue().get("username").asString().equalsIgnoreCase(account.get("username").asString())
                || entry.getValue().get("email").asString().equalsIgnoreCase(account.get("email").asString())));
        if (conflict) {
            write(exchange, 409, "{\"error\":\"User exists with same username or email\"}");
            return;
        }
        realmUsers.put(id, account);
        if (creating) exchange.getResponseHeaders().set("Location", usersPath + "/" + id);
        write(exchange, creating ? 201 : 204, "");
    }

    /** Gửi JSON response; 204 luôn không có body để mô phỏng đúng Admin API. */
    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Reply(String method, String path, int status, String body) { }
    private record Request(String method, String path, String body, String authorization) { }
}
