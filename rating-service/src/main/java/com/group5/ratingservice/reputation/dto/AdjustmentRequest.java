package com.group5.ratingservice.reputation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

public record AdjustmentRequest(
        @NotNull @JsonDeserialize(using = AdjustmentDeltaDeserializer.class) Integer delta,
        @NotBlank @Size(max = 1000) String reason
) {
}
