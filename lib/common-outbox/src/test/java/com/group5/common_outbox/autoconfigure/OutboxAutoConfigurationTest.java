package com.group5.common_outbox.autoconfigure;

import com.group5.common_events.EventKafka;
import com.group5.common_outbox.OutboxRelay;
import com.group5.common_outbox.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OutboxAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
            .withPropertyValues("nexus.outbox.relay.initial-delay=3600000");

    @Test
    void registersTheLibraryForBootAutoConfigurationDiscovery() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()))
                .contains(OutboxAutoConfiguration.class.getName());
    }

    @Test
    void registersTheWriterRelayAndScheduledTaskWhenDatabaseAndEventTemplateArePresent() {
        database().withBean(EventKafka.TEMPLATE, KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxWriter.class).hasSingleBean(OutboxRelay.class);
                    assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
                });
    }

    @Test
    void disablingTheRelayKeepsTheWriterAndDoesNotEnableScheduling() {
        database().withPropertyValues("nexus.outbox.relay.enabled=false")
                .withBean(EventKafka.TEMPLATE, KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .run(context -> assertThat(context).hasSingleBean(OutboxWriter.class)
                        .doesNotHaveBean(OutboxRelay.class).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    void writerDoesNotRequireAnEventKafkaTemplate() {
        database().withBean("kafkaTemplate", KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .run(context -> assertThat(context).hasSingleBean(OutboxWriter.class).doesNotHaveBean(OutboxRelay.class));
    }

    @Test
    void doesNotActivateWithoutDatabaseInfrastructure() {
        runner.run(context -> assertThat(context).doesNotHaveBean(OutboxWriter.class).doesNotHaveBean(OutboxRelay.class));
        runner.withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .run(context -> assertThat(context).doesNotHaveBean(OutboxWriter.class).doesNotHaveBean(OutboxRelay.class));
    }

    @Test
    void backsOffWhenTransactionManagerSelectionIsAmbiguous() {
        database().withBean("otherTransactionManager", PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .run(context -> assertThat(context).doesNotHaveBean(OutboxWriter.class).doesNotHaveBean(OutboxRelay.class));
    }

    @Test
    void keepsApplicationProvidedWriterAndRelay() {
        OutboxWriter writer = mock(OutboxWriter.class);
        OutboxRelay relay = mock(OutboxRelay.class);
        database().withBean(EventKafka.TEMPLATE, KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .withBean(OutboxWriter.class, () -> writer)
                .withBean(OutboxRelay.class, () -> relay)
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxWriter.class).hasSingleBean(OutboxRelay.class);
                    assertThat(context.getBean(OutboxWriter.class)).isSameAs(writer);
                    assertThat(context.getBean(OutboxRelay.class)).isSameAs(relay);
                });
    }

    private ApplicationContextRunner database() {
        return runner.withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean("transactionManager", PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class));
    }
}
