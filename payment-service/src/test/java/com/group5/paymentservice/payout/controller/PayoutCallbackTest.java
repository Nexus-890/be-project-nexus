package com.group5.paymentservice.payout.controller;

import com.group5.common_core.exception.DomainException;
import com.group5.paymentservice.payout.config.PayoutProperties;
import com.group5.paymentservice.payout.provider.PayoutGateway;
import com.group5.paymentservice.payout.provider.SandboxPayoutGateway;
import com.group5.paymentservice.payout.service.PayoutService;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PayoutCallbackTest {
    private final PayoutProperties properties = new PayoutProperties();
    private final PayoutService payouts = mock(PayoutService.class);
    private final PayoutGateway gateway = new SandboxPayoutGateway(properties);
    private final PayoutCallbackController callback =
            new PayoutCallbackController(
                    properties, gateway, payouts, JsonMapper.builder().build());

    @BeforeEach
    void configure() {
        properties.setCallbackSecret("test-only-signature-secret");
    }

    @Test
    void authenticCallbackIsPassedWithItsOriginalAttemptAndProtocolAcknowledgement()
            throws Exception {
        UUID id = UUID.randomUUID();
        byte[] body = body(id, "SUCCEEDED", 1);
        String time = Long.toString(Instant.now().getEpochSecond());
        assertThat(callback.receive("sandbox", body, time, sign(time, body)))
                .containsEntry("status", "ok");
        verify(payouts)
                .apply(
                        eq(id),
                        eq(1),
                        eq("sandbox"),
                        argThat(r -> r.state() == PayoutGateway.State.SUCCEEDED),
                        eq(false));
    }

    @Test
    void forgedExpiredMissingAndTamperedCallbacksAreRejectedBeforeAnyStateChange()
            throws Exception {
        byte[] body = body(UUID.randomUUID(), "SUCCEEDED", 1);
        String time = Long.toString(Instant.now().getEpochSecond());
        String valid = sign(time, body);
        assertThatThrownBy(() -> callback.receive("sandbox", body, time, "0".repeat(64)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> callback.receive("sandbox", body, null, null))
                .isInstanceOf(DomainException.class);
        String expired = Long.toString(Instant.now().minusSeconds(301).getEpochSecond());
        String expiredSignature = sign(expired, body);
        assertThatThrownBy(() -> callback.receive("sandbox", body, expired, expiredSignature))
                .isInstanceOf(DomainException.class);
        byte[] changed =
                new String(body, StandardCharsets.UTF_8)
                        .replace("SUCCEEDED", "RETURNED")
                        .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> callback.receive("sandbox", changed, time, valid))
                .isInstanceOf(DomainException.class);
        verifyNoInteractions(payouts);
    }

    @Test
    void invalidSignedPayloadAndUnconfiguredProviderAreRejected() throws Exception {
        byte[] body = body(UUID.randomUUID(), "SUCCEEDED", 0);
        String time = Long.toString(Instant.now().getEpochSecond());
        String signature = sign(time, body);
        assertThatThrownBy(() -> callback.receive("sandbox", body, time, signature))
                .isInstanceOf(DomainException.class);
        byte[] good = body(UUID.randomUUID(), "SUCCEEDED", 1);
        String goodSignature = sign(time, good);
        assertThatThrownBy(() -> callback.receive("other", good, time, goodSignature))
                .isInstanceOf(DomainException.class);
        properties.setCallbackSecret("");
        assertThatThrownBy(() -> callback.receive("sandbox", good, time, goodSignature))
                .isInstanceOf(DomainException.class);
        verifyNoInteractions(payouts);
    }

    @Test
    void transferDiagnosticNeverIncludesAccountNumber() {
        var transfer =
                new PayoutGateway.Transfer(
                        UUID.randomUUID(),
                        1,
                        "key",
                        java.math.BigDecimal.ONE,
                        "VND",
                        "970415",
                        "0000123456789",
                        "TEST");
        assertThat(transfer.toString()).doesNotContain("0000123456789", "TEST");
    }

    private byte[] body(UUID id, String state, int attempt) {
        return ("{\"payoutId\":\""
                        + id
                        + "\",\"attempt\":"
                        + attempt
                        + ",\"idempotencyKey\":\"payout:"
                        + id
                        + ":"
                        + attempt
                        + "\",\"state\":\""
                        + state
                        + "\",\"reference\":\"test-reference\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private String sign(String time, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(
                new SecretKeySpec(
                        properties.getCallbackSecret().getBytes(StandardCharsets.UTF_8),
                        "HmacSHA256"));
        mac.update((time + ".").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
