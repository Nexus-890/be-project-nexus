package com.group5.userservice.avatar;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.iam.enums.Gender;
import com.group5.userservice.iam.enums.UserStatus;
import com.group5.userservice.shared.exception.UserErrorCode;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = AvatarControllerTest.TestApplication.class)
@TestPropertySource(properties = {
        "spring.config.name=avatar-controller-test", "spring.config.import=",
        "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "eureka.client.enabled=false",
        "management.tracing.export.zipkin.enabled=false"
})
class AvatarControllerTest {
    private static final String PATH = "/api/v1/users/me/avatar";
    private static final UUID USER_ID = UUID.fromString("64fba061-3a91-4a35-a853-221798344510");
    private static final MockMultipartFile FILE = new MockMultipartFile("file", "me.png", "image/png",
            new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});

    @Autowired private WebApplicationContext context;
    @MockitoBean private AvatarService avatars;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(avatars);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void theOwnerUploadsAndGetsTheProfileWithTheNewAvatar() throws Exception {
        when(avatars.replace(eq(USER_ID), any())).thenReturn(profile("https://storage.example/avatars/me.png"));

        mvc.perform(multipart(PATH).file(FILE).header("Authorization", "Bearer buyer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.data.avatarUrl").value("https://storage.example/avatars/me.png"));
        verify(avatars).replace(eq(USER_ID), any());
    }

    @Test
    void aMissingFilePartAndARejectedImageKeepTheirCodes() throws Exception {
        mvc.perform(multipart(PATH).header("Authorization", "Bearer buyer"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_AVATAR_REQUIRED"));
        verifyNoInteractions(avatars);
        when(avatars.replace(eq(USER_ID), any())).thenThrow(new DomainException(UserErrorCode.USER_AVATAR_INVALID_TYPE));
        mvc.perform(multipart(PATH).file(FILE).header("Authorization", "Bearer buyer"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("USER_AVATAR_INVALID_TYPE"));
    }

    @Test
    void anUploadNeedsAValidTokenWithTheProfileUpdatePrivilege() throws Exception {
        mvc.perform(multipart(PATH).file(FILE).header("Authorization", "Bearer viewer"))
                .andExpect(status().isForbidden());
        mvc.perform(multipart(PATH).file(FILE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(avatars);
    }

    private static UserResponse profile(String avatarUrl) {
        return new UserResponse(USER_ID, "me@nexus.local", "0900000001", "Me", Gender.MALE, "HCM",
                LocalDate.of(2000, 1, 2), List.of("BUYER"), UserStatus.ACTIVE,
                OffsetDateTime.parse("2026-11-19T08:00:00Z"), null, null, avatarUrl);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class, FlywayAutoConfiguration.class, KafkaAutoConfiguration.class,
            com.group5.common_events.autoconfigure.EventsAutoConfiguration.class,
            com.group5.common_outbox.autoconfigure.IdempotentConsumerAutoConfiguration.class
    })
    @Import({AvatarController.class, AvatarController.MultipartErrors.class, AvatarController.UploadLimit.class})
    static class TestApplication {
        private static final Map<String, List<String>> PRIVILEGES = Map.of(
                "buyer", List.of("PROFILE.UPDATE"), "viewer", List.of("PROFILE.VIEW"));

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                if (!PRIVILEGES.containsKey(token)) throw new BadJwtException("Invalid test token");
                return Jwt.withTokenValue(token).header("alg", "RS256").subject(USER_ID.toString())
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                        .claim("roles", List.of("BUYER")).claim("privileges", PRIVILEGES.get(token)).build();
            };
        }
    }
}
