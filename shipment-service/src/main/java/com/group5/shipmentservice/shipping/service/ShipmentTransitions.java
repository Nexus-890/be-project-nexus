package com.group5.shipmentservice.shipping.service;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;

public final class ShipmentTransitions {
    private ShipmentTransitions() { }

    public static boolean terminal(ShipmentStatus status) {
        return status == ShipmentStatus.DELIVERED || status == ShipmentStatus.CANCELLED
                || status == ShipmentStatus.RETURNED;
    }

    public static boolean pickedUp(ShipmentStatus status) {
        return switch (status) {
            case SHIPPED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, DELIVERY_FAILED, RETURNING, RETURNED -> true;
            default -> false;
        };
    }

    public static void requireAllowed(ShipmentStatus from, ShipmentStatus to, boolean handedOver) {
        if (to == null) throw new DomainException(ShipmentErrorCode.INVALID_TRANSITION);
        if (from == to) return;
        if (terminal(from) || to == ShipmentStatus.PENDING_TRACKING
                || (to == ShipmentStatus.CANCELLED && (handedOver || pickedUp(from)))) reject();
        if (to == ShipmentStatus.CANCELLED) return;
        if (from == ShipmentStatus.DELIVERY_FAILED && to == ShipmentStatus.OUT_FOR_DELIVERY) return;
        if (rank(to) <= rank(from)) reject();
    }

    private static int rank(ShipmentStatus status) {
        return switch (status) {
            case PENDING_TRACKING -> 0;
            case CREATED -> 1;
            case PICKING -> 2;
            case PACKED -> 3;
            case READY_TO_SHIP -> 4;
            case SHIPPED -> 5;
            case IN_TRANSIT -> 6;
            case OUT_FOR_DELIVERY -> 7;
            case DELIVERY_FAILED -> 8;
            case DELIVERED -> 9;
            case RETURNING -> 10;
            case RETURNED -> 11;
            case CANCELLED -> 12;
        };
    }

    private static void reject() {
        throw new DomainException(ShipmentErrorCode.INVALID_TRANSITION);
    }
}
