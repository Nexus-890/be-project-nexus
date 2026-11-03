package com.group5.notificationservice.shared.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "nexus.notification")
public record NotificationProperties(boolean emailEnabled, @NotBlank String groupId) {
}
