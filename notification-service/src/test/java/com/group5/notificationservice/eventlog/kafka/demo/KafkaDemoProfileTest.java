package com.group5.notificationservice.eventlog.kafka.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class KafkaDemoProfileTest {

    @Test
    void demoComponentsAreNotLoadedInKafkaSmokeProfile() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("kafka-smoke");
            context.register(KafkaDemoListener.class, KafkaDemoMessageRepository.class, KafkaDemoMessageController.class);
            context.refresh();

            assertThat(context.getBeansOfType(KafkaDemoListener.class)).isEmpty();
            assertThat(context.getBeansOfType(KafkaDemoMessageRepository.class)).isEmpty();
            assertThat(context.getBeansOfType(KafkaDemoMessageController.class)).isEmpty();
        }
    }
}
