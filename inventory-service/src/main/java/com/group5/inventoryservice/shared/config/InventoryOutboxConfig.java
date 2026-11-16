package com.group5.inventoryservice.shared.config;

import com.group5.common_outbox.OutboxEventKeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class InventoryOutboxConfig {
    @Bean
    OutboxEventKeyResolver inventoryOutboxKeyResolver() {
        return envelope -> switch (envelope.eventType()) {
            case "InventoryReserved", "InventoryReleased", "InventoryCommitted" ->
                    envelope.payload().required("auctionId").asString();
            default -> envelope.aggregateId();
        };
    }
}
