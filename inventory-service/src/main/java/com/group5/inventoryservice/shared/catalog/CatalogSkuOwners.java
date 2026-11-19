package com.group5.inventoryservice.shared.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.inventoryservice.shared.exception.InventoryErrorCode;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.UUID;

@Component
public class CatalogSkuOwners {

    private final RestClient catalog;

    public CatalogSkuOwners(@LoadBalanced RestClient.Builder builder) {
        this.catalog = builder.baseUrl("http://catalog-service").build();
    }

    public Optional<UUID> sellerOf(UUID skuId) {
        try {
            Sku sku = data(catalog.get().uri("/api/v1/skus/{id}", skuId).retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<Sku>>() { }));
            if (sku == null || sku.productId() == null) {
                return Optional.empty();
            }
            Product product = data(catalog.get().uri("/api/v1/products/{id}", sku.productId()).retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<Product>>() { }));
            return product == null ? Optional.empty() : Optional.ofNullable(product.sellerId());
        } catch (HttpClientErrorException.NotFound missing) {
            return Optional.empty();
        } catch (RestClientException | IllegalStateException failure) {
            throw new DomainException(InventoryErrorCode.CATALOG_UNAVAILABLE);
        }
    }

    private static <T> T data(ApiResponse<T> response) {
        return response == null ? null : response.data();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Sku(UUID id, UUID productId) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Product(UUID id, UUID sellerId) { }
}
