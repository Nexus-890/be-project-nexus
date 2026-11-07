package com.group5.commerceservice.order.event;

import com.group5.commerceservice.order.OrderType;

import java.util.UUID;

/** Canonical completion fact plus the immutable HTML receipt for asynchronous email delivery. */
public record OrderCompleted(
        UUID orderId,
        UUID orderGroupId,
        UUID buyerId,
        UUID sellerId,
        OrderType orderType,
        UUID referenceId,
        long orderVersion,
        String completedAt,
        String orderNumber,
        String receiptHtml) {
}
