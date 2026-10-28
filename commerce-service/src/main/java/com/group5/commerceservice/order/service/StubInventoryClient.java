package com.group5.commerceservice.order.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** Sprint 2 accepts stock checks; Sprint 3 supplies a real implementation and disables this flag. */
@Component
@Slf4j
@ConditionalOnProperty(name = "nexus.order.inventory-stub-enabled", havingValue = "true", matchIfMissing = true)
public class StubInventoryClient implements InventoryClient {
    @Override
    public boolean reserve(UUID productId, UUID skuId, int quantity) {
        log.info("Inventory stub accepted item: productId={}, skuId={}, quantity={}", productId, skuId, quantity);
        return true;
    }
}
