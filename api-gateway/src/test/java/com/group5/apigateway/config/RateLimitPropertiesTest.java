package com.group5.apigateway.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPropertiesTest {

    @Test
    void defaultsRequestsPerMinuteToOneHundred() {
        RateLimitProperties properties = new RateLimitProperties();

        assertThat(properties.getRequestsPerMinute()).isEqualTo(100);
    }

    @Test
    void acceptsPositiveRequestsPerMinute() {
        RateLimitProperties properties = new RateLimitProperties();

        properties.setRequestsPerMinute(150);

        assertThat(properties.getRequestsPerMinute()).isEqualTo(150);
    }

    @Test
    void rejectsZeroRequestsPerMinute() {
        RateLimitProperties properties = new RateLimitProperties();

        assertThatThrownBy(() -> properties.setRequestsPerMinute(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requests-per-minute");
    }

    @Test
    void rejectsNegativeRequestsPerMinute() {
        RateLimitProperties properties = new RateLimitProperties();

        assertThatThrownBy(() -> properties.setRequestsPerMinute(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requests-per-minute");
    }
}
