package com.group5.commerceservice.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class WebhookSignatureVerifierTest {
    // Synthetic unit vector, NOT a captured SePay delivery or a provider credential.
    // Signature independently generated with Python stdlib hmac/hashlib over timestamp + b'.' + BODY.
    private static final String SECRET = "unit-test-only-not-a-provider-credential";
    private static final String TIMESTAMP = "1792227600";
    private static final Instant SIGNED_AT = Instant.ofEpochSecond(Long.parseLong(TIMESTAMP));
    private static final byte[] BODY =
            "{\"id\":1,\"transferAmount\":1000,\"content\":\"NEXUS thử nghiệm\"}\n".getBytes(StandardCharsets.UTF_8);
    private static final String SIGNATURE =
            "sha256=8cc1fb7dfa8c1b21aae5fc209bc66298378c7dea64c61b8414beaf44d61578e9";
    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET, clockAt(SIGNED_AT));

    @Test
    void acceptsIndependentKnownSignatureForExactUtf8Bytes() {
        assertThat(verifier.verify(BODY, SIGNATURE, TIMESTAMP)).isTrue();
    }

    @Test
    void rejectsEverySingleByteMutationWithTheOriginalHeaders() {
        for (int index = 0; index < BODY.length; index++) {
            byte[] changed = BODY.clone();
            changed[index] ^= 1;
            assertThat(verifier.verify(changed, SIGNATURE, TIMESTAMP))
                    .as("mutation at byte %s", index).isFalse();
        }
    }

    @Test
    void rejectsBodyReserializationAndLineEndingChanges() {
        String original = new String(BODY, StandardCharsets.UTF_8);
        for (String changed : new String[] {
                original.stripTrailing(),
                original.replace("\n", "\r\n"),
                original.replace("\"id\":1", "\"id\": 1"),
                original.replace("thử", "th\\u1eed")
        }) {
            assertThat(verifier.verify(changed.getBytes(StandardCharsets.UTF_8), SIGNATURE, TIMESTAMP)).isFalse();
        }
    }

    @Test
    void rejectsWrongSecretAndChangedSignatureOrTimestamp() {
        var wrongKey = new WebhookSignatureVerifier("another-unit-test-only-key", clockAt(SIGNED_AT));
        assertThat(wrongKey.verify(BODY, SIGNATURE, TIMESTAMP)).isFalse();
        assertThat(verifier.verify(BODY, SIGNATURE.substring(0, 70) + "0", TIMESTAMP)).isFalse();
        assertThat(verifier.verify(BODY, SIGNATURE, "1792227601")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {-300, 0, 300})
    void acceptsInclusiveFiveMinuteClockSkew(long seconds) {
        var withinWindow = new WebhookSignatureVerifier(SECRET, clockAt(SIGNED_AT.plusSeconds(seconds)));
        assertThat(withinWindow.verify(BODY, SIGNATURE, TIMESTAMP)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {-301, 301})
    void rejectsExpiredAndFutureRequestsEvenWithAValidSignature(long seconds) {
        var outsideWindow = new WebhookSignatureVerifier(SECRET, clockAt(SIGNED_AT.plusSeconds(seconds)));
        assertThat(outsideWindow.verify(BODY, SIGNATURE, TIMESTAMP)).isFalse();
    }

    @Test
    void usesUnixSecondsRatherThanSubsecondClockPrecision() {
        var boundary = new WebhookSignatureVerifier(SECRET,
                clockAt(SIGNED_AT.plusSeconds(300).plusNanos(999_999_999)));
        assertThat(boundary.verify(BODY, SIGNATURE, TIMESTAMP)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "sha256=", "SHA256=abcd", "sha256=xyz", "sha256=00", "Apikey value"})
    void rejectsMissingOrMalformedSignature(String signature) {
        assertThat(verifier.verify(BODY, signature, TIMESTAMP)).isFalse();
    }

    @Test
    void rejectsWrongLengthNonHexAndNoncanonicalSignatureHeaders() {
        String hash = SIGNATURE.substring(7);
        for (String invalid : new String[] {
                SIGNATURE + "0", SIGNATURE.substring(0, 70),
                "sha256=" + "g" + hash.substring(1),
                "sha256=" + hash.toUpperCase(Locale.ROOT),
                " " + SIGNATURE, SIGNATURE + " ", SIGNATURE + "\n", SIGNATURE + "," + SIGNATURE
        }) {
            assertThat(verifier.verify(BODY, invalid, TIMESTAMP)).isFalse();
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "-1", "+1792227600", "1792227600.0", "1792227600\n", " 1792227600",
            "1792227600 ", "NaN", "9223372036854775807", "9223372036854775808",
            "99999999999999999999", "١٧٩٢٢٢٧٦٠٠"})
    void rejectsMissingMalformedOrOutOfRangeTimestamp(String timestamp) {
        assertThat(verifier.verify(BODY, SIGNATURE, timestamp)).isFalse();
    }

    @Test
    void rejectsNullOrEmptyBodyWithoutThrowing() {
        assertThat(verifier.verify(null, SIGNATURE, TIMESTAMP)).isFalse();
        assertThat(verifier.verify(new byte[0], SIGNATURE, TIMESTAMP)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void rejectsUnconfiguredSecret(String secret) {
        assertThatIllegalArgumentException().isThrownBy(() -> new WebhookSignatureVerifier(secret));
    }

    @Test
    void rejectsMissingClock() {
        assertThatNullPointerException().isThrownBy(() -> new WebhookSignatureVerifier(SECRET, null));
    }

    @Test
    void repeatedVerificationDoesNotRetainMacStateOrMutateBody() {
        byte[] original = BODY.clone();
        assertThat(verifier.verify(BODY, SIGNATURE, TIMESTAMP)).isTrue();
        assertThat(verifier.verify(BODY, SIGNATURE, TIMESTAMP)).isTrue();
        assertThat(BODY).isEqualTo(original);
    }

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
