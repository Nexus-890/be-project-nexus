package com.group5.commerceservice.order.event;

import com.group5.commerceservice.order.OrderType;

import java.util.List;
import java.util.UUID;

public record OrderCreated(
        UUID orderId,
        UUID buyerId,
        String orderNumber,
        OrderType orderType,
        UUID referenceId,
        UUID orderGroupId,
        UUID sellerId,
        long orderVersion,
        List<UUID> productIds) {

    public OrderCreated {
        productIds = List.copyOf(productIds);
    }
}
