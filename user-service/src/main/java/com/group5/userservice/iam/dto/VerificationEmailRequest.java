package com.group5.userservice.iam.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerificationEmailRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email address")
        @Size(max = 255, message = "Email must contain at most 255 characters")
        String email
) {
}
