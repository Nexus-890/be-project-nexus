package com.group5.inventoryservice.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record IntakeRequest(
        @NotNull UUID skuId,
        UUID warehouseId,
        @NotNull @Positive Integer quantity) {
}
