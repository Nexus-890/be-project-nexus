package com.group5.commerceservice.order.dto.request;

import com.group5.commerceservice.promotion.VoucherCodes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record CreateOrderRequest(
        @NotNull @Valid ShippingAddress shippingAddress,
        @NotNull @Pattern(regexp = "SEPAY") String paymentMethod,
        String promoCode, List<String> voucherCodes,
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,29}$") String carrier) {

    public CreateOrderRequest(ShippingAddress shippingAddress, String paymentMethod) {
        this(shippingAddress, paymentMethod, null, null, null);
    }

    public CreateOrderRequest(ShippingAddress shippingAddress, String paymentMethod, String promoCode,
                              List<String> voucherCodes) {
        this(shippingAddress, paymentMethod, promoCode, voucherCodes, null);
    }

    public CreateOrderRequest {
        if (paymentMethod != null) {
            paymentMethod = paymentMethod.trim();
        }
    }

    /** Lưu đúng người nhận và mã địa giới GHN v3; kho/kiện sẽ bổ sung bằng dữ liệu backend. */
    public Map<String, Object> snapshot() {
        ShippingAddress address = shippingAddress;
        var snapshot = new HashMap<String, Object>(Map.of("paymentMethod", paymentMethod, "countryCode", address.countryCode(),
                "recipient", Map.of("name", address.recipientName(), "phone", address.phoneNumber(),
                        "address", Map.of("line1", address.addressLine(), "wardCode", address.wardCode(),
                                "provinceCode", address.provinceCode()))));
        List<String> requested = requestedVouchers();
        if (requested != null) snapshot.put("voucherCodes", requested);
        if (carrier != null) snapshot.put("carrier", carrier);
        return snapshot;
    }

    public List<String> requestedVouchers() {
        return VoucherCodes.requested(voucherCodes, promoCode);
    }

    public record ShippingAddress(
            @NotBlank @Size(max = 150) String recipientName,
            @NotBlank @Pattern(regexp = "^(0[0-9]{9}|\\+84[0-9]{9})$") String phoneNumber,
            @NotBlank @Size(max = 255) String addressLine,
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,8}$") String wardCode,
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,8}$") String provinceCode,
            @NotBlank @Pattern(regexp = "VN") String countryCode) {

        public ShippingAddress {
            recipientName = trim(recipientName);
            phoneNumber = trim(phoneNumber);
            addressLine = trim(addressLine);
            wardCode = trim(wardCode);
            provinceCode = trim(provinceCode);
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
