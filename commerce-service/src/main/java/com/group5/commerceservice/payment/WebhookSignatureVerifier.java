package com.group5.commerceservice.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** Verifies SePay bank-transfer webhooks before their raw body is parsed. */
public final class WebhookSignatureVerifier {
    private static final String ALGORITHM = "HmacSHA256";
    private static final Duration TIMESTAMP_TOLERANCE = Duration.ofMinutes(5);
    private static final Pattern SIGNATURE = Pattern.compile("sha256=([0-9a-f]{64})");
    private static final Pattern TIMESTAMP = Pattern.compile("[0-9]{1,19}");

    private final SecretKeySpec key;
    private final Clock clock;

    public WebhookSignatureVerifier(String secret) {
        this(secret, Clock.systemUTC());
    }

    public WebhookSignatureVerifier(String secret, Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("SePay webhook secret must not be blank");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param rawBody original request bytes, without JSON reserialization or newline changes
     * @param signatureHeader value of X-SePay-Signature (sha256= followed by lowercase hex)
     * @param timestampHeader value of X-SePay-Timestamp (Unix seconds)
     * @return false for missing, malformed, stale or incorrectly signed input
     */
    public boolean verify(byte[] rawBody, String signatureHeader, String timestampHeader) {
        if (rawBody == null || signatureHeader == null || timestampHeader == null
                || !TIMESTAMP.matcher(timestampHeader).matches()) {
            return false;
        }
        var signature = SIGNATURE.matcher(signatureHeader);
        if (!signature.matches()) {
            return false;
        }

        try {
            Instant signedAt = Instant.ofEpochSecond(Long.parseLong(timestampHeader));
            Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            if (Duration.between(signedAt, now).abs().compareTo(TIMESTAMP_TOLERANCE) > 0) {
                return false;
            }
        } catch (NumberFormatException | DateTimeException exception) {
            return false;
        }

        try {
            // Mac is mutable: keep it request-local so this verifier can be shared safely.
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update(timestampHeader.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            byte[] expected = mac.doFinal(rawBody);
            byte[] supplied = HexFormat.of().parseHex(signature.group(1));
            return MessageDigest.isEqual(expected, supplied);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}
