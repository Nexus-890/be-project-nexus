package com.group5.paymentservice.payment.provider.dto;

import jakarta.validation.constraints.NotBlank;

public record PaymentInitiationResult(
        @NotBlank
        String qrImageUrl,
        @NotBlank
        String bankAccount) {
}
