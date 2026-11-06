package com.group5.commerceservice.cart.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

public record CartPricingResponse(
        BigDecimal subtotal, BigDecimal taxAmount, BigDecimal discountAmount,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal estimatedShippingFee, BigDecimal total) { }
