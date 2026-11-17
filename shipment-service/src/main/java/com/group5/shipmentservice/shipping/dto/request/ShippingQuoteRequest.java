package com.group5.shipmentservice.shipping.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ShippingQuoteRequest(
        @NotNull @Valid ShippingAddressRequest toAddress,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid ShippingItemRequest> items,
        @NotNull @Valid ParcelRequest parcel,
        UUID warehouseId,
        @DecimalMin("0") @DecimalMax("5000000") @Digits(integer = 7, fraction = 0) BigDecimal insuranceValue,
        Boolean applyFreeShipping) {

    public ShippingQuoteRequest {
        // Request cũ không gửi cờ vẫn giữ chính sách miễn phí dựa trên giá Catalog.
        applyFreeShipping = applyFreeShipping == null || applyFreeShipping;
    }

    public ShippingQuoteRequest(ShippingAddressRequest toAddress, List<ShippingItemRequest> items,
                                ParcelRequest parcel, UUID warehouseId, BigDecimal insuranceValue) {
        this(toAddress, items, parcel, warehouseId, insuranceValue, true);
    }
}
