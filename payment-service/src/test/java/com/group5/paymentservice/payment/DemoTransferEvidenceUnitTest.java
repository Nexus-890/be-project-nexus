package com.group5.paymentservice.payment;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.paymentservice.payment.dto.request.SepayWebhookRequest;
import com.group5.paymentservice.payment.entity.PaymentInstruction;
import com.group5.paymentservice.payment.entity.PaymentSession;
import com.group5.paymentservice.payment.entity.PaymentTransaction;
import com.group5.paymentservice.payment.enums.PaymentInstructionStatus;
import com.group5.paymentservice.payment.enums.PaymentSessionStatus;
import com.group5.paymentservice.payment.enums.PaymentTransactionStatus;
import com.group5.paymentservice.payment.event.payload.CheckoutPaymentConfirmed;
import com.group5.paymentservice.payment.event.payload.PaymentUnreconciled;
import com.group5.paymentservice.payment.repository.PaymentInstructionRepository;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import com.group5.paymentservice.payment.repository.PaymentTransactionRepository;
import com.group5.paymentservice.payment.service.PaymentConfirmationService;
import com.group5.paymentservice.payment.service.RefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Bằng chứng nghiệp vụ #158 bằng entity thật; không mô phỏng chuyển khoản Live hoặc commit/rollback DB. */
class DemoTransferEvidenceUnitTest {
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00.123456Z");
    private static final OffsetDateTime RECEIVED = NOW.atOffset(ZoneOffset.UTC);
    private static final long PROVIDER_ID = 158001L;
    private final PaymentSessionRepository sessions = mock(PaymentSessionRepository.class);
    private final PaymentInstructionRepository instructions = mock(PaymentInstructionRepository.class);
    private final PaymentTransactionRepository transactions = mock(PaymentTransactionRepository.class);
    private final RefundService refunds = mock(RefundService.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final EventEnvelopeFactory envelopes = mock(EventEnvelopeFactory.class);
    private final PaymentSession session = new PaymentSession();
    private final PaymentInstruction instruction = new PaymentInstruction();
    private final UUID receiptId = UUID.randomUUID();
    private final PaymentConfirmationService service = new PaymentConfirmationService(sessions, instructions,
            transactions, refunds, outbox, envelopes, Clock.fixed(NOW, ZoneOffset.ofHours(7)));

    @BeforeEach
    void prepareStoredTenThousandVndPlan() {
        session.setId(UUID.randomUUID());
        session.setOrderGroupId(UUID.randomUUID());
        session.setBuyerId(UUID.randomUUID());
        session.setTotalAmount(new BigDecimal("10000.00"));
        session.setMemoCode("NX158UNIT");
        session.setVersion(1L);
        session.setDueAt(RECEIVED.plusMinutes(15));
        instruction.setId(UUID.randomUUID());
        instruction.setOrderId(UUID.randomUUID());
        instruction.setBuyerId(session.getBuyerId());
        instruction.setSession(session);
        instruction.setAmount(session.getTotalAmount());
        instruction.setDueAt(session.getDueAt());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1000})
    @DisplayName("10.000 VND đúng hạn hoặc đúng biên deadline phát manifest có receipt và order ID để đối chiếu")
    void exactAmountAtOrBeforeDeadlineCarriesReceiptIdentity(long nanosUntilDeadline) {
        session.setDueAt(RECEIVED.plusNanos(nanosUntilDeadline));
        instruction.setDueAt(session.getDueAt());
        storedPlan();

        service.confirm(session.getOrderGroupId(), receipt("10000"));

        assertThat(savedReceipt().getStatus()).isEqualTo(PaymentTransactionStatus.SUCCESS);
        assertThat(session.getStatus()).isEqualTo(PaymentSessionStatus.PAID);
        assertThat(instruction.getFundedTransaction().getId()).isEqualTo(receiptId);
        var payload = ArgumentCaptor.forClass(CheckoutPaymentConfirmed.class);
        verify(envelopes).create(eq("CheckoutPaymentConfirmed"), eq(session.getId().toString()), payload.capture());
        assertThat(payload.getValue().transactionId()).isEqualTo(receiptId);
        assertThat(payload.getValue().instructions()).extracting(CheckoutPaymentConfirmed.Instruction::orderId)
                .containsExactly(instruction.getOrderId());
        assertThat(payload.getValue().totalAmount()).isEqualTo("10000.00");
        assertThat(payload.getValue().paidAt()).isEqualTo(NOW.toString());
        verify(envelopes, never()).create(eq("PaymentUnreconciled"), anyString(), any());
    }

    @ParameterizedTest
    @CsvSource({"PENDING,false,LATE_PAYMENT", "PENDING,true,LATE_PAYMENT", "EXPIRED,false,LATE_PAYMENT",
            "CANCELED,false,CANCELLED_ORDER_GROUP", "PAID,false,ALREADY_PAID"})
    @DisplayName("Tiền đến muộn/nhóm đã đóng giữ receipt UNRECONCILED và lý do đúng, không funding")
    void unpayableTransferPublishesCorrelatableEvidence(PaymentSessionStatus status, boolean childLate, String reason) {
        session.setStatus(status);
        if (status == PaymentSessionStatus.PENDING) {
            // Chỉ một microsecond quá hạn cũng là muộn; không dùng transactionDate của provider để gia hạn.
            if (childLate) instruction.setDueAt(RECEIVED.minusNanos(1000));
            else session.setDueAt(RECEIVED.minusNanos(1000));
        }
        storedPlan();

        service.confirm(session.getOrderGroupId(), receipt("10000"));

        assertUnreconciled(reason, "10000.00");
        assertThat(session.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9999", "10001"})
    @DisplayName("Thiếu/thừa tiền vẫn ghi đúng số thực nhận và AMOUNT_MISMATCH")
    void incorrectAmountRemainsEvidenceInsteadOfPaid(String amount) {
        storedPlan();
        service.confirm(session.getOrderGroupId(), receipt(amount));
        assertUnreconciled("AMOUNT_MISMATCH", new BigDecimal(amount).setScale(2).toPlainString());
    }

    @Test
    @DisplayName("Delivery lặp đã có receipt không phát thêm thông báo UNRECONCILED hoặc xác nhận")
    void replayDoesNotPublishDuplicateEvidence() {
        when(transactions.findByIdempotencyKey("sepay:" + PROVIDER_ID))
                .thenReturn(Optional.of(new PaymentTransaction()));

        service.confirm(session.getOrderGroupId(), receipt("10000"));

        verify(sessions).lockKey("payment-receipt:sepay:" + PROVIDER_ID);
        verifyNoMoreInteractions(sessions);
        verify(transactions, never()).saveAndFlush(any());
        verifyNoInteractions(instructions, outbox, envelopes, refunds);
    }

    @Test
    @DisplayName("Lỗi ghi outbox đối soát phải nổi ra caller để transaction thật có thể rollback và retry")
    void unreconciledOutboxFailureCannotBeAcknowledgedAsSuccess() {
        storedPlan();
        session.setDueAt(RECEIVED.minusSeconds(1));
        var failure = new IllegalStateException("test outbox unavailable");
        doThrow(failure).when(outbox).write(eq("PaymentSession"), eq(Topics.PAYMENT), any());

        assertThatThrownBy(() -> service.confirm(session.getOrderGroupId(), receipt("10000"))).isSameAs(failure);
        verifyNoInteractions(refunds);
        verify(sessions, never()).saveAndFlush(any());
        // Unit test chỉ chứng minh exception propagation, không khẳng định dữ liệu DB đã rollback.
    }

    private void storedPlan() {
        when(sessions.findLockedByOrderGroupId(session.getOrderGroupId())).thenReturn(Optional.of(session));
        when(instructions.findSessionForUpdate(session.getId())).thenReturn(List.of(instruction));
        when(transactions.saveAndFlush(any())).thenAnswer(call -> {
            PaymentTransaction receipt = call.getArgument(0);
            receipt.setId(receiptId);
            return receipt;
        });
        when(envelopes.create(anyString(), anyString(), any())).thenAnswer(call -> new EventEnvelope<>(
                UUID.randomUUID(), call.getArgument(0), call.getArgument(1), NOW,
                "15800000000040008000000000000001", call.getArgument(2)));
    }

    private void assertUnreconciled(String reason, String amount) {
        PaymentTransaction stored = savedReceipt();
        assertThat(stored.getStatus()).isEqualTo(PaymentTransactionStatus.UNRECONCILED);
        assertThat(stored.getTxnReference()).isEqualTo(Long.toString(PROVIDER_ID));
        assertThat(stored.getReceivedAt()).isEqualTo(RECEIVED);
        var payload = ArgumentCaptor.forClass(PaymentUnreconciled.class);
        verify(envelopes).create(eq("PaymentUnreconciled"), eq(session.getId().toString()), payload.capture());
        PaymentUnreconciled evidence = payload.getValue();
        assertThat(evidence.sessionId()).isEqualTo(session.getId());
        assertThat(evidence.orderGroupId()).isEqualTo(session.getOrderGroupId());
        assertThat(evidence.transactionId()).isEqualTo(receiptId);
        assertThat(evidence.providerTransactionId()).isEqualTo(Long.toString(PROVIDER_ID));
        assertThat(evidence.buyerId()).isEqualTo(session.getBuyerId());
        assertThat(evidence.affectedOrderIds()).containsExactly(instruction.getOrderId());
        assertThat(evidence.amount()).isEqualTo(amount);
        assertThat(evidence.currency()).isEqualTo("VND");
        assertThat(evidence.reasonCode()).isEqualTo(reason);
        assertThat(evidence.reason()).isNotBlank();
        assertThat(evidence.receivedAt()).isEqualTo(NOW.toString());
        assertThat(evidence.providerOccurredAt()).isNull();
        verify(outbox).write(eq("PaymentSession"), eq(Topics.PAYMENT), any());
        verify(envelopes, never()).create(eq("CheckoutPaymentConfirmed"), anyString(), any());
        verify(sessions, never()).saveAndFlush(any());
        verify(instructions, never()).saveAllAndFlush(any());
        verifyNoInteractions(refunds);
        assertThat(session.getPaidAt()).isNull();
        assertThat(instruction.getStatus()).isEqualTo(PaymentInstructionStatus.PENDING);
        assertThat(instruction.getFundedTransaction()).isNull();
    }

    private PaymentTransaction savedReceipt() {
        var receipt = ArgumentCaptor.forClass(PaymentTransaction.class);
        verify(transactions).saveAndFlush(receipt.capture());
        return receipt.getValue();
    }

    private SepayWebhookRequest receipt(String amount) {
        return new SepayWebhookRequest(PROVIDER_ID, "test", "2026-11-17 18:00:00", null,
                session.getMemoCode(), session.getMemoCode(), "in", new BigDecimal(amount),
                null, null, "TEST-158", null);
    }
}
