package com.group5.auctionservice.auction;

import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import feign.FeignException;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
class AuctionCatalog {
    private final CatalogClient client;
    private final CircuitBreaker breaker;

    AuctionCatalog(CatalogClient client, Resilience4JCircuitBreakerFactory factory) {
        this.client = client;
        this.breaker = factory.create("auctionCatalog");
    }

    CatalogClient.Product requireProduct(UUID id) {
        return breaker.run(() -> lookup(id), failure -> {
            throw new DomainException(AuctionErrorCode.AUCTION_CATALOG_UNAVAILABLE);
        }).orElseThrow(() -> new DomainException(AuctionErrorCode.AUCTION_PRODUCT_NOT_FOUND));
    }

    private Optional<CatalogClient.Product> lookup(UUID id) {
        ApiResponse<CatalogClient.Product> response;
        try {
            response = client.getProduct(id);
        } catch (FeignException.NotFound ignored) {
            // A missing product is an expected lookup result and should not open the circuit.
            return Optional.empty();
        }

        CatalogClient.Product product = response == null ? null : response.data();
        if (product == null || product.sellerId() == null || product.name() == null
                || product.skus() == null || product.images() == null) {
            throw new DomainException(AuctionErrorCode.AUCTION_CATALOG_UNAVAILABLE);
        }
        return Optional.of(product);
    }
}
