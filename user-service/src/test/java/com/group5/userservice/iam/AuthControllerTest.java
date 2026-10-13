package com.group5.userservice.iam;

import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_web.exception.GlobalExceptionHandler;
import com.group5.userservice.iam.dto.LoginRequest;
import com.group5.userservice.iam.dto.LoginResponse;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.UserStatus;
import com.group5.userservice.shared.config.IamProperties;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    private final AuthService authService = mock(AuthService.class);
    private final Tracer tracer = mock(Tracer.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AuthController(authService))
            .setControllerAdvice(new GlobalExceptionHandler(null))
            .build();

    @Test
    void validCredentialsReturnTheTokenAndForbidCaching() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(LoginResponse.bearer("signed-token", 3600));

        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"seller@nexus.local","password":"correct-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accessToken").value("signed-token"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void emptyPasswordReturns400WithoutCallingTheService() throws Exception {
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"seller@nexus.local","password":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));

        verifyNoInteractions(authService);
    }

    @Test
    void missingEmailReturns400WithoutCallingTheService() throws Exception {
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"correct-password"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"));

        verifyNoInteractions(authService);
    }

    @Test
    void rejectedLoginReturnsAProblemBodyWith401() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new DomainException(401, "Invalid email or password"));

        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"seller@nexus.local","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void lockedAccountReturns423InTheCommonWebProblemFormatWithTheCurrentTraceId() throws Exception {
        stubCurrentTrace();
        MockMvc tracedMockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler(tracer))
                .build();
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new DomainException(ErrorCode.ACCOUNT_LOCKED));

        tracedMockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"seller@nexus.local","password":"correct-password"}
                                """))
                .andExpect(status().isLocked())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:domain-error"))
                .andExpect(jsonPath("$.title").value("Locked"))
                .andExpect(jsonPath("$.status").value(423))
                .andExpect(jsonPath("$.detail").value("Account is locked"))
                .andExpect(jsonPath("$.instance").value(LOGIN))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void fifthFailedHttpLoginLocksAndNextCorrectPasswordReturns423() throws Exception {
        stubCurrentTrace();
        OffsetDateTime now = OffsetDateTime.parse("2026-10-13T08:00:00Z");
        UserRepository repository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        JwtIssuer jwtIssuer = mock(JwtIssuer.class);
        User user = new User();
        user.setId(UUID.fromString("64fba061-3a91-4a35-a853-221798344510"));
        user.setEmail("seller@nexus.local");
        user.setPasswordHash("stored-password-hash");
        user.setStatus(UserStatus.ACTIVE);

        when(passwordEncoder.encode(anyString())).thenReturn("dummy-password-hash");
        when(passwordEncoder.matches("correct-password", "stored-password-hash")).thenReturn(true);
        when(repository.findByEmailAndDeletedAtIsNull("seller@nexus.local"))
                .thenReturn(Optional.of(user));

        AuthService realService = new AuthService(repository, passwordEncoder, jwtIssuer,
                new IamProperties(), Clock.fixed(now.toInstant(), ZoneOffset.UTC));
        MockMvc serviceMockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(realService))
                .setControllerAdvice(new GlobalExceptionHandler(tracer))
                .build();

        for (int attempt = 1; attempt <= 5; attempt++) {
            serviceMockMvc.perform(post(LOGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"seller@nexus.local","password":"wrong-password"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        assertThat(user.getStatus()).isEqualTo(UserStatus.LOCKED);
        assertThat(user.getFailedLoginCount()).isEqualTo((short) 5);
        assertThat(user.getLockedUntil()).isEqualTo(now.plusMinutes(15));

        serviceMockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"seller@nexus.local","password":"correct-password"}
                                """))
                .andExpect(status().isLocked())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:domain-error"))
                .andExpect(jsonPath("$.title").value("Locked"))
                .andExpect(jsonPath("$.status").value(423))
                .andExpect(jsonPath("$.detail").value("Account is locked"))
                .andExpect(jsonPath("$.instance").value(LOGIN))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));

        verifyNoInteractions(jwtIssuer);
    }

    private void stubCurrentTrace() {
        Span span = mock(Span.class);
        TraceContext traceContext = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn(TRACE_ID);
    }
}
