package com.group5.apigateway.config;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = SecurityConfigTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.name=api-gateway-security-test",
                "spring.main.web-application-type=reactive",
                "spring.cloud.config.enabled=false",
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "management.tracing.export.zipkin.enabled=false",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=user-service",
                "spring.security.oauth2.resourceserver.jwt.audiences[0]=nexus-services"
        })
class SecurityConfigTest {
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    private static final String ME = "/api/v1/users/me";
    private static final List<String> NOTIFICATION_PATHS = List.of(
            "/api/v1/notifications", "/api/v1/notifications/unread-count");
    private static final String KEY_ID = "gateway-rs256-test";
    private static final RSAKey SIGNING_KEY = generateKey();
    private static final RSAKey FOREIGN_KEY = generateKey();
    private static final List<String> SERVICE_LOG = new CopyOnWriteArrayList<>();
    private static final List<String> FORWARDED_TOKENS = new CopyOnWriteArrayList<>();
    private static final HttpServer JWKS = startServer(true);
    private static final HttpServer DOWNSTREAM = startServer(false);

    @LocalServerPort
    private int port;
    @Autowired
    private SecurityConfig.GatewaySecurityProperties permissions;
    private WebTestClient client;

    @DynamicPropertySource
    static void jwksUri(DynamicPropertyRegistry registry) {
        GatewayPermissionsTest.gatewayYaml().forEach((key, value) -> {
            if (key.toString().startsWith("nexus.security.")) {
                registry.add(key.toString(), () -> value);
            }
        });
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/.well-known/jwks.json");
    }

    @BeforeEach
    void setUp() {
        SERVICE_LOG.clear();
        FORWARDED_TOKENS.clear();
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    @AfterAll
    static void closeServers() {
        JWKS.stop(0);
        DOWNSTREAM.stop(0);
    }

    @Test
    void missingTokenReturns401ProblemAndNeverReachesService() {
        client.get().uri(ME).exchange().expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectBody().jsonPath("$.type").isEqualTo("https://nexus/errors/unauthorized")
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.instance").isEqualTo(ME)
                .jsonPath("$.code").isNotEmpty();
        assertThat(SERVICE_LOG).isEmpty();
    }

    @Test
    void markingNotificationsRequiresBearerTokenAtTheGateway() {
        for (String path : List.of("/api/v1/notifications/" + ID + "/read", "/api/v1/notifications/read-all")) {
            client.patch().uri(path).exchange().expectStatus().isUnauthorized()
                    .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
        }
        assertThat(SERVICE_LOG).isEmpty();
    }

    @Test
    void validProfileTokenReturns200AndForwardsOriginalBearerToService() {
        String bearer = "Bearer " + token(SIGNING_KEY, c -> c.claim("privileges", List.of("PROFILE.VIEW")));
        client.get().uri(ME).header(HttpHeaders.AUTHORIZATION, bearer).exchange()
                .expectStatus().isOk().expectBody(String.class).isEqualTo("downstream");
        assertThat(SERVICE_LOG).containsExactly("GET " + ME);
        assertThat(FORWARDED_TOKENS).containsExactly(bearer);
    }

    @Test
    void notificationReadsRequireTokenAndViewPrivilege() {
        for (String path : NOTIFICATION_PATHS) {
            client.get().uri(path).exchange().expectStatus().isUnauthorized();
        }
        assertThat(SERVICE_LOG).isEmpty();

        String withoutPrivilege = "Bearer " + token(SIGNING_KEY, c -> { });
        for (String path : NOTIFICATION_PATHS) {
            client.get().uri(path).header(HttpHeaders.AUTHORIZATION, withoutPrivilege)
                    .exchange().expectStatus().isForbidden();
        }
        assertThat(SERVICE_LOG).isEmpty();

        String withPrivilege = "Bearer " + token(SIGNING_KEY,
                c -> c.claim("privileges", List.of("NOTIFICATION.VIEW")));
        for (String path : NOTIFICATION_PATHS) {
            client.get().uri(path).header(HttpHeaders.AUTHORIZATION, withPrivilege)
                    .exchange().expectStatus().isOk();
        }
        assertThat(SERVICE_LOG).containsExactly(
                "GET /api/v1/notifications", "GET /api/v1/notifications/unread-count");
        assertThat(FORWARDED_TOKENS).containsExactly(withPrivilege, withPrivilege);
    }

    @Test
    void operationalOrderListsRequireOrderListBeforeForwardingToTheService() {
        List<String> paths = List.of("/api/v1/orders/manage", "/api/v1/orders/sales");
        for (String path : paths) {
            client.get().uri(path).exchange().expectStatus().isUnauthorized()
                    .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        assertThat(SERVICE_LOG).isEmpty();

        String detailPrivilege = "Bearer " + token(SIGNING_KEY,
                c -> c.claim("privileges", List.of("ORDER.VIEW")));
        for (String path : paths) {
            client.get().uri(path).header(HttpHeaders.AUTHORIZATION, detailPrivilege)
                    .exchange().expectStatus().isForbidden()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
        }
        assertThat(SERVICE_LOG).isEmpty();

        String listPrivilege = "Bearer " + token(SIGNING_KEY,
                c -> c.claim("privileges", List.of("ORDER.LIST")));
        for (String path : paths) {
            client.get().uri(path).header(HttpHeaders.AUTHORIZATION, listPrivilege)
                    .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("downstream");
        }
        assertThat(SERVICE_LOG).containsExactly("GET /api/v1/orders/manage", "GET /api/v1/orders/sales");
        assertThat(FORWARDED_TOKENS).containsExactly(listPrivilege, listPrivilege);
    }

    @Test
    void paymentInitiationRequiresCheckoutPrivilegeAndForwardsBuyerBearer() {
        String path = "/api/v1/payments";
        client.post().uri(path).exchange().expectStatus().isUnauthorized();
        String withoutPrivilege = "Bearer " + token(SIGNING_KEY, c -> { });
        client.post().uri(path).header(HttpHeaders.AUTHORIZATION, withoutPrivilege)
                .exchange().expectStatus().isForbidden();
        assertThat(SERVICE_LOG).isEmpty();
        String buyer = "Bearer " + token(SIGNING_KEY, c -> c.claim("roles", List.of("BUYER"))
                .claim("privileges", List.of("CHECKOUT.START")));
        client.post().uri(path).header(HttpHeaders.AUTHORIZATION, buyer).exchange().expectStatus().isOk();
        assertThat(SERVICE_LOG).containsExactly("POST " + path);
        assertThat(FORWARDED_TOKENS).containsExactly(buyer);
    }

    @Test
    void changingLastSignatureCharacterReturns401WithNoServiceLog() {
        String good = token(SIGNING_KEY, c -> c.claim("privileges", List.of("PROFILE.VIEW")));
        // RSA-2048 signatures end in two significant base64url bits; changing only
        // unused padding bits (e.g. A -> B) would not actually change the signature.
        String bad = good.substring(0, good.length() - 1) + (good.endsWith("A") ? "Q" : "A");
        assertThat(Base64.getUrlDecoder().decode(bad.split("\\.")[2]))
                .isNotEqualTo(Base64.getUrlDecoder().decode(good.split("\\.")[2]));
        expectInvalidToken(bad);

        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        char alias = alphabet.charAt(alphabet.indexOf(good.charAt(good.length() - 1)) + 1);
        String nonCanonical = good.substring(0, good.length() - 1) + alias;
        assertThat(Base64.getUrlDecoder().decode(nonCanonical.split("\\.")[2]))
                .isEqualTo(Base64.getUrlDecoder().decode(good.split("\\.")[2]));
        expectInvalidToken(nonCanonical);
    }

    @Test
    void rejectsWrongIssuerAudienceExpiredMissingExpiryAndForeignSignature() {
        expectInvalidToken(token(SIGNING_KEY, c -> c.issuer("wrong-issuer")));
        expectInvalidToken(token(SIGNING_KEY, c -> c.audience("wrong-audience")));
        expectInvalidToken(token(SIGNING_KEY, c -> c.audience((List<String>) null)));
        expectInvalidToken(token(SIGNING_KEY, c -> c.expirationTime(Date.from(Instant.now().minusSeconds(120)))));
        expectInvalidToken(token(SIGNING_KEY, c -> c.expirationTime(null)));
        expectInvalidToken(token(SIGNING_KEY, c -> c.notBeforeTime(Date.from(Instant.now().plusSeconds(120)))));
        expectInvalidToken(token(FOREIGN_KEY, c -> { }));
        expectInvalidToken("not-a-jwt");
    }

    @Test
    void rejectsAlgorithmsOtherThanRs256() throws Exception {
        SignedJWT jwt = SignedJWT.parse(token(SIGNING_KEY, c -> { }));
        SignedJWT rs512 = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS512).keyID(KEY_ID).build(),
                jwt.getJWTClaimsSet());
        rs512.sign(new RSASSASigner(SIGNING_KEY));
        expectInvalidToken(rs512.serialize());
        String unsigned = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "." + jwt.getPayload().toBase64URL() + ".";
        expectInvalidToken(unsigned);
    }

    @Test
    void allNinePublicPairsWorkWithoutBearerToken() {
        for (SecurityConfig.Endpoint endpoint : permissions.endpoints()) {
            if (endpoint.isPublic()) {
                client.method(endpoint.method()).uri(endpoint.path().replace("{id}", ID))
                        .exchange().expectStatus().isOk();
            }
        }
        assertThat(SERVICE_LOG).hasSize(9);
    }

    @Test
    void otherMethodsOnPublicPathsAreNotPublic() {
        for (SecurityConfig.Endpoint endpoint : permissions.endpoints()) {
            if (!endpoint.isPublic()) {
                continue;
            }
            for (HttpMethod method : HttpMethod.values()) {
                if (method == endpoint.method()) {
                    continue;
                }
                var response = client.method(method).uri(endpoint.path().replace("{id}", ID)).exchange();
                if (method == HttpMethod.TRACE) {
                    // Spring Security's HTTP firewall rejects TRACE before JWT.
                    response.expectStatus().isBadRequest();
                    continue;
                }
                response.expectStatus().isUnauthorized();
                if (method != HttpMethod.HEAD) {
                    response.expectBody().jsonPath("$.code").isNotEmpty();
                }
            }
        }
        assertThat(SERVICE_LOG).isEmpty();
    }

    @Test
    void everyDocumentedPrivilegeIsRequiredAndSufficient() {
        for (SecurityConfig.Endpoint endpoint : permissions.endpoints()) {
            if (endpoint.isPublic()) {
                continue;
            }
            String path = endpoint.path().replace("{id}", ID).replace("{imageId}", ID);
            int requests = SERVICE_LOG.size();
            client.method(endpoint.method()).uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(SIGNING_KEY, c -> { }))
                    .exchange().expectStatus().isForbidden()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().jsonPath("$.type").isEqualTo("https://nexus/errors/forbidden")
                    .jsonPath("$.code").isNotEmpty();
            assertThat(SERVICE_LOG).hasSize(requests);
            client.method(endpoint.method()).uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(SIGNING_KEY,
                            c -> c.claim("privileges", List.of(endpoint.privilege()))))
                    .exchange().expectStatus().isOk();
            assertThat(SERVICE_LOG).hasSize(requests + 1);
        }
    }

    @Test
    void postProductsDoesNotInheritPublicGetAndScopeOrRoleCannotGrantPrivilege() {
        client.post().uri("/api/v1/products").exchange().expectStatus().isUnauthorized();
        for (Consumer<JWTClaimsSet.Builder> claim : List.<Consumer<JWTClaimsSet.Builder>>of(
                c -> c.claim("scope", "PRODUCT.CREATE"),
                c -> c.claim("roles", List.of("ADMIN")),
                c -> c.claim("privileges", List.of("SCOPE_PRODUCT.CREATE")))) {
            client.post().uri("/api/v1/products")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(SIGNING_KEY, claim))
                    .exchange().expectStatus().isForbidden();
        }
        assertThat(SERVICE_LOG).isEmpty();
    }

    @Test
    void literalRoutesSwaggerActuatorAndUnknownPathsAreNotPublic() {
        for (String path : List.of("/api/v1/products/mine", "/api/v1/products/search",
                "/api/v1/products/discovery", "/api/v1/products/not-a-uuid",
                "/api/v1/categories", "/swagger-ui.html", "/actuator/health", "/unrouted")) {
            client.get().uri(path).exchange().expectStatus().isUnauthorized();
        }
        assertThat(SERVICE_LOG).isEmpty();
        client.get().uri("/unrouted").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(SIGNING_KEY, c -> { }))
                .exchange().expectStatus().isNotFound().expectBody()
                .jsonPath("$.type").isEqualTo("https://nexus/errors/not-found");
    }

    @Test
    void invalidBearerIsRejectedEvenOnPublicPaths() {
        client.get().uri("/api/v1/products").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isNotEmpty();
        assertThat(SERVICE_LOG).isEmpty();
    }

    private void expectInvalidToken(String token) {
        client.get().uri(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.type").isEqualTo("https://nexus/errors/unauthorized")
                .jsonPath("$.code").isNotEmpty();
        assertThat(SERVICE_LOG).isEmpty();
    }

    private static String token(RSAKey key, Consumer<JWTClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer("user-service").audience("nexus-services")
                .subject(ID).claim("roles", List.of("SELLER")).claim("privileges", List.of())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600)))
                .jwtID(UUID.randomUUID().toString());
        customizer.accept(claims);
        try {
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(KEY_ID).type(JOSEObjectType.JWT).build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static HttpServer startServer(boolean jwks) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(jwks ? "/.well-known/jwks.json" : "/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                if (!jwks) {
                    SERVICE_LOG.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                    String bearer = exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                    if (bearer != null) {
                        FORWARDED_TOKENS.add(bearer);
                    }
                }
                byte[] body = (jwks ? new JWKSet(SIGNING_KEY.toPublicJWK()).toString() : "downstream")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE,
                        jwks ? MediaType.APPLICATION_JSON_VALUE : MediaType.TEXT_PLAIN_VALUE);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({SecurityConfig.class, GatewayErrorHandler.class})
    static class TestApplication {
        @Bean
        RouteLocator routes(RouteLocatorBuilder builder) {
            return builder.routes().route("fixture", route -> route
                    .path("/api/v1/auth/**", "/api/v1/users/**", "/api/v1/products/**",
                            "/api/v1/categories/**", "/api/v1/skus/**", "/api/v1/carts/**",
                            "/api/v1/orders", "/api/v1/orders/**",
                            "/api/v1/notifications", "/api/v1/notifications/**", "/.well-known/**",
                            "/api/v1/payments", "/api/v1/payments/**")
                    .uri("http://127.0.0.1:" + DOWNSTREAM.getAddress().getPort())).build();
        }
    }
}
