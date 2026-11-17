package com.group5.ratingservice.eligibility.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EligibilityAuthorizationTest {
    private final EligibilityAuthorization authorization = new EligibilityAuthorization("nexus-auction-service");

    @Test
    void machineIdentityNeedsTheExactClientMarkerAndAuthority() {
        assertThat(authorization.allowed(token("nexus-auction-service", true,
                "RATING.ELIGIBILITY.CHECK"))).isTrue();
        assertThat(authorization.allowed(token("nexus-auction-service", true, "REPUTATION.VIEW"))).isFalse();
        assertThat(authorization.allowed(token("other-service", true, "RATING.ELIGIBILITY.CHECK"))).isFalse();
        assertThat(authorization.allowed(token("nexus-web", true, "RATING.ELIGIBILITY.CHECK"))).isFalse();
        assertThat(authorization.allowed(token(123, true, "RATING.ELIGIBILITY.CHECK"))).isFalse();
    }

    @Test
    void serviceMarkerMustBeAnActualBooleanTrue() {
        for (Object marker : List.of(false, "true", "TRUE", 1)) {
            assertThat(authorization.allowed(token("nexus-auction-service", marker,
                    "RATING.ELIGIBILITY.CHECK"))).isFalse();
        }
        assertThat(authorization.allowed(token("nexus-auction-service", null,
                "RATING.ELIGIBILITY.CHECK"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUYER", "SELLER", "ADMIN", "SUPPORT_STAFF"})
    void humanRolesCannotReplaceMachineIdentityEvenWithTheAuthority(String role) {
        var human = token("nexus-web", null, "ROLE_" + role, "RATING.ELIGIBILITY.CHECK");
        assertThat(authorization.allowed(human)).isFalse();
    }

    @Test
    void authenticationMustBeAnAuthenticatedJwt() {
        assertThat(authorization.allowed(null)).isFalse();
        assertThat(authorization.allowed(UsernamePasswordAuthenticationToken.authenticated(
                "service-account", "unused", List.of(new SimpleGrantedAuthority("RATING.ELIGIBILITY.CHECK")))))
                .isFalse();
        var unauthenticated = token("nexus-auction-service", true, "RATING.ELIGIBILITY.CHECK");
        unauthenticated.setAuthenticated(false);
        assertThat(authorization.allowed(unauthenticated)).isFalse();
    }

    @Test
    void configurationCanRestrictReplaceOrDisableTheDefaultAllowlist() {
        var replacement = new EligibilityAuthorization(" alternate-service, other-service , ");
        assertThat(replacement.allowed(token("alternate-service", true, "RATING.ELIGIBILITY.CHECK"))).isTrue();
        assertThat(replacement.allowed(token("other-service", true, "RATING.ELIGIBILITY.CHECK"))).isTrue();
        assertThat(replacement.allowed(token("nexus-auction-service", true, "RATING.ELIGIBILITY.CHECK"))).isFalse();
        assertThat(new EligibilityAuthorization("").allowed(token("nexus-auction-service", true,
                "RATING.ELIGIBILITY.CHECK"))).isFalse();
    }

    private static JwtAuthenticationToken token(Object client, Object marker, String... authorities) {
        var jwt = Jwt.withTokenValue("test-only").header("alg", "RS256").subject("test-service-account")
                .claim("azp", client);
        if (marker != null) {
            jwt.claim("service_account", marker);
        }
        return new JwtAuthenticationToken(jwt.build(), List.of(authorities).stream()
                .map(SimpleGrantedAuthority::new).toList());
    }
}
