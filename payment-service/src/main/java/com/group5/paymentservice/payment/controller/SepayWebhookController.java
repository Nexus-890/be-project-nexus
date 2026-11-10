package com.group5.paymentservice.payment.controller;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ProtocolResponse;
import com.group5.paymentservice.payment.service.PaymentMatcher;
import com.group5.paymentservice.payment.dto.request.SepayWebhookRequest;
import com.group5.paymentservice.payment.provider.sepay.WebhookSignatureVerifier;
import com.group5.paymentservice.shared.exception.PaymentErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class SepayWebhookController {
    private static final Logger log = LoggerFactory.getLogger(SepayWebhookController.class);
    private final WebhookSignatureVerifier verifier;
    private final JsonMapper json;
    private final PaymentMatcher matcher;

    public SepayWebhookController(WebhookSignatureVerifier verifier, JsonMapper json, PaymentMatcher matcher) {
        this.verifier = verifier;
        this.json = json;
        this.matcher = matcher;
    }

    @PostMapping("/api/v1/webhooks/sepay")
    @ProtocolResponse("SePay bank-transfer webhook requires an unwrapped success acknowledgement")
    public Acknowledgement receive(@RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = "X-SePay-Signature", required = false) String signature,
            @RequestHeader(value = "X-SePay-Timestamp", required = false) String timestamp) {
        if (!verifier.verify(rawBody, signature, timestamp)) {
            throw new DomainException(PaymentErrorCode.UNAUTHENTICATED, "The webhook signature is invalid");
        }

        SepayWebhookRequest request;
        try {
            request = json.readValue(rawBody, SepayWebhookRequest.class);
        } catch (JacksonException error) {
            //lỗi parse JSON
            throw new DomainException(PaymentErrorCode.INVALID_WEBHOOK_REQUEST);
        }
        if (request == null) throw new DomainException(PaymentErrorCode.INVALID_WEBHOOK_REQUEST);

        String memo = matcher.memoOf(request);
        if (matcher.match(request).isEmpty()) {
            String safeMemo = memo.replaceAll("\\p{Cc}", "?");
            // chỉ ghi memo cần tra cứu, không ghi toàn bộ request Sepay
            // WARN Unmatched SePay memo: NX.......
            log.warn("Unmatched SePay memo: {}", safeMemo.substring(0, Math.min(safeMemo.length(), 128)));
        }
        return new Acknowledgement(true);
    }

    public record Acknowledgement(boolean success) { }
}
