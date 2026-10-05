package com.group5.fulfillmentservice.config;

import com.group5.fulfillmentservice.shared.config.FulfillmentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;

import static org.assertj.core.api.Assertions.assertThat;

class FulfillmentPropertiesTest {

    @Test
    void bindsDefaultWarehouseCodeFromConfigFile() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(
                "../config-service/src/main/resources/config/fulfillment-service.yml"
        ));

        var properties = yaml.getObject();
        assertThat(properties).isNotNull();

        var source = new PropertiesPropertySource("fulfillment-config", properties);
        var binder = new Binder(ConfigurationPropertySources.from(source));

        var config = binder.bind(
                "nexus.fulfillment",
                Bindable.of(FulfillmentProperties.class)
        ).get();

        assertThat(config.defaultWarehouseCode()).isEqualTo("WH_001");
    }
}