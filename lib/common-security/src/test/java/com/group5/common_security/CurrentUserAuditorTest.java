package com.group5.common_security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentUserAuditorTest {
    private final CurrentUserAuditor auditor = new CurrentUserAuditor();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void consumersAndAnonymousRequestsUseStableSystemActor() {
        assertThat(auditor.getCurrentAuditor()).contains(CurrentUserAuditor.SYSTEM_ID);
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "anonymous", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThat(auditor.getCurrentAuditor()).contains(CurrentUserAuditor.SYSTEM_ID);
    }

    @Test
    void resolvesCurrentUserPrincipal() {
        UUID seller = UUID.fromString("68000000-0000-4000-8000-000000000002");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new CurrentUser(seller, Set.of("SELLER"), Set.of()), null, List.of()));
        assertThat(auditor.getCurrentAuditor()).contains(seller);
    }

    @Test
    void resolvesJwtUsedByResourceServer() {
        UUID user = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(user.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthConverter().convert(jwt));
        assertThat(auditor.getCurrentAuditor()).contains(user);
    }
}
