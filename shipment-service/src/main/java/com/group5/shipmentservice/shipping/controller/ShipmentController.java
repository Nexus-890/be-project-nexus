package com.group5.shipmentservice.shipping.controller;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import com.group5.common_security.CurrentUser;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.dto.request.CreateShipmentRequest;
import com.group5.shipmentservice.shipping.dto.response.ShipmentResponse;
import com.group5.shipmentservice.shipping.entity.Shipment;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;
import com.group5.shipmentservice.shipping.service.ShipmentCreationService;
import com.group5.shipmentservice.shipping.service.ShipmentReadService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shipments")
public class ShipmentController {
    private final ShipmentCreationService creation;
    private final ShipmentReadService reads;

    public ShipmentController(ShipmentCreationService creation, ShipmentReadService reads) {
        this.creation = creation;
        this.reads = reads;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SHIPPING.CREATE_SHIPMENT') and hasAnyRole('ADMIN','SUPPORT_STAFF')")
    public ResponseEntity<ApiResponse<ShipmentResponse>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateShipmentRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new DomainException(ShipmentErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (idempotencyKey.length() > 100) {
            throw new DomainException(ShipmentErrorCode.INVALID_PARAMETER);
        }
        Shipment shipment = creation.createManually(idempotencyKey, request);
        return ResponseEntity.created(URI.create("/api/v1/shipments/" + shipment.getId()))
                .body(ApiResponse.of(ShipmentResponse.from(shipment)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SHIPPING.VIEW_SHIPMENT')")
    public ApiResponse<ShipmentResponse> get(CurrentUser user, @PathVariable UUID id) {
        return ApiResponse.of(reads.get(user, id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SHIPPING.VIEW_SHIPMENT')")
    public ApiResponse<List<ShipmentResponse>> list(CurrentUser user,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) ShipmentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ShipmentResponse> result = reads.list(user, orderId, status, page, size);
        return ApiResponse.page(result.getContent(), new PageInfo(result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages()));
    }
}
