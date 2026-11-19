package com.group5.userservice.password;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "nexus.password-reset")
public class PasswordResetProperties {
    @NotBlank
    private String webBaseUrl = "http://localhost:3000";
    @NotBlank
    private String mailFrom = "no-reply@nexus.local";
    @NotNull
    private Duration linkLifetime = Duration.ofMinutes(30);
    @NotNull
    private Duration resendInterval = Duration.ofMinutes(1);
}
