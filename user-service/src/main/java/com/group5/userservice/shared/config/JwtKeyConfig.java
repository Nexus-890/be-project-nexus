package com.group5.userservice.shared.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.stream.Collectors;

@Slf4j
@Configuration
public class JwtKeyConfig {

    private static final String PRIVATE_KEY_SETTING = "nexus.iam.jwt.private-key-path";
    private static final String PUBLIC_KEY_SETTING = "nexus.iam.jwt.public-key-path";
    private static final int MINIMUM_KEY_BITS = 2048;

    @Bean
    public RSAKey jwtSigningKey(JwtProperties properties) {
        //READ SECRET KEY FORM PEM FILES
        RSAPrivateKey privateKey = readPrivateKey(properties.getPrivateKeyPath());
        RSAPublicKey publicKey = readPublicKey(properties.getPublicKeyPath());

        //COMPARE MODULUS KEYS
        //Modulus is a large number have in two pem files ->> DIFFERENCE -> SERVICE STOP
        if (!privateKey.getModulus().equals(publicKey.getModulus())) {
            throw new IllegalStateException(
                    PRIVATE_KEY_SETTING + " and " + PUBLIC_KEY_SETTING + " do not belong to the same RSA key pair");
        }
        int keyBits = privateKey.getModulus().bitLength();
        //RS256 COUNTER MINIMUM_KEY_BITS > 2 BYTES
        if (keyBits < MINIMUM_KEY_BITS) {
            throw new IllegalStateException(PRIVATE_KEY_SETTING + " holds a " + keyBits
                    + "-bit RSA key, RS256 needs at least " + MINIMUM_KEY_BITS + " bits");
        }



        log.info("Loaded JWT signing key kid={} ({} bits)", properties.getKeyId(), keyBits);

        //WRAP: 2 KEY -> ONE RSAKey, have kid (name key), alg, purpose
        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(properties.getKeyId())
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();
    }

    @Bean
    public JwtEncoder jwtEncoder(RSAKey jwtSigningKey) {
        //WRAP RSAKey into NimbusJwtEncoder is a tool in SPRING SECURITY to know sign token
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwtSigningKey)));
    }

    private RSAPrivateKey readPrivateKey(String path) {
        byte[] der = readPem(path, PRIVATE_KEY_SETTING);
        try {
            //KeyFactory transform byte into object key
            //PKCS8EncodedKeySpec  | -> was born by openssl
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(
                    PRIVATE_KEY_SETTING + " must point to an unencrypted RSA private key in PKCS#8 PEM format: " + path, ex);
        }
    }

    private RSAPublicKey readPublicKey(String path) {
        byte[] der = readPem(path, PUBLIC_KEY_SETTING);
        try {
            //KeyFactory transform byte into object key
            //X509EncodedKeySpec   | -> was born by openssl
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(
                    PUBLIC_KEY_SETTING + " must point to an RSA public key in X.509 PEM format: " + path, ex);
        }
    }

    private byte[] readPem(String path, String setting) {
        //readPem purpose to generate file into byte
        try {
            String base64 = Files.readAllLines(Path.of(path)).stream()
                    .map(String::trim)
                    .filter(line -> !line.startsWith("-----"))
                    .collect(Collectors.joining());
            //pem is data key BASE64, begin and end iss `-----`
            //KeyFactory transform byte into object key
            //PKCS8EncodedKeySpec  |
            //X509EncodedKeySpec   | -> was born by openssl Hehe
            return Base64.getDecoder().decode(base64);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException(setting + " points to a file that cannot be read as PEM: " + path, ex);
        }
    }
}