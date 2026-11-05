package com.group5.commerceservice.order.event;

import java.util.List;
import java.util.UUID;

public record CheckoutPaymentRequested(
        UUID orderGroupId,
        UUID buyerId,
        String currency,
        String totalAmount,
        String dueAt,
        String occurredAt,
        int orderCount,
        List<OrderShare> orders) {

    public CheckoutPaymentRequested {
        orders = List.copyOf(orders);
    }

    public record OrderShare(UUID orderId, UUID sellerId, String amount, String paymentDueAt, long orderVersion) {
    }
}
