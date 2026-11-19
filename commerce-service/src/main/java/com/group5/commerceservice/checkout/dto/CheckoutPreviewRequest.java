package com.group5.commerceservice.checkout.dto;

import com.group5.commerceservice.order.dto.request.CreateOrderRequest.ShippingAddress;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public record CheckoutPreviewRequest(
        @NotNull @Valid ShippingAddress shippingAddress,
        @NotNull @Pattern(regexp = "SEPAY") String paymentMethod,
        String promoCode, List<String> voucherCodes,
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,29}$") String carrier) {
    public CheckoutPreviewRequest(ShippingAddress shippingAddress, String paymentMethod, String promoCode) {
        this(shippingAddress, paymentMethod, promoCode, null, null);
    }

    public CheckoutPreviewRequest(ShippingAddress shippingAddress, String paymentMethod, String promoCode,
                                  List<String> voucherCodes) {
        this(shippingAddress, paymentMethod, promoCode, voucherCodes, null);
    }

    public CheckoutPreviewRequest {
        if (paymentMethod != null) paymentMethod = paymentMethod.trim();
    }
}
