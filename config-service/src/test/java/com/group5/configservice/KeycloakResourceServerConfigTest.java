package com.group5.configservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakResourceServerConfigTest {
    private static final String ISSUER = "http://localhost:18080/realms/nexus";
    private static final String JWKS = ISSUER + "/protocol/openid-connect/certs";

    @Test
    void gatewayAndAllBusinessServicesTrustOnlyKeycloakByDefault() {
        for (String name : List.of("api-gateway", "user-service", "catalog-service", "commerce-service",
                "auction-service", "inventory-service", "notification-service", "payment-service",
                "shipment-service", "rating-service")) {
            StandardEnvironment environment = environment(name);
            String prefix = name.equals("api-gateway")
                    ? "spring.security.oauth2.resourceserver.jwt." : "nexus.security.jwt.";
            assertThat(environment.getProperty(prefix + (name.equals("api-gateway") ? "issuer-uri" : "issuer")))
                    .as("%s issuer", name).isEqualTo(ISSUER);
            assertThat(environment.getProperty(prefix + "jwk-set-uri")).as("%s JWKS", name).isEqualTo(JWKS);
            assertThat(environment.getProperty(prefix + (name.equals("api-gateway") ? "audiences[0]" : "audience")))
                    .isEqualTo("nexus-services");
            environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "deployment", Map.of("KEYCLOAK_ISSUER_URI", "https://identity.example/realms/nexus",
                    "KEYCLOAK_JWK_SET_URI", "https://identity.internal/realms/nexus/protocol/openid-connect/certs")));
            assertThat(environment.getProperty(prefix + (name.equals("api-gateway") ? "issuer-uri" : "issuer")))
                    .isEqualTo("https://identity.example/realms/nexus");
            assertThat(environment.getProperty(prefix + "jwk-set-uri"))
                    .isEqualTo("https://identity.internal/realms/nexus/protocol/openid-connect/certs");
        }
    }

    @Test
    void platformServicesDoNotBecomeResourceServers() {
        for (String name : List.of("config-service", "discovery-service")) {
            ClassPathResource resource = new ClassPathResource("config/" + name + ".yml");
            if (resource.exists()) {
                assertThat(yaml(name).stringPropertyNames()).noneMatch(key ->
                        key.startsWith("nexus.security.jwt.") || key.startsWith("spring.security.oauth2.resourceserver."));
            }
        }
    }

    private static StandardEnvironment environment(String name) {
        StandardEnvironment environment = new StandardEnvironment();
        // Test shipped defaults independently of the developer's shell environment.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new PropertiesPropertySource(name, yaml(name)));
        return environment;
    }

    private static Properties yaml(String name) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("config/" + name + ".yml"));
        return factory.getObject();
    }
}
