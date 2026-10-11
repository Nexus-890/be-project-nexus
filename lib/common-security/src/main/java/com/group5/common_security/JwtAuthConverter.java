package com.group5.common_security;


import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.List;

public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    public static final String ROLES_CLAIM = "roles";
    public static final String PRIVILEGES_CLAIM = "privileges";

    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    public AbstractAuthenticationToken convert(Jwt source) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String role : claim(source, ROLES_CLAIM)) {
            authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + role));
        }

        for (String privilege : claim(source, PRIVILEGES_CLAIM)) {
            authorities.add(new SimpleGrantedAuthority(privilege));
        }

        return new JwtAuthenticationToken(source, authorities, source.getSubject());
    }

    static List<String> claim (Jwt jwt, String name) {
        List<String> values = jwt.getClaimAsStringList(name);
        return values == null ? List.of() : values;
    }
}
