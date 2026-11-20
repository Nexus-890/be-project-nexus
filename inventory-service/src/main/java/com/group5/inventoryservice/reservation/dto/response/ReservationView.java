package com.group5.inventoryservice.reservation.dto.response;

import com.group5.inventoryservice.reservation.ReservationStatus;
import com.group5.inventoryservice.reservation.entity.InventoryReservation;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public record ReservationView(
        UUID id,
        UUID skuId,
        UUID warehouseId,
        UUID orderId,
        int quantity,
        ReservationStatus status,
        OffsetDateTime expiresAt,
        OffsetDateTime releasedAt,
        OffsetDateTime pickedAt,
        OffsetDateTime shippedAt) {

    public static ReservationView from(InventoryReservation reservation) {
        return new ReservationView(reservation.getId(), reservation.getSkuId(), reservation.getWarehouseId(),
                reservation.getOrderId(), reservation.getQuantity(), reservation.getStatus(),
                utc(reservation.getExpiresAt()), utc(reservation.getReleasedAt()), utc(reservation.getPickedAt()),
                utc(reservation.getShippedAt()));
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }
}
