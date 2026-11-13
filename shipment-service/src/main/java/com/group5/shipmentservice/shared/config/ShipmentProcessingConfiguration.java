package com.group5.shipmentservice.shared.config;

import com.group5.common_events.EventKafka;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ShipmentProcessingConfiguration {
    @Bean
    SmartInitializingSingleton shipmentEventRetryPolicy(
            @Qualifier(EventKafka.LISTENER_CONTAINER_FACTORY) ConcurrentKafkaListenerContainerFactory<Object, Object> factory) {
        return () -> {
            var handler = new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
            handler.setClassifications(Map.of(), true);
            factory.setCommonErrorHandler(handler);
        };
    }
}
