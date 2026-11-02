package com.group5.paymentservice.shared.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "nexus.payment")
public record PaymentProperties(
        @NotBlank(message = "nexus.payment.provider must not be blank")
        String provider,
        @DefaultValue("3") @Min(1) int retryLimit
) {}