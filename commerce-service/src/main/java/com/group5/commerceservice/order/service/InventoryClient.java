package com.group5.commerceservice.order.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface InventoryClient {
    Reservation reserve(UUID orderGroupId, List<Item> items);

    record Item(UUID orderId, UUID skuId, int quantity) { }

    record Reservation(List<UUID> reservationIds, UUID warehouseId, OffsetDateTime expiresAt) { }
}
