package com.group5.catalogservice.product.dto.response;

import lombok.Getter;

@Getter
public final class OwnerProductResponse extends ProductResponse {
    private final String description;
    private final boolean auctionEnable;

    public OwnerProductResponse(ProductResponse product, String description, boolean auctionEnable) {
        super(product.getId(), product.getName(), product.getSlug(), product.getStatus(),
                product.getCategoryId(), product.getSellerId(), product.getMinPrice(), product.getMaxPrice());
        setImages(product.getImages());
        this.description = description;
        this.auctionEnable = auctionEnable;
    }
}
