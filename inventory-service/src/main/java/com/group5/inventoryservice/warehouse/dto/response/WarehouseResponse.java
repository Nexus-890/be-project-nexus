package com.group5.inventoryservice.warehouse.dto.response;

import com.group5.inventoryservice.warehouse.enums.WarehouseStatus;

import java.util.UUID;

public record WarehouseResponse(UUID id, String code, String name, String address, WarehouseStatus status) {
}
