package com.group5.userservice.password.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PasswordResetConfirmRequest(
        @NotBlank(message = "The reset link is invalid")
        @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = "The reset link is invalid")
        String token,
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 128, message = "Password must contain 8 to 128 characters")
        String newPassword
) {
    @Override
    public String toString() {
        return "PasswordResetConfirmRequest[redacted]";
    }
}
