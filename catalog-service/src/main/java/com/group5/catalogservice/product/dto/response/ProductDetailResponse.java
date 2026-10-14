package com.group5.catalogservice.product.dto.response;

import lombok.Getter;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter
public class ProductDetailResponse extends ProductResponse.ProductResponse {
    private final String description;
    private final Boolean auctionEnable;
    private final List<SkuResponse> skus;
    private final List<ImageResponse> images;

    public ProductDetailResponse(UUID id, String name, String slug, String status,
                                 UUID categoryId, UUID sellerId, String description,
                                 Boolean auctionEnable, List<SkuResponse> skus,
                                 List<ImageResponse> images) {
        super(id, name, slug, status, categoryId, sellerId);
        this.description = description;
        this.auctionEnable = auctionEnable;
        this.skus = List.copyOf(skus);
        this.images = List.copyOf(images);
    }

    public record SkuResponse(UUID id, UUID productId, String skuCode, String color,
                              String size, BigDecimal price, String status) {}

    public record ImageResponse(UUID id, String imageUrl, Short sortOrder) {}
}
