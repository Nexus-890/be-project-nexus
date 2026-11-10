package com.group5.inventoryservice.reservation.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record ReserveRequest(
        @NotNull UUID orderGroupId,
        @NotEmpty @Size(max = MAX_ITEMS) List<@NotNull @Valid Item> items) {

    public static final int MAX_ITEMS = 100;
    public static final int MAX_QUANTITY = 999;

    public record Item(
            @NotNull UUID orderId,
            @NotNull UUID skuId,
            @NotNull @Positive @Max(MAX_QUANTITY) Integer quantity) {
    }
}
