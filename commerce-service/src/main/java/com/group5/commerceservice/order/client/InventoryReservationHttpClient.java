package com.group5.commerceservice.order.client;

import com.group5.common_core.model.ApiResponse;
import com.group5.commerceservice.order.client.config.OrderFeignConfiguration;
import com.group5.commerceservice.order.service.InventoryClient;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

@FeignClient(name = "inventory-service", contextId = "orderInventory",
        url = "${nexus.order.inventory-base-url:}", configuration = OrderFeignConfiguration.class)
public interface InventoryReservationHttpClient {
    @PostMapping("/api/v1/inventory/reservations")
    ApiResponse<InventoryClient.Reservation> reserve(@RequestBody Request request);

    @GetMapping("/internal/v1/inventory/reservations")
    ApiResponse<List<LinkedReservation>> linked(@RequestParam("orderId") UUID orderId);

    record Request(UUID orderGroupId, List<InventoryClient.Item> items) { }
    record LinkedReservation(UUID reservationId, UUID warehouseId, String status) { }
}
