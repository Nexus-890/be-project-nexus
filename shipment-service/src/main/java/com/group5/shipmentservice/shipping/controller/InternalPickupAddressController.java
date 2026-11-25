package com.group5.shipmentservice.shipping.controller;

import com.group5.common_core.model.ApiResponse;
import com.group5.shipmentservice.shipping.dto.request.PickupAddressRequest;
import com.group5.shipmentservice.shipping.dto.response.PickupAddressCheckResponse;
import com.group5.shipmentservice.shipping.service.PickupAddressCheckService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/shipping/pickup-address-checks")
public class InternalPickupAddressController {
    private final PickupAddressCheckService checks;

    public InternalPickupAddressController(PickupAddressCheckService checks) {
        this.checks = checks;
    }

    @PostMapping
    @PreAuthorize("hasRole('SELLER') and hasAuthority('SHIPPING.QUOTE')")
    public ApiResponse<PickupAddressCheckResponse> check(@Valid @RequestBody PickupAddressRequest request) {
        return ApiResponse.of(checks.check(request));
    }
}
