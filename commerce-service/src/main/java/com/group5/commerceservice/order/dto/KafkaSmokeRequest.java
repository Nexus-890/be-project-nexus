package com.group5.commerceservice.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record KafkaSmokeRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") String key,
        @NotBlank @Size(max = 2000) String message) {
}
