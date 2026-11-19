package com.group5.commerceservice.order.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryClient {
    Reservation reserve(UUID orderGroupId, List<Item> items);

    default Optional<OrderShippingService.Pickup> pickup(UUID sellerId) {
        return Optional.empty();
    }

    record Item(UUID orderId, UUID skuId, int quantity) { }

    record OrderWarehouse(UUID orderId, UUID warehouseId) { }

    record Reservation(List<UUID> reservationIds, UUID warehouseId, OffsetDateTime expiresAt,
                       List<OrderWarehouse> orders) {
        public Reservation(List<UUID> reservationIds, UUID warehouseId, OffsetDateTime expiresAt) {
            this(reservationIds, warehouseId, expiresAt, null);
        }

        public UUID warehouseFor(UUID orderId) {
            if (orders == null) return warehouseId;
            return orders.stream().filter(order -> orderId.equals(order.orderId())).map(OrderWarehouse::warehouseId)
                    .findFirst().orElse(warehouseId);
        }
    }
}
