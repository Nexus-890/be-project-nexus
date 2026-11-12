package com.group5.inventoryservice.shared.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;

@Component
public class ReservationTimeout {

    private final Environment environment;
    private volatile Duration timeout;

    public ReservationTimeout(InventoryProperties properties, Environment environment) {
        this.environment = environment;
        this.timeout = Duration.ofMinutes(properties.reservationTimeoutMinutes());
    }

    public OffsetDateTime expiresAt(OffsetDateTime createdAt) {
        return createdAt.plus(timeout);
    }

    @EventListener(EnvironmentChangeEvent.class)
    public void reload() {
        InventoryProperties refreshed = Binder.get(environment).bindOrCreate("nexus.inventory", InventoryProperties.class);
        timeout = Duration.ofMinutes(refreshed.reservationTimeoutMinutes());
    }
}
