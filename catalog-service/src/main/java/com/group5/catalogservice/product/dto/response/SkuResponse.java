package com.group5.catalogservice.product.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record SkuResponse(
        UUID id, UUID productId, String skuCode, String color,
        String size, BigDecimal price, String status
) {
}
