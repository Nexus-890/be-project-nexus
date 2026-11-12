package com.group5.inventoryservice.shared.config;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelope;
import com.group5.inventoryservice.inventory.InvalidOrderEventException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class InventoryKafkaConfiguration {

    public static final String DEAD_LETTER_TOPIC = "nexus.inventory.dlq";
    static final int MAX_RETRIES = 5;
    static final long INITIAL_RETRY_INTERVAL_MS = 1000L;
    static final long MAX_RETRY_INTERVAL_MS = 16000L;

    @Bean
    DefaultKafkaProducerFactory<String, Object> inventoryDeadLetterProducerFactory(
            KafkaProperties properties, ObjectProvider<JsonMapper> jsonMapper) {
        JacksonJsonSerializer<EventEnvelope<?>> json =
                new JacksonJsonSerializer<>(jsonMapper.getIfAvailable(JsonMapper::shared));
        json.setAddTypeInfo(false);
        DelegatingByTypeSerializer values = new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(), EventEnvelope.class, json));
        return new DefaultKafkaProducerFactory<>(properties.buildProducerProperties(), new StringSerializer(),
                values);
    }

    @Bean
    KafkaTemplate<String, Object> inventoryDeadLetterTemplate(
            @Qualifier("inventoryDeadLetterProducerFactory") DefaultKafkaProducerFactory<String, Object> factory) {
        return new KafkaTemplate<>(factory);
    }

    @Bean
    DeadLetterPublishingRecoverer inventoryDeadLetterRecoverer(
            @Qualifier("inventoryDeadLetterTemplate") KafkaTemplate<String, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, failure) -> new TopicPartition(DEAD_LETTER_TOPIC, -1));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        return recoverer;
    }

    @Bean
    CommonErrorHandler inventoryEventErrorHandler(DeadLetterPublishingRecoverer inventoryDeadLetterRecoverer) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_RETRY_INTERVAL_MS);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(MAX_RETRY_INTERVAL_MS);
        DefaultErrorHandler handler = new DefaultErrorHandler(inventoryDeadLetterRecoverer, backOff);
        handler.addNotRetryableExceptions(InvalidOrderEventException.class, DomainException.class);
        return handler;
    }
}
