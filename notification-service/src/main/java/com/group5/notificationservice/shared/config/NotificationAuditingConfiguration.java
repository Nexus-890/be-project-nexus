package com.group5.notificationservice.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;
import java.util.UUID;

/** Notification writes originate from background consumers and use the system actor. */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "notificationAuditor")
public class NotificationAuditingConfiguration {

    private static final UUID SYSTEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @Bean
    AuditorAware<UUID> notificationAuditor() {
        return () -> Optional.of(SYSTEM_ID);
    }
}
