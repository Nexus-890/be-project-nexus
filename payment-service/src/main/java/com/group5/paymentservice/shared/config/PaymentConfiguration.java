package com.group5.paymentservice.shared.config;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class PaymentConfiguration {
    static final String PAYMENT_DLQ_TOPIC = "nexus.payment.dlq";
    private static final long RETRY_INTERVAL_MS = 1000L;

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock paymentClock() {
        return Clock.systemUTC();
    }

    @Bean
    DefaultKafkaProducerFactory<String, Object> paymentDlqProducerFactory(
            KafkaProperties properties, ObjectProvider<JsonMapper> jsonMapper) {
        var json = new JacksonJsonSerializer<EventEnvelope<?>>(jsonMapper.getIfAvailable(JsonMapper::shared));
        json.setAddTypeInfo(false);
        // Deserialization failures contain raw bytes; listener failures contain an envelope.
        var serializer = new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(), EventEnvelope.class, json));
        return new DefaultKafkaProducerFactory<>(properties.buildProducerProperties(),
                new StringSerializer(), serializer);
    }

    @Bean
    KafkaTemplate<String, Object> paymentDlqKafkaTemplate(
            @Qualifier("paymentDlqProducerFactory") DefaultKafkaProducerFactory<String, Object> factory) {
        return new KafkaTemplate<>(factory);
    }

    @Bean
    DeadLetterPublishingRecoverer paymentDeadLetterRecoverer(
            @Qualifier("paymentDlqKafkaTemplate") KafkaTemplate<String, Object> dlq) {
        // Let the producer choose a DLQ partition; preserve the source partition in headers.
        var recoverer = new DeadLetterPublishingRecoverer(dlq,
                (record, failure) -> new TopicPartition(PAYMENT_DLQ_TOPIC, -1));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        return recoverer;
    }

    @Bean
    CommonErrorHandler paymentEventErrorHandler(
            PaymentProperties payments, DeadLetterPublishingRecoverer paymentDeadLetterRecoverer) {
        return new DefaultErrorHandler(paymentDeadLetterRecoverer,
                new FixedBackOff(RETRY_INTERVAL_MS, payments.retryLimit()));
    }

    @Bean
    SmartInitializingSingleton paymentListenerErrorHandlerCustomizer(
            @Qualifier(EventKafka.LISTENER_CONTAINER_FACTORY)
            ConcurrentKafkaListenerContainerFactory<Object, Object> factory,
            CommonErrorHandler paymentEventErrorHandler) {
        // The shared event listener factory is built in common-events; pin the
        // payment retry/DLQ policy on it before any listener container starts.
        return () -> factory.setCommonErrorHandler(paymentEventErrorHandler);
    }

}
