package com.group5.shipmentservice.shipping.adapter.ghn;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;

public final class GhnStatusMapper {
    private GhnStatusMapper() {
    }

    public static ShipmentStatus map(String status) {
        if (status == null) {
            throw new DomainException(ShipmentErrorCode.INVALID_CARRIER_STATUS);
        }
        return switch (status) {
            case "ready_to_pick" -> ShipmentStatus.CREATED;
            case "picking", "money_collect_picking" -> ShipmentStatus.PICKING;
            case "picked" -> ShipmentStatus.SHIPPED;
            case "storing", "sorting", "transporting" -> ShipmentStatus.IN_TRANSIT;
            case "delivering", "money_collect_delivering" -> ShipmentStatus.OUT_FOR_DELIVERY;
            case "delivered" -> ShipmentStatus.DELIVERED;
            case "delivery_fail", "exception", "lost", "damage", "scrap" -> ShipmentStatus.DELIVERY_FAILED;
            case "waiting_to_return", "return", "return_transporting", "return_sorting", "returning", "return_fail"
                    -> ShipmentStatus.RETURNING;
            case "returned" -> ShipmentStatus.RETURNED;
            case "cancel" -> ShipmentStatus.CANCELLED;
            default -> throw new DomainException(ShipmentErrorCode.INVALID_CARRIER_STATUS);
        };
    }
}
