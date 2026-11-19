package com.group5.ratingservice.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResolveDisputeRequest(@NotNull DisputeStatus status, @NotBlank @Size(max = 1000) String reason) { }
