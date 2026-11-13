package com.group5.commerceservice.order.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Commerce-owned data for authorized manual shipment creation. */
public record OrderShippingSnapshotResponse(
        UUID orderId,
        UUID buyerId,
        long orderVersion,
        String status,
        OffsetDateTime paidAt,
        UUID warehouseId,
        BigDecimal shippingFee,
        Map<String, Object> shippingInfo) {
}
