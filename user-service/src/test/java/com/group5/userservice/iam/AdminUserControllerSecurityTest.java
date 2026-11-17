package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.dto.*;
import com.group5.userservice.iam.enums.*;
import com.group5.userservice.shared.exception.UserErrorCode;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Kiểm tra HTTP thực, chữ ký JWT, role/privilege, DTO validation và envelope; service được mock. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = AdminUserControllerSecurityTest.TestApplication.class)
@TestPropertySource(properties = {"spring.config.name=admin-user-http-test", "spring.config.import=",
        "spring.cloud.config.enabled=false", "spring.cloud.compatibility-verifier.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "management.tracing.export.zipkin.enabled=false", "nexus.security.jwt.issuer=user-service",
        "nexus.security.jwt.audience=nexus-services"})
class AdminUserControllerSecurityTest {
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final String URL = "/api/v1/users/" + ID;
    private static final RSAKey KEY = key();
    private static final HttpServer JWKS = jwks();
    private static final String CREATE = """
            {"email":" NEW@nexus.local ","phone":"090-000-0001","password":"test-password",
             "fullName":" User One ","identityNumber":"identity-test","gender":"MALE","address":"HCM",
             "dateOfBirth":"2000-01-01","roleCodes":["BUYER"]}
            """;
    @Autowired WebApplicationContext context;
    @Autowired AdminUserService users;
    @Autowired RoleService roles;
    private MockMvc mvc;

    /** Đăng ký JWKS local để filter xác minh chữ ký thay vì bypass decoder. */
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("nexus.security.jwt.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    /** Dừng đúng HTTP server fixture khi hoàn thành lớp test. */
    @AfterAll static void stop() { JWKS.stop(0); }

    /** Tạo MockMvc có security filter thật và làm mới các mock nghiệp vụ. */
    @BeforeEach void setup() {
        reset(users, roles);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    /** Admin tạo user trả 201 có Location, success envelope và không lộ credential. */
    @Test void adminCreateReturns201AndNormalizedRequest() throws Exception {
        when(users.create(any(), eq(ACTOR))).thenReturn(profile());
        mvc.perform(post("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN", "USER.CREATE"))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE))
                .andExpect(status().isCreated()).andExpect(header().string("Location", URL))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.id").value(ID.toString()))
                .andExpect(jsonPath("$.data.password").doesNotExist()).andExpect(jsonPath("$.page").doesNotExist());
        verify(users).create(argThat(request -> request.email().equals("new@nexus.local")
                && request.phone().equals("0900000001") && request.roleCodes().equals(List.of("BUYER"))), eq(ACTOR));
    }

    /** Support được đổi profile nhưng các field role/password lạ không truyền đến service. */
    @Test void supportUpdatesWhitelistedProfile() throws Exception {
        when(users.update(eq(ID), any(), eq(ACTOR))).thenReturn(profile());
        mvc.perform(put(URL).header(HttpHeaders.AUTHORIZATION, bearer("SUPPORT_STAFF", "USER.UPDATE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"New Name\",\"roles\":[\"ADMIN\"],\"password\":\"ignored\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(ID.toString()));
        verify(users).update(eq(ID), eq(new UpdateProfileRequest("New Name", null, null, null, null)), eq(ACTOR));
    }

    /** Support có cả privilege giả cũng không được xóa user, đổi password hoặc gán role. */
    @ParameterizedTest @ValueSource(strings = {"delete", "password", "roles"})
    void supportCannotPerformAdminOperations(String action) throws Exception {
        var request = action.equals("delete") ? delete(URL) : put(URL + "/" + action)
                .contentType(MediaType.APPLICATION_JSON).content(action.equals("password") ? "{\"newPassword\":\"long-password\"}" : "{\"roleCodes\":[\"ADMIN\"]}");
        mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer("SUPPORT_STAFF", "USER.UPDATE", "USER.DELETE", "USER.CHANGE_PASSWORD")))
                .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(users);
    }

    /** Buyer mang privilege USER.UPDATE vẫn bị role guard từ chối. */
    @Test void buyerWithForgedUpdatePrivilegeIsDenied() throws Exception {
        mvc.perform(put(URL).header(HttpHeaders.AUTHORIZATION, bearer("BUYER", "USER.UPDATE"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"address\":\"HCM\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(users);
    }

    /** Admin thiếu privilege tương ứng cũng bị từ chối ở service HTTP trực tiếp. */
    @Test void adminWithoutPrivilegeIsDenied() throws Exception {
        mvc.perform(delete(URL).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(users);
    }

    /** Endpoint quản trị bắt buộc Bearer JWT và giữ header challenge chuẩn. */
    @Test void missingTokenReturns401() throws Exception {
        mvc.perform(delete(URL)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        verifyNoInteractions(users);
    }

    /** Xóa user thành công trả 204 body rỗng. */
    @Test void deleteReturnsBodyless204() throws Exception {
        mvc.perform(delete(URL).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN", "USER.DELETE")))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(users).delete(ID, ACTOR);
    }

    /** Reset password thành công trả 204 và chỉ gửi mật khẩu mới sang service. */
    @Test void passwordReturnsBodyless204() throws Exception {
        mvc.perform(put(URL + "/password").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN", "USER.CHANGE_PASSWORD"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"long-password\"}"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(users).changePassword(ID, "long-password", ACTOR);
    }

    /** Lỗi sau remote success trả operationId để đối soát, không khuyến khích phát lại password/unlock. */
    @ParameterizedTest @ValueSource(strings = {"password", "unlock"})
    void remoteSuccessLocalFailureReturnsReconciliationId(String action) throws Exception {
        UUID operationId = UUID.fromString("00000000-0000-4000-8000-000000000099");
        DomainException failure = new DomainException(UserErrorCode.USER_IDENTITY_RECONCILIATION_REQUIRED,
                UserErrorCode.USER_IDENTITY_RECONCILIATION_REQUIRED.message(),
                java.util.Map.of("operationId", operationId.toString()));
        if (action.equals("password")) doThrow(failure).when(users).changePassword(ID, "long-password", ACTOR);
        else doThrow(failure).when(users).unlock(ID, ACTOR);
        var request = action.equals("password")
                ? put(URL + "/password").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN", "USER.CHANGE_PASSWORD"))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"long-password\"}")
                : post(URL + "/unlock").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN", "USER.UPDATE"));
        mvc.perform(request).andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("USER_IDENTITY_RECONCILIATION_REQUIRED"))
                .andExpect(jsonPath("$.operationId").value(operationId.toString()));
    }

    /** Password không đúng policy cơ bản bị validation chặn trước khi gọi Keycloak. */
    @Test void shortPasswordIsValidationError() throws Exception {
        mvc.perform(put(URL + "/password").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN", "USER.CHANGE_PASSWORD"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(users);
    }

    /** Bộ role trống không đến được bước gán role provider. */
    @Test void emptyRolesReturn400() throws Exception {
        mvc.perform(put(URL + "/roles").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN", "USER.UPDATE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roleCodes\":[]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(users);
    }

    /** Support mở khóa nhận 204 và không cần body request. */
    @Test void supportCanUnlock() throws Exception {
        mvc.perform(post(URL + "/unlock").header(HttpHeaders.AUTHORIZATION, bearer("SUPPORT_STAFF", "USER.UPDATE")))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(users).unlock(ID, ACTOR);
    }

    /** Tài khoản đã mở khóa trả named conflict theo Problem Details. */
    @Test void alreadyUnlockedReturnsNamed409() throws Exception {
        doThrow(new DomainException(UserErrorCode.USER_ACCOUNT_NOT_LOCKED)).when(users).unlock(ID, ACTOR);
        mvc.perform(post(URL + "/unlock").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN", "USER.UPDATE")))
                .andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("USER_ACCOUNT_NOT_LOCKED"));
    }

    /** Role list phân trang có page ngang data kể cả trang ngoài phạm vi. */
    @Test void roleListPaginatesAndSorts() throws Exception {
        when(roles.list(null)).thenReturn(List.of(new RoleService.RoleResponse(ID.toString(), "BUYER", "BUYER", null, List.of())));
        mvc.perform(get("/api/v1/roles?page=2&size=1").header(HttpHeaders.AUTHORIZATION, bearer("SUPPORT_STAFF", "ROLE.LIST")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.number").value(2)).andExpect(jsonPath("$.page.totalElements").value(1));
    }

    /** Permissions complete-list cũng trả page rõ ràng theo API style guide. */
    @Test void permissionsUseCompleteListPage() throws Exception {
        when(roles.permissions()).thenReturn(List.of(new RoleService.PermissionResponse("USER.VIEW", "USER.VIEW", "Read users")));
        mvc.perform(get("/api/v1/permissions").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN", "ROLE.VIEW")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].code").value("USER.VIEW"))
                .andExpect(jsonPath("$.page.totalElements").value(1)).andExpect(jsonPath("$.page.totalPages").value(1));
    }

    /** Tham số role list không hợp lệ luôn trả 400, không phát sinh query provider. */
    @ParameterizedTest @ValueSource(strings = {"size=0", "size=101", "page=-1", "sort=password"})
    void invalidRoleListQueryReturns400(String query) throws Exception {
        mvc.perform(get("/api/v1/roles?" + query).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN", "ROLE.LIST")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(roles);
    }

    /** Tạo profile response không có dữ liệu credential để kiểm tra envelope. */
    private static UserResponse profile() {
        return new UserResponse(ID, "new@nexus.local", "0900000001", "User One", Gender.MALE, "HCM",
                LocalDate.of(2000, 1, 1), List.of("BUYER"), UserStatus.ACTIVE, OffsetDateTime.parse("2026-11-14T07:00:00Z"));
    }

    /** Ký JWT RS256 với role và privilege riêng cho từng trường hợp truy cập. */
    private static String bearer(String role, String... privileges) {
        try {
            Instant now = Instant.now();
            var claims = new JWTClaimsSet.Builder().issuer("user-service").audience("nexus-services").subject(ACTOR.toString())
                    .claim("roles", List.of(role)).claim("privileges", List.of(privileges)).issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(3600))).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("admin211").build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return "Bearer " + jwt.serialize();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    /** Sinh RSA key dùng riêng cho fixture bảo mật, không dùng credential runtime. */
    private static RSAKey key() {
        try { return new RSAKeyGenerator(2048).keyID("admin211").generate(); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    /** Phục vụ public key để decoder thật tải và xác minh JWT của test. */
    private static HttpServer jwks() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body); exchange.close();
            });
            server.start(); return server;
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class, FlywayAutoConfiguration.class, KafkaAutoConfiguration.class,
            com.group5.common_events.autoconfigure.EventsAutoConfiguration.class,
            com.group5.common_outbox.autoconfigure.IdempotentConsumerAutoConfiguration.class})
    static class TestApplication {
        /** Cấp service mock cho kiểm tra riêng hợp đồng HTTP. */
        @Bean AdminUserService users() { return mock(AdminUserService.class); }
        /** Cấp role service mock; không truy cập Keycloak thật trong lớp test MVC. */
        @Bean RoleService roles() { return mock(RoleService.class); }
        /** Đăng ký controller quản trị với method security thật. */
        @Bean AdminUserController userController(AdminUserService users) { return new AdminUserController(users); }
        /** Đăng ký controller role để kiểm tra page, validation và authorization. */
        @Bean RoleController roleController(RoleService roles) { return new RoleController(roles); }
    }
}
