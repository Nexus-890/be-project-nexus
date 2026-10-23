package com.group5.commerceservice.cart.dto;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;
import com.group5.common_core.model.ApiResponse;

@FeignClient(
        name = "catalog-service",
        path = "/api/v1/skus"
)
public interface CatalogClient {

    @GetMapping("/{id}")
    ApiResponse<CatalogSkuResponse> getSkuById(@PathVariable("id") UUID id);
}
