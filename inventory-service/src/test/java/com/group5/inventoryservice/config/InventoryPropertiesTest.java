package com.group5.inventoryservice.config;

import com.group5.inventoryservice.shared.config.InventoryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryPropertiesTest {

    @Test
    void bindsDefaultWarehouseCodeFromConfigFile() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(
                "../config-service/src/main/resources/config/inventory-service.yml"
        ));

        var properties = yaml.getObject();
        assertThat(properties).isNotNull();

        var source = new PropertiesPropertySource("inventory-config", properties);
        var binder = new Binder(ConfigurationPropertySources.from(source));

        var config = binder.bind(
                "nexus.inventory",
                Bindable.of(InventoryProperties.class)
        ).get();

        assertThat(config.defaultWarehouseCode()).isEqualTo("WH_001");
    }
}