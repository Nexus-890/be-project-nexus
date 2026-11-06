package com.group5.paymentservice.payment.event.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCancelled(
        @NotNull UUID orderId,
        @NotNull UUID buyerId,
        String orderNumber,
        String orderType,
        UUID referenceId,
        @NotNull Instant cancelledAt,
        @NotBlank @Size(max = 500) String reason,
        @NotNull UUID orderGroupId,
        UUID sellerId,
        @NotNull @Min(0) Long orderVersion,
        String paidAmount,
        String currency,
        @NotNull UUID actorId) {
}
