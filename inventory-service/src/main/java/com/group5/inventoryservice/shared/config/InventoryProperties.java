package com.group5.inventoryservice.shared.config;

import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "nexus.inventory")
public record InventoryProperties(
        String defaultWarehouseCode,
        @AssertFalse(message = "ALLOW_OVERSELL must remain false: available stock cannot go below zero")
        boolean allowOversell,
        @Positive Integer reservationTimeoutMinutes) {

    public static final int DEFAULT_RESERVATION_TIMEOUT_MINUTES = 30;

    public InventoryProperties {
        if (reservationTimeoutMinutes == null) {
            reservationTimeoutMinutes = DEFAULT_RESERVATION_TIMEOUT_MINUTES;
        }
    }
}
