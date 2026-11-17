package com.group5.commerceservice.order.client;

import com.group5.common_core.model.ApiResponse;
import com.group5.commerceservice.order.client.config.OrderFeignConfiguration;
import com.group5.commerceservice.order.service.OrderShippingService.Parcel;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@FeignClient(name = "shipment-service", contextId = "orderShipping",
        url = "${nexus.order.shipping-base-url:}", configuration = OrderFeignConfiguration.class)
public interface ShippingQuoteHttpClient {
    @PostMapping("/api/v1/shipping/quotes")
    ApiResponse<List<Response>> quote(@RequestBody Request request);

    record Request(Address toAddress, List<Item> items, Parcel parcel, UUID warehouseId,
                   @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                   Boolean applyFreeShipping) {
        public Request(Address toAddress, List<Item> items, Parcel parcel, UUID warehouseId) {
            this(toAddress, items, parcel, warehouseId, null);
        }
    }
    record Address(String provinceCode, String wardCode, String line1, String recipientName, String recipientPhone) { }
    record Item(UUID skuId, int quantity) { }
    record Response(String carrier, String serviceType, BigDecimal fee, OffsetDateTime estimatedDeliveryAt) { }
}
