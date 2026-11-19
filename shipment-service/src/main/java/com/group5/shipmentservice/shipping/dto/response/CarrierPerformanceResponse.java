package com.group5.shipmentservice.shipping.dto.response;

import java.math.BigDecimal;

public record CarrierPerformanceResponse(
        String carrier,
        String name,
        long shipments,
        long delivered,
        long failed,
        long inProgress,
        long cancelled,
        BigDecimal successRate,
        BigDecimal averageDeliveryHours,
        BigDecimal onTimeRate) {
}
