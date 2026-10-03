package com.group5.notificationservice.eventlog.kafka.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class KafkaSmokeProfileInactiveTest {

    @Test
    void smokeComponentsAreNotLoadedWithoutKafkaSmokeProfile() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("test");
            context.register(KafkaSmokeListener.class, KafkaSmokeMessageStore.class, KafkaSmokeMessageController.class);
            context.refresh();

            assertThat(context.getBeansOfType(KafkaSmokeListener.class)).isEmpty();
            assertThat(context.getBeansOfType(KafkaSmokeMessageStore.class)).isEmpty();
            assertThat(context.getBeansOfType(KafkaSmokeMessageController.class)).isEmpty();
        }
    }
}
