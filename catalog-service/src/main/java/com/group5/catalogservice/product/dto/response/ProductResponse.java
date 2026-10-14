package com.group5.catalogservice.product.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

import lombok.Value;


@Value
public class ProductResponse {

    private UUID id;
    private String name;
    private String slug;
    private String status;
    private UUID categoryId;
    private UUID sellerId;

}