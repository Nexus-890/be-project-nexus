package com.group5.paymentservice.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class PaymentConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock paymentClock() {
        return Clock.systemUTC();
    }

    @Bean
    CommonErrorHandler paymentEventErrorHandler() {
        return new DefaultErrorHandler((record, failure) -> {
            throw new IllegalStateException("Payment event requires retry or operator replay", failure);
        }, new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
