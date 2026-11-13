package com.group5.shipmentservice.shipping.dto.response;

import com.group5.shipmentservice.shipping.entity.Shipment;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ShipmentResponse(
        UUID id,
        UUID orderId,
        UUID warehouseId,
        UUID buyerId,
        String carrier,
        String serviceType,
        String trackingCode,
        ShipmentStatus status,
        BigDecimal shippingFee,
        Map<String, Object> shippingInfo,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long version) {

    public static ShipmentResponse from(Shipment shipment) {
        return new ShipmentResponse(shipment.getId(), shipment.getOrderId(), shipment.getWarehouseId(),
                shipment.getBuyerId(), shipment.getCarrier(), shipment.getServiceType(), shipment.getTrackingCode(),
                shipment.getShipmentStatus(), shipment.getShippingFee(), shipment.getShippingInfo(),
                shipment.getCreatedAt(), shipment.getUpdatedAt(), shipment.getVersion());
    }
}
