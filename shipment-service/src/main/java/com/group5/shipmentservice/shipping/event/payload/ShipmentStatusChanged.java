package com.group5.shipmentservice.shipping.event.payload;

import com.group5.shipmentservice.shipping.enums.ShipmentStatus;
import java.time.Instant;
import java.util.UUID;

public record ShipmentStatusChanged(UUID shipmentId, UUID orderId, UUID buyerId, UUID warehouseId,
                                    String carrier, String trackingCode, ShipmentStatus status, UUID historyId,
                                    long shipmentVersion, String source, String carrierEventId, UUID actorId,
                                    Instant changedAt, String reason) { }
