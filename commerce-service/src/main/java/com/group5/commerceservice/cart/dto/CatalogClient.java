package com.group5.commerceservice.cart.dto;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.UUID;
import com.group5.common_core.model.ApiResponse;

@FeignClient(
        name = "catalog-service",
        configuration = com.group5.commerceservice.order.client.config.OrderFeignConfiguration.class
)
public interface CatalogClient {

    @GetMapping("/api/v1/skus/{id}")
    ApiResponse<CatalogSkuResponse> getSkuById(@PathVariable("id") UUID id);

    @GetMapping("/api/v1/products/{id}")
    ApiResponse<ProductResponse> getProductById(@PathVariable("id") UUID id);

    @GetMapping("/internal/v1/catalog/skus/{id}")
    ApiResponse<CatalogSkuResponse> getOrderSkuById(@PathVariable("id") UUID id);

    record ProductResponse(UUID id, UUID sellerId, String name, String status, List<ImageResponse> images) { }

    record ImageResponse(String imageUrl, Short sortOrder) { }
}
