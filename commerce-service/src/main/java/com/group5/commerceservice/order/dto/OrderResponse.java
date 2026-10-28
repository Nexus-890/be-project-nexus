package com.group5.commerceservice.order.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        String orderNumber,
        UUID sellerId,
        String status,
        BigDecimal totalAmount,
        List<OrderItemResponse> items) {

    public OrderResponse {
        items = List.copyOf(items);
    }
}
