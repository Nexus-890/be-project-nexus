package com.group5.commerceservice.cart.dto;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.UUID;

@FeignClient(
        name = "catalog-service",
        path = "/api/v1"
)
public interface CatalogClient {

    @GetMapping("/skus/{id}")
    CatalogSkuResponse getSkuById(@PathVariable("id") UUID id);

    @GetMapping("/products/{id}")
    ProductResponse getProductById(@PathVariable("id") UUID id);

    record ProductResponse(UUID id, UUID sellerId, String name, String status, List<ImageResponse> images) { }

    record ImageResponse(String imageUrl, Short sortOrder) { }
}
