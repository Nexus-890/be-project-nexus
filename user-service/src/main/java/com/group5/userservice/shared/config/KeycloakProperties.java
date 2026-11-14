package com.group5.userservice.shared.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "nexus.keycloak")
@Getter
@Setter
@Validated
public class KeycloakProperties {

    @NotBlank
    private String serverUrl;

    @NotBlank
    private String realm = "nexus";

    @NotBlank
    private String clientId = "user-service";

    @NotBlank
    private String clientSecret;

    @NotNull
    private Duration timeout = Duration.ofSeconds(5);

    @NotBlank
    private String verifyEmailClientId = "nexus-web";

    @NotBlank
    private String verifyEmailRedirectUri = "http://localhost:3000/";
}
