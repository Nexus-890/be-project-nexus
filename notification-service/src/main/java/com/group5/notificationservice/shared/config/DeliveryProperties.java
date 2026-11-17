package com.group5.notificationservice.shared.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "nexus.notification.delivery")
@Component
@RefreshScope
public class DeliveryProperties {

    @Min(1)
    private int retryLimit = 3;

    @NotNull
    private Duration baseDelay = Duration.ofSeconds(1);

    @NotNull
    private Duration maxDelay = Duration.ofSeconds(30);

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double jitter = 0.2;

    @NotNull
    private Duration pollInterval = Duration.ofSeconds(2);

    @NotNull
    private Duration attemptTimeout = Duration.ofMinutes(2);

    @Min(1)
    private int batchSize = 20;

    @NotBlank
    private String from = "no-reply@nexus.local";
}
