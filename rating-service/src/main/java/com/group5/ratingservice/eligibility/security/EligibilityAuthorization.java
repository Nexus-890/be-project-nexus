package com.group5.ratingservice.eligibility.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component("eligibilityAuthorization")
public final class EligibilityAuthorization {
    private static final String REQUIRED_AUTHORITY = "RATING.ELIGIBILITY.CHECK";
    private final Set<String> allowedClients;

    public EligibilityAuthorization(
            @Value("${nexus.rating.eligibility.allowed-clients:nexus-auction-service}") String allowedClients) {
        this.allowedClients = Arrays.stream(allowedClients.split(","))
                .map(String::strip).filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    public boolean allowed(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            return false;
        }
        var claims = token.getToken().getClaims();
        if (!Boolean.TRUE.equals(claims.get("service_account"))
                || !(claims.get("azp") instanceof String client) || !allowedClients.contains(client)) {
            return false;
        }
        return token.getAuthorities().stream()
                .anyMatch(authority -> REQUIRED_AUTHORITY.equals(authority.getAuthority()));
    }
}
