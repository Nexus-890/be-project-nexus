package com.group5.paymentservice.payment;

import com.group5.paymentservice.shared.config.SepayWebhookConfiguration;
import com.group5.paymentservice.shared.config.SepayWebhookProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class SepayWebhookConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class, SepayWebhookConfiguration.class)
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void refusesToStartWithoutASecretOrWithBlankSecret() {
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("nexus.payment.sepay.webhook.secret= ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void configuredSecretIsRedactedFromPropertyDescription() {
        runner.withPropertyValues("nexus.payment.sepay.webhook.secret=test-only-private-value")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SepayWebhookProperties.class).toString())
                            .contains("REDACTED").doesNotContain("test-only-private-value");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SepayWebhookProperties.class)
    static class PropertiesConfiguration { }
}
