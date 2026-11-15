package com.group5.common_security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Set;
import java.util.UUID;

public record CurrentUser (UUID id, Set<String> roles, Set<String> privileges, String displayName){

    public CurrentUser(UUID id, Set<String> roles, Set<String> privileges) {
        this(id, roles, privileges, null);
    }

    public static CurrentUser from (Jwt jwt) {
        return new CurrentUser(
                UUID.fromString(jwt.getSubject()),
                Set.copyOf(JwtAuthConverter.claim(jwt, JwtAuthConverter.ROLES_CLAIM)),
                Set.copyOf(JwtAuthConverter.claim(jwt, JwtAuthConverter.PRIVILEGES_CLAIM)),
                jwt.getClaimAsString("name")
        );
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasPrivilege(String privilege) {
        return privileges.contains(privilege);
    }

}
