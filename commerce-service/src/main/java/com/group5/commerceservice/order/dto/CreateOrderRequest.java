package com.group5.commerceservice.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;
import java.util.Map;

public record CreateOrderRequest(
        @NotNull @Valid ShippingAddress shippingAddress,
        @NotNull @Pattern(regexp = "SEPAY") String paymentMethod) {

    public CreateOrderRequest {
        if (paymentMethod != null) {
            paymentMethod = paymentMethod.trim();
        }
    }

    public Map<String, Object> snapshot() {
        ShippingAddress address = shippingAddress;
        return Map.of("paymentMethod", paymentMethod, "shippingAddress", Map.of(
                "recipientName", address.recipientName(), "phoneNumber", address.phoneNumber(),
                "addressLine", address.addressLine(), "ward", address.ward(), "district", address.district(),
                "city", address.city(), "countryCode", address.countryCode()));
    }

    public record ShippingAddress(
            @NotBlank @Size(max = 150) String recipientName,
            @NotBlank @Size(min = 8, max = 20) String phoneNumber,
            @NotBlank @Size(max = 500) String addressLine,
            @NotBlank @Size(max = 100) String ward,
            @NotBlank @Size(max = 100) String district,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Pattern(regexp = "VN") String countryCode) {

        public ShippingAddress {
            recipientName = trim(recipientName);
            phoneNumber = trim(phoneNumber);
            addressLine = trim(addressLine);
            ward = trim(ward);
            district = trim(district);
            city = trim(city);
            countryCode = trim(countryCode);
            if (countryCode != null) {
                countryCode = countryCode.toUpperCase(Locale.ROOT);
            }
        }

        private static String trim(String value) {
            return value == null ? null : value.trim();
        }
    }
}
