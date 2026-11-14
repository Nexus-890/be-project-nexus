package com.group5.commerceservice.order.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryStockUnavailable(@NotNull UUID orderId, @NotEmpty List<@NotNull Line> reservations) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(@NotNull UUID reservationId, @NotNull UUID skuId, int quantity) {
    }
}
