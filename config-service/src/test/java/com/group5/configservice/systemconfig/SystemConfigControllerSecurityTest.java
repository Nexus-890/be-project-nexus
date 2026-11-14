package com.group5.configservice.systemconfig;

import com.group5.common_core.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = SystemConfigControllerSecurityTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=config-admin-http-test", "spring.cloud.config.enabled=false",
                "management.tracing.export.enabled=false"})
class SystemConfigControllerSecurityTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-4000-8000-000000000060");
    @LocalServerPort int port;
    @MockitoBean SystemConfigService service;
    @MockitoBean SystemConfigStore store;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    // Chuẩn bị response để kiểm tra HTTP boundary độc lập với filesystem của Config Server.
    void setup() {
        when(service.list()).thenReturn(List.of(new SystemConfigService.ConfigResponse(
                "NOTIFICATION_RETRY_LIMIT", 3, 3, "Retry attempts", "notification-service", "RUNTIME", 3)));
        when(service.update(eq("NOTIFICATION_RETRY_LIMIT"), eq(4), any(), any()))
                .thenReturn(new SystemConfigService.UpdatedResponse("NOTIFICATION_RETRY_LIMIT", 4));
    }

    @Test
    // Thiếu token phải nhận Problem Details 401 và Bearer challenge trước khi gọi service.
    void anonymousRequestsAreRejected() throws Exception {
        var response = request("GET", "/api/v1/system/configs", null, null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(response.body()).contains("UNAUTHENTICATED");
        verifyNoInteractions(service);
    }

    @Test
    // Support hoặc Buyer có thêm privilege vẫn không được dùng API chỉ dành cho Admin.
    void wrongRolesCannotReadOrWriteEvenWithPrivileges() throws Exception {
        for (String role : List.of("SUPPORT_STAFF", "BUYER")) {
            assertThat(request("GET", "/api/v1/system/configs", role + "~SYSTEM.CONFIG.VIEW", null).statusCode()).isEqualTo(403);
            assertThat(request("PUT", "/api/v1/system/configs/NOTIFICATION_RETRY_LIMIT",
                    role + "~SYSTEM.CONFIG.UPDATE", "{\"value\":4}").statusCode()).isEqualTo(403);
        }
        verifyNoInteractions(service);
    }

    @Test
    // Vai trò Admin chưa đủ nếu JWT thiếu đúng privilege của thao tác.
    void adminMustHaveTheExactPrivilege() throws Exception {
        assertThat(request("GET", "/api/v1/system/configs", "ADMIN~OTHER", null).statusCode()).isEqualTo(403);
        assertThat(request("PUT", "/api/v1/system/configs/NOTIFICATION_RETRY_LIMIT",
                "ADMIN~SYSTEM.CONFIG.VIEW", "{\"value\":4}").statusCode()).isEqualTo(403);
        verifyNoInteractions(service);
    }

    @Test
    // Danh sách dùng envelope và page ngang data; không trả native Config Server JSON.
    void adminListUsesTheStandardListEnvelope() throws Exception {
        var response = request("GET", "/api/v1/system/configs", "ADMIN~SYSTEM.CONFIG.VIEW", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"code\":0", "\"data\":[", "\"page\":", "\"totalElements\":1", "\"defaultValue\":3");
    }

    @Test
    // Lấy actor và bearer từ JWT; actorId trong body không thay thế người thực hiện.
    void updateUsesAuthenticatedActorAndTypedValue() throws Exception {
        String token = "ADMIN~SYSTEM.CONFIG.UPDATE";
        var response = request("PUT", "/api/v1/system/configs/NOTIFICATION_RETRY_LIMIT", token,
                "{\"value\":4,\"actorId\":\"00000000-0000-4000-8000-000000000001\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"data\":{", "\"value\":4").doesNotContain("\"page\"");
        verify(service).update("NOTIFICATION_RETRY_LIMIT", 4, ACTOR, token);
    }

    @Test
    // Key không tồn tại phải giữ named business code và status 400 của config-service.
    void unknownKeyReturnsNamedProblem() throws Exception {
        when(service.update(eq("UNKNOWN"), any(), any(), any())).thenThrow(new DomainException(ConfigErrorCode.CONFIG_KEY_UNKNOWN));
        var response = request("PUT", "/api/v1/system/configs/UNKNOWN", "ADMIN~SYSTEM.CONFIG.UPDATE", "{\"value\":4}");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("CONFIG_KEY_UNKNOWN");
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
    }

    @Test
    // JSON sai cú pháp bị từ chối trước khi có thể tạo thay đổi hoặc audit.
    void malformedJsonDoesNotReachTheService() throws Exception {
        assertThat(request("PUT", "/api/v1/system/configs/NOTIFICATION_RETRY_LIMIT",
                "ADMIN~SYSTEM.CONFIG.UPDATE", "{").statusCode()).isEqualTo(400);
        verify(service, never()).update(any(), any(), any(), any());
    }

    @Test
    // Giao thức đọc cấu hình cũ vẫn đi qua security khi không có JWT và giữ 404 thật.
    void nativeProtocolIsNotTurnedIntoAnAuthenticatedApi() throws Exception {
        assertThat(request("GET", "/notification-service/default", null, null).statusCode()).isEqualTo(404);
    }

    // Gửi request HTTP thật; decoder fixture chỉ thay chữ ký, giữ filter và method security thật.
    private HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({SystemConfigController.class, SystemConfigConfiguration.class})
    static class TestApplication {
        @Bean
        // Tạo claim fixture để kiểm tra quyền; không giả lập controller hay method interceptor.
        JwtDecoder fixtureDecoder() {
            return token -> {
                String[] fields = token.split("~", 2);
                if (fields.length != 2) throw new BadJwtException("Invalid fixture token");
                return Jwt.withTokenValue(token).header("alg", "RS256").subject(ACTOR.toString())
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                        .claim("roles", List.of(fields[0])).claim("privileges", List.of(fields[1])).build();
            };
        }
    }
}
