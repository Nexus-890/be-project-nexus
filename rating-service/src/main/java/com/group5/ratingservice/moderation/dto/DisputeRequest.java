package com.group5.ratingservice.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DisputeRequest(@NotBlank @Size(max = 1000) String reason) { }
