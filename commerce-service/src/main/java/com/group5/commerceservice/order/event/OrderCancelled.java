package com.group5.commerceservice.order.event;

import com.group5.commerceservice.order.OrderType;

import java.util.UUID;

public record OrderCancelled(
        UUID orderId,
        UUID buyerId,
        String orderNumber,
        OrderType orderType,
        UUID referenceId,
        String cancelledAt,
        String reason,
        UUID orderGroupId,
        UUID sellerId,
        long orderVersion,
        String paidAmount,
        String currency,
        UUID actorId) {
}
