package com.group5.apigateway.session;

import com.group5.apigateway.config.GatewayErrorHandler;
import com.group5.apigateway.config.SecurityConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chạy gateway thật với một Keycloak giả (JDK HttpServer) để kiểm cookie phiên từ đầu đến cuối.
 * Keycloak giả giữ danh sách refresh token đã dùng, nên test xoay token và dùng lại token cũ chạy đúng như thật.
 */
@SpringBootTest(classes = SessionControllerTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.name=api-gateway-session-test",
                "spring.main.web-application-type=reactive",
                "spring.cloud.config.enabled=false",
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "management.tracing.export.zipkin.enabled=false",
                "spring.security.oauth2.resourceserver.jwt.audiences[0]=nexus-services"
        })
class SessionControllerTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String VERIFIER = "v".repeat(64);
    private static final String REDIRECT = "http://localhost:3000/auth/callback";

    // Mọi lần gọi vào Keycloak giả, dạng "token <form>" hoặc "logout <refresh token>"
    private static final List<String> KEYCLOAK_CALLS = new CopyOnWriteArrayList<>();
    private static final Set<String> USED_REFRESH = ConcurrentHashMap.newKeySet();
    private static final List<String> DOWNSTREAM_CALLS = new CopyOnWriteArrayList<>();
    private static final HttpServer KEYCLOAK = startKeycloak();
    private static final HttpServer DOWNSTREAM = startDownstream();

    @LocalServerPort
    private int port;
    @Autowired
    private SessionProperties session;
    private WebTestClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        gatewayYaml().forEach((key, value) -> {
            if (key.toString().startsWith("nexus.security.")) {
                registry.add(key.toString(), () -> value);
            }
        });
        String keycloak = "http://127.0.0.1:" + KEYCLOAK.getAddress().getPort();
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> keycloak + "/realms/nexus");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> keycloak + "/jwks-not-used");
    }

    // cấu hình thật của gateway trên Config Server, để test dùng đúng origin và cờ cookie như khi chạy
    private static java.util.Properties gatewayYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("../config-service/src/main/resources/config/api-gateway.yml"));
        return yaml.getObject();
    }

    @BeforeEach
    void setUp() {
        KEYCLOAK_CALLS.clear();
        USED_REFRESH.clear();
        DOWNSTREAM_CALLS.clear();
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    @AfterAll
    static void stopServers() {
        KEYCLOAK.stop(0);
        DOWNSTREAM.stop(0);
    }

    // ---- POST /api/v1/auth/session

    @Test
    void signInReturnsTheAccessTokenInTheBodyAndTheRefreshTokenOnlyInAnHttpOnlyCookie() {
        client.post().uri("/api/v1/auth/session").headers(this::fromTheApp)
                .bodyValue(Map.of("code", "good-code", "codeVerifier", VERIFIER, "redirectUri", REDIRECT))
                .exchange().expectStatus().isOk()
                .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
                .expectHeader().value(HttpHeaders.SET_COOKIE, cookie -> assertThat(cookie)
                        .startsWith("nexus_rt=refresh-1;")
                        .contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/api/v1/auth", "Max-Age=1800"))
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.accessToken").isEqualTo("access-1")
                .jsonPath("$.data.idToken").isEqualTo("id-1")
                .jsonPath("$.data.expiresIn").isEqualTo(300)
                // refresh token chỉ nằm trong cookie, không có trong body
                .jsonPath("$.data.refreshToken").doesNotExist()
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent(), StandardCharsets.UTF_8))
                        .doesNotContain("refresh-1"));

        // trình duyệt là public client nên gateway không gửi secret, chỉ gửi mã + PKCE verifier
        assertThat(KEYCLOAK_CALLS).singleElement().satisfies(call -> assertThat(call)
                .contains("grant_type=authorization_code", "client_id=nexus-web", "code=good-code",
                        "code_verifier=" + VERIFIER, "redirect_uri=" + REDIRECT)
                .doesNotContain("client_secret"));
    }

    @Test
    void signInWithACodeKeycloakRefusesReturns401AndSetsNoCookie() {
        client.post().uri("/api/v1/auth/session").headers(this::fromTheApp)
                .bodyValue(Map.of("code", "used-code", "codeVerifier", VERIFIER, "redirectUri", REDIRECT))
                .exchange().expectStatus().isUnauthorized()
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE)
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void signInWithAShortVerifierReturns400WithoutCallingKeycloak() {
        client.post().uri("/api/v1/auth/session").headers(this::fromTheApp)
                .bodyValue(Map.of("code", "good-code", "codeVerifier", "short", "redirectUri", REDIRECT))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_PARAMETER");
        assertThat(KEYCLOAK_CALLS).isEmpty();
    }

    @Test
    void theSessionEndpointsAreAnsweredByTheGatewayAndNeverForwardedToAService() {
        client.post().uri("/api/v1/auth/session").headers(this::fromTheApp)
                .bodyValue(Map.of("code", "good-code", "codeVerifier", VERIFIER, "redirectUri", REDIRECT))
                .exchange().expectStatus().isOk();
        client.post().uri("/api/v1/auth/refresh").headers(this::fromTheApp).exchange().expectStatus().isNoContent();
        client.post().uri("/api/v1/auth/logout").headers(this::fromTheApp).exchange().expectStatus().isNoContent();
        assertThat(DOWNSTREAM_CALLS).isEmpty();
    }

    // ---- POST /api/v1/auth/refresh

    @Test
    void refreshTradesTheCookieForANewAccessTokenAndRotatesTheCookie() {
        client.post().uri("/api/v1/auth/refresh").headers(this::fromTheApp)
                .cookie(session.cookieName(), "refresh-1")
                .exchange().expectStatus().isOk()
                .expectHeader().value(HttpHeaders.SET_COOKIE, cookie -> assertThat(cookie)
                        .startsWith("nexus_rt=refresh-2;").contains("HttpOnly", "SameSite=Lax"))
                .expectBody().jsonPath("$.data.accessToken").isEqualTo("access-2");
        assertThat(KEYCLOAK_CALLS).singleElement().satisfies(call -> assertThat(call)
                .contains("grant_type=refresh_token", "refresh_token=refresh-1"));
    }

    @Test
    void anAlreadyUsedRefreshTokenReturns401AndClearsTheCookie() {
        USED_REFRESH.add("refresh-1");
        client.post().uri("/api/v1/auth/refresh").headers(this::fromTheApp)
                .cookie(session.cookieName(), "refresh-1")
                .exchange().expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.SET_COOKIE, cookie -> assertThat(cookie)
                        .startsWith("nexus_rt=;").contains("Max-Age=0"));
    }

    @Test
    void refreshWithoutTheCookieReturns204WithoutCallingKeycloak() {
        client.post().uri("/api/v1/auth/refresh").headers(this::fromTheApp)
                .exchange().expectStatus().isNoContent()
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE)
                .expectBody().isEmpty();
        assertThat(KEYCLOAK_CALLS).isEmpty();
    }

    @Test
    void whenKeycloakIsDownRefreshReturns503AndKeepsTheCookie() {
        client.post().uri("/api/v1/auth/refresh").headers(this::fromTheApp)
                .cookie(session.cookieName(), "keycloak-down")
                .exchange().expectStatus().isEqualTo(503)
                // token vẫn có thể còn dùng được, xóa cookie lúc này sẽ đăng xuất oan người dùng
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE)
                .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
    }

    // ---- CSRF: header tùy biến + Origin

    @Test
    void aCallWithoutTheAppHeaderIsRefusedAndTheCookieIsLeftAlone() {
        client.post().uri("/api/v1/auth/refresh").header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(session.cookieName(), "refresh-1")
                .exchange().expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE)
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
        assertThat(KEYCLOAK_CALLS).isEmpty();
    }

    @Test
    void aCallFromAnotherOriginIsRefusedEvenWithTheAppHeader() {
        client.post().uri("/api/v1/auth/refresh").header("X-Requested-With", "nexus-web")
                .header(HttpHeaders.ORIGIN, "http://evil.example")
                .cookie(session.cookieName(), "refresh-1")
                .exchange().expectStatus().isForbidden();
        assertThat(KEYCLOAK_CALLS).isEmpty();
    }

    // ---- POST /api/v1/auth/logout

    @Test
    void logoutEndsTheKeycloakSessionAndClearsTheCookie() {
        client.post().uri("/api/v1/auth/logout").headers(this::fromTheApp)
                .cookie(session.cookieName(), "refresh-1")
                .exchange().expectStatus().isNoContent()
                .expectHeader().value(HttpHeaders.SET_COOKIE, cookie -> assertThat(cookie)
                        .startsWith("nexus_rt=;").contains("Max-Age=0", "HttpOnly"));
        assertThat(KEYCLOAK_CALLS).singleElement().isEqualTo("logout refresh-1");
    }

    @Test
    void logoutStillClearsTheCookieWhenKeycloakFails() {
        client.post().uri("/api/v1/auth/logout").headers(this::fromTheApp)
                .cookie(session.cookieName(), "logout-fails")
                .exchange().expectStatus().isNoContent()
                .expectHeader().value(HttpHeaders.SET_COOKIE, cookie -> assertThat(cookie).contains("Max-Age=0"));
    }

    // ---- cookie không được lọt xuống service

    @Test
    void theCookieIsRemovedFromRequestsForwardedToAService() {
        client.get().uri("/api/v1/products").cookie(session.cookieName(), "refresh-1")
                .exchange().expectStatus().isOk();
        assertThat(DOWNSTREAM_CALLS).singleElement().isEqualTo("GET /api/v1/products cookie=none");
    }

    private void fromTheApp(HttpHeaders headers) {
        headers.set("X-Requested-With", "nexus-web");
        headers.set(HttpHeaders.ORIGIN, ORIGIN);
    }

    // ---- Keycloak giả

    private static HttpServer startKeycloak() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/realms/nexus/protocol/openid-connect/token", exchange -> {
                String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                KEYCLOAK_CALLS.add("token " + URLDecoder.decode(form, StandardCharsets.UTF_8));
                Map<String, String> values = parse(form);
                String grant = values.get("grant_type");
                if ("authorization_code".equals(grant)) {
                    if ("good-code".equals(values.get("code"))) {
                        respond(exchange, 200, tokens("access-1", "refresh-1", "id-1"));
                    } else {
                        respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
                    }
                } else if ("keycloak-down".equals(values.get("refresh_token"))) {
                    respond(exchange, 503, "{}");
                } else if ("refresh-1".equals(values.get("refresh_token")) && USED_REFRESH.add("refresh-1")) {
                    // refresh token được xoay: mỗi lần đổi nhận một cái mới, cái cũ hết dùng được
                    respond(exchange, 200, tokens("access-2", "refresh-2", "id-2"));
                } else {
                    respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
                }
            });
            server.createContext("/realms/nexus/protocol/openid-connect/logout", exchange -> {
                Map<String, String> values = parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String refresh = values.get("refresh_token");
                KEYCLOAK_CALLS.add("logout " + refresh);
                respond(exchange, "logout-fails".equals(refresh) ? 500 : 204, "");
            });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static HttpServer startDownstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                boolean hasCookie = exchange.getRequestHeaders().containsKey(HttpHeaders.COOKIE);
                DOWNSTREAM_CALLS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                        + " cookie=" + (hasCookie ? "present" : "none"));
                respond(exchange, 200, "downstream");
            });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String tokens(String access, String refresh, String id) {
        return "{\"access_token\":\"" + access + "\",\"refresh_token\":\"" + refresh + "\",\"id_token\":\"" + id
                + "\",\"expires_in\":300,\"refresh_expires_in\":1800,\"token_type\":\"Bearer\"}";
    }

    private static Map<String, String> parse(String form) {
        Map<String, String> values = new HashMap<>();
        for (String pair : form.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({SecurityConfig.class, GatewayErrorHandler.class, SessionConfig.class, KeycloakTokenClient.class,
            SessionGuard.class, SessionCookies.class, SessionController.class, CookieStrippingFilter.class})
    static class TestApplication {
        // route giống thật: /api/v1/auth/** trỏ sang user-service, để chứng minh controller của gateway thắng route
        @Bean
        RouteLocator routes(RouteLocatorBuilder builder) {
            return builder.routes().route("fixture", route -> route
                    .path("/api/v1/auth/**", "/api/v1/products")
                    .uri("http://127.0.0.1:" + DOWNSTREAM.getAddress().getPort())).build();
        }
    }
}
