package com.group5.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitConfigServerYamlTest {

    @Test
    void bindsRequestsPerMinuteFromConfigServerYaml() throws Exception {
        Path yamlPath = Path.of("../config-service/src/main/resources/config/api-gateway.yml");
        String yaml = Files.readString(yamlPath, StandardCharsets.UTF_8);

        assertThat(yaml).contains("requests-per-minute:");

        // Dùng giá trị khác default để bắt lỗi sai tên property trong Config Server.
        String demoYaml = yaml.replaceFirst("requests-per-minute:\\s*\\d+", "requests-per-minute: 150");
        YamlPropertiesFactoryBean yamlFactory = new YamlPropertiesFactoryBean();
        yamlFactory.setResources(new ByteArrayResource(demoYaml.getBytes(StandardCharsets.UTF_8)));

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new PropertiesPropertySource("api-gateway.yml", yamlFactory.getObject()));

        RateLimitProperties properties = Binder.get(environment)
                .bind("nexus.gateway.rate-limit", RateLimitProperties.class)
                .orElseThrow(IllegalStateException::new);

        assertThat(properties.getRequestsPerMinute()).isEqualTo(150);
    }
}
