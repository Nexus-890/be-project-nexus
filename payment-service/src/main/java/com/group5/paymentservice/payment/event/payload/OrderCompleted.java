package com.group5.paymentservice.payment.event.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Payment needs identity/completion only; receipt HTML is consumed by notification. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCompleted(@NotNull UUID orderId, @NotNull UUID orderGroupId, @NotNull UUID buyerId,
                             @NotNull UUID sellerId, @NotBlank String orderType, UUID referenceId,
                             @PositiveOrZero long orderVersion, @NotNull Instant completedAt,
                             @NotBlank @Size(max = 100) String orderNumber) { }
