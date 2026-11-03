package com.group5.paymentservice.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Acceptance tests for the unmodified provider delivery, not a generated test vector. */
@EnabledIfEnvironmentVariable(named = "SEPAY_CAPTURE_TEST", matches = "true",
        disabledReason = "Enable only with a captured SePay body, its original headers and matching secret")
class WebhookCapturedDeliveryTest {
    private byte[] body;
    private String signature;
    private String timestamp;
    private WebhookSignatureVerifier verifier;

    @BeforeEach
    void loadCapturedDelivery() throws IOException {
        String secret = requiredEnvironment("SEPAY_WEBHOOK_SECRET");
        signature = requiredEnvironment("SEPAY_CAPTURE_SIGNATURE");
        timestamp = requiredEnvironment("SEPAY_CAPTURE_TIMESTAMP");

        Path sample = Path.of("..", "docs", "api", "sepay-webhook-sample.json").toAbsolutePath();
        assertThat(Files.isRegularFile(sample))
                .as("Capture an actual SePay delivery to docs/api/sepay-webhook-sample.json first")
                .isTrue();
        body = Files.readAllBytes(sample);
        assertThat(body.length).as("The captured body must not be empty").isPositive();

        // A historical delivery must still exercise the timestamp check, at its actual signing time.
        Instant signedAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
        verifier = new WebhookSignatureVerifier(secret, Clock.fixed(signedAt, ZoneOffset.UTC));
    }

    @Test
    void acceptsCapturedBodyWithItsOriginalSignatureHeaders() {
        assertThat(verifier.verify(body, signature, timestamp))
                .as("The original captured body and headers must verify with the matching secret")
                .isTrue();
    }

    @Test
    void rejectsOneByteMutationWithTheOriginalSignatureHeaders() {
        // Do not let an invalid baseline make the tampering assertion pass vacuously.
        assertThat(verifier.verify(body, signature, timestamp))
                .as("Verify the original capture before checking tampering")
                .isTrue();
        byte[] changed = body.clone();
        changed[0] ^= 1;

        assertThat(verifier.verify(changed, signature, timestamp))
                .as("Changing one body byte must invalidate the original signature")
                .isFalse();
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        // Assert only presence: never include the value of a credential in test reports.
        assertThat(value != null && !value.isBlank())
                .as("Set %s outside Git before enabling captured-delivery tests", name)
                .isTrue();
        return value;
    }
}
