package com.group5.userservice.iam.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.group5.userservice.iam.enums.Gender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.Locale;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateProfileRequest(

        @Size(min = 1, max = 150, message = "Full name must contain 1 to 150 characters")
        String fullName,

        @PastOrPresent(message = "Date of birth must not be in the future")
        LocalDate dateOfBirth,

        Gender gender,

        String address,

        @Email(message = "Invalid email address")
        @Size(min = 1, max = 255, message = "Email must contain 1 to 255 characters")
        String email,

        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "Invalid phone number")
        String phone
) {
    public UpdateProfileRequest {
        fullName = trim(fullName);
        address = trim(address);
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        phone = phone == null ? null : phone.trim().replaceAll("[\\s()\\-]", "");
    }

    public UpdateProfileRequest(String fullName, LocalDate dateOfBirth, Gender gender, String address, String email) {
        this(fullName, dateOfBirth, gender, address, email, null);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

}
