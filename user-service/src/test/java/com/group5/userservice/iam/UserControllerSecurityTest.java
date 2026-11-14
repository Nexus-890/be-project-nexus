package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.shared.exception.UserErrorCode;
import com.group5.userservice.iam.dto.UpdateProfileRequest;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.iam.enums.Gender;
import com.group5.userservice.iam.enums.UserStatus;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real common-security filter chain, JWT signature validation, MVC resolver and DTO validation.
 * UserService is mocked: database updates and rollback belong to the transaction tests.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = UserControllerSecurityTest.TestApplication.class)
@TestPropertySource(properties = {
                "spring.config.name=profile-security-test",
                "spring.config.import=",
                "spring.cloud.config.enabled=false",
                "spring.cloud.compatibility-verifier.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "eureka.client.enabled=false",
                "management.tracing.export.zipkin.enabled=false",
                "nexus.security.jwt.issuer=user-service",
                "nexus.security.jwt.audience=nexus-services"
        })
class UserControllerSecurityTest {

    private static final String ME = "/api/v1/users/me";
    private static final String ISSUER = "user-service";
    private static final String AUDIENCE = "nexus-services";
    private static final String KEY_ID = "own-profile-rs256-test";
    private static final UUID USER_ID = UUID.fromString("64fba061-3a91-4a35-a853-221798344510");
    private static final UUID OTHER_ID = UUID.fromString("8237c207-333f-41f9-b2bc-0fa75fbb6d62");
    private static final RSAKey SIGNING_KEY = generateKey();
    private static final HttpServer JWKS_SERVER = startJwksServer();

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserService userService;
    @Autowired
    private Tracer tracer;

    private MockMvc mockMvc;
    private String traceId;

    @DynamicPropertySource
    static void jwkSetUri(DynamicPropertyRegistry registry) {
        registry.add("nexus.security.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort()
                        + "/.well-known/jwks.json");
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.stop(0);
    }

    @BeforeEach
    void setUp() {
        reset(userService);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void getWithoutATokenReturns401BeforeCallingTheService() throws Exception {
        expectUnauthorized(perform(get(ME)));
        verifyNoInteractions(userService);
    }

    @Test
    void putWithoutATokenReturns401BeforeCallingTheService() throws Exception {
        expectUnauthorized(perform(put(ME)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"X\"}")));
        verifyNoInteractions(userService);
    }

    @Test
    void getUsesTheJwtSubjectEvenWhenTheQueryContainsAnotherUserId() throws Exception {
        when(userService.getOwnProfile(USER_ID)).thenReturn(profile("Original", "owner@nexus.local", "Old"));

        perform(withToken(get(ME).param("id", OTHER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.data.email").value("owner@nexus.local"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.password_hash").doesNotExist());

        verify(userService).getOwnProfile(USER_ID);
    }

    @Test
    void theAcceptanceCriteriaPartialPutReturnsAllThreeChangedFieldsWithoutAHash() throws Exception {
        when(userService.updateOwnProfile(eq(USER_ID), any(UpdateProfileRequest.class)))
                .thenReturn(profile("X", "x@nexus.local", "HCM"));

        perform(withToken(put(ME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"X","email":"x@nexus.local","address":"HCM"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.data.fullName").value("X"))
                .andExpect(jsonPath("$.data.email").value("x@nexus.local"))
                .andExpect(jsonPath("$.data.address").value("HCM"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.password_hash").doesNotExist());

        verify(userService).updateOwnProfile(USER_ID,
                new UpdateProfileRequest("X", null, null, "HCM", "x@nexus.local"));
    }

    @Test
    void putNormalizesAllowedFieldsAndCannotBindPasswordIdStatusOrRoles() throws Exception {
        when(userService.updateOwnProfile(eq(USER_ID), any(UpdateProfileRequest.class)))
                .thenReturn(profile("X", "x@nexus.local", "HCM"));

        perform(withToken(put(ME).param("id", OTHER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName":" X ",
                                  "email":" X@NEXUS.LOCAL ",
                                  "address":" HCM ",
                                  "id":"8237c207-333f-41f9-b2bc-0fa75fbb6d62",
                                  "password":"attempted-change",
                                  "passwordHash":"attempted-hash",
                                  "status":"DELETED",
                                  "roles":["ADMIN"],
                                  "phone":" 099 999-9999 ",
                                  "identityNumber":"attempted-identity"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.data.phone").value("0900000001"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.roles[0]").value("BUYER"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        ArgumentCaptor<UpdateProfileRequest> request = ArgumentCaptor.forClass(UpdateProfileRequest.class);
        verify(userService).updateOwnProfile(eq(USER_ID), request.capture());
        assertThat(request.getValue()).isEqualTo(
                new UpdateProfileRequest("X", null, null, "HCM", "x@nexus.local", "0999999999"));
    }

    @Test
    void dateOfBirthAndGenderAreAllowedProfileFields() throws Exception {
        when(userService.updateOwnProfile(eq(USER_ID), any(UpdateProfileRequest.class)))
                .thenReturn(profile("Original", "owner@nexus.local", "Old"));

        perform(withToken(put(ME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateOfBirth\":\"2000-01-02\",\"gender\":\"FEMALE\"}"))
                .andExpect(status().isOk());

        verify(userService).updateOwnProfile(USER_ID,
                new UpdateProfileRequest(null, LocalDate.of(2000, 1, 2), Gender.FEMALE, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"fullName\":\"   \"}",
            "{\"email\":\"not-an-email\"}",
            "{\"email\":\"   \"}",
            "{\"dateOfBirth\":\"9999-01-01\"}"
    })
    void invalidAllowedFieldsReturn400WithoutCallingTheService(String body) throws Exception {
        perform(withToken(put(ME)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:validation-error"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].code").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].detail").isNotEmpty())
                .andExpect(jsonPath("$.traceId").value(traceId));
        verifyNoInteractions(userService);
    }

    @Test
    void anInvalidGenderReturns400WithoutCallingTheService() throws Exception {
        perform(withToken(put(ME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gender\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:invalid-request-body"));
        verifyNoInteractions(userService);
    }

    @Test
    void aDuplicateEmailErrorUsesTheCommon409ProblemFormat() throws Exception {
        when(userService.updateOwnProfile(eq(USER_ID), any(UpdateProfileRequest.class)))
                .thenThrow(new DomainException(UserErrorCode.USER_EMAIL_ALREADY_USED));

        perform(withToken(put(ME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"taken@nexus.local\",\"fullName\":\"X\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:domain-error"))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("USER_EMAIL_ALREADY_USED"))
                .andExpect(jsonPath("$.detail").value("email is already used"))
                .andExpect(jsonPath("$.instance").value(ME))
                .andExpect(jsonPath("$.traceId").value(traceId));
    }

    @Test
    void aSignedTokenWithTheWrongIssuerReturns401() throws Exception {
        expectUnauthorized(perform(get(ME).header(HttpHeaders.AUTHORIZATION,
                "Bearer " + token(claims -> claims.issuer("another-service")))));
        verifyNoInteractions(userService);
    }

    private void expectUnauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value(ME))
                .andExpect(jsonPath("$.traceId").value(traceId));
        assertThat(traceId).matches("[0-9a-f]{32}");
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        Span span = tracer.nextSpan().name("profile-security-request").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            traceId = span.context().traceId();
            return mockMvc.perform(request);
        } finally {
            span.end();
        }
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token(claims -> { }));
    }

    private static UserResponse profile(String name, String email, String address) {
        return new UserResponse(USER_ID, email, "0900000001", name, Gender.MALE, address,
                LocalDate.of(2000, 1, 2), List.of("BUYER"), UserStatus.ACTIVE,
                OffsetDateTime.parse("2026-10-14T08:00:00Z"), null);
    }

    private static String token(Consumer<JWTClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(AUDIENCE).subject(USER_ID.toString())
                .claim("roles", List.of("BUYER")).claim("privileges", List.of())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600)))
                .jwtID(UUID.randomUUID().toString());
        customizer.accept(claims);
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(KEY_ID).type(JOSEObjectType.JWT).build(), claims.build());
            jwt.sign(new RSASSASigner(SIGNING_KEY));
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

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            KafkaAutoConfiguration.class
    })
    static class TestApplication {
        @Bean
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        UserController userController(UserService userService) {
            return new UserController(userService);
        }
    }
}
