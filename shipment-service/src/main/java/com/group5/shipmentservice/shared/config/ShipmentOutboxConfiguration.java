package com.group5.shipmentservice.shared.config;

import com.group5.common_outbox.OutboxEventKeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class ShipmentOutboxConfiguration {
    @Bean
    public OutboxEventKeyResolver shipmentOutboxEventKeyResolver() {
        return event -> {
            if (!"ShipmentCreated".equals(event.eventType())) return event.aggregateId();
            JsonNode orderId = event.payload().path("orderId");
            if (!orderId.isString()) {
                throw new IllegalArgumentException("Shipment event requires an orderId publication key");
            }
            UUID parsed = UUID.fromString(orderId.asString());
            if (!parsed.toString().equals(orderId.asString())) {
                throw new IllegalArgumentException("Shipment orderId must be a canonical UUID");
            }
            return parsed.toString();
        };
    }
}
