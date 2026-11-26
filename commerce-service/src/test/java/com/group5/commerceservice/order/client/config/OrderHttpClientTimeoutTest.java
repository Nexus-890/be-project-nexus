package com.group5.commerceservice.order.client.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderHttpClientTimeoutTest {
    @Test
    void inventoryCallsKeepTheTwoSecondCap() {
        assertThatThrownBy(() -> new OrderHttpClient(3500)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shippingQuotesMayWaitLongerThanShipmentsOwnBudget() {
        assertThatCode(() -> new OrderHttpClient(null, null, List.of(), 3500,
                OrderHttpClient.SHIPPING_MAX_TIMEOUT_MILLIS).close()).doesNotThrowAnyException();
        assertThatThrownBy(() -> new OrderHttpClient(null, null, List.of(), 4001,
                OrderHttpClient.SHIPPING_MAX_TIMEOUT_MILLIS)).isInstanceOf(IllegalArgumentException.class);
    }
}
