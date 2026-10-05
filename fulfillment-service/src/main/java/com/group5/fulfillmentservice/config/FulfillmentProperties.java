package com.group5.fulfillmentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexus.fulfillment")
public record FulfillmentProperties(String defaultWarehouseCode) {
}