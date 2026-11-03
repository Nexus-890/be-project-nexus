package com.group5.paymentservice.payment.event.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderGroupCancelled(
        @NotNull UUID orderGroupId,
        @NotNull UUID buyerId,
        @NotNull @Min(0) Long groupVersion,
        @NotBlank @Size(max = 500) String reason,
        @NotNull UUID actorId,
        @NotNull Instant cancelledAt,
        @NotEmpty List<@NotNull @Valid CancelledOrder> orders) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CancelledOrder(@NotNull UUID orderId, @NotNull @Min(0) Long orderVersion) {
    }
}
