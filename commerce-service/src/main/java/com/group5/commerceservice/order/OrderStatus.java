package com.group5.commerceservice.order;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum OrderStatus {
    PENDING,
    PAID,
    SHIPPED,
    DELIVERED,
    COMPLETED,
    CANCELED,
    REFUND_REQUESTED,
    REFUNDED;

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
            PENDING, EnumSet.of(PAID, CANCELED),
            PAID, EnumSet.of(CANCELED, SHIPPED),
            SHIPPED, EnumSet.of(DELIVERED),
            DELIVERED, EnumSet.of(COMPLETED),
            CANCELED, EnumSet.of(REFUND_REQUESTED),
            REFUND_REQUESTED, EnumSet.of(REFUNDED));

    public boolean canMoveTo(OrderStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
