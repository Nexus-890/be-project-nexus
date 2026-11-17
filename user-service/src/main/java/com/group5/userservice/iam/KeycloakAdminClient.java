package com.group5.userservice.iam;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.group5.common_core.exception.DomainException;
import com.group5.userservice.shared.config.KeycloakProperties;
import com.group5.userservice.shared.exception.UserErrorCode;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Calls the Keycloak Admin REST API as the user-service service account. */
@Component
public class KeycloakAdminClient {
    public static final Set<String> APPLICATION_ROLES = Set.of("ADMIN", "BUYER", "SELLER", "SUPPORT_STAFF");
    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofSeconds(30);
    private static final int MEMBER_PAGE_SIZE = 500;
    private static final Set<String> BUILT_IN_ROLES = Set.of("offline_access", "uma_authorization");
    private static final List<String> PROFILE_FIELDS = List.of("username", "firstName", "lastName", "attributes");

    private final KeycloakProperties properties;
    private final Clock clock;
    private final RestClient client;
    private String accessToken;
    private Instant accessTokenRefreshAt = Instant.MIN;

    public KeycloakAdminClient(KeycloakProperties properties, Clock clock,
                               ObjectProvider<ObservationRegistry> observations) {
        this.properties = properties;
        this.clock = clock;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getTimeout());
        requestFactory.setReadTimeout(properties.getTimeout());
        this.client = RestClient.builder()
                .baseUrl(properties.getServerUrl())
                .requestFactory(requestFactory)
                .observationRegistry(observations.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();
    }

    public Optional<RealmRole> realmRole(String name) {
        try {
            return Optional.ofNullable(withToken(token -> client.get()
                    .uri("/admin/realms/{realm}/roles/{role}", properties.getRealm(), name)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .body(RealmRole.class)));
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        } catch (RestClientException exception) {
            throw unavailable("read realm role " + name, exception);
        }
    }

    public UUID createUser(String email, String password, String fullName) {
        int split = fullName.lastIndexOf(' ');
        String firstName = split < 0 ? fullName : fullName.substring(0, split).strip();
        String lastName = split < 0 ? fullName : fullName.substring(split + 1);
        NewUser user = new NewUser(email, email, firstName, lastName, true, false,
                List.of(new Credential("password", password, false)));
        try {
            URI location = withToken(token -> client.post()
                    .uri("/admin/realms/{realm}/users", properties.getRealm())
                    .headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(user)
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getLocation());
            if (location == null) {
                throw new DomainException(UserErrorCode.USER_IDENTITY_PROVIDER_UNAVAILABLE);
            }
            String path = location.getPath();
            return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
        } catch (HttpClientErrorException.Conflict exception) {
            throw new DomainException(UserErrorCode.USER_EMAIL_ALREADY_USED);
        } catch (HttpClientErrorException.BadRequest exception) {
            log.warn("Keycloak rejected a new account: {}", exception.getResponseBodyAsString());
            throw new DomainException(UserErrorCode.USER_IDENTITY_REJECTED);
        } catch (RestClientException exception) {
            throw unavailable("create user", exception);
        }
    }

    public void assignRealmRole(UUID userId, RealmRole role) {
        try {
            withToken(token -> client.post()
                    .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", properties.getRealm(), userId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(role))
                    .retrieve()
                    .toBodilessEntity());
        } catch (RestClientException exception) {
            throw unavailable("assign realm role " + role.name(), exception);
        }
    }

    public List<String> realmRoles(UUID userId) {
        try {
            RealmRole[] roles = withToken(token -> client.get()
                    .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", properties.getRealm(), userId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .body(RealmRole[].class));
            if (roles == null) {
                return List.of();
            }
            String defaultRoles = "default-roles-" + properties.getRealm();
            return Arrays.stream(roles)
                    .map(RealmRole::name)
                    .filter(name -> !name.equals(defaultRoles) && !BUILT_IN_ROLES.contains(name))
                    .sorted()
                    .toList();
        } catch (HttpClientErrorException.NotFound exception) {
            return List.of();
        } catch (RestClientException exception) {
            throw unavailable("read realm roles of user " + userId, exception);
        }
    }

    public Set<UUID> userIdsWithRole(String role) {
        Set<UUID> ids = new HashSet<>();
        try {
            for (int first = 0; ; first += MEMBER_PAGE_SIZE) {
                int offset = first;
                UserId[] page = withToken(token -> client.get()
                        .uri("/admin/realms/{realm}/roles/{role}/users?briefRepresentation=true&first={first}&max={max}",
                                properties.getRealm(), role, offset, MEMBER_PAGE_SIZE)
                        .headers(headers -> headers.setBearerAuth(token))
                        .retrieve()
                        .body(UserId[].class));
                if (page == null) {
                    return ids;
                }
                for (UserId user : page) {
                    ids.add(user.id());
                }
                if (page.length < MEMBER_PAGE_SIZE) {
                    return ids;
                }
            }
        } catch (HttpClientErrorException.NotFound exception) {
            return Set.of();
        } catch (RestClientException exception) {
            throw unavailable("read members of realm role " + role, exception);
        }
    }

    public Optional<EmailState> emailState(UUID userId) {
        try {
            return Optional.ofNullable(withToken(token -> client.get()
                    .uri("/admin/realms/{realm}/users/{id}", properties.getRealm(), userId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .body(EmailState.class)));
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        } catch (RestClientException exception) {
            throw unavailable("read email of user " + userId, exception);
        }
    }

    public void updateEmail(UUID userId, String email, boolean emailVerified) {
        try {
            withToken(token -> {
                Map<String, Object> user = client.get()
                        .uri("/admin/realms/{realm}/users/{id}", properties.getRealm(), userId)
                        .headers(headers -> headers.setBearerAuth(token))
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() { });
                if (user == null) {
                    throw new DomainException(UserErrorCode.USER_IDENTITY_PROVIDER_UNAVAILABLE);
                }
                Map<String, Object> change = new LinkedHashMap<>();
                for (String field : PROFILE_FIELDS) {
                    if (user.containsKey(field)) {
                        change.put(field, user.get(field));
                    }
                }
                synchronizeEmailUsername(user, change, email);
                change.put("email", email);
                change.put("emailVerified", emailVerified);
                return client.put()
                        .uri("/admin/realms/{realm}/users/{id}", properties.getRealm(), userId)
                        .headers(headers -> headers.setBearerAuth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(change)
                        .retrieve()
                        .toBodilessEntity();
            });
        } catch (HttpClientErrorException.NotFound exception) {
            log.warn("Profile {} has no Keycloak account, so its new email is stored only in user-service", userId);
        } catch (HttpClientErrorException.Conflict exception) {
            throw new DomainException(UserErrorCode.USER_EMAIL_ALREADY_USED);
        } catch (HttpClientErrorException.BadRequest exception) {
            log.warn("Keycloak rejected an email change: {}", exception.getResponseBodyAsString());
            throw new DomainException(UserErrorCode.USER_IDENTITY_REJECTED);
        } catch (RestClientException exception) {
            throw unavailable("update email of user " + userId, exception);
        }
    }

    public void deleteUser(UUID userId) {
        try {
            withToken(token -> client.delete()
                    .uri("/admin/realms/{realm}/users/{id}", properties.getRealm(), userId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .toBodilessEntity());
        } catch (HttpClientErrorException.NotFound exception) {
            log.warn("Keycloak user {} was already gone", userId);
        } catch (RestClientException exception) {
            throw unavailable("delete user " + userId, exception);
        }
    }

    /** Đọc snapshot tài khoản để hoàn tác thay đổi profile hoặc trạng thái đăng nhập. */
    public Map<String, Object> userState(UUID userId) {
        return adminCall("read account", () -> withToken(token -> client.get()
                .uri("/admin/realms/{realm}/users/{id}", properties.getRealm(), userId)
                .headers(headers -> headers.setBearerAuth(token)).retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() { })));
    }

    /** Khôi phục các trường tài khoản có thể ghi; không ghi credential hoặc role vào profile. */
    public void restoreUser(UUID userId, Map<String, Object> state) {
        Map<String, Object> writable = new LinkedHashMap<>();
        for (String field : List.of("username", "firstName", "lastName", "email", "emailVerified", "enabled", "attributes", "requiredActions")) {
            if (state.containsKey(field)) writable.put(field, state.get(field));
        }
        adminCall("write account", () -> withToken(token -> client.put()
                .uri("/admin/realms/{realm}/users/{id}", properties.getRealm(), userId)
                .headers(headers -> headers.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .body(writable).retrieve().toBodilessEntity()));
    }

    /** Đồng bộ email, tên và enabled mà vẫn giữ attribute và action đang có trên Keycloak. */
    public void updateUser(UUID userId, String email, String fullName, boolean enabled) {
        Map<String, Object> state = new LinkedHashMap<>(userState(userId));
        if (!java.util.Objects.equals(state.get("email"), email)) state.put("emailVerified", false);
        synchronizeEmailUsername(state, state, email);
        int split = fullName.lastIndexOf(' ');
        state.put("firstName", split < 0 ? fullName : fullName.substring(0, split).strip());
        state.put("lastName", split < 0 ? "" : fullName.substring(split + 1));
        state.put("email", email);
        state.put("enabled", enabled);
        restoreUser(userId, state);
    }

    /** Giải phóng email đăng nhập cũ nhưng giữ username riêng, kể cả realm chuẩn hóa chữ thường. */
    private static void synchronizeEmailUsername(Map<String, Object> before, Map<String, Object> change, String email) {
        if (before.get("username") instanceof String username && before.get("email") instanceof String previousEmail
                && username.equalsIgnoreCase(previousEmail)) {
            change.put("username", email);
        }
    }

    /** Đọc role gán trực tiếp, giữ cả built-in để không vô tình xóa chúng khi đổi role nghiệp vụ. */
    public List<RealmRole> assignedRealmRoles(UUID userId) {
        RealmRole[] roles = adminCall("read assigned roles", () -> withToken(token -> client.get()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", properties.getRealm(), userId)
                .headers(headers -> headers.setBearerAuth(token)).retrieve().body(RealmRole[].class)));
        return roles == null ? List.of() : List.of(roles);
    }

    /** Thay bộ role nghiệp vụ, chỉ xóa role cố định và luôn giữ role hệ thống của realm. */
    public void replaceRealmRoles(UUID userId, List<RealmRole> roles) {
        List<RealmRole> previous = assignedRealmRoles(userId).stream()
                .filter(role -> APPLICATION_ROLES.contains(role.name())).toList();
        if (!previous.isEmpty()) {
            adminCall("remove application roles", () -> withToken(token -> client.method(org.springframework.http.HttpMethod.DELETE)
                    .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", properties.getRealm(), userId)
                    .headers(headers -> headers.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                    .body(previous).retrieve().toBodilessEntity()));
        }
        if (!roles.isEmpty()) {
            adminCall("assign application roles", () -> withToken(token -> client.post()
                    .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", properties.getRealm(), userId)
                    .headers(headers -> headers.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                    .body(roles).retrieve().toBodilessEntity()));
        }
    }

    /** Đặt mật khẩu theo policy của Keycloak; không đọc hoặc lưu mật khẩu cũ trong user_db. */
    public void resetPassword(UUID userId, String password) {
        adminCall("reset password", () -> withToken(token -> client.put()
                .uri("/admin/realms/{realm}/users/{id}/reset-password", properties.getRealm(), userId)
                .headers(headers -> headers.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .body(new Credential("password", password, false)).retrieve().toBodilessEntity()));
    }

    /** Kiểm tra khóa do brute-force detector, độc lập với cờ enabled của tài khoản. */
    public boolean isBruteForceLocked(UUID userId) {
        Map<String, Object> state = adminCall("read brute-force lock", () -> withToken(token -> client.get()
                .uri("/admin/realms/{realm}/attack-detection/brute-force/users/{id}", properties.getRealm(), userId)
                .headers(headers -> headers.setBearerAuth(token)).retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() { })));
        return state != null && Boolean.TRUE.equals(state.get("disabled"));
    }

    /** Xóa số lần đăng nhập sai và khóa tạm thời trong brute-force detector của Keycloak. */
    public void unlock(UUID userId) {
        adminCall("clear brute-force lock", () -> withToken(token -> client.delete()
                .uri("/admin/realms/{realm}/attack-detection/brute-force/users/{id}", properties.getRealm(), userId)
                .headers(headers -> headers.setBearerAuth(token)).retrieve().toBodilessEntity()));
    }

    /** Đọc bốn realm role nghiệp vụ được hỗ trợ, không cho phép CRUD role qua user-service. */
    public List<RoleDetails> availableRealmRoles() {
        RoleDetails[] roles = adminCall("read realm roles", () -> withToken(token -> client.get()
                .uri("/admin/realms/{realm}/roles?first=0&max=500", properties.getRealm())
                .headers(headers -> headers.setBearerAuth(token)).retrieve().body(RoleDetails[].class)));
        return roles == null ? List.of() : Arrays.stream(roles)
                .filter(role -> APPLICATION_ROLES.contains(role.name())).toList();
    }

    /** Tra role theo UUID Keycloak, từ chối built-in role và mọi role ngoài bộ cố định. */
    public RoleDetails realmRoleById(UUID roleId) {
        try {
            RoleDetails role = withToken(token -> client.get()
                    .uri("/admin/realms/{realm}/roles-by-id/{id}", properties.getRealm(), roleId)
                    .headers(headers -> headers.setBearerAuth(token)).retrieve().body(RoleDetails.class));
            if (role == null || role.clientRole() || !APPLICATION_ROLES.contains(role.name()))
                throw new DomainException(UserErrorCode.USER_ROLE_NOT_FOUND);
            return role;
        } catch (HttpClientErrorException.NotFound exception) {
            throw new DomainException(UserErrorCode.USER_ROLE_NOT_FOUND);
        } catch (RestClientException exception) {
            throw unavailable("read role by id", exception);
        }
    }

    /** Đọc quyền client tổng hợp của một realm role, không dùng bảng role/permission cũ trong DB. */
    public List<RoleDetails> rolePermissions(String name) {
        RoleDetails[] roles = adminCall("read role privileges", () -> withToken(token -> client.get()
                .uri("/admin/realms/{realm}/roles/{name}/composites", properties.getRealm(), name)
                .headers(headers -> headers.setBearerAuth(token)).retrieve().body(RoleDetails[].class)));
        return roles == null ? List.of() : Arrays.stream(roles).filter(RoleDetails::clientRole)
                .filter(role -> role.name().contains(".")).toList();
    }

    /** Đọc các client role privilege của nexus-web với phân trang để không mất quyền ở cuối danh sách. */
    public List<RoleDetails> permissions() {
        ClientDetails[] clients = adminCall("resolve privilege client", () -> withToken(token -> client.get()
                .uri("/admin/realms/{realm}/clients?clientId=nexus-web", properties.getRealm())
                .headers(headers -> headers.setBearerAuth(token)).retrieve().body(ClientDetails[].class)));
        if (clients == null || clients.length != 1) throw new DomainException(UserErrorCode.USER_IDENTITY_PROVIDER_UNAVAILABLE);
        List<RoleDetails> result = new java.util.ArrayList<>();
        for (int first = 0; ; first += MEMBER_PAGE_SIZE) {
            int offset = first;
            RoleDetails[] roles = adminCall("read client privileges", () -> withToken(token -> client.get()
                    .uri("/admin/realms/{realm}/clients/{id}/roles?first={first}&max={max}", properties.getRealm(), clients[0].id(), offset, MEMBER_PAGE_SIZE)
                    .headers(headers -> headers.setBearerAuth(token)).retrieve().body(RoleDetails[].class)));
            if (roles == null) break;
            result.addAll(Arrays.stream(roles).filter(role -> role.name().contains(".")).toList());
            if (roles.length < MEMBER_PAGE_SIZE) break;
        }
        return List.copyOf(result);
    }

    /** Chuẩn hóa lỗi Admin API thành mã nghiệp vụ và giữ nguyên lỗi provider trong server log. */
    private <T> T adminCall(String action, java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException.NotFound exception) {
            throw new DomainException(UserErrorCode.USER_IDENTITY_NOT_FOUND);
        } catch (HttpClientErrorException.Conflict exception) {
            throw new DomainException(UserErrorCode.USER_EMAIL_ALREADY_USED);
        } catch (HttpClientErrorException.BadRequest exception) {
            throw new DomainException(UserErrorCode.USER_IDENTITY_REJECTED);
        } catch (RestClientException exception) {
            throw unavailable(action, exception);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoleDetails(String id, String name, String description, boolean clientRole) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ClientDetails(String id, String clientId) { }

    private <T> T withToken(Function<String, T> request) {
        String token = accessToken();
        try {
            return request.apply(token);
        } catch (HttpClientErrorException.Unauthorized exception) {
            discard(token);
            return request.apply(accessToken());
        }
    }

    private synchronized String accessToken() {
        Instant now = clock.instant();
        if (accessToken != null && now.isBefore(accessTokenRefreshAt)) {
            return accessToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        TokenResponse token;
        try {
            token = client.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", properties.getRealm())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientException exception) {
            throw unavailable("obtain a service-account token", exception);
        }
        if (token == null || token.accessToken() == null) {
            throw new DomainException(UserErrorCode.USER_IDENTITY_PROVIDER_UNAVAILABLE);
        }
        accessToken = token.accessToken();
        accessTokenRefreshAt = now.plusSeconds(token.expiresIn()).minus(TOKEN_REFRESH_MARGIN);
        return accessToken;
    }

    private synchronized void discard(String token) {
        if (token.equals(accessToken)) {
            accessToken = null;
        }
    }

    private static DomainException unavailable(String action, RestClientException exception) {
        log.error("Keycloak call failed: {}", action, exception);
        return new DomainException(UserErrorCode.USER_IDENTITY_PROVIDER_UNAVAILABLE);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RealmRole(String id, String name) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmailState(String email, boolean emailVerified) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserId(UUID id) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("expires_in") long expiresIn) { }

    record NewUser(String username, String email, String firstName, String lastName,
                   boolean enabled, boolean emailVerified, List<Credential> credentials) { }

    record Credential(String type, String value, boolean temporary) {
        @Override
        public String toString() {
            return "Credential[redacted]";
        }
    }
}
