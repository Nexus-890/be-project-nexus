package com.group5.inventoryservice.reservation.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.UUID;

public record ReserveRequest(
        @NotNull UUID orderGroupId,
        @NotEmpty List<@NotNull @Valid Item> items) {

    public record Item(
            @NotNull UUID orderId,
            @NotNull UUID skuId,
            @NotNull @Positive Integer quantity) {
    }
}
