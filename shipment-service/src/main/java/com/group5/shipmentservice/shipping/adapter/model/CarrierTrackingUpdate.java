package com.group5.shipmentservice.shipping.adapter.model;

import com.group5.shipmentservice.shipping.enums.ShipmentStatus;

import java.time.OffsetDateTime;

public record CarrierTrackingUpdate(String trackingCode, ShipmentStatus status, String carrierEventId,
                                    OffsetDateTime occurredAt, String description) {
}
