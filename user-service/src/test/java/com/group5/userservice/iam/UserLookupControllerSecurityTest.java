package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.dto.UserListRequest;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.iam.enums.Gender;
import com.group5.userservice.iam.enums.UserStatus;
import com.group5.userservice.shared.exception.UserErrorCode;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = UserLookupControllerSecurityTest.TestApplication.class)
@TestPropertySource(properties = {
        "spring.config.name=user-lookup-security-test",
        "spring.config.import=",
        "spring.cloud.config.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "eureka.client.enabled=false",
        "management.tracing.export.zipkin.enabled=false",
        "nexus.security.jwt.issuer=user-service",
        "nexus.security.jwt.audience=nexus-services"
})
class UserLookupControllerSecurityTest {

    private static final String USERS = "/api/v1/users";
    private static final String ISSUER = "user-service";
    private static final String AUDIENCE = "nexus-services";
    private static final String KEY_ID = "user-lookup-rs256-test";
    private static final UUID CALLER_ID = UUID.fromString("0f3c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f");
    private static final UUID OTHER_ID = UUID.fromString("9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d");
    private static final RSAKey SIGNING_KEY = generateKey();
    private static final HttpServer JWKS_SERVER = startJwksServer();

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserService userService;

    private MockMvc mockMvc;

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
    void aBuyerReadingTheirOwnIdGets200() throws Exception {
        when(userService.getUser(CALLER_ID)).thenReturn(user(CALLER_ID));

        mockMvc.perform(get(USERS + "/" + CALLER_ID).header(HttpHeaders.AUTHORIZATION, bearer("BUYER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(CALLER_ID.toString()))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void aBuyerReadingAnotherIdGets403WithoutCallingTheService() throws Exception {
        mockMvc.perform(get(USERS + "/" + OTHER_ID).header(HttpHeaders.AUTHORIZATION, bearer("BUYER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:problem:forbidden"));

        verifyNoInteractions(userService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPPORT_STAFF"})
    void staffWithUserViewCanReadAnotherId(String role) throws Exception {
        when(userService.getUser(OTHER_ID)).thenReturn(user(OTHER_ID));

        mockMvc.perform(get(USERS + "/" + OTHER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(role, "USER.VIEW")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(OTHER_ID.toString()));
    }

    @Test
    void anUnknownIdGets404ForACallerWithUserView() throws Exception {
        when(userService.getUser(OTHER_ID)).thenThrow(new DomainException(UserErrorCode.USER_NOT_FOUND));

        mockMvc.perform(get(USERS + "/" + OTHER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN", "USER.VIEW")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void aMalformedIdGets400WithoutCallingTheService() throws Exception {
        mockMvc.perform(get(USERS + "/not-a-uuid")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN", "USER.VIEW")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }

    @Test
    void withoutATokenBothEndpointsGet401() throws Exception {
        mockMvc.perform(get(USERS + "/" + OTHER_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(USERS)).andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void aBuyerListingUsersGets403WithoutCallingTheService() throws Exception {
        mockMvc.perform(get(USERS).param("page", "0").param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer("BUYER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPPORT_STAFF"})
    void staffWithUserListGetOnePageAndTheTotal(String role) throws Exception {
        when(userService.listUsers(any(UserListRequest.class)))
                .thenReturn(new PageImpl<>(List.of(user(CALLER_ID), user(OTHER_ID)), PageRequest.of(0, 2), 5));

        mockMvc.perform(get(USERS).param("page", "0").param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(role, "USER.LIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(5))
                .andExpect(jsonPath("$.page.totalPages").value(3))
                .andExpect(jsonPath("$.data[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data[1].passwordHash").doesNotExist());

        ArgumentCaptor<UserListRequest> request = ArgumentCaptor.forClass(UserListRequest.class);
        verify(userService).listUsers(request.capture());
        assertThat(request.getValue().pageable())
                .isEqualTo(PageRequest.of(0, 2, Sort.by("fullName").and(Sort.by("id"))));
    }

    @Test
    void emptyUserListKeepsTheEnvelopeAndPageMetadata() throws Exception {
        when(userService.listUsers(any(UserListRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(USERS)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN", "USER.LIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"size=0", "size=101", "page=-1", "sort=passwordHash", "direction=up", "status=UNKNOWN"})
    void invalidListParametersGet400WithoutCallingTheService(String query) throws Exception {
        mockMvc.perform(get(USERS + "?" + query)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN", "USER.LIST")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }

    private static UserResponse user(UUID id) {
        return new UserResponse(id, id + "@nexus.local", "0900000001", "User " + id, Gender.MALE,
                "HCM", LocalDate.of(2000, 1, 2), List.of("BUYER"), UserStatus.ACTIVE,
                OffsetDateTime.parse("2026-10-14T08:00:00Z"), null);
    }

    private static String bearer(String role, String... privileges) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(AUDIENCE).subject(CALLER_ID.toString())
                .claim("roles", List.of(role)).claim("privileges", List.of(privileges))
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(KEY_ID).type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner(SIGNING_KEY));
            return "Bearer " + jwt.serialize();
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
            // Test HTTP không cần event transport khi common-outbox được bổ sung cho quản trị user.
            KafkaAutoConfiguration.class, com.group5.common_events.autoconfigure.EventsAutoConfiguration.class,
            com.group5.common_outbox.autoconfigure.IdempotentConsumerAutoConfiguration.class
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
