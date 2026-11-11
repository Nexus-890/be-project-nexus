package com.group5.inventoryservice.inventory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPaid(UUID orderId, String orderNumber, String orderType, UUID referenceId, UUID warehouseId) {

    public OrderPaid {
        Objects.requireNonNull(orderId, "orderId must not be null");
    }
}
