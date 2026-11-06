package com.group5.commerceservice.cart.dto.response;

import java.util.UUID;

public record CartIssue(UUID itemId, UUID skuId, Code code) {
    public enum Code { OUT_OF_STOCK, PRICE_CHANGED, PRODUCT_INACTIVE }
}
