package com.group5.commerceservice.shared.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PriceBreakdownTest {

    @Test
    void totalIsSubtotalMinusDiscountPlusTaxPlusShipping() {
        PriceBreakdown price = new PriceBreakdown(new BigDecimal("300000.00"), new BigDecimal("20000.00"),
                new BigDecimal("15000.00"), new BigDecimal("30000.00"));

        assertThat(price.total()).isEqualByComparingTo("325000.00");
    }

    @Test
    void aShippingFeeNotQuotedYetCountsAsZero() {
        PriceBreakdown price = new PriceBreakdown(new BigDecimal("300000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                null);

        assertThat(price.total()).isEqualByComparingTo("300000.00");
    }
}
