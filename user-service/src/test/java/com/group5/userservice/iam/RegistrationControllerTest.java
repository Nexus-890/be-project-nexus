package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.shared.exception.UserErrorCode;
import com.group5.common_web.exception.GlobalExceptionHandler;
import com.group5.userservice.iam.dto.RegisterRequest;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.iam.enums.Gender;
import com.group5.userservice.iam.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegistrationControllerTest {

    private static final String REGISTER = "/api/v1/auth/register";

    private final UserService userService = mock(UserService.class);
    private final EmailVerificationService emailVerification = mock(EmailVerificationService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AuthController(userService, emailVerification))
            .setControllerAdvice(new GlobalExceptionHandler(null))
            .build();

    @Test
    void validRequestReturns201WithNoStoreAndTheCreatedUser() throws Exception {
        when(userService.register(any(RegisterRequest.class))).thenReturn(response());

        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data").isMap())
                .andExpect(jsonPath("$.page").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.email").value("buyer@nexus.local"))
                .andExpect(jsonPath("$.data.roles[0]").value("BUYER"));
    }

    @Test
    void responseDoesNotContainThePassword() throws Exception {
        when(userService.register(any(RegisterRequest.class))).thenReturn(response());

        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void responseDoesNotContainIdentityNumber() throws Exception {
        when(userService.register(any(RegisterRequest.class))).thenReturn(response());

        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.identityNumber").doesNotExist());
    }

    @Test
    void invalidEmailReturns400NamingEmailWithoutCallingTheService() throws Exception {
        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson().replace("buyer@nexus.local", "not-an-email")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"));

        verifyNoInteractions(userService);
    }

    @Test
    void missingIdentityNumberReturns400NamingIdentityNumberWithoutCallingTheService() throws Exception {
        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"buyer@nexus.local","phone":"0901234567","password":"correct-password",
                                 "fullName":"Buyer One","gender":"MALE","address":"1 Main Street",
                                 "dateOfBirth":"2000-01-01"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("identityNumber"));

        verifyNoInteractions(userService);
    }

    @Test
    void missingAddressReturns400NamingAddressWithoutCallingTheService() throws Exception {
        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"buyer@nexus.local","phone":"0901234567","password":"correct-password",
                                 "fullName":"Buyer One","identityNumber":"ID123456","gender":"MALE",
                                 "dateOfBirth":"2000-01-01"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("address"));

        verifyNoInteractions(userService);
    }

    @Test
    void disabledSelfRegistrationReturns403() throws Exception {
        when(userService.register(any(RegisterRequest.class)))
                .thenThrow(new DomainException(UserErrorCode.USER_REGISTRATION_DISABLED));

        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_REGISTRATION_DISABLED"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void duplicateRegistrationReturns409() throws Exception {
        when(userService.register(any(RegisterRequest.class)))
                .thenThrow(new DomainException(UserErrorCode.USER_PROFILE_CONFLICT));

        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_PROFILE_CONFLICT"))
                .andExpect(jsonPath("$.status").value(409));
    }

    private UserResponse response() {
        return new UserResponse(
                UUID.fromString("7c47f673-6a1f-4b7b-bfb6-90e5b674eb81"),
                "buyer@nexus.local",
                "0901234567",
                "Buyer One",
                Gender.MALE,
                "1 Main Street",
                LocalDate.parse("2000-01-01"),
                List.of("BUYER"),
                UserStatus.ACTIVE,
                OffsetDateTime.parse("2026-01-02T03:04:05Z"));
    }

    private String validJson() {
        return """
                {"email":"buyer@nexus.local","phone":"0901234567","password":"correct-password",
                 "fullName":"Buyer One","identityNumber":"ID123456","gender":"MALE","address":"1 Main Street",
                 "dateOfBirth":"2000-01-01"}
                """;
    }
}
