package com.group5.ratingservice.rating.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.UUID;

public record CreateRatingRequest(
        @NotNull UUID orderId,
        @NotNull @Min(1) @Max(5) @JsonDeserialize(using = RatingScoreDeserializer.class) Integer score,
        @Size(max = 1000) String comment
) {
}
