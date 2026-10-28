package com.group5.commerceservice.order.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponse(
        UUID orderItemId,
        UUID productId,
        UUID skuId,
        String productName,
        String skuCode,
        String imageUrl,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal totalPrice) {
}
