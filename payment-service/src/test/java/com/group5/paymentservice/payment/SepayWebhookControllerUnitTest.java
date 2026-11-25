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
import com.group5.paymentservice.shared.exception.PaymentErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Kiểm tra giao tiếp webhook với nghiệp vụ xác nhận bằng chữ ký thật, không khởi động Spring hoặc Docker. */
@ExtendWith(MockitoExtension.class)
class SepayWebhookControllerUnitTest {
    private static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");
    private static final String SECRET = "checkout156-controller-unit-test-secret";
    private static final String MEMO = "NX156UNIT";
    private static final UUID GROUP = UUID.fromString("15600000-0000-4000-8000-000000000001");

    @Mock private PaymentSessionRepository sessions;
    @Mock private PaymentConfirmationService confirmation;
    @Mock private ManualPayoutBankReconciliationService payouts;
    private SepayWebhookController controller;

    @BeforeEach
    void setUp() {
        var verifier = new WebhookSignatureVerifier(SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
        var properties = new SepayProperties("970415", "0000015600", "UNIT TEST", "compact", "SEVQR");
        controller = new SepayWebhookController(verifier, JsonMapper.shared(),
                new PaymentMatcher(sessions, properties), confirmation, payouts);
    }

    @Test
    @DisplayName("Chỉ xác nhận group tìm từ memo, không tin orderGroupId do webhook tự gửi")
    void signedReceiptUsesMatchedGroupAndPreservesProviderEvidence() throws Exception {
        matchedSession();
        byte[] body = body("""
                {"id":156001,"code":" nx156unit ","transferType":"in","transferAmount":350000.00,
                 "orderGroupId":"15600000-0000-4000-8000-000000000099","providerAddition":true}
                """);

        var acknowledgement = controller.receive(body, sign(body), Long.toString(NOW.getEpochSecond()));

        assertThat(acknowledgement.success()).isTrue();
        ArgumentCaptor<SepayWebhookRequest> received = ArgumentCaptor.forClass(SepayWebhookRequest.class);
        verify(confirmation).confirm(eq(GROUP), received.capture());
        assertThat(received.getValue().id()).isEqualTo(156001L);
        assertThat(received.getValue().transferAmount()).isEqualByComparingTo("350000.00");
        assertThat(received.getValue().transferType()).isEqualTo("in");
        assertThat(received.getValue().code()).isEqualTo(" nx156unit ");
        verify(sessions).findByMemoCode(MEMO);
    }

    @ParameterizedTest
    @MethodSource("confirmationFailures")
    @DisplayName("Lỗi xác nhận phải truyền ra ngoài để provider retry, không trả ACK thành công")
    void confirmationFailureCannotBeConvertedIntoASuccessAcknowledgement(RuntimeException failure) throws Exception {
        matchedSession();
        byte[] body = body("{\"id\":156002,\"code\":\"NX156UNIT\",\"transferType\":\"in\",\"transferAmount\":350000}");
        doThrow(failure).when(confirmation).confirm(eq(GROUP), any(SepayWebhookRequest.class));
        String signature = sign(body);

        // Không mô phỏng rollback bằng mock; ca này chỉ bảo vệ ranh giới ACK của controller.
        assertThatThrownBy(() -> controller.receive(body, signature, Long.toString(NOW.getEpochSecond())))
                .isSameAs(failure);
        verify(confirmation).confirm(eq(GROUP), any(SepayWebhookRequest.class));
    }

    @Test
    @DisplayName("Raw body bị sửa sau khi ký không được tra memo hay gọi xác nhận")
    void tamperingWithTheSignedBodyCannotReachConfirmation() throws Exception {
        byte[] original = body("{\"id\":156003,\"code\":\"NX156UNIT\",\"transferType\":\"in\",\"transferAmount\":350000}");
        byte[] changed = body(new String(original, StandardCharsets.UTF_8).replace("350000", "300000"));
        String signature = sign(original);

        assertThatThrownBy(() -> controller.receive(changed, signature, Long.toString(NOW.getEpochSecond())))
                .isInstanceOf(DomainException.class)
                .hasMessage("The webhook signature is invalid");
        verifyNoInteractions(sessions, confirmation);
    }

    private void matchedSession() {
        PaymentSession session = new PaymentSession();
        session.setOrderGroupId(GROUP);
        session.setMemoCode(MEMO);
        when(sessions.findByMemoCode(MEMO)).thenReturn(Optional.of(session));
    }

    private static Stream<RuntimeException> confirmationFailures() {
        return Stream.of(new IllegalStateException("Synthetic outbox failure"),
                new DomainException(PaymentErrorCode.INVALID_WEBHOOK_REQUEST));
    }

    private static byte[] body(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(Long.toString(NOW.getEpochSecond()).getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) '.');
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
