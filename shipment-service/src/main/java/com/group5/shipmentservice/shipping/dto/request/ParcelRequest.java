package com.group5.shipmentservice.shipping.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ParcelRequest(
        @NotNull @Min(1) @Max(50000) Integer weightGrams,
        @NotNull @Min(1) @Max(200) Integer lengthCm,
        @NotNull @Min(1) @Max(200) Integer widthCm,
        @NotNull @Min(1) @Max(200) Integer heightCm) {
}
