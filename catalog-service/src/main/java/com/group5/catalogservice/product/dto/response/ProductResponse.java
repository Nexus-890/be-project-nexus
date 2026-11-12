package com.group5.catalogservice.product.dto.response;

import com.group5.catalogservice.product.enums.ProductStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ProductResponse {
    private UUID id;
    private String name;
    private String slug;
    private ProductStatus status;
    private UUID categoryId;
    private UUID sellerId;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private List<ImageResponse> images = List.of();

    public ProductResponse(
            UUID id,
            String name,
            String slug,
            ProductStatus status,
            UUID categoryId,
            UUID sellerId,
            BigDecimal minPrice,
            BigDecimal maxPrice) {

        this.id = id;
        this.name = name;
        this.slug = slug;
        this.status = status;
        this.categoryId = categoryId;
        this.sellerId = sellerId;
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
    }

    public ProductResponse(
            UUID id,
            String name,
            String slug,
            ProductStatus status,
            UUID categoryId,
            UUID sellerId) {

        this(
                id,
                name,
                slug,
                status,
                categoryId,
                sellerId,
                null,
                null
        );
    }
}
