package com.group5.shipmentservice.shipping.resilience;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;

import java.time.Duration;

public final class ShippingDeadline {
    private final long expiresAt;

    //tính toán thời gian
    private ShippingDeadline(Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Thời gian chờ phải lớn hơn 0");
        }
        this.expiresAt = System.nanoTime() + timeout.toNanos();
    }

    public static ShippingDeadline after(Duration timeout) {
        return new ShippingDeadline(timeout);
    }

    public Duration remaining() {
        return Duration.ofNanos(Math.max(0, expiresAt - System.nanoTime()));
    }

    public Duration remainingOrThrow() {
        Duration remaining = remaining();
        if (remaining.isZero()) {
            throw new DomainException(ShipmentErrorCode.CARRIER_UNAVAILABLE);
        }
        return remaining;
    }

    public boolean timedOut() {
        return remaining().isZero();
    }
}
