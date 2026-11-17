package com.group5.commerceservice.cart.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.group5.commerceservice.promotion.VoucherPricing.Quote;

import java.util.List;
import java.math.BigDecimal;

public record CartPricingResponse(
        BigDecimal subtotal, BigDecimal taxAmount, BigDecimal discountAmount,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal estimatedShippingFee, BigDecimal total,
        List<Quote> vouchers) { }
