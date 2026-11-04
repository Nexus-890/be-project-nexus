package com.group5.notificationservice.shared.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "nexus.health")
public record HealthProperties(
        @NotEmpty
        @DefaultValue({"user-service", "catalog-service", "commerce-service", "auction-service", "inventory-service",
                "payment-service", "shipment-service", "rating-service", "notification-service"})
        List<@NotBlank String> services,
        @NotNull @DefaultValue("http://localhost:18080/realms/nexus") URI keycloakRealmUrl,
        @NotNull @DefaultValue("2s") Duration timeout) {
}
