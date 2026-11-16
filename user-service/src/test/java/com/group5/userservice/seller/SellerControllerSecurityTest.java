package com.group5.userservice.seller;

import com.group5.common_security.CurrentUser;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Kiểm tra JWT thật, phân quyền method, CurrentUser và validate request. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = SellerControllerSecurityTest.Config.class)
@TestPropertySource(properties = {"spring.config.name=seller-security-test", "spring.config.import=",
        "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "management.tracing.export.zipkin.enabled=false",
        "nexus.security.jwt.issuer=seller-test", "nexus.security.jwt.audience=nexus-services"})
class SellerControllerSecurityTest {
    private static final UUID SELLER = UUID.randomUUID();
    private static final RSAKey KEY = key();
    private static final HttpServer JWKS = server();
    @Autowired WebApplicationContext context;
    @Autowired SellerService service;
    MockMvc mvc;

    @DynamicPropertySource static void jwks(DynamicPropertyRegistry registry) {
        registry.add("nexus.security.jwt.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }
    @AfterAll static void closeServer() { JWKS.stop(0); }
    @BeforeEach void setup() {
        reset(service);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test void anonymousAndOtherRolesCannotReadOrUpdateProfiles() throws Exception {
        for (String path : List.of("/api/v1/sellers/me")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            for (String role : List.of("BUYER", "ADMIN", "SUPPORT_STAFF")) {
                mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(role)))
                        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            }
        }
        verifyNoInteractions(service);
    }

    @Test void getUsesAuthenticatedSellerAndReturnsTheStandardEnvelope() throws Exception {
        when(service.getMe(any())).thenReturn(profile());
        mvc.perform(get("/api/v1/sellers/me").param("userId", UUID.randomUUID().toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer("SELLER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.userId").value(SELLER.toString()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        verify(service).getMe(new CurrentUser(SELLER, Set.of("SELLER"), Set.of()));
    }

    @Test void putCannotBindAnotherIdentityOrApplicationDecision() throws Exception {
        when(service.updateMe(any(), any())).thenReturn(profile());
        mvc.perform(put("/api/v1/sellers/me").header(HttpHeaders.AUTHORIZATION, bearer("SELLER"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"companyName":" Company ","contactPhone":" 0900000001 ",
                         "userId":"00000000-0000-0000-0000-000000000001","status":"ACTIVE",
                         "applicationNote":"injected","reviewedBy":"00000000-0000-0000-0000-000000000001"}
                        """))
                .andExpect(status().isOk());
        verify(service).updateMe(eq(new CurrentUser(SELLER, Set.of("SELLER"), Set.of())),
                eq(new SellerProfileRequest("Company", "0900000001")));
    }

    @Test void invalidProfilesReturnProblemDetailsWithoutCallingService() throws Exception {
        for (String body : List.of("{}", "{\"companyName\":\" \",\"contactPhone\":\"0900000001\"}",
                "{\"companyName\":\"Company\",\"contactPhone\":\"123456789012345678901\"}")) {
            mvc.perform(put("/api/v1/sellers/me").header(HttpHeaders.AUTHORIZATION, bearer("SELLER"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(service);
    }

    private static SellerProfileResponse profile() {
        var at = OffsetDateTime.parse("2026-11-16T04:00:00Z");
        return new SellerProfileResponse(SELLER, "Company", "0900000001", SellerStatus.ACTIVE,
                null, null, at, new UUID(0, 0), at, 0, at, at);
    }
    private static String bearer(String role) {
        try {
            Instant now = Instant.now();
            var claims = new JWTClaimsSet.Builder().issuer("seller-test").audience("nexus-services")
                    .subject(SELLER.toString()).claim("roles", List.of(role)).claim("privileges", List.of())
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(600))).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("seller-test").build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return "Bearer " + jwt.serialize();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static RSAKey key() {
        try { return new RSAKeyGenerator(2048).keyID("seller-test").generate(); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start(); return server;
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class, FlywayAutoConfiguration.class, KafkaAutoConfiguration.class})
    static class Config {
        @Bean SellerService sellerService() { return mock(SellerService.class); }
        @Bean SellerController controller(SellerService service) { return new SellerController(service); }
    }
}
