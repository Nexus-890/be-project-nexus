package com.group5.commerceservice.order.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

public record OrderRefundRequested(
        @NotNull UUID refundId,
        @NotNull UUID orderId,
        UUID orderGroupId,
        UUID sessionId,
        UUID instructionId,
        UUID incomingTransactionId,
        @NotBlank String amount,
        @NotBlank String currency,
        @NotBlank String status,
        @NotBlank String reason,
        @NotNull UUID actorId,
        @PositiveOrZero long refundVersion,
        long instructionVersion,
        String requestedAt) {
}
