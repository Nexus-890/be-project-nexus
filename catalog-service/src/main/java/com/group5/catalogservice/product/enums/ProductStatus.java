package com.group5.catalogservice.product.enums;

import java.util.Map;
import java.util.Set;

public enum ProductStatus {
    DRAFT,
    INACTIVE,
    ACTIVE;
    private static final Map<ProductStatus, Set<ProductStatus>> TRANSITIONS =
            Map.of(
                    ProductStatus.DRAFT, Set.of(ProductStatus.ACTIVE),
                    ProductStatus.ACTIVE, Set.of(ProductStatus.INACTIVE),
                    ProductStatus.INACTIVE, Set.of(ProductStatus.ACTIVE)
            );

    public boolean canTransitionTo(ProductStatus targetStatus) {
        return targetStatus != null
                && TRANSITIONS.get(this).contains(targetStatus);
    }
}
