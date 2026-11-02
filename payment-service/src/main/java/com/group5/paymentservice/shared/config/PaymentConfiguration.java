package com.group5.paymentservice.shared.config;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
public class PaymentConfiguration {
    private static final Logger log = LoggerFactory.getLogger(PaymentConfiguration.class);

    static final String PAYMENT_DLQ_TOPIC = "nexus.payment.dlq";
    private static final long RETRY_INTERVAL_MS = 1000L;
    private static final long DLQ_SEND_TIMEOUT_SECONDS = 10L;

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock paymentClock() {
        return Clock.systemUTC();
    }

    @Bean
    CommonErrorHandler paymentEventErrorHandler(
            PaymentProperties payments,
            @Qualifier(EventKafka.TEMPLATE) KafkaTemplate<String, EventEnvelope<?>> events) {
        return new DefaultErrorHandler(
                (ConsumerRecord<?, ?> record, Exception failure) -> sendToDlq(events, record, failure),
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

    private void sendToDlq(KafkaTemplate<String, EventEnvelope<?>> events,
                           ConsumerRecord<?, ?> record, Exception failure) {
        log.warn("PAYMENT_EVENT_DLQ topic={} partition={} offset={} key={} error={}",
                record.topic(), record.partition(), record.offset(), record.key(),
                failure.toString());
        try {
            events.send(PAYMENT_DLQ_TOPIC, (String) record.key(),
                    (EventEnvelope<?>) record.value()).get(DLQ_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception publishFailure) {
            throw new IllegalStateException("Payment DLQ publish failed; record will be retried", publishFailure);
        }
    }
}
