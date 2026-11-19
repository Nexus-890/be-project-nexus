package com.group5.inventoryservice.warehouse.controller;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.inventoryservice.warehouse.dto.request.CreateWarehouseRequest;
import com.group5.inventoryservice.warehouse.dto.request.UpdateOwnWarehouseRequest;
import com.group5.inventoryservice.warehouse.dto.request.UpdateWarehouseRequest;
import com.group5.inventoryservice.warehouse.dto.response.WarehouseResponse;
import com.group5.inventoryservice.warehouse.enums.WarehouseStatus;
import com.group5.inventoryservice.warehouse.service.WarehouseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/warehouses")
public class WarehouseController {

    private final WarehouseService service;

    public WarehouseController(WarehouseService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WarehouseResponse> create(@Valid @RequestBody CreateWarehouseRequest request) {
        return ApiResponse.of(service.create(request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT_STAFF')")
    public ApiResponse<List<WarehouseResponse>> list(@RequestParam(required = false) WarehouseStatus status) {
        return ApiResponse.list(service.list(status));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('SELLER')")
    public ApiResponse<WarehouseResponse> mine(CurrentUser currentUser) {
        return ApiResponse.of(service.mine(currentUser.id()));
    }

    @PutMapping("/me")
    @PreAuthorize("hasRole('SELLER')")
    public ApiResponse<WarehouseResponse> updateMine(@Valid @RequestBody UpdateOwnWarehouseRequest request,
                                                     CurrentUser currentUser) {
        return ApiResponse.of(service.updateMine(currentUser.id(), request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT_STAFF')")
    public ApiResponse<WarehouseResponse> getById(@PathVariable UUID id) {
        return ApiResponse.of(service.getById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WarehouseResponse> update(@PathVariable UUID id,
                                               @Valid @RequestBody UpdateWarehouseRequest request) {
        return ApiResponse.of(service.update(id, request));
    }
}
