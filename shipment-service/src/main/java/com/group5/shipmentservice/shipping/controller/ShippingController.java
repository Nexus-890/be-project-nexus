package com.group5.shipmentservice.shipping.controller;

import com.group5.common_core.model.ApiResponse;
import com.group5.shipmentservice.shipping.dto.request.ShippingQuoteRequest;
import com.group5.shipmentservice.shipping.dto.response.LocalityResponse;
import com.group5.shipmentservice.shipping.dto.response.ShippingQuoteResponse;
import com.group5.shipmentservice.shipping.service.ShippingAddressService;
import com.group5.shipmentservice.shipping.service.ShippingQuoteService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/shipping")
public class ShippingController {
    private final ShippingAddressService addresses;
    private final ShippingQuoteService quotes;

    public ShippingController(ShippingAddressService addresses, ShippingQuoteService quotes){
        this.addresses = addresses;
        this.quotes = quotes;
    }

    @GetMapping("/provinces")
    public ApiResponse<List<LocalityResponse>> getProvinces() {
        return ApiResponse.list(addresses.getProvinces());
    }

    @GetMapping("/wards")
    public ApiResponse<List<LocalityResponse>> getWards(@RequestParam String provinceCode) {
        return ApiResponse.list(addresses.getWards(provinceCode));
    }

    @PostMapping("/quotes")
    @PreAuthorize("hasAuthority('SHIPPING.QUOTE')")
    public ApiResponse<List<ShippingQuoteResponse>> quote(@Valid @RequestBody ShippingQuoteRequest request) {
        return ApiResponse.list(quotes.quote(request));
    }
}
