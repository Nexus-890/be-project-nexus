package com.group5.commerceservice.checkout.dto;

import com.group5.commerceservice.order.dto.request.CreateOrderRequest.ShippingAddress;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CheckoutPreviewRequest(
        @NotNull @Valid ShippingAddress shippingAddress,
        @NotNull @Pattern(regexp = "SEPAY") String paymentMethod,
        String promoCode) {
    public CheckoutPreviewRequest {
        if (paymentMethod != null) paymentMethod = paymentMethod.trim();
    }
}
