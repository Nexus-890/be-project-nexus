package com.group5.catalogservice.product.dto.response;

import com.group5.catalogservice.product.enums.SkuStatus;
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

    private UUID id;
    private UUID productId;
    private String skuCode;
    private String color;
    private String size;
    private BigDecimal price;
    private SkuStatus status;

}
