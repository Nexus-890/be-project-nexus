package com.group5.userservice.phone.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ConfirmPhoneRequest(
        @NotBlank(message = "Code is required")
        @Pattern(regexp = "^[0-9]{6}$", message = "Code must be 6 digits")
        String code
) {
}
