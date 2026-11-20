package com.group5.inventoryservice.reservation.dto.response;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(List<UUID> reservationIds, UUID warehouseId, OffsetDateTime expiresAt,
                                  List<OrderWarehouse> orders) {

    public record OrderWarehouse(UUID orderId, UUID warehouseId) {
    }

    public ReservationResponse {
        reservationIds = List.copyOf(reservationIds);
        expiresAt = expiresAt.withOffsetSameInstant(ZoneOffset.UTC);
    }
}
