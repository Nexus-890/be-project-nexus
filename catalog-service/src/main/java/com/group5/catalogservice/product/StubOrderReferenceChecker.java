package com.group5.catalogservice.product;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Explicit opt-in for isolated local catalog development without commerce. */
@Component
@ConditionalOnProperty(prefix = "nexus.catalog.order-references", name = "mode", havingValue = "stub")
public class StubOrderReferenceChecker implements OrderReferenceChecker {
    @Override
    public boolean hasOrderReferences(UUID productId) {
        return false;
    }
}
