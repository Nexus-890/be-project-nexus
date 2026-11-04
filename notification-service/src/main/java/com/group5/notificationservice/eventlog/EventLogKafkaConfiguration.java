package com.group5.notificationservice.eventlog;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

@Configuration
public class EventLogKafkaConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> eventLogListenerContainerFactory(
            KafkaProperties properties, ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            EventLogRecorder recorder) {
        var config = properties.buildConsumerProperties();
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, new DefaultKafkaConsumerFactory<>(config));
        // Invalid JSON/envelopes must not block the partition. Preserve the raw
        // record durably before advancing; a failed rejection write is not recovery.
        var errors = new DefaultErrorHandler((record, failure) -> recorder.reject(
                record.topic(), record.partition(), record.offset(), (String) record.key(), (String) record.value(),
                org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure).getClass().getSimpleName()),
                new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
        // Database/transaction failures retry; all other failures are quarantined.
        errors.setClassifications(Map.of(DataAccessException.class, true, TransactionException.class, true), false);
        factory.setCommonErrorHandler(errors);
        return factory;
    }
}
