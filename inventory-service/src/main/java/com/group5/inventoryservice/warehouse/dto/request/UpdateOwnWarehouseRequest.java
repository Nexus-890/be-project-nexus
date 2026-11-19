package com.group5.inventoryservice.warehouse.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateOwnWarehouseRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 500) String address) {
}
