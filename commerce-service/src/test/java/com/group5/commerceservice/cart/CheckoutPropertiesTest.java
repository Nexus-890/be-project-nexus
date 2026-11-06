package com.group5.commerceservice.cart;

import com.group5.commerceservice.shared.config.CheckoutProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withInitializer(context -> {
                var yaml = new YamlPropertiesFactoryBean();
                yaml.setResources(new FileSystemResource("../config-service/src/main/resources/config/commerce-service.yml"));
                context.getEnvironment().getPropertySources().addLast(
                        new PropertiesPropertySource("config-server", yaml.getObject()));
            });

    @Test void configServerDefaultsAndEnvironmentOverridesBind() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CheckoutProperties.class).isPriceRevalidation()).isTrue();
            assertThat(context.getBean(CheckoutProperties.class).getFreeShippingThreshold()).isEqualByComparingTo("1000000");
        });
        runner.withPropertyValues("FREE_SHIPPING_THRESHOLD=500000", "CHECKOUT_PRICE_REVALIDATION=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CheckoutProperties.class).getFreeShippingThreshold()).isEqualByComparingTo("500000");
                });
    }

    @Test void disabledRevalidationAndNegativeThresholdFailAtStartup() {
        runner.withPropertyValues("CHECKOUT_PRICE_REVALIDATION=false")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("FREE_SHIPPING_THRESHOLD=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CheckoutProperties.class)
    static class Config { }
}
