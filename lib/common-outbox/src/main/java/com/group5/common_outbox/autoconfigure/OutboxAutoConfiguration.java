package com.group5.common_outbox.autoconfigure;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.autoconfigure.EventsAutoConfiguration;
import com.group5.common_outbox.OutboxRelay;
import com.group5.common_outbox.OutboxWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration(
        after = {EventsAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class},
        afterName = "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
@ConditionalOnBean(JdbcTemplate.class)
@ConditionalOnSingleCandidate(PlatformTransactionManager.class)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnSingleCandidate(JdbcTemplate.class)
    public OutboxWriter outboxWriter(JdbcTemplate jdbc, ObjectProvider<JsonMapper> jsonMapper,
                                     PlatformTransactionManager transactionManager) {
        return new OutboxWriter(jdbc, jsonMapper.getIfAvailable(JsonMapper::shared), transactionManager);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnSingleCandidate(JdbcTemplate.class)
    @ConditionalOnBean(name = EventKafka.TEMPLATE)
    @ConditionalOnProperty(prefix = "nexus.outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
    @EnableScheduling
    static class RelayConfiguration {

        @Bean
        @ConditionalOnMissingBean
        OutboxRelay outboxRelay(JdbcTemplate jdbc, ObjectProvider<JsonMapper> jsonMapper,
                                @Qualifier(EventKafka.TEMPLATE) KafkaTemplate<String, EventEnvelope<?>> kafka,
                                PlatformTransactionManager transactionManager,
                                @Value("${nexus.outbox.relay.send-timeout:10s}") String sendTimeout) {
            return new OutboxRelay(jdbc, jsonMapper.getIfAvailable(JsonMapper::shared), kafka,
                    transactionManager, DurationStyle.detectAndParse(sendTimeout));
        }
    }
}
