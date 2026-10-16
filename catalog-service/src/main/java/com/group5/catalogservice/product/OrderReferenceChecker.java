package com.group5.catalogservice.product;

import java.util.UUID;

public interface OrderReferenceChecker {
    boolean hasOrderReferences(UUID productId);
}
