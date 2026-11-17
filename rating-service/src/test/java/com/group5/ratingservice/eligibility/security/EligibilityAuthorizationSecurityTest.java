package com.group5.ratingservice.eligibility.security;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.autoconfigure.SecurityAutoConfiguration;
import com.group5.ratingservice.shared.exception.RatingSecurityProblemAdvice;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(EligibilityAuthorizationSecurityTest.Config.class)
@WebAppConfiguration
@TestPropertySource(properties = {
        "nexus.security.jwt.issuer=http://localhost:18080/realms/nexus",
        "nexus.security.jwt.audience=nexus-services"
})
class EligibilityAuthorizationSecurityTest {
    private static final String ISSUER = "http://localhost:18080/realms/nexus";
    private static final String ENDPOINT = "/internal/v1/eligibility/68000000-0000-4000-8000-000000000003";
    private static final RSAKey KEY = key();
    private static final RSAKey FOREIGN_KEY = key();
    private static final HttpServer JWKS = jwks();

    @Autowired private WebApplicationContext context;
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
    static void stopJwks() {
        JWKS.stop(0);
    }

    @Test
    void genuineSignedMachineTokenReachesTheGuardedInternalOperation() throws Exception {
        client.perform(get(ENDPOINT).header(HttpHeaders.AUTHORIZATION, "Bearer " + token(KEY, claims -> { })))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.eligible").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUYER", "SELLER", "ADMIN", "SUPPORT_STAFF"})
    void ordinaryRoleCannotCallInternalOperationEvenWithItsAuthority(String role) throws Exception {
        String bearer = token(KEY, claims -> claims.claim("azp", "nexus-web")
                .claim("roles", List.of(role)).claim("service_account", null));
        forbidden(bearer);
    }

    @Test
    void serviceMarkerClientAndAuthorityAreAllRequired() throws Exception {
        forbidden(token(KEY, claims -> claims.claim("privileges", List.of("REPUTATION.VIEW"))));
        forbidden(token(KEY, claims -> claims.claim("azp", "other-service")));
        forbidden(token(KEY, claims -> claims.claim("service_account", "true")));
        forbidden(token(KEY, claims -> claims.claim("service_account", false)));
    }

    @Test
    void issuerAudienceAndSignatureAreVerifiedBeforeMachineAuthorization() throws Exception {
        for (String bearer : List.of(token(KEY, claims -> claims.issuer("user-service")),
                token(KEY, claims -> claims.audience(List.of("other-service"))),
                token(KEY, claims -> claims.audience((List<String>) null)),
                token(FOREIGN_KEY, claims -> { }),
                token(KEY, claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(3600)))))) {
            client.perform(get(ENDPOINT).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("RATING_UNAUTHENTICATED"));
        }
    }

    @Test
    void missingTokenAndForgedIdentityHeadersDoNotGrantInternalAccess() throws Exception {
        client.perform(get(ENDPOINT).header("X-User-Id", UUID.randomUUID()).header("X-Role", "ADMIN")
                        .header("X-Privileges", "RATING.ELIGIBILITY.CHECK"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("RATING_UNAUTHENTICATED"));
    }

    private void forbidden(String bearer) throws Exception {
        client.perform(get(ENDPOINT).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RATING_FORBIDDEN"));
    }

    private static String token(RSAKey key, Consumer<JWTClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).issuer(ISSUER)
                .audience("nexus-services").issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("azp", "nexus-auction-service").claim("service_account", true)
                .claim("privileges", List.of("RATING.ELIGIBILITY.CHECK"));
        customizer.accept(claims);
        try {
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rating-machine-test").build(),
                    claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static RSAKey key() {
        try {
            return new RSAKeyGenerator(2048).keyID("rating-machine-test").generate();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static HttpServer jwks() {
        try {
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/certs", exchange -> {
                exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @ImportAutoConfiguration(SecurityAutoConfiguration.class)
    @Import({RatingSecurityProblemAdvice.class, EligibilityAuthorization.class})
    static class Config {
        @Bean Probe probe() { return new Probe(); }
    }

    @RestController
    public static class Probe {
        @GetMapping("/internal/v1/eligibility/{userId}")
        @PreAuthorize("@eligibilityAuthorization.allowed(authentication)")
        public ApiResponse<Map<String, Boolean>> eligibility() {
            return ApiResponse.of(Map.of("eligible", true));
        }
    }
}
