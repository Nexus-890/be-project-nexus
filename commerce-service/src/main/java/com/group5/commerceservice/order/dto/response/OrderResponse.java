package com.group5.commerceservice.order.dto.response;

import com.group5.commerceservice.order.OrderStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        String orderNumber,
        UUID sellerId,
        OrderStatus status,
        BigDecimal totalAmount,
        List<OrderItemResponse> items) {

    public OrderResponse {
        items = List.copyOf(items);
    }
}
