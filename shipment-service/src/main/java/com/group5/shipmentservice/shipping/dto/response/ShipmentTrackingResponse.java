package com.group5.shipmentservice.shipping.dto.response;

import com.group5.shipmentservice.shipping.entity.Shipment;
import com.group5.shipmentservice.shipping.entity.ShipmentHistory;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;

import java.util.List;
import java.util.UUID;

public record ShipmentTrackingResponse(UUID shipmentId, UUID orderId, String carrier, String trackingCode,
                                       ShipmentStatus status, List<ShipmentHistoryResponse> history) {
    public static ShipmentTrackingResponse from(Shipment shipment, List<ShipmentHistory> history) {
        return new ShipmentTrackingResponse(shipment.getId(), shipment.getOrderId(), shipment.getCarrier(),
                shipment.getTrackingCode(), shipment.getShipmentStatus(),
                history.stream().map(ShipmentHistoryResponse::from).toList());
    }
}
