package com.group5.paymentservice.payment.controller;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ProtocolResponse;
import com.group5.paymentservice.payment.service.PaymentMatcher;
import com.group5.paymentservice.payment.service.PaymentConfirmationService;
import com.group5.paymentservice.payment.dto.request.SepayWebhookRequest;
import com.group5.paymentservice.payment.provider.sepay.WebhookSignatureVerifier;
import com.group5.paymentservice.payout.service.ManualPayoutBankReconciliationService;
import com.group5.paymentservice.shared.exception.PaymentErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** Nhận webhook SePay, xác thực raw body và chuyển giao dịch khớp memo sang luồng xác nhận thanh toán. */
@RestController
public class SepayWebhookController {
    private static final Logger log = LoggerFactory.getLogger(SepayWebhookController.class);
    private final WebhookSignatureVerifier verifier;
    private final ObjectReader webhookReader;
    private final PaymentMatcher matcher;
    private final PaymentConfirmationService confirmation;
    private final ManualPayoutBankReconciliationService payouts;

    public SepayWebhookController(WebhookSignatureVerifier verifier, JsonMapper json, PaymentMatcher matcher,
                                  PaymentConfirmationService confirmation,
                                  ManualPayoutBankReconciliationService payouts) {
        this.verifier = verifier;
        // Chỉ webhook từ chối ID dạng số thực, tránh cắt phần thập phân trước khi kiểm tra nghiệp vụ.
        this.webhookReader = json.readerFor(SepayWebhookRequest.class)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        this.matcher = matcher;
        this.confirmation = confirmation;
        this.payouts = payouts;
    }

    /** Trả ACK theo giao thức SePay; chỉ xử lý nghiệp vụ sau khi chữ ký và JSON hợp lệ. */
    @PostMapping("/api/v1/webhooks/sepay")
    @ProtocolResponse("SePay bank-transfer webhook requires an unwrapped success acknowledgement")
    public Acknowledgement receive(@RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = "X-SePay-Signature", required = false) String signature,
            @RequestHeader(value = "X-SePay-Timestamp", required = false) String timestamp) {
        // Kiểm tra chữ ký trên nguyên byte nhận được trước khi parse JSON hoặc truy cập payment_db.
        if (!verifier.verify(rawBody, signature, timestamp)) {
            throw new DomainException(PaymentErrorCode.UNAUTHENTICATED, "The webhook signature is invalid");
        }

        SepayWebhookRequest request;
        try {
            request = webhookReader.readValue(rawBody);
        } catch (JacksonException error) {
            // Body đã xác thực nhưng sai cấu trúc JSON phải trả lỗi nghiệp vụ có mã PAYMENT_*.
            throw new DomainException(PaymentErrorCode.INVALID_WEBHOOK_REQUEST);
        }
        if (request == null) throw new DomainException(PaymentErrorCode.INVALID_WEBHOOK_REQUEST);
        if ("out".equalsIgnoreCase(request.transferType())) {
            payouts.reconcile(request);
            return new Acknowledgement(true);
        }

        // Matcher chỉ tra cứu session; service bên dưới tự khóa và đọc lại dữ liệu trước khi xác nhận.
        String memo = matcher.memoOf(request);
        var matched = matcher.match(request);
        if (matched.isEmpty()) {
            String safeMemo = memo.replaceAll("\\p{Cc}", "?");
            // Chỉ log memo đã lọc ký tự điều khiển và giới hạn độ dài, không log chữ ký hoặc toàn bộ body.
            log.warn("Unmatched SePay memo: {}", safeMemo.substring(0, Math.min(safeMemo.length(), 128)));
        } else {
            confirmation.confirm(matched.get().getOrderGroupId(), request);
        }
        // ACK xác nhận đã nhận webhook, không chờ Commerce hoàn thành chuyển trạng thái qua Kafka.
        return new Acknowledgement(true);
    }

    public record Acknowledgement(boolean success) { }
}
