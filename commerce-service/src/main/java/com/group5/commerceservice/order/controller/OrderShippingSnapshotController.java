package com.group5.commerceservice.order.controller;

import com.group5.common_core.model.ApiResponse;
import com.group5.commerceservice.order.dto.response.OrderShippingSnapshotResponse;
import com.group5.commerceservice.order.service.OrderShippingSnapshotService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/orders")
public class OrderShippingSnapshotController {
    private final OrderShippingSnapshotService snapshots;

    public OrderShippingSnapshotController(OrderShippingSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @GetMapping("/{id}/shipping-snapshot")
    @PreAuthorize("hasAuthority('SHIPPING.CREATE_SHIPMENT') and hasAnyRole('ADMIN','SUPPORT_STAFF')")
    public ApiResponse<OrderShippingSnapshotResponse> get(@PathVariable UUID id) {
        return ApiResponse.of(snapshots.get(id));
    }
}
