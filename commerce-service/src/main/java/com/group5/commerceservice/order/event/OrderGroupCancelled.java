package com.group5.commerceservice.order.event;

import java.util.List;
import java.util.UUID;

public record OrderGroupCancelled(
        UUID orderGroupId,
        UUID buyerId,
        long groupVersion,
        String reason,
        UUID actorId,
        String cancelledAt,
        List<CancelledOrder> orders) {

    public OrderGroupCancelled {
        orders = List.copyOf(orders);
    }

    public record CancelledOrder(UUID orderId, long orderVersion) {
    }
}
