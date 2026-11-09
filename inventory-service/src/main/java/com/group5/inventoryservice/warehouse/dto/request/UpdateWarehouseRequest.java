package com.group5.inventoryservice.warehouse.dto.request;

import com.group5.inventoryservice.warehouse.enums.WarehouseStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateWarehouseRequest(
        @NotBlank @Size(max = 150) String name,
        String address,
        @NotNull WarehouseStatus status) {
}
