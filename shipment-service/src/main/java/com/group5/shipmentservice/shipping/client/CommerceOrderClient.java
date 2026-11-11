package com.group5.shipmentservice.shipping.client;

import com.group5.common_core.model.ApiResponse;
import com.group5.shipmentservice.shipping.client.config.CommerceFeignConfiguration;
import com.group5.shipmentservice.shipping.dto.response.PaidOrderShippingResponse;
import feign.Request;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

@FeignClient(name = "commerce-service", contextId = "shippingCommerce", path = "/internal/v1/orders",
        url = "${nexus.shipping.commerce-base-url:}", configuration = CommerceFeignConfiguration.class)
public interface CommerceOrderClient {
    @GetMapping("/{id}/shipping-snapshot")
    ApiResponse<PaidOrderShippingResponse> getPaidOrder(@PathVariable("id") UUID id,
            @RequestHeader("Authorization") String authorization, Request.Options options);
}
