package com.group5.auctionservice.auction;

import com.group5.common_core.model.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.UUID;

@FeignClient(name = "catalog-service", contextId = "auctionCatalog",
        url = "${nexus.auction.catalog-base-url:}")
public interface CatalogClient {
    @GetMapping("/api/v1/products/{id}")
    ApiResponse<Product> getProduct(@PathVariable("id") UUID id);

    record Product(UUID sellerId, String name, List<Sku> skus, List<Image> images) {
        String firstImageUrl() {
            return images.isEmpty() ? null : images.getFirst().imageUrl();
        }
    }

    record Sku(UUID id, String skuCode) {
        public Sku(UUID id) {
            this(id, null);
        }
    }

    record Image(String imageUrl) {}
}
