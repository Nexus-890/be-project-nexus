package com.group5.shipmentservice.shipping.client.config;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import feign.Request;

import java.time.Duration;

public final class CatalogDeadlineOptions extends Request.Options {
    private final ShippingDeadline deadline;

    public CatalogDeadlineOptions(ShippingDeadline deadline) {
        super(remaining(deadline), remaining(deadline), false);
        this.deadline = deadline;
    }

    public ShippingDeadline deadline() {
        return deadline;
    }

    public Duration remaining() {
        return remaining(deadline);
    }

    private static Duration remaining(ShippingDeadline deadline) {
        Duration remaining = deadline.remaining();
        if (remaining.isZero()) {
            throw new DomainException(ShipmentErrorCode.CATALOG_UNAVAILABLE);
        }
        return remaining;
    }
}
