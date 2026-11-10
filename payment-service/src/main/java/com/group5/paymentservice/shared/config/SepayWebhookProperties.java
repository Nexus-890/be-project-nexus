package com.group5.paymentservice.shared.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "nexus.payment.sepay.webhook")
public record SepayWebhookProperties(@NotBlank String secret) {
    @Override
    public String toString() {
        return "SepayWebhookProperties[secret=REDACTED]";
    }
}
