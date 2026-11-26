package com.group5.commerceservice.cart.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
public class CatalogSkuResponse {

    private UUID id;
    private UUID productId;
    private String skuCode;
    private String color;
    private String size;
    private BigDecimal price;
    private BigDecimal costPrice;
    private String status;
    private ShippingParcel shippingParcel;

    /** Số đo một gói SKU do người bán khai báo; không lấy từ checkout request. */
    public record ShippingParcel(Integer weightGrams, Integer lengthCm, Integer widthCm, Integer heightCm) { }
}
