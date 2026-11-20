package com.group5.commerceservice.checkout.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.group5.commerceservice.cart.dto.response.CartIssue;
import com.group5.commerceservice.promotion.VoucherPricing.Quote;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CheckoutPreviewResponse(
        List<Item> items, BigDecimal subtotal,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal shippingFee,
        BigDecimal taxAmount, BigDecimal discountAmount, BigDecimal total, List<CartIssue> issues,
        List<Quote> vouchers, List<ShippingOption> shippingOptions,
        @JsonInclude(JsonInclude.Include.ALWAYS) String selectedCarrier) {
    public CheckoutPreviewResponse(List<Item> items, BigDecimal subtotal, BigDecimal shippingFee, BigDecimal taxAmount,
                                   BigDecimal discountAmount, BigDecimal total, List<CartIssue> issues,
                                   List<Quote> vouchers) {
        this(items, subtotal, shippingFee, taxAmount, discountAmount, total, issues, vouchers, List.of(), null);
    }

    public record ShippingOption(String carrier, String carrierName, BigDecimal fee,
                                 OffsetDateTime estimatedDeliveryAt, boolean recommended) { }

    public record Item(UUID itemId, UUID productId, UUID skuId, int quantity,
                       BigDecimal unitPrice, BigDecimal lineTotal) { }
}
