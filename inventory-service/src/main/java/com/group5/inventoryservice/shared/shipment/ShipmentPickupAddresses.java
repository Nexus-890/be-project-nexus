package com.group5.inventoryservice.shared.shipment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.group5.common_core.exception.DomainException;
import com.group5.inventoryservice.shared.exception.InventoryErrorCode;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

@Component
public class ShipmentPickupAddresses {

    private final RestClient shipment;

    public ShipmentPickupAddresses(@LoadBalanced RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(3));
        requests.setReadTimeout(Duration.ofSeconds(10));
        this.shipment = builder.clone().baseUrl("http://shipment-service").requestFactory(requests).build();
    }

    public void verify(String provinceCode, String wardCode, String line1) {
        String authorization = callerAuthorization();
        try {
            shipment.post().uri("/internal/v1/shipping/pickup-address-checks")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Check(provinceCode, wardCode, line1))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException rejected) {
            throw rejection(rejected);
        } catch (RestClientException | IllegalStateException failure) {
            throw new DomainException(InventoryErrorCode.PICKUP_CHECK_UNAVAILABLE);
        }
    }

    private static DomainException rejection(HttpClientErrorException rejected) {
        return switch (problemCode(rejected)) {
            case "SHIPMENT_PICKUP_ADDRESS_CONFLICT" -> new DomainException(InventoryErrorCode.INVALID_PICKUP_ADDRESS,
                    "The carrier does not accept this house number and street for the selected ward");
            case "SHIPMENT_INVALID_ADDRESS", "SHIPMENT_VALIDATION_FAILED", "SHIPMENT_INVALID_PARAMETER" ->
                    new DomainException(InventoryErrorCode.INVALID_PICKUP_ADDRESS,
                            "The selected ward does not belong to the selected province");
            case "SHIPMENT_NO_SERVICE" -> new DomainException(InventoryErrorCode.INVALID_PICKUP_ADDRESS,
                    "The carrier does not collect parcels from this ward");
            default -> new DomainException(InventoryErrorCode.PICKUP_CHECK_UNAVAILABLE);
        };
    }

    private static String problemCode(HttpClientErrorException rejected) {
        try {
            Problem problem = rejected.getResponseBodyAs(Problem.class);
            return problem == null || problem.code() == null ? "" : problem.code();
        } catch (RuntimeException unreadable) {
            return "";
        }
    }

    private static String callerAuthorization() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String authorization = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                    && !authorization.substring(7).isBlank()) {
                return authorization;
            }
        }
        throw new DomainException(InventoryErrorCode.PICKUP_CHECK_UNAVAILABLE);
    }

    record Check(String provinceCode, String wardCode, String line1) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Problem(String code) { }
}
