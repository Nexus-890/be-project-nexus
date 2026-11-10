package com.group5.shipmentservice.shipping.client;

import com.group5.common_core.model.ApiResponse;
import com.group5.shipmentservice.shipping.client.config.CatalogFeignConfiguration;
import com.group5.shipmentservice.shipping.dto.response.CatalogSkuResponse;
import feign.Request;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(name = "catalog-service", contextId = "shippingCatalog", path = "/api/v1",
        url = "${nexus.shipping.catalog-base-url:}", configuration = CatalogFeignConfiguration.class)
public interface CatalogClient {
    @GetMapping("/skus/{id}")
    ApiResponse<CatalogSkuResponse> getSkuById(@PathVariable("id") UUID id, Request.Options options);
}
