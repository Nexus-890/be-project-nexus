package com.group5.shipmentservice.shipping;

/** Values shared by shipments and shipment_history in PostgreSQL. */
public enum ShipmentStatus {
    CREATED,
    PENDING_TRACKING,
    PICKING,
    PACKED,
    READY_TO_SHIP,
    SHIPPED,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    DELIVERY_FAILED,
    RETURNING,
    RETURNED,
    CANCELLED
}
