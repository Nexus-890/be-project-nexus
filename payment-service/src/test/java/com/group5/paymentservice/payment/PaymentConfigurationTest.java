package com.group5.paymentservice.payment;

import com.group5.paymentservice.shared.config.PaymentProperties;
import com.group5.paymentservice.shared.config.SepayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues("nexus.payment.provider=SEPAY", "nexus.payment.sepay.bank-id=970415",
                    "nexus.payment.sepay.bank-account=0000000002",
                    "nexus.payment.sepay.account-name=NEXUS TEST", "nexus.payment.sepay.vietqr-template=compact");

    @Test
    void configServerYamlResolvesHostSettingsWithoutEmbeddingBankDetails() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("../config-service/src/main/resources/config/payment-service.yml"));
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addLast(
                        new PropertiesPropertySource("payment-yaml", yaml.getObject())))
                .withPropertyValues("SEPAY_BANK_ACCOUNT=0000000002", "SEPAY_ACCOUNT_NAME=NEXUS TEST")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SepayProperties.class)).isEqualTo(
                            new SepayProperties("970415", "0000000002", "NEXUS TEST", "compact", "SEVQR"));
                    assertThat(context.getBean(PaymentProperties.class).retryLimit()).isEqualTo(3);
                });
    }

    @Test
    void defaultsRetryLimitToThreeAndPreservesLeadingAccountZeros() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PaymentProperties.class).retryLimit()).isEqualTo(3);
            assertThat(context.getBean(SepayProperties.class).bankAccount()).isEqualTo("0000000002");
            assertThat(context.getBean(SepayProperties.class).transferContentPrefix()).isEmpty();
        });
    }

    @Test
    void configServerYamlAllowsBankAndEmptyPrefixOverride() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("../config-service/src/main/resources/config/payment-service.yml"));
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addLast(
                        new PropertiesPropertySource("payment-yaml", yaml.getObject())))
                .withPropertyValues("SEPAY_BANK_ID=970416", "SEPAY_TRANSFER_CONTENT_PREFIX=",
                        "SEPAY_BANK_ACCOUNT=0000000002", "SEPAY_ACCOUNT_NAME=NEXUS TEST")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SepayProperties.class).bankId()).isEqualTo("970416");
                    assertThat(context.getBean(SepayProperties.class).transferContentPrefix()).isEmpty();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "nexus.payment.retry-limit=0",
            "nexus.payment.sepay.bank-account=",
            "nexus.payment.sepay.bank-account=123/other",
            "nexus.payment.sepay.account-name=",
            "nexus.payment.sepay.bank-id=970415?",
            "nexus.payment.sepay.vietqr-template=../other",
            "nexus.payment.sepay.vietqr-template=compact2",
            "nexus.payment.sepay.transfer-content-prefix=SEVQR&amount=1",
            "nexus.payment.sepay.transfer-content-prefix=ABCDEFGHIJKLMNOPQ"
    })
    void invalidConfigurationFailsStartup(String setting) {
        runner.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({PaymentProperties.class, SepayProperties.class})
    static class PropertiesConfiguration {
    }
}
