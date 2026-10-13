package com.group5.userservice.shared.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class IamPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(IamTestConfig.class);

    @Test
    void missingSettingsUseTheDefaultThresholdAndLockDuration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(IamProperties.class);
            IamProperties properties = context.getBean(IamProperties.class);
            assertThat(properties.getMaxLoginAttempts()).isEqualTo(5);
            assertThat(properties.getLockDurationMinutes()).isEqualTo(15);
        });
    }

    @Test
    void configuredKebabCaseKeysOverrideTheDefaults() {
        contextRunner
                .withPropertyValues(
                        "nexus.iam.max-login-attempts=3",
                        "nexus.iam.lock-duration-minutes=7")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IamProperties properties = context.getBean(IamProperties.class);
                    assertThat(properties.getMaxLoginAttempts()).isEqualTo(3);
                    assertThat(properties.getLockDurationMinutes()).isEqualTo(7);
                });
    }

    @Test
    void maximumThresholdFitsTheDatabaseSmallintCounter() {
        contextRunner
                .withPropertyValues("nexus.iam.max-login-attempts=32767")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IamProperties.class).getMaxLoginAttempts())
                            .isEqualTo(32767);
                });
    }

    @ParameterizedTest(name = "startup rejects {0}={1}")
    @CsvSource({
            "max-login-attempts, 0, maxLoginAttempts",
            "max-login-attempts, -1, maxLoginAttempts",
            "max-login-attempts, 32768, maxLoginAttempts",
            "lock-duration-minutes, 0, lockDurationMinutes",
            "lock-duration-minutes, -1, lockDurationMinutes"
    })
    void invalidLockoutSettingsFailStartupAndIdentifyTheSetting(
            String key, String value, String field) {
        contextRunner
                .withPropertyValues("nexus.iam." + key + "=" + value)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("nexus.iam")
                            .hasMessageContaining(field);
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IamProperties.class)
    static class IamTestConfig {
    }
}
