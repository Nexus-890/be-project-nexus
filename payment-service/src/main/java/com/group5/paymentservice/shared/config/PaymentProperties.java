package com.group5.paymentservice.shared.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "nexus.payment")
public record PaymentProperties(
        @NotBlank(message = "nexus.payment.provider must not be blank")
        String provider
) {}