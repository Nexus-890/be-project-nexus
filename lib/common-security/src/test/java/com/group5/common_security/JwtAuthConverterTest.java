package com.group5.common_security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthConverterTest {

    private static final String USER_ID = "64fba061-3a91-4a35-a853-221798344510";

    private final JwtAuthConverter converter = new JwtAuthConverter();

    @Test
    void rolesGetTheRolePrefixAndPrivilegesKeepTheirNames() {
        Jwt jwt = jwt()
                .claim("roles", List.of("SELLER", "BUYER"))
                .claim("privileges", List.of("PRODUCT.CREATE"))
                .build();

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_SELLER", "ROLE_BUYER", "PRODUCT.CREATE");
        assertThat(authentication.getName()).isEqualTo(USER_ID);
        assertThat(authentication.isAuthenticated()).isTrue();
    }

    @Test
    void aTokenWithoutRolesOrPrivilegesGetsNoAuthority() {
        assertThat(converter.convert(jwt().build()).getAuthorities()).isEmpty();
    }

    @Test
    void currentUserReadsIdRolesAndPrivilegesFromTheToken() {
        CurrentUser user = CurrentUser.from(jwt()
                .claim("roles", List.of("SELLER"))
                .claim("privileges", List.of("PRODUCT.CREATE"))
                .build());

        assertThat(user.id()).isEqualTo(UUID.fromString(USER_ID));
        assertThat(user.roles()).containsExactly("SELLER");
        assertThat(user.privileges()).containsExactly("PRODUCT.CREATE");
        assertThat(user.hasRole("SELLER")).isTrue();
        assertThat(user.hasPrivilege("PRODUCT.DELETE")).isFalse();
    }

    private static Jwt.Builder jwt() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(USER_ID)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600));
    }
}
