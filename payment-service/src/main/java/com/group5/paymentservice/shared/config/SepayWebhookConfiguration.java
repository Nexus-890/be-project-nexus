package com.group5.paymentservice.shared.config;

import com.group5.paymentservice.payment.provider.sepay.WebhookSignatureVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class SepayWebhookConfiguration {
    @Bean
    WebhookSignatureVerifier sepayWebhookSignatureVerifier(SepayWebhookProperties properties, Clock clock) {
        return new WebhookSignatureVerifier(properties.secret(), clock);
    }
}
