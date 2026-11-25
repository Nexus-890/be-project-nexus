package com.group5.paymentservice.payment;

import com.group5.common_core.exception.DomainException;
import com.group5.paymentservice.payment.controller.SepayWebhookController;
import com.group5.paymentservice.payment.dto.request.SepayWebhookRequest;
import com.group5.paymentservice.payment.entity.PaymentSession;
import com.group5.paymentservice.payment.provider.sepay.WebhookSignatureVerifier;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import com.group5.paymentservice.payment.service.PaymentConfirmationService;
import com.group5.paymentservice.payment.service.PaymentMatcher;
import com.group5.paymentservice.payout.service.ManualPayoutBankReconciliationService;
import com.group5.paymentservice.shared.config.SepayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test riêng cho ID giao dịch SePay: chạy bằng nút Run của IntelliJ với JDK 21.
 * Không cần Spring context, Docker, DB, Kafka hoặc secret trong .env.
 * Dùng chữ ký HMAC, JSON reader và matcher thật; chỉ mock repository/service nghiệp vụ.
 * Việc không gọi confirmation bảo vệ ranh giới xử lý; dữ liệu DB được kiểm tra riêng
 * trong PaymentConfirmationIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SePay webhook — kiểm tra ID giao dịch trước khi xác nhận thanh toán")
class SepayWebhookReceiptIdUnitTest {
    private static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");
    private static final String TIMESTAMP = Long.toString(NOW.getEpochSecond());
    private static final String SECRET = "receipt-id-unit-test-only-secret";
    private static final String MEMO = "NX156IDUNIT";
    private static final UUID GROUP_ID = UUID.fromString("15600000-0000-4000-8000-000000000001");

    @Mock private PaymentSessionRepository sessions;
    @Mock private PaymentConfirmationService confirmation;
    @Mock private ManualPayoutBankReconciliationService payouts;
    private SepayWebhookController controller;

    /** Cố định thời gian để chữ ký luôn còn hạn, không phụ thuộc ngày giờ máy chạy test. */
    @BeforeEach
    void setUp() {
        var verifier = new WebhookSignatureVerifier(SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
        var properties = new SepayProperties("970415", "0000015600", "UNIT TEST", "compact", "SEVQR");
        controller = new SepayWebhookController(verifier, JsonMapper.shared(),
                new PaymentMatcher(sessions, properties), confirmation, payouts);
    }

    /** Ký nguyên raw body chứa ID sai; không deserialize trước vì có thể che lỗi cắt phần lẻ. */
    @ParameterizedTest(name = "ID {0} phải trả lỗi 400 trước khi truy cập dữ liệu")
    @ValueSource(strings = {"156987654.9", "156987654.0", "1.569876549e8", "9223372036854775808"})
    @DisplayName("ID dạng số thực hoặc vượt Long bị từ chối với PAYMENT_INVALID_WEBHOOK_REQUEST")
    void invalidSignedReceiptIdCannotReachPaymentProcessing(String idLiteral) throws Exception {
        byte[] body = rawBody(idLiteral);
        String signature = sign(body);

        assertThatThrownBy(() -> controller.receive(body, signature, TIMESTAMP))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(400);
                    assertThat(error.getErrorCode()).isEqualTo("PAYMENT_INVALID_WEBHOOK_REQUEST");
                });

        // Không tra memo hay gọi confirmation, nên request sai không đi vào luồng ghi thanh toán.
        verifyNoInteractions(sessions, confirmation);
    }

    /** Request sai không được chuyển ID đã cắt xuống service; ID nguyên gửi sau vẫn được chuyển đúng. */
    @Test
    @DisplayName("Từ chối 156987654.9 rồi tiếp nhận đúng ID nguyên 156987654")
    void rejectingFractionalIdDoesNotPreventTheFollowingIntegerReceipt() throws Exception {
        byte[] fractional = rawBody("156987654.9");
        String signature = sign(fractional);
        assertThatThrownBy(() -> controller.receive(fractional, signature, TIMESTAMP))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo("PAYMENT_INVALID_WEBHOOK_REQUEST"));
        verifyNoInteractions(sessions, confirmation);

        // Chỉ chuẩn bị session cho request hợp lệ sau khi đã chứng minh request lỗi không truy cập DB.
        matchSession();
        byte[] integer = rawBody("156987654");
        assertThat(controller.receive(integer, sign(integer), TIMESTAMP).success()).isTrue();

        ArgumentCaptor<SepayWebhookRequest> received = ArgumentCaptor.forClass(SepayWebhookRequest.class);
        verify(confirmation).confirm(eq(GROUP_ID), received.capture());
        assertThat(received.getValue().id()).isEqualTo(156987654L);
        // Tiền vẫn được đọc thành BigDecimal; giới hạn số nguyên chỉ áp dụng cho trường ID.
        assertThat(received.getValue().transferAmount()).isEqualByComparingTo("350000.00");
        assertThat(received.getValue().accumulated()).isEqualByComparingTo("1234567.89");
        verify(sessions).findByMemoCode(MEMO);
        verifyNoMoreInteractions(sessions, confirmation);
    }

    /** Giữ nguyên ID lớn nhất của Long, tránh chuyển trung gian qua double làm mất độ chính xác. */
    @Test
    @DisplayName("ID nguyên Long.MAX_VALUE được chuyển nguyên vẹn xuống nghiệp vụ")
    void largestLongIdIsPreservedExactly() throws Exception {
        matchSession();
        byte[] body = rawBody("9223372036854775807");

        assertThat(controller.receive(body, sign(body), TIMESTAMP).success()).isTrue();

        ArgumentCaptor<SepayWebhookRequest> received = ArgumentCaptor.forClass(SepayWebhookRequest.class);
        verify(confirmation).confirm(eq(GROUP_ID), received.capture());
        assertThat(received.getValue().id()).isEqualTo(Long.MAX_VALUE);
    }

    /** Chữ ký sai luôn bị chặn trước khi đọc JSON, kể cả khi ID cũng có phần thập phân. */
    @Test
    @DisplayName("Chữ ký sai trả 401 trước bước kiểm tra ID")
    void invalidSignatureIsRejectedBeforeReceiptIdParsing() throws Exception {
        byte[] body = rawBody("156987654.9");
        String signature = sign(body);
        String changed = signature.substring(0, signature.length() - 1) + (signature.endsWith("0") ? "1" : "0");

        assertThatThrownBy(() -> controller.receive(body, changed, TIMESTAMP))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(401);
                    assertThat(error.getErrorCode()).isEqualTo("PAYMENT_UNAUTHENTICATED");
                });
        verifyNoInteractions(sessions, confirmation);
    }

    /** Mô phỏng kết quả tra memo đúng; không tạo bản ghi DB hoặc giả lập transaction. */
    private void matchSession() {
        PaymentSession session = new PaymentSession();
        session.setOrderGroupId(GROUP_ID);
        session.setMemoCode(MEMO);
        when(sessions.findByMemoCode(MEMO)).thenReturn(Optional.of(session));
    }

    /** Ghép ID dưới dạng JSON literal để giữ nguyên phần thập phân/số mũ của dữ liệu đầu vào. */
    private static byte[] rawBody(String idLiteral) {
        return ("{\"id\":" + idLiteral + ",\"code\":\"" + MEMO
                + "\",\"transferType\":\"in\",\"transferAmount\":350000.00,\"accumulated\":1234567.89}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Tạo chữ ký đúng giao thức timestamp.rawBody bằng secret chỉ dùng trong unit test. */
    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(TIMESTAMP.getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) '.');
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
