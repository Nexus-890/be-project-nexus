package com.group5.inventoryservice.inventory.dto;

import com.group5.inventoryservice.inventory.InventoryBalance;

import java.util.UUID;

public record BalanceResponse(
        UUID skuId,
        UUID warehouseId,
        int total,
        int available,
        int reserved,
        int unavailable) {

    public static BalanceResponse from(InventoryBalance balance) {
        return new BalanceResponse(
                balance.getSkuId(),
                balance.getWarehouseId(),
                balance.getTotalQuantity(),
                balance.getAvailableQuantity(),
                balance.getReservedQuantity(),
                balance.getUnavailableQuantity());
    }
}
