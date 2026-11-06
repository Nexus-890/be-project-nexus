package com.group5.commerceservice.checkout.controller;

import com.group5.commerceservice.cart.service.CartPricingService;
import com.group5.commerceservice.checkout.dto.CheckoutPreviewRequest;
import com.group5.commerceservice.checkout.dto.CheckoutPreviewResponse;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/checkout")
public class CheckoutController {
    private final CartPricingService pricing;

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('CHECKOUT.START')")
    public ApiResponse<CheckoutPreviewResponse> preview(
            @AuthenticationPrincipal CurrentUser user, @Valid @RequestBody CheckoutPreviewRequest request) {
        return ApiResponse.of(pricing.preview(user, request.promoCode()));
    }
}
