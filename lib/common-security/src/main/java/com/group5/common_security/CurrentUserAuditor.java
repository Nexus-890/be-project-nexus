package com.group5.common_security;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;
import java.util.UUID;

/** Resolves the actor for HTTP requests and the stable system actor for consumers. */
public class CurrentUserAuditor implements AuditorAware<UUID> {

    public static final UUID SYSTEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    public static UUID currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return SYSTEM_ID;
        }
        if (authentication.getPrincipal() instanceof CurrentUser user) {
            return user.id();
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return CurrentUser.from(jwt).id();
        }
        return SYSTEM_ID;
    }

    @Override
    public Optional<UUID> getCurrentAuditor() {
        return Optional.of(currentActor());
    }
}
