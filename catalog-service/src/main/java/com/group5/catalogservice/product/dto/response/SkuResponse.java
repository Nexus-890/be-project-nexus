package com.group5.catalogservice.product.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SkuResponse {

    UUID id;
    UUID productId;
    String skuCode;
    String color;
    String size;
    BigDecimal price;
    String status;
}
