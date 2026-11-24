package com.group5.paymentservice.payout.controller;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ProtocolResponse;
import com.group5.paymentservice.payout.config.PayoutProperties;
import com.group5.paymentservice.payout.dto.request.PayoutCallbackRequest;
import com.group5.paymentservice.payout.provider.PayoutAttemptKey;
import com.group5.paymentservice.payout.provider.PayoutGateway;
import com.group5.paymentservice.payout.service.PayoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static com.group5.paymentservice.shared.exception.PaymentErrorCode.*;

@RestController
@RequestMapping("/api/v1/payments/payouts/callbacks")
@RequiredArgsConstructor
public class PayoutCallbackController {
    private final PayoutProperties properties;
    private final PayoutGateway gateway;
    private final PayoutService payouts;
    private final JsonMapper mapper;

    @PostMapping("/{provider}")
    @ProtocolResponse("The payout provider expects a plain acknowledgement, not an ApiResponse")
    public Map<String, String> receive(@PathVariable String provider, @RequestBody byte[] body,
            @RequestHeader(value = "X-Payout-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Payout-Signature", required = false) String signature) {
        verify(timestamp, signature, body);
        if (!gateway.provider().equals(provider)) throw new DomainException(PAYOUT_CALLBACK_INVALID);
        PayoutCallbackRequest callback;
        try {
            callback = mapper.readValue(body, PayoutCallbackRequest.class);
        } catch (RuntimeException invalid) {
            throw new DomainException(PAYOUT_CALLBACK_INVALID);
        }
        if (callback.payoutId() == null || callback.attempt() < 1 || callback.state() == null
                || callback.state() == PayoutGateway.State.NOT_FOUND
                || !PayoutAttemptKey.of(callback.payoutId(), callback.attempt()).equals(callback.idempotencyKey()))
            throw new DomainException(PAYOUT_CALLBACK_INVALID);
        payouts.apply(callback.payoutId(), callback.attempt(), provider,
                new PayoutGateway.Result(callback.state(), callback.reference(), callback.providerStatus()), false);
        return Map.of("status", "ok");
    }

    // Kiểm tra chữ ký trên body gốc và giới hạn thời gian để chặn callback giả hoặc phát lại quá hạn.
    private void verify(String timestamp, String signature, byte[] body) {
        try {
            long signedTime = Long.parseLong(timestamp);
            if (properties.getCallbackSecret().isBlank() || signature == null || signature.length() != 64
                    || signedTime < Instant.now().getEpochSecond() - 300
                    || signedTime > Instant.now().getEpochSecond() + 300)
                throw new IllegalArgumentException();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getCallbackSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            byte[] expected = mac.doFinal(body);
            if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature))) throw new IllegalArgumentException();
        } catch (Exception invalid) {
            throw new DomainException(PAYOUT_CALLBACK_SIGNATURE_INVALID);
        }
    }
}
