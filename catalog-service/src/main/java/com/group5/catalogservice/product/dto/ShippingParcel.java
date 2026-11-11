package com.group5.catalogservice.product.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Số đo bao gói vận chuyển của một đơn vị SKU do chủ sản phẩm cung cấp. */
public record ShippingParcel(
        @NotNull @Min(1) @Max(50000) Integer weightGrams,
        @NotNull @Min(1) @Max(200) Integer lengthCm,
        @NotNull @Min(1) @Max(200) Integer widthCm,
        @NotNull @Min(1) @Max(200) Integer heightCm) {
}
