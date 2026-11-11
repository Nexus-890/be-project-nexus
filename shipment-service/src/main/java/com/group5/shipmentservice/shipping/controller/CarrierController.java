package com.group5.shipmentservice.shipping.controller;

import com.group5.common_core.model.ApiResponse;
import com.group5.shipmentservice.shipping.dto.response.CarrierResponse;
import com.group5.shipmentservice.shipping.service.CarrierService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/carriers")
public class CarrierController {
    private final CarrierService carrierService;

    public CarrierController(CarrierService carrierService) {
        this.carrierService = carrierService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SHIPPING.QUOTE')")
    public ApiResponse<List<CarrierResponse>> getCarriers() {
        return ApiResponse.list(carrierService.getCarriers());
    }
}
