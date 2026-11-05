package com.group5.userservice.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class IamPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(IamTestConfig.class);

    @Test
    void missingSettingsUseTheBuyerRoleAndAllowSelfRegistration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(IamProperties.class);
            IamProperties properties = context.getBean(IamProperties.class);
            assertThat(properties.getDefaultUserRole()).isEqualTo("BUYER");
            assertThat(properties.isAllowSelfRegistration()).isTrue();
        });
    }

    @Test
    void registrationSettingsBindFromConfig() {
        contextRunner
                .withPropertyValues(
                        "nexus.iam.default-user-role=SELLER",
                        "nexus.iam.allow-self-registration=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IamProperties properties = context.getBean(IamProperties.class);
                    assertThat(properties.getDefaultUserRole()).isEqualTo("SELLER");
                    assertThat(properties.isAllowSelfRegistration()).isFalse();
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IamProperties.class)
    static class IamTestConfig {
    }
}
