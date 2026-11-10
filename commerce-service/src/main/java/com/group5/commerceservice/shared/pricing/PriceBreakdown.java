package com.group5.commerceservice.shared.pricing;

import java.math.BigDecimal;
import java.util.Objects;

public record PriceBreakdown(BigDecimal subtotal, BigDecimal discountAmount, BigDecimal taxAmount,
                             BigDecimal shippingFee) {

    public PriceBreakdown {
        Objects.requireNonNull(subtotal, "subtotal");
        Objects.requireNonNull(discountAmount, "discountAmount");
        Objects.requireNonNull(taxAmount, "taxAmount");
    }

    public BigDecimal total() {
        BigDecimal shipping = shippingFee == null ? BigDecimal.ZERO : shippingFee;
        return subtotal.subtract(discountAmount).add(taxAmount).add(shipping);
    }
}
