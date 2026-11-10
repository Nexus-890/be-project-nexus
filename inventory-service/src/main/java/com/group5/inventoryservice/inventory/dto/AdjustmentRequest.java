package com.group5.inventoryservice.inventory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AdjustmentRequest(
        @NotNull UUID skuId,
        UUID warehouseId,
        @NotNull @NonZero @Min(-MAX_DELTA) @Max(MAX_DELTA) Integer quantityDelta,
        @NotBlank @Size(max = MAX_REASON) String reason) {

    public static final int MAX_DELTA = 1_000_000;
    public static final int MAX_REASON = 500;
}
