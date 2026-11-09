package com.group5.inventoryservice;

import com.group5.common_security.CurrentUser;
import com.group5.inventoryservice.inventory.InventoryBalanceRepository;
import com.group5.inventoryservice.inventory.InventoryMovementRepository;
import com.group5.inventoryservice.warehouse.repository.WarehouseRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Loads the real service application and its classpath security auto-configuration.
 * Only unrelated database/Kafka infrastructure is isolated; JWT verification is real.
 */
@SpringBootTest(classes = InventoryServiceApplication.class, properties = {
        "spring.config.name=jwt-resource-server-test",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "management.tracing.export.zipkin.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "nexus.security.jwt.issuer=http://localhost:18080/realms/nexus",
        "nexus.security.jwt.audience=nexus-services"
})
@Import(KeycloakTokenTrustTest.Probe.class)
class KeycloakTokenTrustTest {
    private static final String ISSUER = "http://localhost:18080/realms/nexus";
    private static final String SUBJECT = "68000000-0000-4000-8000-000000000002";
    private static final RSAKey KEY = key();
    private static final RSAKey LEGACY_KEY = key();
    private static final HttpServer JWKS = jwks();
    @Autowired private WebApplicationContext context;
    @MockitoBean private InventoryBalanceRepository balances;
    @MockitoBean private InventoryMovementRepository movements;
    @MockitoBean private WarehouseRepository warehouses;
    private MockMvc client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nexus.security.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/certs");
    }

    @BeforeEach
    void client() {
        client = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterAll
    static void stop() { JWKS.stop(0); }

    @Test
    void directProtectedRequestRequiresBearerEvenWithForgedIdentityHeaders() throws Exception {
        client.perform(get("/test/security").header("X-User-Id", SUBJECT).header("X-Role", "ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void keycloakTokenPreservesFlatRolesPrivilegesAndSubject() throws Exception {
        client.perform(get("/test/security").header("Authorization", "Bearer " + token(KEY, ISSUER, List.of("nexus-services"))))
                .andExpect(status().isOk()).andExpect(content().string(SUBJECT));
    }

    @Test
    void wrongIssuerMissingOrWrongAudienceAndLegacySignatureAreRejected() throws Exception {
        for (String invalid : List.of(token(KEY, "user-service", List.of("nexus-services")),
                token(KEY, ISSUER, null), token(KEY, ISSUER, List.of("other-service")),
                token(LEGACY_KEY, ISSUER, List.of("nexus-services")))) {
            client.perform(get("/test/security").header("Authorization", "Bearer " + invalid))
                    .andExpect(status().isUnauthorized());
        }
    }

    private static String token(RSAKey key, String issuer, List<String> audience) throws Exception {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("realm-key").build(),
                new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(SUBJECT)
                        .claim("azp", "nexus-web").claim("roles", List.of("SELLER"))
                        .claim("privileges", List.of("PRODUCT.CREATE"))
                        .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static RSAKey key() {
        try { return new RSAKeyGenerator(2048).keyID("realm-key").generate(); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static HttpServer jwks() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/certs", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    @RestController
    static class Probe {
        @GetMapping("/test/security")
        @PreAuthorize("hasRole('SELLER') and hasAuthority('PRODUCT.CREATE')")
        public String protectedEndpoint(CurrentUser user) { return user.id().toString(); }
    }
}
