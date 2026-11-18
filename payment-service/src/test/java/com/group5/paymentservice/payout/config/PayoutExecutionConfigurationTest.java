package com.group5.paymentservice.payout.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.group5.paymentservice.payout.job.PayoutDispatchJob;
import com.group5.paymentservice.payout.job.PayoutReconcileJob;
import com.group5.paymentservice.payout.service.PayoutDispatchService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class PayoutExecutionConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(Config.class)
                    .withPropertyValues(
                            "nexus.payout.dispatch-cron=0 0 0 1 1 *",
                            "nexus.payout.reconcile-interval=PT1H");

    @Test
    void missingSwitchFailsClosedWithoutRegisteringAutomaticScheduledTasks() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(PayoutDispatchJob.class)
                            .doesNotHaveBean(PayoutReconcileJob.class);
                    assertThat(
                                    context.getBean(ScheduledAnnotationBeanPostProcessor.class)
                                            .getScheduledTasks())
                            .isEmpty();
                    verifyNoInteractions(context.getBean(PayoutDispatchService.class));
                    assertThat(context.getBean(PayoutProperties.class).isJobsEnabled()).isFalse();
                    assertThat(context.getBean(ManualPayoutProperties.class).isEnabled()).isFalse();
                });
    }

    @Test
    void manualProfileRegistersNoAutomaticTasks() {
        runner.withPropertyValues(
                        "nexus.payout.execution-mode=MANUAL",
                        "nexus.payout.manual.enabled=true",
                        "nexus.payout.jobs-enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(
                                            context.getBean(
                                                            ScheduledAnnotationBeanPostProcessor
                                                                    .class)
                                                    .getScheduledTasks())
                                    .isEmpty();
                            verifyNoInteractions(context.getBean(PayoutDispatchService.class));
                        });
    }

    @Test
    void legacyAutoNeedsAnExplicitOptInAndRegistersBothTasks() {
        runner.withPropertyValues(
                        "nexus.payout.execution-mode=AUTO",
                        "nexus.payout.jobs-enabled=true",
                        "nexus.payout.manual.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context)
                                    .hasSingleBean(PayoutDispatchJob.class)
                                    .hasSingleBean(PayoutReconcileJob.class);
                            assertThat(
                                            context.getBean(
                                                            ScheduledAnnotationBeanPostProcessor
                                                                    .class)
                                                    .getScheduledTasks())
                                    .hasSize(2);
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTO", "MANUAL"})
    void bothDisabledAreAValidPauseForEitherNewPayoutMode(String mode) {
        runner.withPropertyValues(
                        "nexus.payout.execution-mode=" + mode,
                        "nexus.payout.jobs-enabled=false",
                        "nexus.payout.manual.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTO", "MANUAL"})
    void bothEnabledFailStartup(String mode) {
        runner.withPropertyValues(
                        "nexus.payout.execution-mode=" + mode,
                        "nexus.payout.jobs-enabled=true",
                        "nexus.payout.manual.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledExecutorMustMatchTheNewPayoutMode() {
        runner.withPropertyValues(
                        "nexus.payout.execution-mode=AUTO", "nexus.payout.manual.enabled=true")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(
                        "nexus.payout.execution-mode=MANUAL", "nexus.payout.jobs-enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableConfigurationProperties({PayoutProperties.class, ManualPayoutProperties.class})
    @Import({PayoutExecutionConfiguration.class, PayoutDispatchJob.class, PayoutReconcileJob.class})
    static class Config {
        @Bean
        PayoutDispatchService dispatcher() {
            return mock(PayoutDispatchService.class);
        }
    }
}
