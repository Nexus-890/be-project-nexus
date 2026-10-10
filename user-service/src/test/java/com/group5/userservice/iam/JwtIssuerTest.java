package com.group5.userservice.iam;

import com.group5.userservice.shared.config.IamProperties;
import com.group5.userservice.shared.config.JwtProperties;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class JwtIssuerTest {

    private static final String KEY_ID = "nexus-rs256-test";
    private static final Instant NOW = Instant.parse("2026-10-08T03:00:00Z");
    private static final UUID USER_ID = UUID.fromString("64fba061-3a91-4a35-a853-221798344510");
    private static final List<String> ROLES = List.of("SELLER");
    private static final List<String> PRIVILEGES = List.of("PRODUCT.CREATE", "PRODUCT.UPDATE");

    private static RSAPublicKey publicKey;
    private static JwtEncoder jwtEncoder;

    @BeforeAll
    static void createSigningKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        publicKey = (RSAPublicKey) pair.getPublic();
        RSAKey signingKey = new RSAKey.Builder(publicKey)
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(KEY_ID)
                .build();
        jwtEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }

    @Test
    void tokenCarriesExactlyTheAdrClaims() throws Exception {
        SignedJWT token = SignedJWT.parse(issuer(60, fixedClock()).issueAccessToken(USER_ID, ROLES, PRIVILEGES));
        JWTClaimsSet claims = token.getJWTClaimsSet();

        assertThat(token.verify(new RSASSAVerifier(publicKey))).isTrue();
        assertThat(claims.getClaims().keySet())
                .containsExactlyInAnyOrder("iss", "aud", "sub", "roles", "privileges", "iat", "exp", "jti");
        assertThat(claims.getIssuer()).isEqualTo("user-service");
        assertThat(claims.getAudience()).containsExactly("nexus-services");
        assertThat(claims.getSubject()).isEqualTo(USER_ID.toString());
        assertThat(claims.getStringListClaim("roles")).containsExactlyElementsOf(ROLES);
        assertThat(claims.getStringListClaim("privileges")).containsExactlyElementsOf(PRIVILEGES);
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(NOW);
        assertThat(claims.getJWTID()).isNotBlank();
    }

    @Test
    void headerCarriesKeyIdAndRs256() throws Exception {
        SignedJWT token = SignedJWT.parse(issuer(60, fixedClock()).issueAccessToken(USER_ID, ROLES, PRIVILEGES));

        assertThat(token.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(token.getHeader().getKeyID()).isEqualTo(KEY_ID);
        assertThat(token.getHeader().getType()).isEqualTo(JOSEObjectType.JWT);
    }

    @Test
    void sixtyMinuteExpiryGivesThreeThousandSixHundredSeconds() throws Exception {
        assertThat(lifetimeSeconds(60)).isEqualTo(3600);
    }

    @Test
    void lifetimeFollowsTheConfiguredExpiry() throws Exception {
        assertThat(lifetimeSeconds(15)).isEqualTo(900);
    }

    @Test
    void everyTokenGetsItsOwnId() throws Exception {
        JwtIssuer issuer = issuer(60, fixedClock());

        String first = SignedJWT.parse(issuer.issueAccessToken(USER_ID, ROLES, PRIVILEGES)).getJWTClaimsSet().getJWTID();
        String second = SignedJWT.parse(issuer.issueAccessToken(USER_ID, ROLES, PRIVILEGES)).getJWTClaimsSet().getJWTID();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void tokenIsAcceptedByAResourceServerDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer("user-service"),
                new JwtClaimValidator<List<String>>("aud", audience -> audience != null && audience.contains("nexus-services"))));

        Jwt jwt = decoder.decode(issuer(60, Clock.systemUTC()).issueAccessToken(USER_ID, ROLES, PRIVILEGES));

        assertThat(jwt.getSubject()).isEqualTo(USER_ID.toString());
        assertThat(jwt.getClaimAsStringList("privileges")).containsExactlyElementsOf(PRIVILEGES);
    }

    @Test
    void userIdIsRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> issuer(60, fixedClock()).issueAccessToken(null, ROLES, PRIVILEGES))
                .withMessage("userId");
    }

    private long lifetimeSeconds(long expiryMinutes) throws Exception {
        JWTClaimsSet claims = SignedJWT.parse(issuer(expiryMinutes, fixedClock())
                .issueAccessToken(USER_ID, ROLES, PRIVILEGES)).getJWTClaimsSet();
        return (claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()) / 1000;
    }

    private JwtIssuer issuer(long expiryMinutes, Clock clock) {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setIssuer("user-service");
        jwtProperties.setAudience("nexus-services");

        IamProperties iamProperties = new IamProperties();
        iamProperties.setAccessTokenExpiryMinutes(expiryMinutes);

        return new JwtIssuer(jwtEncoder, jwtProperties, iamProperties, clock);
    }

    private Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}