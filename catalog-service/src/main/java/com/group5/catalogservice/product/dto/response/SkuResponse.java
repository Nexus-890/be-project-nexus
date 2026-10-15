package com.group5.catalogservice.product.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class SkuResponse {

    private UUID id;
    private UUID productId;
    private String skuCode;
    private String color;
    private String size;
    private BigDecimal price;
    private String status;
}