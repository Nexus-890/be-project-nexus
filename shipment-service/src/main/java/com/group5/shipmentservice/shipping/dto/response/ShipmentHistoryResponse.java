package com.group5.shipmentservice.shipping.dto.response;

import com.group5.shipmentservice.shipping.entity.ShipmentHistory;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ShipmentHistoryResponse(UUID id, ShipmentStatus status, String description,
                                      OffsetDateTime createdAt) {
    public static ShipmentHistoryResponse from(ShipmentHistory history) {
        return new ShipmentHistoryResponse(history.getId(), history.getShipmentStatus(),
                history.getDescription(), history.getCreatedAt());
    }
}
