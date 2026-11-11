package com.group5.shipmentservice.shared.config;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxEntry;
import com.group5.common_outbox.OutboxRelay;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "nexus.outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class ShipmentOutboxConfiguration {

    @Bean
    public OutboxRelay shipmentOutboxRelay(JdbcTemplate jdbc, ObjectProvider<JsonMapper> mapper,
            @Qualifier(EventKafka.TEMPLATE) KafkaTemplate<String, EventEnvelope<?>> kafka,
            PlatformTransactionManager transactions,
            @Value("${nexus.outbox.relay.send-timeout:3s}") String sendTimeout) {
        return new ShipmentRelay(jdbc, mapper.getIfAvailable(JsonMapper::shared), kafka, transactions,
                DurationStyle.detectAndParse(sendTimeout));
    }

    static final class ShipmentRelay extends OutboxRelay {
        ShipmentRelay(JdbcTemplate jdbc, JsonMapper mapper, KafkaTemplate<String, EventEnvelope<?>> kafka,
                PlatformTransactionManager transactions, Duration sendTimeout) {
            super(jdbc, mapper, kafka, transactions, sendTimeout);
        }

        @Override
        protected String publicationKey(OutboxEntry entry, EventEnvelope<?> envelope) {
            if (!Topics.SHIPMENT.equals(entry.topic())) {
                return super.publicationKey(entry, envelope);
            }
            if (!(envelope.payload() instanceof JsonNode payload)
                    || !payload.path("orderId").isString()) {
                throw new IllegalArgumentException("Shipment event requires an orderId publication key");
            }
            return UUID.fromString(payload.path("orderId").asString()).toString();
        }
    }
}
