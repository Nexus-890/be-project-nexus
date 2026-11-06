package com.group5.commerceservice.cart.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonDeserialize;

@Getter
@Setter
public class UpdateCartItemRequest {

    @NotNull
    @Positive
    @Max(999)
    @JsonDeserialize(using = CartQuantityDeserializer.class)
    private Integer quantity;
}
