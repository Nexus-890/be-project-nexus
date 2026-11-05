package com.group5.userservice.iam.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public record RegisterRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email address")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        @NotBlank(message = "Phone is required")
        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "Invalid phone number")
        String phone,

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must contain at least 8 characters")
        String password,

        @NotBlank(message = "Full name is required")
        @Size(max = 150, message = "Full name must not exceed 150 characters")
        String fullName,

        @NotBlank(message = "Identity number is required")
        @Size(max = 255, message = "Identity number must not exceed 255 characters")
        String identityNumber,

        @NotBlank(message = "Gender is required")
        @Pattern(regexp = "MALE|FEMALE|OTHER", message = "Gender must be MALE, FEMALE or OTHER")
        String gender,

        @NotBlank(message = "Address is required")
        String address,

        @NotBlank(message = "Date of birth is required")
        @RegisterRequest.DateOfBirth
        String dateOfBirth
) {
    public RegisterRequest {
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        phone = phone == null ? null : phone.trim().replaceAll("[\\s()\\-]", "");
        fullName = trim(fullName);
        identityNumber = trim(identityNumber);
        gender = trim(gender);
        address = trim(address);
        dateOfBirth = trim(dateOfBirth);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    @Override
    public String toString() {
        return "RegisterRequest[redacted]";
    }

    @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = DateOfBirthValidator.class)
    public @interface DateOfBirth {
        String message() default "Date of birth must be a valid yyyy-MM-dd date, not in the future";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    public static class DateOfBirthValidator implements ConstraintValidator<DateOfBirth, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null || value.isBlank()) {
                return true; // @NotBlank reports missing values.
            }
            try {
                return value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                        && !LocalDate.parse(value).isAfter(LocalDate.now());
            } catch (DateTimeParseException exception) {
                return false;
            }
        }
    }
}
