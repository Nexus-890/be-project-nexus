package com.group5.commerceservice.cart.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
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
    @Max(999)
    private Integer quantity;
}
