package com.group5.notificationservice.shared.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "nexus.keycloak")
public class KeycloakProperties {

    private String serverUrl = "http://localhost:18080";

    private String realm = "nexus";

    private String clientId = "notification-service";

    private String clientSecret;

    private Duration timeout = Duration.ofSeconds(5);
}
