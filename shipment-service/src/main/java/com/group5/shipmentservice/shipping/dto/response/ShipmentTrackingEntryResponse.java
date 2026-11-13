package com.group5.shipmentservice.shipping.dto.response;

import com.group5.shipmentservice.shipping.entity.ShipmentHistory;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ShipmentTrackingEntryResponse(UUID id, ShipmentStatus status, String description, String source,
                                            String carrierEventId, UUID changedBy, OffsetDateTime changedAt) {
    public static ShipmentTrackingEntryResponse from(ShipmentHistory history) {
        return new ShipmentTrackingEntryResponse(history.getId(), history.getShipmentStatus(), history.getDescription(),
                history.getSource(), history.getCarrierEventId(), history.getChangedBy(), history.getCreatedAt());
    }
}
