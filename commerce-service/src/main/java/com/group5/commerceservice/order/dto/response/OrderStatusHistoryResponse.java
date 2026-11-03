package com.group5.commerceservice.order.dto.response;

import com.group5.commerceservice.order.OrderStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OrderStatusHistoryResponse(
        UUID id,
        OrderStatus previousStatus,
        OrderStatus newStatus,
        String reason,
        UUID changedBy,
        OffsetDateTime createdAt) {
}
