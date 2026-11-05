package com.group5.userservice.iam;

import com.group5.userservice.iam.dto.RegisterRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void constructorNormalizesEmailBeforeValidationAndStorage() {
        RegisterRequest request = validRequestWithEmail("  Buyer@Nexus.Local  ");

        assertThat(request.email()).isEqualTo("buyer@nexus.local");
    }

    @Test
    void constructorNormalizesPhoneBeforeValidationAndStorage() {
        RegisterRequest request = validRequestWithPhone(" 090-123 45(67) ");

        assertThat(request.phone()).isEqualTo("0901234567");
    }

    @Test
    void invalidEmailViolatesTheEmailField() {
        Set<String> fields = violationFields(validRequestWithEmail("not-an-email"));

        assertThat(fields).contains("email");
    }

    @Test
    void shortPasswordViolatesThePasswordField() {
        Set<String> fields = violationFields(validRequestWithPassword("short"));

        assertThat(fields).contains("password");
    }

    @Test
    void passwordBeyondTheOldBcryptByteLimitIsAccepted() {
        Set<String> fields = violationFields(validRequestWithPassword("é".repeat(37)));

        assertThat(fields).doesNotContain("password");
    }

    @Test
    void missingIdentityNumberViolatesTheIdentityNumberField() {
        Set<String> fields = violationFields(new RegisterRequest(
                "buyer@nexus.local",
                "0901234567",
                "correct-password",
                "Buyer One",
                null,
                "MALE",
                "1 Main Street",
                "2000-01-01"));

        assertThat(fields).contains("identityNumber");
    }

    @Test
    void missingAddressViolatesTheAddressField() {
        Set<String> fields = violationFields(new RegisterRequest(
                "buyer@nexus.local",
                "0901234567",
                "correct-password",
                "Buyer One",
                "ID123456",
                "MALE",
                null,
                "2000-01-01"));

        assertThat(fields).contains("address");
    }

    @Test
    void futureDateOfBirthViolatesTheDateOfBirthField() {
        Set<String> fields = violationFields(new RegisterRequest(
                "buyer@nexus.local",
                "0901234567",
                "correct-password",
                "Buyer One",
                "ID123456",
                "MALE",
                "1 Main Street",
                "2999-01-01"));

        assertThat(fields).contains("dateOfBirth");
    }

    @Test
    void invalidGenderViolatesTheGenderField() {
        Set<String> fields = violationFields(new RegisterRequest(
                "buyer@nexus.local",
                "0901234567",
                "correct-password",
                "Buyer One",
                "ID123456",
                "UNKNOWN",
                "1 Main Street",
                "2000-01-01"));

        assertThat(fields).contains("gender");
    }

    @Test
    void requestToStringDoesNotPrintThePassword() {
        assertThat(validRequest().toString()).doesNotContain("correct-password");
    }

    private Set<String> violationFields(RegisterRequest request) {
        return validator.validate(request)
                .stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private RegisterRequest validRequest() {
        return new RegisterRequest(
                "buyer@nexus.local",
                "0901234567",
                "correct-password",
                "Buyer One",
                "ID123456",
                "MALE",
                "1 Main Street",
                "2000-01-01");
    }

    private RegisterRequest validRequestWithEmail(String email) {
        return new RegisterRequest(
                email,
                "0901234567",
                "correct-password",
                "Buyer One",
                "ID123456",
                "MALE",
                "1 Main Street",
                "2000-01-01");
    }

    private RegisterRequest validRequestWithPhone(String phone) {
        return new RegisterRequest(
                "buyer@nexus.local",
                phone,
                "correct-password",
                "Buyer One",
                "ID123456",
                "MALE",
                "1 Main Street",
                "2000-01-01");
    }

    private RegisterRequest validRequestWithPassword(String password) {
        return new RegisterRequest(
                "buyer@nexus.local",
                "0901234567",
                password,
                "Buyer One",
                "ID123456",
                "MALE",
                "1 Main Street",
                "2000-01-01");
    }
}
