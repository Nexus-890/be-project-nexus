package com.group5.userservice.shared;

import com.group5.common_security.CurrentUserAuditor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.UUID;

@TestConfiguration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "testAuditor")
public class PostgresAuditingTestConfiguration {
    @Bean
    AuditorAware<UUID> testAuditor() {
        return new CurrentUserAuditor();
    }
}
