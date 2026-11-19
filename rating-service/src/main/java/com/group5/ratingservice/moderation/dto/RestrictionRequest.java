package com.group5.ratingservice.moderation.dto;

import com.group5.ratingservice.eligibility.enums.EligibilityAction;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record RestrictionRequest(@NotNull EligibilityAction action,
        @NotBlank @Size(max = 1000) String reason, @NotNull @Future OffsetDateTime restrictedUntil) { }
