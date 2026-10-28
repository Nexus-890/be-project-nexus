package com.group5.commerceservice.order.service;

import java.util.UUID;

public interface InventoryClient {
    boolean reserve(UUID productId, UUID skuId, int quantity);
}
