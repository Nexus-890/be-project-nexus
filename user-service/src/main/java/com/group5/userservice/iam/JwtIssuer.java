package com.group5.userservice.iam;

import com.group5.userservice.shared.config.IamProperties;
import com.group5.userservice.shared.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JwtIssuer { //<-- AuthService

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final IamProperties iamProperties;
    private final Clock clock;

    public String issueAccessToken(UUID userId, Collection<String> roles, Collection<String> privileges) {
        Objects.requireNonNull(userId, "userId");           //|
        Objects.requireNonNull(roles, "roles");             //| -> BLOCK NULL SOON
        Objects.requireNonNull(privileges, "privileges");   //|

        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS); // .now() -> truncate
        Instant expiresAt = issuedAt.plus(Duration.ofMinutes(iamProperties.getAccessTokenExpiryMinutes()));

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256) //alg RS256
                .type("JWT")
                .build();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer()) // Issuer
                .audience(List.of(jwtProperties.getAudience())) //Audience
                .subject(userId.toString()) // userId
                .issuedAt(issuedAt) //Time issued at
                .expiresAt(expiresAt) //Expire At ...
                .id(UUID.randomUUID().toString()) //RANDOM UUID FOR TOKEN
                .claim("roles", List.copyOf(roles)) //Attribute
                .claim("privileges", List.copyOf(privileges)) //Attribute
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}