package com.group5.ratingservice.rating.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateRatingRequest(@NotNull UUID orderId, @NotNull UUID toUserId,
                                 @NotNull @Min(1) @Max(5) Integer score,
                                 @Size(max = 1000) String comment) { }
