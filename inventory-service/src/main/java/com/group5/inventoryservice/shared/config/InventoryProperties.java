package com.group5.inventoryservice.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexus.inventory")
public record InventoryProperties(String defaultWarehouseCode) {
}