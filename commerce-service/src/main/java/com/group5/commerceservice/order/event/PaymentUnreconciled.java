package com.group5.commerceservice.order.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentUnreconciled(
        @NotNull UUID sessionId,
        @NotNull UUID orderGroupId,
        @NotNull UUID transactionId,
        @NotBlank String providerTransactionId,
        @NotEmpty List<@NotNull UUID> affectedOrderIds,
        @NotBlank String reasonCode,
        @NotBlank @Size(max = 500) String reason) {
}
