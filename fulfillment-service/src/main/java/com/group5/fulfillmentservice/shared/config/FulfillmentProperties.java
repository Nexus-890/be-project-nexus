package com.group5.fulfillmentservice.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexus.fulfillment")
public record FulfillmentProperties(String defaultWarehouseCode) {
}