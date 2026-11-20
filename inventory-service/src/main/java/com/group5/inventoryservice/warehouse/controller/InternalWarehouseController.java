package com.group5.inventoryservice.warehouse.controller;

import com.group5.common_core.model.ApiResponse;
import com.group5.inventoryservice.warehouse.dto.response.WarehousePickupResponse;
import com.group5.inventoryservice.warehouse.service.WarehouseService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/inventory/warehouses")
public class InternalWarehouseController {

    private final WarehouseService service;

    public InternalWarehouseController(WarehouseService service) {
        this.service = service;
    }

    @GetMapping("/pickup")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<WarehousePickupResponse> pickup(@RequestParam UUID sellerId,
                                                       @RequestParam(required = false) List<UUID> skuIds) {
        return ApiResponse.of(service.pickup(sellerId, skuIds));
    }
}
