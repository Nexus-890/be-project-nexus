package com.group5.shipmentservice.shipping.controller;

import com.group5.shipmentservice.shipping.dto.request.ShipmentOverrideRequest;
import com.group5.shipmentservice.shipping.dto.request.ShipmentStatusRequest;
import com.group5.shipmentservice.shipping.dto.response.ShipmentTrackingEntryResponse;
import com.group5.shipmentservice.shipping.service.ShipmentStatusService;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import com.group5.common_security.CurrentUser;
import com.group5.shipmentservice.shipping.dto.response.ShipmentResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shipments")
public class ShipmentStatusController {
    private final ShipmentStatusService statuses;

    public ShipmentStatusController(ShipmentStatusService statuses) { this.statuses = statuses; }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAuthority('SHIPPING.VIEW_SHIPMENT')")
    public ApiResponse<List<ShipmentTrackingEntryResponse>> tracking(CurrentUser user, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var result = statuses.tracking(user, id, page, size);
        return ApiResponse.page(result.getContent(), new PageInfo(result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages()));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('SHIPPING.UPDATE_STATUS') and hasAnyRole('ADMIN','SUPPORT_STAFF')")
    public ApiResponse<ShipmentResponse> update(CurrentUser user, @PathVariable UUID id,
                                                @Valid @RequestBody ShipmentStatusRequest request) {
        return ApiResponse.of(statuses.update(user, id, request.status(), request.reason(), false));
    }

    @PostMapping("/{id}/override")
    @PreAuthorize("hasAuthority('SHIPPING.MANUAL_OVERRIDE') and hasAnyRole('ADMIN','SUPPORT_STAFF')")
    public ApiResponse<ShipmentResponse> override(CurrentUser user, @PathVariable UUID id,
                                                  @Valid @RequestBody ShipmentOverrideRequest request) {
        return ApiResponse.of(statuses.update(user, id, request.status(), request.reason(), true));
    }
}
