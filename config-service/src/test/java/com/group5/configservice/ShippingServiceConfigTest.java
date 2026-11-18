package com.group5.configservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ShippingServiceConfigTest {
    @Test
    void onlyAddressReadsArePublicAtGatewayAndShipmentService() {
        List<String> paths = List.of("/api/v1/shipping/provinces", "/api/v1/shipping/wards");
        for (String service : List.of("api-gateway", "shipment-service")) {
            Properties properties = yaml(service);
            for (String path : paths) {
                assertThat(properties.stringPropertyNames()).anySatisfy(key -> {
                    assertThat(key).startsWith("nexus.security.public-endpoints[").endsWith(".path");
                    assertThat(properties.getProperty(key)).isEqualTo(path);
                    assertThat(properties.getProperty(key.replace(".path", ".method"))).isEqualTo("GET");
                });
            }
            assertThat(properties.stringPropertyNames().stream()
                    .filter(key -> key.startsWith("nexus.security.public-endpoints[") && key.endsWith(".path"))
                    .map(properties::getProperty))
                    .doesNotContain("/api/v1/shipping/**", "/api/v1/shipping/quotes", "/api/v1/carriers",
                            "/api/v1/shipping/districts");
        }
    }

    @Test
    void shippingDefaultsAndOverridesUseEnvironmentWithoutGhnTokenProperty() {
        Properties properties = yaml("shipment-service");
        assertThat(properties.stringPropertyNames()).noneMatch(key -> key.toLowerCase().contains("token"));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new PropertiesPropertySource("shipping", properties));
        assertThat(environment.getProperty("nexus.shipping.carrier")).isEqualTo("NONE");
        assertThat(environment.getProperty("nexus.shipping.quote-carrier")).isEqualTo("NONE");
        assertThat(environment.getProperty("nexus.shipping.booking-carrier")).isEqualTo("NONE");
        assertThat(environment.getProperty("nexus.shipping.api-timeout-ms")).isEqualTo("3000");
        assertThat(environment.getProperty("nexus.shipping.retry-limit")).isEqualTo("3");
        assertThat(environment.getProperty("nexus.shipping.catalog-base-url")).isEmpty();
        assertThat(environment.getProperty("nexus.shipping.ghn.http-timeout-ms")).isEqualTo("1500");
        assertThat(environment.getProperty("nexus.shipping.ghn.master-data-cache-ttl-ms")).isEqualTo("300000");
        environment.getPropertySources().addFirst(new MapPropertySource("deployment", Map.of(
                "SHIPPING_CARRIER", "GHN", "SHIPPING_API_TIMEOUT_MS", "1200", "SHIPPING_RETRY_LIMIT", "2",
                "FREE_SHIPPING_THRESHOLD", "500000", "SHIPPING_DEFAULT_WAREHOUSE_ID", "11111111-1111-4111-8111-111111111111",
                "SHIPPING_PICKUP_WARD_CODE", "1003646", "GHN_PAYMENT_TYPE_ID", "2",
                "GHN_REQUIRED_NOTE", "CHOXEMHANGKHONGTHU")));
        assertThat(environment.getProperty("nexus.shipping.carrier")).isEqualTo("GHN");
        assertThat(environment.getProperty("nexus.shipping.quote-carrier")).isEqualTo("GHN");
        assertThat(environment.getProperty("nexus.shipping.booking-carrier")).isEqualTo("GHN");
        assertThat(environment.getProperty("nexus.shipping.api-timeout-ms")).isEqualTo("1200");
        assertThat(environment.getProperty("nexus.shipping.retry-limit")).isEqualTo("2");
        assertThat(environment.getProperty("nexus.shipping.free-shipping-threshold")).isEqualTo("500000");
        assertThat(environment.getProperty("nexus.shipping.default-pickup.ward-code")).isEqualTo("1003646");
        assertThat(environment.getProperty("nexus.shipping.ghn.payment-type-id")).isEqualTo("2");
        assertThat(environment.getProperty("nexus.shipping.ghn.required-note")).isEqualTo("CHOXEMHANGKHONGTHU");
        environment.getPropertySources().addFirst(new MapPropertySource("catalog-local", Map.of(
                "SHIPPING_CATALOG_BASE_URL", "http://127.0.0.1:8082")));
        assertThat(environment.getProperty("nexus.shipping.catalog-base-url")).isEqualTo("http://127.0.0.1:8082");
    }

    @Test
    void quoteAndBookingDeploymentSettingsOverrideLegacyIndependently() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new PropertiesPropertySource("shipping", yaml("shipment-service")));
        environment.getPropertySources().addFirst(new MapPropertySource("deployment", Map.of(
                "SHIPPING_CARRIER", "GHN", "SHIPPING_BOOKING_CARRIER", "FAKE")));
        assertThat(environment.getProperty("nexus.shipping.quote-carrier")).isEqualTo("GHN");
        assertThat(environment.getProperty("nexus.shipping.booking-carrier")).isEqualTo("FAKE");

        environment.getPropertySources().addFirst(new MapPropertySource("disable-quotes", Map.of(
                "SHIPPING_QUOTE_CARRIER", "NONE")));
        assertThat(environment.getProperty("nexus.shipping.quote-carrier")).isEqualTo("NONE");
        assertThat(environment.getProperty("nexus.shipping.booking-carrier")).isEqualTo("FAKE");
        assertThat(environment.getProperty("nexus.shipping.carrier")).isEqualTo("GHN");
    }

    private Properties yaml(String service) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("config/" + service + ".yml"));
        return factory.getObject();
    }
}
