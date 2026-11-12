package com.group5.inventoryservice.config;

import com.group5.inventoryservice.shared.config.InventoryProperties;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryPropertiesTest {

    @Test
    void bindsTheConfigServerFileWithA30MinuteTimeoutThatItsEnvironmentVariableOverrides() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(
                "../config-service/src/main/resources/config/inventory-service.yml"
        ));

        var properties = yaml.getObject();
        assertThat(properties).isNotNull();
        assertThat(properties).containsKey("nexus.inventory.reservation-timeout-minutes");

        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new PropertiesPropertySource("inventory-config", properties));
        var binder = Binder.get(environment);

        var config = binder.bind(
                "nexus.inventory",
                Bindable.of(InventoryProperties.class)
        ).get();

        assertThat(config.defaultWarehouseCode()).isEqualTo("WH_001");
        assertThat(config.allowOversell()).isFalse();
        assertThat(config.reservationTimeoutMinutes()).isEqualTo(30);

        environment.getPropertySources().addFirst(new MapPropertySource("env",
                Map.of("INVENTORY_RESERVATION_TIMEOUT_MINUTES", "5")));
        assertThat(binder.bind("nexus.inventory", Bindable.of(InventoryProperties.class)).get()
                .reservationTimeoutMinutes()).isEqualTo(5);
    }

    @Test
    void aMissingTimeoutIs30MinutesAndOneBelowAMinuteIsRejected() {
        assertThat(new InventoryProperties("WH_001", false, null).reservationTimeoutMinutes())
                .isEqualTo(InventoryProperties.DEFAULT_RESERVATION_TIMEOUT_MINUTES);
        assertThat(new InventoryProperties("WH_001", false, 1).reservationTimeoutMinutes()).isEqualTo(1);
        for (int minutes : new int[] {0, -5}) {
            assertThatThrownBy(() -> new InventoryProperties("WH_001", false, minutes))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("nexus.inventory.reservation-timeout-minutes must be at least 1");
        }
    }

    @Test
    void oversellCannotBeTurnedOn() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(new InventoryProperties("WH_001", true, 30));

            assertThat(violations).extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("allowOversell");
            assertThat(factory.getValidator().validate(new InventoryProperties("WH_001", false, null))).isEmpty();
        }
    }
}