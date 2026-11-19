package com.group5.userservice.password.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "Current password is required")
        @Size(max = 128, message = "Current password must contain at most 128 characters")
        String currentPassword,
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 128, message = "Password must contain 8 to 128 characters")
        String newPassword
) {
    @Override
    public String toString() {
        return "ChangePasswordRequest[redacted]";
    }
}
