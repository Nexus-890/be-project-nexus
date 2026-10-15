package com.group5.userservice.iam;

import com.group5.userservice.iam.dto.UpdateProfileRequest;
import com.group5.userservice.iam.enums.Gender;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateProfileRequestTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);
    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.byDefaultProvider()
            .configure()
            .clockProvider(() -> Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), ZoneOffset.UTC))
            .buildValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void missingFieldsAreValidForAPartialUpdate() {
        assertThat(violationFields(new UpdateProfileRequest(null, null, null, null, null))).isEmpty();
    }

    @Test
    void allFiveAllowedFieldsAreAcceptedAndEmailIsNormalizedBeforeValidation() {
        UpdateProfileRequest request = new UpdateProfileRequest(
                " X ", LocalDate.of(2000, 1, 2), Gender.FEMALE, " HCM ", " X@NEXUS.LOCAL ");

        assertThat(violationFields(request)).isEmpty();
        assertThat(request.fullName()).isEqualTo("X");
        assertThat(request.address()).isEqualTo("HCM");
        assertThat(request.email()).isEqualTo("x@nexus.local");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void anExplicitBlankFullNameIsRejected(String name) {
        assertThat(violationFields(new UpdateProfileRequest(name, null, null, null, null)))
                .containsExactly("fullName");
    }

    @Test
    void fullNameFitsTheDatabaseLimit() {
        assertThat(violationFields(new UpdateProfileRequest("a".repeat(150), null, null, null, null)))
                .isEmpty();
        assertThat(violationFields(new UpdateProfileRequest("a".repeat(151), null, null, null, null)))
                .containsExactly("fullName");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-an-email"})
    void invalidEmailIsRejected(String email) {
        assertThat(violationFields(new UpdateProfileRequest(null, null, null, null, email)))
                .containsExactly("email");
    }

    @Test
    void emailOverTheDatabaseLimitIsRejectedEvenWhenItsFormatIsValid() {
        String email = "x@" + "a".repeat(63) + "." + "b".repeat(63) + "."
                + "c".repeat(63) + "." + "d".repeat(61);
        assertThat(email).hasSize(255);
        assertThat(violationFields(new UpdateProfileRequest(null, null, null, null, email))).isEmpty();
        assertThat(violationFields(new UpdateProfileRequest(null, null, null, null, email + "e")))
                .containsExactly("email");
    }

    @Test
    void todayIsAllowedButAFutureDateOfBirthIsRejected() {
        assertThat(violationFields(new UpdateProfileRequest(null, TODAY, null, null, null))).isEmpty();
        assertThat(violationFields(new UpdateProfileRequest(null, TODAY.plusDays(1), null, null, null)))
                .containsExactly("dateOfBirth");
    }

    private static Set<String> violationFields(UpdateProfileRequest request) {
        return VALIDATOR.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
