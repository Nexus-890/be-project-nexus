package com.group5.inventoryservice.warehouse.dto.response;

import java.util.UUID;

public record WarehousePickupResponse(UUID warehouseId, UUID sellerId, String provinceCode, String wardCode,
                                      String line1) {
}
