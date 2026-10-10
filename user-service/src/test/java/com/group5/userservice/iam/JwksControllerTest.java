package com.group5.userservice.iam;

import com.group5.userservice.shared.config.IamProperties;
import com.group5.userservice.shared.config.JwtProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JwksControllerTest {

    private static final String KEY_ID = "nexus-rs256-test";
    private static final String JWKS_PATH = "/.well-known/jwks.json";

    private static RSAKey signingKey;
    private static MockMvc mockMvc;

    @BeforeAll
    static void createSigningKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        signingKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(KEY_ID)
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();
        mockMvc = MockMvcBuilders.standaloneSetup(new JwksController(signingKey)).build();
    }

    @Test
    void returnsOnePublicKeyWithoutAnAuthorizationHeader() throws Exception {
        mockMvc.perform(get(JWKS_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").value(KEY_ID))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].n").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].e").value("AQAB"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"d", "p", "q", "dp", "dq", "qi", "oth"})
    void neverPublishesAPrivateParameter(String parameter) throws Exception {
        mockMvc.perform(get(JWKS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0]." + parameter).doesNotExist());
    }

    @Test
    void publishedKeyVerifiesATokenFromTheIssuer() throws Exception {
        SignedJWT token = SignedJWT.parse(issuer().issueAccessToken(
                UUID.randomUUID(), List.of("SELLER"), List.of("PRODUCT.CREATE")));
        String body = mockMvc.perform(get(JWKS_PATH)).andReturn().getResponse().getContentAsString();

        JWK published = JWKSet.parse(body).getKeyByKeyId(token.getHeader().getKeyID());

        assertThat(published).isNotNull();
        assertThat(published.isPrivate()).isFalse();
        assertThat(token.verify(new RSASSAVerifier(published.toRSAKey().toRSAPublicKey()))).isTrue();
    }

    private JwtIssuer issuer() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setIssuer("user-service");
        jwtProperties.setAudience("nexus-services");

        return new JwtIssuer(
                new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey))),
                jwtProperties,
                new IamProperties(),
                Clock.systemUTC());
    }
}
