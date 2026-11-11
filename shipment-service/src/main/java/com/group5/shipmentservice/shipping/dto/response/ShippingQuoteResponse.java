package com.group5.shipmentservice.shipping.dto.response;

import com.group5.shipmentservice.shipping.enums.ShippingServiceType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ShippingQuoteResponse(String carrier, ShippingServiceType serviceType, BigDecimal fee, OffsetDateTime estimatedDeliveryAt) {
}
