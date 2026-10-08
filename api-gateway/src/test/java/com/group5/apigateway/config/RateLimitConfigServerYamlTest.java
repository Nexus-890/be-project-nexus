package com.group5.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitConfigServerYamlTest {

    @Test
    void importsLocalEnvFromServiceOrBackendWorkingDirectory() {
        YamlPropertiesFactoryBean yamlFactory = new YamlPropertiesFactoryBean();
        yamlFactory.setResources(new FileSystemResource("src/main/resources/application.yaml"));
        var yaml = yamlFactory.getObject();

        assertThat(yaml.getProperty("spring.config.import[0]"))
                .isEqualTo("optional:file:../infra/local-dev/.env[.properties]");
        assertThat(yaml.getProperty("spring.config.import[1]"))
                .isEqualTo("optional:file:./infra/local-dev/.env[.properties]");
        assertThat(yaml.getProperty("spring.config.import[2]"))
                .isEqualTo("configserver:http://localhost:8888");
    }

    @Test
    void disablesRedisHealthWithoutDisablingRedisPasswordConfiguration() {
        YamlPropertiesFactoryBean yamlFactory = new YamlPropertiesFactoryBean();
        yamlFactory.setResources(new FileSystemResource(
                "../config-service/src/main/resources/config/api-gateway.yml"));
        var yaml = yamlFactory.getObject();

        assertThat(yaml.getProperty("management.health.redis.enabled")).isEqualTo("false");
        assertThat(yaml.getProperty("spring.data.redis.password")).isEqualTo("${REDIS_PASSWORD:}");
    }

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
