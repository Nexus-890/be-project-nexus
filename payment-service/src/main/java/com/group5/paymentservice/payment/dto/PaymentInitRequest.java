package com.group5.paymentservice.payment.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PaymentInitRequest(
        @NotNull
        UUID orderGroupId) {
}
