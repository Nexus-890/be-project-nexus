package com.group5.shipmentservice.shipping.dto.response;

import com.group5.shipmentservice.shipping.enums.ShippingServiceType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ShippingQuoteResponse(String carrier, ShippingServiceType serviceType, BigDecimal fee,
                                    OffsetDateTime estimatedDeliveryAt, String carrierName, boolean recommended) {
    public ShippingQuoteResponse(String carrier, ShippingServiceType serviceType, BigDecimal fee,
                                 OffsetDateTime estimatedDeliveryAt) {
        this(carrier, serviceType, fee, estimatedDeliveryAt, null, false);
    }

    public ShippingQuoteResponse withChoice(String name, boolean isRecommended) {
        return new ShippingQuoteResponse(carrier, serviceType, fee, estimatedDeliveryAt, name, isRecommended);
    }
}
