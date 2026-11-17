package com.group5.inventoryservice.reservation;

import java.util.List;
import java.util.UUID;

public record InventoryStockUnavailable(UUID orderId, List<Line> reservations, String detectedAt) {

    public record Line(UUID reservationId, UUID skuId, UUID warehouseId, int quantity, int availableQuantity) {
    }
}
