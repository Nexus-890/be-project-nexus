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
    private String status;
}
