package com.group5.shipmentservice.shared.config;

import com.group5.common_security.CurrentUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@ConditionalOnMissingClass("com.group5.common_security.CurrentUserAuditor")
@EnableJpaAuditing(auditorAwareRef = "shipmentAuditor")
public class PersistenceConfiguration {

    public static final UUID SYSTEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    /** Service-local until the shared #249 auditor is available on develop. */
    @Bean
    AuditorAware<UUID> shipmentAuditor() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()
                    || authentication instanceof AnonymousAuthenticationToken) {
                return Optional.of(SYSTEM_ID);
            }
            if (authentication.getPrincipal() instanceof CurrentUser user) {
                return Optional.of(user.id());
            }
            if (authentication.getPrincipal() instanceof Jwt jwt) {
                return Optional.of(CurrentUser.from(jwt).id());
            }
            return Optional.of(SYSTEM_ID);
        };
    }
}
