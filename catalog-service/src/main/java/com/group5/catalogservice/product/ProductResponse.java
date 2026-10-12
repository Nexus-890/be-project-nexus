package com.group5.catalogservice.product;

import java.util.UUID;

import lombok.Value;

@Value
public class ProductResponse {

    UUID id;
    UUID categoryId;
    UUID sellerId;
    String name;
    String slug;
    String description;
    String status;
    Boolean auctionEnable;
    Boolean hasOrder;
    Boolean inAuction;

    public ProductResponse(Product product) {
        this.id = product.getId();
        this.categoryId = product.getCategory().getId();
        this.sellerId = product.getSellerId();
        this.name = product.getName();
        this.slug = product.getSlug();
        this.description = product.getDescription();
        this.status = product.getStatus();
        this.auctionEnable = product.getAuctionEnable();
        this.hasOrder = product.getHasOrder();
        this.inAuction = product.getInAuction();
    }
}