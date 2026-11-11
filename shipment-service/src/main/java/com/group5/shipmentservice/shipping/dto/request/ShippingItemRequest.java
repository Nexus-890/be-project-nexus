package com.group5.shipmentservice.shipping.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ShippingItemRequest(
        @NotNull UUID skuId,
        @NotNull @Min(1) @Max(999) Integer quantity) {
}
