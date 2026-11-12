package com.group5.inventoryservice.warehouse.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWarehouseRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 150) String name,
        String address) {
}
