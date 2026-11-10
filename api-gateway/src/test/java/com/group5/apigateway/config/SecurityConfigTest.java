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
import com.group5.apigateway.ratelimit.RedisWindowRateLimiter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.core.env.Environment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = SecurityConfigTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.name=api-gateway-security-test",
                "spring.main.web-application-type=reactive",
                "spring.cloud.config.enabled=false",
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=true",
                "management.tracing.export.zipkin.enabled=false",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:18080/realms/nexus",
                "spring.security.oauth2.resourceserver.jwt.audiences[0]=nexus-services"
        })
class SecurityConfigTest {
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    private static final String ME = "/api/v1/users/me";
    private static final String ISSUER = "http://localhost:18080/realms/nexus";
    private static final List<String> NOTIFICATION_PATHS = List.of(
            "/api/v1/notifications", "/api/v1/notifications/unread-count");
    private static final String KEY_ID = "gateway-rs256-test";
    private static final RSAKey SIGNING_KEY = generateKey();
    private static final RSAKey FOREIGN_KEY = generateKey();
    private static final List<String> SERVICE_LOG = new CopyOnWriteArrayList<>();
    private static final List<String> FORWARDED_TOKENS = new CopyOnWriteArrayList<>();
    private static final HttpServer JWKS = startServer(true);
    private static final HttpServer DOWNSTREAM = startServer(false);
    private static final Map<String, HttpServer> DOMAIN_SERVERS = Map.of(
            "payment-service", startDomainServer("payment-service"),
            "shipment-service", startDomainServer("shipment-service"),
            "rating-service", startDomainServer("rating-service"),
            "inventory-service", startDomainServer("inventory-service"));
    private static final List<String> DOMAIN_LOG = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean RATE_LIMITED = new AtomicBoolean();

    @LocalServerPort
    private int port;
    @Autowired
    private SecurityConfig.GatewaySecurityProperties permissions;
    @Autowired
    private RouteLocator routeLocator;
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
        DOMAIN_LOG.clear();
        RATE_LIMITED.set(false);
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    @AfterAll
    static void closeServers() {
        JWKS.stop(0);
        DOWNSTREAM.stop(0);
        DOMAIN_SERVERS.values().forEach(server -> server.stop(0));
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
    void preflightFromTheFrontendIsAnsweredWithoutATokenAndAllowsCredentials() {
        client.options().uri("/api/v1/auth/register")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,idempotency-key,x-requested-with")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, v -> assertThat(v).contains("POST"));
        assertThat(SERVICE_LOG).isEmpty();
    }

    @Test
    void guestCartPreflightAllowsTheGuestTokenHeader() {
        client.options().uri("/api/v1/carts/me/items/" + ID)
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-guest-token")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000")
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        value -> assertThat(value.toLowerCase(java.util.Locale.ROOT)).contains("x-guest-token"));
        assertThat(SERVICE_LOG).isEmpty();
    }

    @Test
    void preflightFromAnotherOriginIsRefused() {
        client.options().uri(ME)
                .header(HttpHeaders.ORIGIN, "http://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange().expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(SERVICE_LOG).isEmpty();
    }

    @Test
    void theActualResponseCarriesTheAllowOriginHeaderAndStillNeedsTheToken() {
        client.get().uri(ME).header(HttpHeaders.ORIGIN, "http://localhost:3000").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }

    @Test
    void aWildcardOriginIsRejectedAtStartupBecauseItCannotBeUsedWithCookies() {
        SecurityConfig.GatewaySecurityProperties properties = new SecurityConfig.GatewaySecurityProperties();
        properties.setCorsAllowedOrigins(List.of("*"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SecurityConfig().corsConfigurationSource(properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("wildcard");
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
    void eventAndAuditLogsApplyBuyerSupportAndIndependentDetailPrivileges() {
        String list = "/api/v1/event-logs";
        String detail = list + "/" + ID;
        String audit = "/api/v1/audit-logs";
        String buyer = "Bearer " + token(SIGNING_KEY, c -> c.claim("roles", List.of("BUYER")));
        String support = "Bearer " + token(SIGNING_KEY, c -> c.claim("roles", List.of("SUPPORT_STAFF"))
                .claim("privileges", List.of("EVENT_LOG.VIEW", "EVENT_LOG.VIEW_DETAIL")));
        for (String path : List.of(list, detail, audit)) {
            client.get().uri(path).exchange().expectStatus().isUnauthorized();
            client.get().uri(path).header(HttpHeaders.AUTHORIZATION, buyer).exchange().expectStatus().isForbidden();
        }
        assertThat(SERVICE_LOG).isEmpty();
        client.get().uri(list).header(HttpHeaders.AUTHORIZATION, support).exchange().expectStatus().isOk();
        client.get().uri(detail).header(HttpHeaders.AUTHORIZATION, support).exchange().expectStatus().isOk();
        client.get().uri(audit).header(HttpHeaders.AUTHORIZATION, support).exchange().expectStatus().isForbidden();
        String listOnly = "Bearer " + token(SIGNING_KEY, c -> c.claim("privileges", List.of("EVENT_LOG.VIEW")));
        client.get().uri(detail).header(HttpHeaders.AUTHORIZATION, listOnly).exchange().expectStatus().isForbidden();
        String auditOnly = "Bearer " + token(SIGNING_KEY, c -> c.claim("privileges", List.of("AUDIT_LOG.VIEW")));
        client.get().uri(audit).header(HttpHeaders.AUTHORIZATION, auditOnly).exchange().expectStatus().isOk();
        assertThat(SERVICE_LOG).containsExactly("GET " + list, "GET " + detail, "GET " + audit);
    }

    @Test
    void productionRoutesSendAllLogPathsToNotificationService() {
        var route = routeLocator.getRoutes().filter(r -> r.getId().equals("notification-service")).blockFirst();
        assertThat(route).isNotNull();
        assertThat(route.getUri().toString()).isEqualTo("lb://notification-service");
        for (String path : List.of("/api/v1/event-logs", "/api/v1/event-logs/" + ID, "/api/v1/audit-logs")) {
            var exchange = org.springframework.mock.web.server.MockServerWebExchange.from(
                    org.springframework.mock.http.server.reactive.MockServerHttpRequest.get(path));
            assertThat(reactor.core.publisher.Mono.from(route.getPredicate().apply(exchange)).block()).as(path).isTrue();
        }
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
    void reservingStockNeedsATokenAndForwardsTheBuyerBearerToInventory() {
        String path = "/api/v1/inventory/reservations";
        client.post().uri(path).exchange().expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        assertThat(DOMAIN_LOG).isEmpty();
        String buyer = "Bearer " + token(SIGNING_KEY, c -> c.claim("roles", List.of("BUYER")));
        client.post().uri(path).header(HttpHeaders.AUTHORIZATION, buyer).exchange()
                .expectStatus().isOk().expectBody(String.class).isEqualTo("inventory-service");
        assertThat(DOMAIN_LOG).containsExactly("inventory-service POST " + path);
        assertThat(FORWARDED_TOKENS).containsExactly(buyer);
    }

    @Test
    void refundsRequireOrderRefundAndABuyerTokenNeverReachesTheService() {
        String complete = "/api/v1/refunds/" + ID + "/complete";
        String buyer = "Bearer " + token(SIGNING_KEY, c -> c.claim("roles", List.of("BUYER"))
                .claim("privileges", List.of("ORDER.VIEW", "CHECKOUT.START")));
        client.post().uri("/api/v1/refunds").header(HttpHeaders.AUTHORIZATION, buyer)
                .exchange().expectStatus().isForbidden();
        client.get().uri("/api/v1/refunds?orderId=" + ID).header(HttpHeaders.AUTHORIZATION, buyer)
                .exchange().expectStatus().isForbidden();
        client.post().uri(complete).header(HttpHeaders.AUTHORIZATION, buyer)
                .exchange().expectStatus().isForbidden();
        assertThat(SERVICE_LOG).isEmpty();

        String support = "Bearer " + token(SIGNING_KEY, c -> c.claim("roles", List.of("SUPPORT_STAFF"))
                .claim("privileges", List.of("ORDER.REFUND")));
        client.post().uri("/api/v1/refunds").header(HttpHeaders.AUTHORIZATION, support)
                .exchange().expectStatus().isOk();
        client.post().uri(complete).header(HttpHeaders.AUTHORIZATION, support)
                .exchange().expectStatus().isOk();
        assertThat(SERVICE_LOG).containsExactly("POST /api/v1/refunds", "POST " + complete);
        assertThat(FORWARDED_TOKENS).containsExactly(support, support);
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
        expectInvalidToken(token(SIGNING_KEY, c -> c.issuer("user-service")));
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
    void rejectsLegacyUserServiceTokenEvenIfIssuerClaimsToBeKeycloak() {
        expectInvalidToken(token(FOREIGN_KEY, c -> c.issuer("user-service")));
        expectInvalidToken(token(FOREIGN_KEY, c -> c.claim("privileges", List.of("PROFILE.VIEW"))));
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
    void allConfiguredPublicPairsWorkWithoutBearerToken() {
        for (SecurityConfig.Endpoint endpoint : permissions.endpoints()) {
            if (endpoint.isPublic()) {
                client.method(endpoint.method()).uri(concretePath(endpoint.path()))
                        .exchange().expectStatus().isOk();
            }
        }
        assertThat(SERVICE_LOG).hasSize((int) permissions.endpoints().stream()
                .filter(SecurityConfig.Endpoint::isPublic).count());
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
                var response = client.method(method).uri(concretePath(endpoint.path())).exchange();
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
            String path = concretePath(endpoint.path());
            int requests = SERVICE_LOG.size();
            var anonymousResponse = client.method(endpoint.method()).uri(path).exchange();
            if (endpoint.allowGuest()) {
                anonymousResponse.expectStatus().isOk();
                requests++;
            } else {
                anonymousResponse.expectStatus().isUnauthorized();
            }
            assertThat(SERVICE_LOG).hasSize(requests);
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
                    .exchange().expectStatus().isOk().expectBody(String.class)
                    .value(body -> assertThat(body).isNotEmpty());
            assertThat(SERVICE_LOG).hasSize(requests + 1);
        }
    }

    @Test
    void auctionScheduleRequiresUpdatePrivilegeAndDoesNotInheritPublicGet() {
        String path = "/api/v1/auctions/" + ID + "/schedule";
        client.post().uri(path).exchange().expectStatus().isUnauthorized();
        client.post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(SIGNING_KEY,
                        c -> c.claim("privileges", List.of("AUCTION.CREATE"))))
                .exchange().expectStatus().isForbidden();
        assertThat(SERVICE_LOG).isEmpty();

        client.post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(SIGNING_KEY,
                        c -> c.claim("privileges", List.of("AUCTION.UPDATE"))))
                .exchange().expectStatus().isOk();
        assertThat(SERVICE_LOG).containsExactly("POST " + path);
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
                "/api/v1/auctions/mine", "/api/v1/auctions/not-a-uuid", "/api/v1/auctions/" + ID + "/bids",
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

    @Test
    void guestCartPoliciesAllowAnonymousButStillRejectInvalidBearerAndProtectMerge() {
        for (SecurityConfig.Endpoint endpoint : permissions.endpoints()) {
            if (!endpoint.allowGuest()) continue;
            String path = endpoint.path().replace("{id}", ID);
            client.method(endpoint.method()).uri(path).header("X-Guest-Token", "guest-owner")
                    .exchange().expectStatus().isOk();
            client.method(endpoint.method()).uri(path).header("X-Guest-Token", "guest-owner")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer invalid")
                    .exchange().expectStatus().isUnauthorized();
        }
        assertThat(SERVICE_LOG).hasSize(4);
        client.post().uri("/api/v1/carts/me/merge").header("X-Guest-Token", "guest-owner")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void newPrefixesHaveExactlyOneOwningLoadBalancedRoute() {
        Map<String, String> prefixes = Map.ofEntries(
                Map.entry("/api/v1/payments", "payment-service"),
                Map.entry("/api/v1/refunds", "payment-service"),
                Map.entry("/api/v1/webhooks/sepay", "payment-service"),
                Map.entry("/api/v1/shipments", "shipment-service"),
                Map.entry("/api/v1/shipping", "shipment-service"),
                Map.entry("/api/v1/carriers", "shipment-service"),
                Map.entry("/api/v1/webhooks/carriers", "shipment-service"),
                Map.entry("/api/v1/ratings", "rating-service"),
                Map.entry("/api/v1/reputations", "rating-service"),
                Map.entry("/api/v1/inventory", "inventory-service"),
                Map.entry("/api/v1/warehouses", "inventory-service"));
        String bearer = "Bearer " + token(SIGNING_KEY, c -> c.claim("privileges",
                permissions.endpoints().stream().filter(endpoint -> !endpoint.isPublic())
                        .map(SecurityConfig.Endpoint::privilege).distinct().toList()));
        for (var entry : prefixes.entrySet()) {
            List<String> paths = entry.getKey().endsWith("/sepay")
                    ? List.of(entry.getKey()) : List.of(entry.getKey(), entry.getKey() + "/" + ID);
            for (String path : paths) {
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
                var matches = routeLocator.getRoutes()
                        .filterWhen(route -> route.getPredicate().apply(exchange)).collectList().block();
                assertThat(matches).as("unique owner for %s", path).hasSize(1);
                assertThat(matches.getFirst().getId()).isEqualTo(entry.getValue());
                assertThat(matches.getFirst().getUri().toString()).isEqualTo("lb://" + entry.getValue());
                assertThat(matches.getFirst().getFilters()).isNotEmpty();
                client.get().uri(path).header(HttpHeaders.AUTHORIZATION, bearer).exchange()
                        .expectStatus().isOk().expectBody(String.class).isEqualTo(entry.getValue());
                assertThat(DOMAIN_LOG).contains(entry.getValue() + " GET " + path);
            }
        }
        assertThat(routeLocator.getRoutes().map(route -> route.getUri().toString()).collectList().block())
                .doesNotContain("lb://fulfillment-service");
    }

    @Test
    void publicAddressListsAndSepayCallbackReachTheirOwnService() {
        for (String path : List.of("/api/v1/shipping/provinces",
                "/api/v1/shipping/districts?provinceCode=202",
                "/api/v1/shipping/wards?districtCode=1442")) {
            client.get().uri(path).exchange().expectStatus().isOk()
                    .expectBody(String.class).isEqualTo("shipment-service");
        }
        client.post().uri("/api/v1/webhooks/sepay").bodyValue("provider-payload")
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("payment-service");
        for (String carrier : List.of("ghn", "ghtk")) {
            client.post().uri("/api/v1/webhooks/carriers/" + carrier).bodyValue("provider-payload")
                    .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("shipment-service");
        }
        assertThat(DOMAIN_LOG).containsExactly(
                "shipment-service GET /api/v1/shipping/provinces",
                "shipment-service GET /api/v1/shipping/districts",
                "shipment-service GET /api/v1/shipping/wards",
                "payment-service POST /api/v1/webhooks/sepay",
                "shipment-service POST /api/v1/webhooks/carriers/ghn",
                "shipment-service POST /api/v1/webhooks/carriers/ghtk");
    }

    @Test
    void webhookAndAddressExceptionsDoNotLeakToOtherPathsOrMethods() {
        for (String path : List.of("/api/v1/webhooks/unrelated", "/api/v1/webhooks/sepay/extra",
                "/api/v1/webhooks/carriers/unknown", "/api/v1/webhooks/carriers/ghn/extra",
                "/api/v1/webhooks/carriers/GHN", "/api/v1/webhooks/carriers/ghtk/extra",
                "/api/v1/shipping/quotes", "/api/v1/shipping/provinces/extra")) {
            client.post().uri(path).exchange().expectStatus().isUnauthorized();
            client.get().uri(path).exchange().expectStatus().isUnauthorized();
        }
        client.get().uri("/api/v1/webhooks/sepay").exchange().expectStatus().isUnauthorized();
        assertThat(SERVICE_LOG).isEmpty();
    }

    @Test
    void callbacksPreserveRawBodyProviderAuthenticationAndDownstreamRejection() {
        // This is a fixture protocol, not a claim about the SePay/GHN/GHTK implementation.
        byte[] payload = "{\"deliveryId\":\"fixture-123\", \"amount\":350000}\n"
                .getBytes(StandardCharsets.UTF_8);
        for (String path : List.of("/api/v1/webhooks/sepay",
                "/api/v1/webhooks/carriers/ghn", "/api/v1/webhooks/carriers/ghtk")) {
            String owner = path.endsWith("sepay") ? "payment-service" : "shipment-service";
            client.post().uri(path).header("X-Test-Require-Provider-Auth", "true")
                    .header("X-Provider-Signature", "invalid").bodyValue(payload)
                    .exchange().expectStatus().isUnauthorized()
                    .expectHeader().valueEquals("X-Test-Owning-Service", owner);
            client.post().uri(path).header("X-Test-Require-Provider-Auth", "true")
                    .header("X-Provider-Signature", fixtureSignature(payload)).bodyValue(payload)
                    .exchange().expectStatus().isOk()
                    .expectHeader().valueEquals("X-Test-Owning-Service", owner);
        }
        assertThat(DOMAIN_LOG).hasSize(6);
    }

    private static String fixtureSignature(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("gateway-fixture-only".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void internalAndKeycloakPathsAreNeverForwardedEvenWithValidToken() {
        String bearer = "Bearer " + token(SIGNING_KEY, c -> { });
        for (String path : List.of("/internal/v1/eligibility/" + ID,
                "/internal/v1/inventory/reservations", "/realms/nexus",
                "/realms/nexus/protocol/openid-connect/certs", "/admin/realms/nexus",
                "/api/v1/orders/" + ID + "/payment")) {
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST)) {
                client.method(method).uri(path).exchange().expectStatus().isUnauthorized();
                client.method(method).uri(path).header(HttpHeaders.AUTHORIZATION, bearer)
                        .exchange().expectStatus().isNotFound();
            }
        }
        assertThat(SERVICE_LOG).isEmpty();
    }

    @Test
    void newRoutesRetainRateLimitProblemAndRetryAfterWithoutForwarding() {
        RATE_LIMITED.set(true);
        String bearer = "Bearer " + token(SIGNING_KEY, c -> c.claim("privileges", List.of("CHECKOUT.START")));
        client.post().uri("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange().expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "17")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(429);
        assertThat(SERVICE_LOG).isEmpty();
    }

    private static String concretePath(String path) {
        return path.replace("{id}", ID).replace("{userId}", ID).replace("{imageId}", ID);
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
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("nexus-services")
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
    @Import({SecurityConfig.class, GatewayErrorHandler.class, RouteConfig.class})
    @LoadBalancerClients(defaultConfiguration = StubInstances.class)
    static class TestApplication {
        @Bean
        KeyResolver clientIpKeyResolver() {
            return exchange -> Mono.just("gateway-test-client");
        }

        @Bean
        @Primary
        RedisWindowRateLimiter ipRateLimiter() {
            RedisWindowRateLimiter limiter = mock(RedisWindowRateLimiter.class);
            when(limiter.isAllowed(anyString(), anyString())).thenAnswer(invocation ->
                    Mono.just(new RateLimiter.Response(!RATE_LIMITED.get(), RATE_LIMITED.get()
                            ? Map.of("Retry-After", "17") : Map.of())));
            return limiter;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StubInstances {
        @Bean
        ServiceInstanceListSupplier serviceInstanceListSupplier(Environment environment) {
            String serviceId = environment.getProperty("loadbalancer.client.name");
            HttpServer server = DOMAIN_SERVERS.getOrDefault(serviceId, DOWNSTREAM);
            ServiceInstance instance = new DefaultServiceInstance(serviceId + "-test", serviceId,
                    "127.0.0.1", server.getAddress().getPort(), false);
            return new ServiceInstanceListSupplier() {
                @Override
                public String getServiceId() {
                    return serviceId;
                }

                @Override
                public Flux<List<ServiceInstance>> get() {
                    return Flux.just(List.of(instance));
                }
            };
        }
    }

    private static HttpServer startDomainServer(String serviceId) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] payload = exchange.getRequestBody().readAllBytes();
                String request = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
                SERVICE_LOG.add(request);
                DOMAIN_LOG.add(serviceId + " " + request);
                String bearer = exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                if (bearer != null) FORWARDED_TOKENS.add(bearer);
                byte[] body = serviceId.getBytes(StandardCharsets.UTF_8);
                boolean rejected = exchange.getRequestHeaders().containsKey("X-Test-Require-Provider-Auth")
                        && !fixtureSignature(payload).equals(exchange.getRequestHeaders().getFirst("X-Provider-Signature"));
                exchange.getResponseHeaders().set("X-Test-Owning-Service", serviceId);
                exchange.sendResponseHeaders(rejected ? 401 : 200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
