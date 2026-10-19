package com.group5.commerceservice.cart.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class CartItemResponse {

    private UUID id;
    private UUID productId;
    private UUID skuId;
    private Integer quantity;
    private BigDecimal unitPrice;
}
