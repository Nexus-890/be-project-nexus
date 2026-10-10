package com.group5.userservice.shared.config;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class JwtKeyConfigTest {

    @TempDir
    static Path keyDirectory;

    private static KeyPair keyPair;
    private static Path privateKeyFile;
    private static Path publicKeyFile;
    private static Path otherPublicKeyFile;
    private static Path weakPrivateKeyFile;
    private static Path weakPublicKeyFile;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "nexus.iam.jwt.issuer=user-service",
                    "nexus.iam.jwt.audience=nexus-services",
                    "nexus.iam.jwt.key-id=nexus-rs256-test");

    @BeforeAll
    static void writeKeyFiles() throws Exception {
        keyPair = generate(2048);
        privateKeyFile = writePem("private.pem", "PRIVATE", keyPair.getPrivate().getEncoded());
        publicKeyFile = writePem("public.pem", "PUBLIC", keyPair.getPublic().getEncoded());

        otherPublicKeyFile = writePem("other-public.pem", "PUBLIC", generate(2048).getPublic().getEncoded());

        KeyPair weakPair = generate(1024);
        weakPrivateKeyFile = writePem("weak-private.pem", "PRIVATE", weakPair.getPrivate().getEncoded());
        weakPublicKeyFile = writePem("weak-public.pem", "PUBLIC", weakPair.getPublic().getEncoded());
    }

    @Test
    void loadsTheKeyPairFromThePemFiles() {
        contextRunner
                .withPropertyValues(
                        "nexus.iam.jwt.private-key-path=" + privateKeyFile,
                        "nexus.iam.jwt.public-key-path=" + publicKeyFile)
                .run(context -> {
                    assertThat(context).hasSingleBean(JwtEncoder.class);
                    RSAKey signingKey = context.getBean(RSAKey.class);
                    assertThat(signingKey.getKeyID()).isEqualTo("nexus-rs256-test");
                    assertThat(signingKey.toRSAPrivateKey()).isEqualTo(keyPair.getPrivate());
                    assertThat(signingKey.toRSAPublicKey()).isEqualTo(keyPair.getPublic());
                });
    }

    @Test
    void startupFailsAndNamesTheSettingWhenThePrivateKeyPathIsMissing() {
        contextRunner
                .withPropertyValues("nexus.iam.jwt.public-key-path=" + publicKeyFile)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("nexus.iam.jwt")
                            .hasMessageContaining("privateKeyPath");
                });
    }

    @Test
    void startupFailsAndNamesTheSettingWhenThePrivateKeyPathIsBlank() {
        contextRunner
                .withPropertyValues(
                        "nexus.iam.jwt.private-key-path=",
                        "nexus.iam.jwt.public-key-path=" + publicKeyFile)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("privateKeyPath");
                });
    }

    @Test
    void startupFailsAndNamesTheSettingWhenTheKeyFileDoesNotExist() {
        Path missingFile = keyDirectory.resolve("missing.pem");
        contextRunner
                .withPropertyValues(
                        "nexus.iam.jwt.private-key-path=" + missingFile,
                        "nexus.iam.jwt.public-key-path=" + publicKeyFile)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("nexus.iam.jwt.private-key-path")
                            .hasStackTraceContaining(missingFile.toString());
                });
    }

    @Test
    void startupFailsWhenThePublicKeyBelongsToAnotherPair() {
        contextRunner
                .withPropertyValues(
                        "nexus.iam.jwt.private-key-path=" + privateKeyFile,
                        "nexus.iam.jwt.public-key-path=" + otherPublicKeyFile)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("do not belong to the same RSA key pair");
                });
    }

    @Test
    void startupFailsWhenTheKeyIsShorterThan2048Bits() {
        contextRunner
                .withPropertyValues(
                        "nexus.iam.jwt.private-key-path=" + weakPrivateKeyFile,
                        "nexus.iam.jwt.public-key-path=" + weakPublicKeyFile)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("RS256 needs at least 2048 bits");
                });
    }

    private static KeyPair generate(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static Path writePem(String fileName, String kind, byte[] der) throws Exception {
        String label = kind + " KEY";
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        Path file = keyDirectory.resolve(fileName);
        Files.writeString(file, "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n");
        return file;
    }

    @Configuration
    @EnableConfigurationProperties(JwtProperties.class)
    @Import(JwtKeyConfig.class)
    static class TestConfiguration {
    }
}