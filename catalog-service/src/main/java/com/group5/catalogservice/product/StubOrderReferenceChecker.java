package com.group5.catalogservice.product;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Sprint 1 stub. Replace with the order integration in Sprint 2. */
@Component
public class StubOrderReferenceChecker implements OrderReferenceChecker {
    @Override
    public boolean hasOrderReferences(UUID productId) {
        return false;
    }
}
