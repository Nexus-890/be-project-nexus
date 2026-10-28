package com.group5.paymentservice.payment.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentInitiationRequest(
        @NotNull
        UUID sessionId,

        @NotNull
        @Positive
        @Digits(integer = 17, fraction = 2)
        BigDecimal amount,

        @NotBlank
        @Pattern(regexp = "^[A-Z]{3}$")
        String currency,

        @NotBlank
        @Size(max = 32)
        @Pattern(regexp = "^[A-Z0-9]+$")
        String memoCode,

        @NotNull
        Instant expiresAt) {
}
