package com.group5.shipmentservice.shipping.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Commerce owns this immutable paid-order snapshot; callers cannot supply its buyer or price. */
public record PaidOrderShippingResponse(
        UUID orderId,
        UUID buyerId,
        long orderVersion,
        String status,
        OffsetDateTime paidAt,
        UUID warehouseId,
        BigDecimal shippingFee,
        Map<String, Object> shippingInfo) {
}
