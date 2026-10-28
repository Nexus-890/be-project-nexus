package com.group5.commerceservice.order.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record OrderGroupResponse(
        UUID orderGroupId,
        String status,
        BigDecimal totalAmount,
        String currency,
        OffsetDateTime dueAt,
        List<OrderResponse> orders) {

    public OrderGroupResponse {
        orders = List.copyOf(orders);
    }
}
