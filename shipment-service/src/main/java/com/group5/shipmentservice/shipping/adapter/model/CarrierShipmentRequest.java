package com.group5.shipmentservice.shipping.adapter.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Trusted booking snapshot and stable provider request identity. */
public record CarrierShipmentRequest(
        UUID requestId,
        UUID orderId,
        UUID warehouseId,
        String serviceType,
        BigDecimal shippingFee,
        Map<String, Object> shippingInfo) {

    public CarrierShipmentRequest {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(warehouseId, "warehouseId must not be null");
        Objects.requireNonNull(shippingFee, "shippingFee must not be null");
        shippingInfo = Collections.unmodifiableMap(new LinkedHashMap<>(shippingInfo));
    }
}
