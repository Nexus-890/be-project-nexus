package com.group5.inventoryservice.inventory.dto;

import com.group5.inventoryservice.inventory.InventoryMovement;
import com.group5.inventoryservice.inventory.MovementType;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public record MovementResponse(
        UUID id,
        UUID skuId,
        UUID warehouseId,
        MovementType movementType,
        int quantity,
        UUID referenceId,
        String reason,
        OffsetDateTime createdAt) {

    public static MovementResponse from(InventoryMovement movement) {
        return new MovementResponse(movement.getId(), movement.getSkuId(), movement.getWarehouseId(),
                movement.getMovementType(), movement.getQuantity(), movement.getReferenceId(), movement.getReason(),
                movement.getCreatedAt().withOffsetSameInstant(ZoneOffset.UTC));
    }
}
