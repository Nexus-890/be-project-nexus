package com.group5.commerceservice.cart.dto;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.UUID;
import com.group5.common_core.model.ApiResponse;

@FeignClient(
        name = "catalog-service",
        url = "${nexus.order.catalog-costs.base-url:}",
        configuration = com.group5.commerceservice.order.client.config.OrderFeignConfiguration.class
)
public interface CatalogClient {

    @GetMapping("/api/v1/skus/{id}")
    ApiResponse<CatalogSkuResponse> getSkuById(@PathVariable("id") UUID id);

    @GetMapping("/api/v1/products/{id}")
    ApiResponse<ProductResponse> getProductById(@PathVariable("id") UUID id);

    @org.springframework.web.bind.annotation.PostMapping("/internal/v1/catalog/sku-costs")
    ApiResponse<List<Cost>> getCosts(@org.springframework.web.bind.annotation.RequestBody CostRequest request);

    record CostRequest(List<UUID> skuIds) { }
    record Cost(UUID skuId, UUID productId, UUID sellerId, java.math.BigDecimal unitCostPrice) { }

    record ProductResponse(UUID id, UUID sellerId, String name, String status, List<ImageResponse> images) { }

    record ImageResponse(String imageUrl, Short sortOrder) { }
}
