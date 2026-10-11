package com.group5.common_security;

import com.group5.common_security.autoconfigure.NexusSecurityProperties;
import com.group5.common_security.autoconfigure.SecurityAutoConfiguration;
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
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = SecurityAutoConfigurationTest.TestApplication.class,
        properties = {
                "nexus.security.jwt.issuer=user-service",
                "nexus.security.jwt.audience=nexus-services",
                "nexus.security.public-endpoints[0].method=GET",
                "nexus.security.public-endpoints[0].path=/test/public"
        })
class SecurityAutoConfigurationTest {

    private static final String ISSUER = "user-service";
    private static final String AUDIENCE = "nexus-services";
    private static final String KEY_ID = "nexus-rs256-test";
    private static final UUID USER_ID = UUID.fromString("64fba061-3a91-4a35-a853-221798344510");

    private static final RSAKey SIGNING_KEY = generateKey();
    private static final RSAKey FOREIGN_KEY = generateKey();
    private static final HttpServer JWKS_SERVER = startJwksServer();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private Tracer tracer;

    private MockMvc mockMvc;
    private String traceId;

    @DynamicPropertySource
    static void jwkSetUri(DynamicPropertyRegistry registry) {
        registry.add("nexus.security.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/.well-known/jwks.json");
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.stop(0);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void validTokenReachesTheControllerAndCurrentUserCarriesItsClaims() throws Exception {
        perform(withToken(get("/test/me"), token(SIGNING_KEY, claims -> { })))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.roles[0]").value("SELLER"))
                .andExpect(jsonPath("$.privileges[0]").value("PRODUCT.CREATE"))
                .andExpect(jsonPath("$.isSeller").value(true));
    }

    @Test
    void missingTokenReturnsAProblemWithTypeAndTraceId() throws Exception {
        perform(get("/test/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/test/me"))
                .andExpect(jsonPath("$.traceId").value(traceId));
        assertThat(traceId).matches("[0-9a-f]{32}");
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        Instant past = Instant.now().minusSeconds(7200);
        String expired = token(SIGNING_KEY, claims -> claims
                .issueTime(Date.from(past))
                .expirationTime(Date.from(past.plusSeconds(3600))));

        expectUnauthorized(expired);
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        expectUnauthorized(token(SIGNING_KEY, claims -> claims.issuer("someone-else")));
    }

    @Test
    void wrongAudienceIsRejected() throws Exception {
        expectUnauthorized(token(SIGNING_KEY, claims -> claims.audience("another-system")));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        expectUnauthorized(token(FOREIGN_KEY, claims -> { }));
    }

    @Test
    void roleClaimBecomesARoleAuthority() throws Exception {
        perform(withToken(get("/test/seller"), token(SIGNING_KEY, claims -> { })))
                .andExpect(status().isOk());

        perform(withToken(get("/test/seller"),
                        token(SIGNING_KEY, claims -> claims.claim("roles", List.of("BUYER")))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:forbidden"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.traceId").value(traceId));
    }

    @Test
    void privilegeClaimBecomesAnAuthorityWithTheSameName() throws Exception {
        perform(withToken(post("/test/products"), token(SIGNING_KEY, claims -> { })))
                .andExpect(status().isOk());

        perform(withToken(post("/test/products"),
                        token(SIGNING_KEY, claims -> claims.claim("privileges", List.of("PRODUCT.UPDATE")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:problem:forbidden"));
    }

    @Test
    void configuredPublicEndpointIsOpenForThatMethodOnly() throws Exception {
        perform(get("/test/public"))
                .andExpect(status().isOk());

        perform(post("/test/public"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:problem:unauthorized"));
    }

    @Test
    void healthEndpointNeedsNoToken() throws Exception {
        int status = perform(get("/actuator/health")).andReturn().getResponse().getStatus();

        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void aMissingSettingStopsTheApplicationAndNamesTheProperty() {
        NexusSecurityProperties properties = new NexusSecurityProperties();
        properties.getJwt().setJwkSetUri("http://127.0.0.1/.well-known/jwks.json");
        properties.getJwt().setAudience(AUDIENCE);

        assertThatIllegalStateException()
                .isThrownBy(() -> new SecurityAutoConfiguration().jwtDecoder(properties))
                .withMessage("nexus.security.jwt.issuer must be set");
    }

    private void expectUnauthorized(String token) throws Exception {
        perform(withToken(get("/test/me"), token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:problem:unauthorized"))
                .andExpect(jsonPath("$.traceId").value(traceId));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        Span span = tracer.nextSpan().name("test-request").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            traceId = span.context().traceId();
            return mockMvc.perform(request);
        } finally {
            span.end();
        }
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static String token(RSAKey key, Consumer<JWTClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(USER_ID.toString())
                .claim("roles", List.of("SELLER"))
                .claim("privileges", List.of("PRODUCT.CREATE"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .jwtID(UUID.randomUUID().toString());
        customizer.accept(claims);
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).type(JOSEObjectType.JWT).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static HttpServer startJwksServer() {
        try {
            byte[] body = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @RestController
    public static class TestController {

        @GetMapping("/test/me")
        public Map<String, Object> me(CurrentUser user) {
            return Map.of(
                    "id", user.id().toString(),
                    "roles", user.roles(),
                    "privileges", user.privileges(),
                    "isSeller", user.hasRole("SELLER"));
        }

        @GetMapping("/test/seller")
        @PreAuthorize("hasRole('SELLER')")
        public String seller() {
            return "ok";
        }

        @PostMapping("/test/products")
        @PreAuthorize("hasAuthority('PRODUCT.CREATE')")
        public String createProduct() {
            return "ok";
        }

        @GetMapping("/test/public")
        public String publicRead() {
            return "ok";
        }

        @PostMapping("/test/public")
        public String publicWrite() {
            return "ok";
        }
    }
}
