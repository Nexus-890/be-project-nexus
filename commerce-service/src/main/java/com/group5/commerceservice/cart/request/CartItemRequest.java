package com.group5.commerceservice.cart.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class CartItemRequest {

    @NotNull
    private UUID skuId;

    @NotNull
    @Positive
    private Integer quantity;
}