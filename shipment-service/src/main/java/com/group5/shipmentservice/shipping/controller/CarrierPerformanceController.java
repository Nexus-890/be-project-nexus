package com.group5.shipmentservice.shipping.controller;

import com.group5.common_core.model.ApiResponse;
import com.group5.shipmentservice.shipping.dto.response.CarrierPerformanceResponse;
import com.group5.shipmentservice.shipping.service.CarrierPerformanceService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/carriers/performance")
public class CarrierPerformanceController {
    private final CarrierPerformanceService performance;

    public CarrierPerformanceController(CarrierPerformanceService performance) {
        this.performance = performance;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SHIPPING.VIEW_SHIPMENT') and hasAnyRole('ADMIN', 'SUPPORT_STAFF')")
    public ApiResponse<List<CarrierPerformanceResponse>> performance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ApiResponse.list(performance.performance(from, to));
    }
}
