package com.group5.catalogservice.product;

import com.group5.catalogservice.shared.config.CatalogProperties;
import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/** Checks commerce-owned order data through an authenticated HTTP API. */
@Component
@ConditionalOnProperty(prefix = "nexus.catalog.order-references", name = "mode", havingValue = "http", matchIfMissing = true)
public class HttpOrderReferenceChecker implements OrderReferenceChecker {
    private static final String UNAVAILABLE = "Cannot verify product order references; retry the deletion later";

    private final RestClient client;

    public HttpOrderReferenceChecker(CatalogProperties properties, ObjectProvider<ObservationRegistry> observations) {
        CatalogProperties.OrderReferences configuration = properties.getOrderReferences();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(configuration.getConnectTimeoutMillis());
        requestFactory.setReadTimeout(configuration.getReadTimeoutMillis());
        client = RestClient.builder()
                .baseUrl(configuration.getBaseUrl())
                .requestFactory(requestFactory)
                .observationRegistry(observations.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();
    }

    @Override
    public boolean hasOrderReferences(UUID productId) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new DomainException(503, UNAVAILABLE);
        }
        String authorization = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                || authorization.substring(7).isBlank()) {
            throw new DomainException(503, UNAVAILABLE);
        }

        try {
            ApiResponse<OrderReferencesResponse> response = client.get()
                    .uri("/internal/order-references/products/{productId}", productId)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<OrderReferencesResponse>>() { });
            if (response == null || response.data() == null || response.data().hasOrders() == null) {
                throw new DomainException(503, UNAVAILABLE);
            }
            return response.data().hasOrders();
        } catch (RestClientException exception) {
            // A failed lookup must never turn into permission to delete a referenced product.
            throw new DomainException(503, UNAVAILABLE);
        }
    }

    public record OrderReferencesResponse(Boolean hasOrders) { }
}
