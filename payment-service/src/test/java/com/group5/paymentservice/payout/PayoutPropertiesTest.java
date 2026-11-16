package com.group5.paymentservice.payout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test void defaultsToZeroFeeAndAnExplicitSandboxProvider() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(PayoutProperties.class);
            assertThat(properties.getPlatformFeeRate()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(properties.getProvider()).isEqualTo("sandbox");
            assertThat(properties.getMaxAttempts()).isPositive();
        });
    }

    @Test void configServerYamlKeepsZeroFeeAndAllowsDeploymentRateOverride() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("../config-service/src/main/resources/config/payment-service.yml"));
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addLast(
                        new PropertiesPropertySource("payment-yaml", yaml.getObject())))
                .withPropertyValues("PAYOUT_PLATFORM_FEE_RATE=0.0125")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PayoutProperties.class).getPlatformFeeRate()).isEqualByComparingTo("0.0125");
                    assertThat(context.getBean(PayoutProperties.class).getProvider()).isEqualTo("sandbox");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "nexus.payout.platform-fee-rate=-0.0001",
            "nexus.payout.platform-fee-rate=1.0001",
            "nexus.payout.platform-fee-rate=0.12345",
            "nexus.payout.max-attempts=0",
            "nexus.payout.retry-backoff=0s",
            "nexus.payout.reconcile-interval=-1s",
            "nexus.payout.provider-timeout=0s",
            "nexus.payout.provider=unconfigured-provider",
            "nexus.payout.sandbox-outcome=UNKNOWN_OUTCOME"
    })
    void unsafeFeeRetryOrProviderSettingsFailStartup(String setting) {
        runner.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0125", "1.0000"})
    void fullSupportedFeeRangeIsAccepted(String rate) {
        runner.withPropertyValues("nexus.payout.platform-fee-rate=" + rate).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PayoutProperties.class).getPlatformFeeRate()).isEqualByComparingTo(rate);
        });
    }

    @Test void httpProviderCannotStartWithoutItsOutboundCredentialsAndCallbackSecret() {
        runner.withPropertyValues("nexus.payout.provider=http", "nexus.payout.provider-url=https://payout.example.test")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("nexus.payout.provider=http", "nexus.payout.provider-url=https://payout.example.test",
                        "nexus.payout.provider-token=test-only-token", "nexus.payout.callback-secret=test-only-callback")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("nexus.payout.provider=http", "nexus.payout.provider-url=http://payout.example.test",
                        "nexus.payout.provider-token=test-only-token", "nexus.payout.callback-secret=test-only-callback")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PayoutProperties.class)
    static class Config { }
}
