package com.group5.paymentservice.payment;

import com.group5.common_core.exception.DomainException;
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
import com.group5.paymentservice.payment.enums.PaymentTransactionType;
import com.group5.paymentservice.payment.event.payload.CheckoutPaymentConfirmed;
import com.group5.paymentservice.payment.repository.PaymentInstructionRepository;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import com.group5.paymentservice.payment.repository.PaymentTransactionRepository;
import com.group5.paymentservice.payment.service.PaymentConfirmationService;
import com.group5.paymentservice.payment.service.RefundService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Kiểm thử nghiệp vụ xác nhận thanh toán bằng entity thật và clock cố định, không cần Spring hoặc Docker. */
class PaymentConfirmationServiceTest {
    private static final long PROVIDER_ID = 156001L;
    private static final String RECEIPT_KEY = "sepay:" + PROVIDER_ID;
    private static final Instant NOW = Instant.parse("2026-11-11T03:00:00.123456789Z");
    private static final OffsetDateTime RECEIVED_AT = NOW.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    private static final UUID RECEIPT_ID = UUID.fromString("15600000-0000-4000-8000-000000000001");
    private static final UUID GROUP_ID = UUID.fromString("15600000-0000-4000-8000-000000000002");
    private static final UUID BUYER_ID = UUID.fromString("15600000-0000-4000-8000-000000000003");

    private final PaymentSessionRepository sessions = mock(PaymentSessionRepository.class);
    private final PaymentInstructionRepository instructions = mock(PaymentInstructionRepository.class);
    private final PaymentTransactionRepository transactions = mock(PaymentTransactionRepository.class);
    private final RefundService refunds = mock(RefundService.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final EventEnvelopeFactory envelopes = mock(EventEnvelopeFactory.class);
    // Dùng clock lệch múi giờ để phát hiện việc vô tình phát paidAt không theo UTC.
    private final PaymentConfirmationService service = new PaymentConfirmationService(sessions, instructions,
            transactions, refunds, outbox, envelopes, Clock.fixed(NOW, ZoneOffset.ofHours(7)));
    private final PaymentSession session = session();
    private final List<PaymentInstruction> plan = new ArrayList<>(List.of(instruction("200000"), instruction("150000.00")));

    @Test
    @DisplayName("AC1/ST2-ST3: thu 350000, cấp đủ hai phần tiền rồi mới xử lý hoàn tiền đang chờ")
    void exactReceiptFundsBothOrdersAndPublishesTheCompleteManifestBeforeApplyingPendingRefunds() {
        storedPlan();
        persistReceipt();
        createEnvelope();
        when(sessions.saveAndFlush(session)).thenAnswer(invocation -> {
            session.setVersion(8L);
            return session;
        });

        service.confirm(GROUP_ID, request(PROVIDER_ID, "in", "350000.00"));

        PaymentTransaction receipt = capturedReceipt();
        assertThat(receipt.getId()).isEqualTo(RECEIPT_ID);
        assertThat(receipt.getSessionId()).isEqualTo(session.getId());
        assertThat(receipt.getProvider()).isEqualTo("SEPAY");
        assertThat(receipt.getTxnReference()).isEqualTo(Long.toString(PROVIDER_ID));
        assertThat(receipt.getIdempotencyKey()).isEqualTo(RECEIPT_KEY);
        assertThat(receipt.getAmount()).isEqualByComparingTo("350000.00");
        assertThat(receipt.getCurrency()).isEqualTo("VND");
        assertThat(receipt.getType()).isEqualTo(PaymentTransactionType.NORMAL);
        assertThat(receipt.getStatus()).isEqualTo(PaymentTransactionStatus.SUCCESS);
        assertThat(receipt.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertFunded(receipt);

        var event = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outbox).write(eq("PaymentSession"), eq(Topics.PAYMENT), event.capture());
        assertThat(event.getValue().aggregateId()).isEqualTo(session.getId().toString());
        CheckoutPaymentConfirmed confirmation = (CheckoutPaymentConfirmed) event.getValue().payload();
        assertThat(confirmation.sessionId()).isEqualTo(session.getId());
        assertThat(confirmation.orderGroupId()).isEqualTo(GROUP_ID);
        assertThat(confirmation.transactionId()).isEqualTo(RECEIPT_ID);
        assertThat(confirmation.buyerId()).isEqualTo(BUYER_ID);
        assertThat(confirmation.currency()).isEqualTo("VND");
        assertThat(confirmation.totalAmount()).isEqualTo("350000.00");
        assertThat(confirmation.paidAt()).isEqualTo(RECEIVED_AT.toInstant().toString());
        assertThat(session.getVersion()).isEqualTo(8L);
        assertThat(confirmation.sessionVersion()).isEqualTo(8L);
        assertThat(confirmation.instructions()).containsExactly(
                new CheckoutPaymentConfirmed.Instruction(plan.get(0).getId(), plan.get(0).getOrderId(), "200000.00"),
                new CheckoutPaymentConfirmed.Instruction(plan.get(1).getId(), plan.get(1).getOrderId(), "150000.00"));

        // Hook hoàn tiền cần nhìn thấy đủ funding và confirmation; unit test không thay thế kiểm chứng rollback tại DB.
        var order = inOrder(sessions, instructions, outbox, refunds);
        order.verify(sessions).saveAndFlush(session);
        order.verify(instructions).saveAllAndFlush(plan);
        order.verify(outbox).write(eq("PaymentSession"), eq(Topics.PAYMENT), any());
        order.verify(refunds).applyPendingCancellations(GROUP_ID);
        verifyNoMoreInteractions(outbox, refunds);
    }

    @Test
    @DisplayName("AC2/ST1: receipt đã lưu lâu hơn cửa sổ callback không thanh toán lại một nhóm khác")
    void storedReceiptIdentityStopsReplayBeforeLoadingAnyGroupOrInstruction() {
        PaymentTransaction existing = new PaymentTransaction();
        existing.setId(RECEIPT_ID);
        existing.setCreatedAt(RECEIVED_AT.minusDays(30));
        when(transactions.findByIdempotencyKey(RECEIPT_KEY)).thenReturn(Optional.of(existing));

        service.confirm(UUID.randomUUID(), request(PROVIDER_ID, "in", "350000"));

        verify(sessions).lockKey("payment-receipt:" + RECEIPT_KEY);
        verify(transactions).findByIdempotencyKey(RECEIPT_KEY);
        verifyNoMoreInteractions(sessions, transactions);
        verifyNoInteractions(instructions, envelopes, outbox, refunds);
    }

    @Test
    @DisplayName("ST1: mã giao dịch provider vẫn chống trùng khi receipt cũ dùng idempotency key khác")
    void legacyReceiptProviderIdentityStopsReplayBeforeAnyNewFunding() {
        when(transactions.existsByProviderAndTxnReference("SEPAY", Long.toString(PROVIDER_ID))).thenReturn(true);

        service.confirm(GROUP_ID, request(PROVIDER_ID, "in", "350000"));

        verify(sessions).lockKey("payment-receipt:" + RECEIPT_KEY);
        verify(transactions).findByIdempotencyKey(RECEIPT_KEY);
        verify(transactions).existsByProviderAndTxnReference("SEPAY", Long.toString(PROVIDER_ID));
        verifyNoMoreInteractions(sessions, transactions);
        verifyNoInteractions(instructions, envelopes, outbox, refunds);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidIncomingReceipts")
    @DisplayName("Dữ liệu tiền vào không hợp lệ trả PAYMENT_INVALID_WEBHOOK_REQUEST trước mọi truy cập DB")
    void invalidIncomingReceiptNeverChangesFinancialEvidence(String label, SepayWebhookRequest receipt) {
        assertThatThrownBy(() -> service.confirm(GROUP_ID, receipt)).isInstanceOfSatisfying(DomainException.class, error -> {
            assertThat(error.getCode()).isEqualTo(400);
            assertThat(error.getErrorCode()).isEqualTo("PAYMENT_INVALID_WEBHOOK_REQUEST");
        });
        verifyNoInteractions(sessions, instructions, transactions, refunds, outbox, envelopes);
    }

    private static Stream<Arguments> invalidIncomingReceipts() {
        return Stream.of(
                Arguments.of("Thiếu provider ID", request(null, "in", "350000")),
                Arguments.of("Provider ID bằng 0", request(0L, "in", "350000")),
                Arguments.of("Provider ID âm", request(-1L, "in", "350000")),
                Arguments.of("Thiếu số tiền", request(PROVIDER_ID, "in", null)),
                Arguments.of("Số tiền âm", request(PROVIDER_ID, "in", "-0.01")),
                Arguments.of("Tiền có hơn hai số thập phân", request(PROVIDER_ID, "in", "350000.001")),
                Arguments.of("Tiền vượt numeric(19,2)", request(PROVIDER_ID, "in", "100000000000000000")),
                Arguments.of("Thiếu chiều giao dịch", request(PROVIDER_ID, null, "350000")),
                Arguments.of("Chiều giao dịch rỗng", request(PROVIDER_ID, "", "350000")),
                Arguments.of("Chiều giao dịch không được hỗ trợ", request(PROVIDER_ID, "other", "350000")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"out", "OUT"})
    @DisplayName("Tiền chuyển ra không ghi receipt hoặc thay đổi funding, kể cả khi thiếu các trường tiền vào")
    void outgoingMovementDoesNotTouchAnyFinancialDependency(String direction) {
        service.confirm(GROUP_ID, request(null, direction, null));

        verifyNoInteractions(sessions, instructions, transactions, refunds, outbox, envelopes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"300000", "350000.01", "0", "99999999999999999.99"})
    @DisplayName("AC6/ST4: số tiền hợp lệ nhưng khác tổng chỉ được lưu UNRECONCILED")
    void amountMismatchStoresEvidenceWithoutFundingOrConfirmation(String amount) {
        storedPlan();
        persistReceipt();

        service.confirm(GROUP_ID, request(PROVIDER_ID, "in", amount));

        assertUnreconciledWithoutFunding();
        assertThat(capturedReceipt().getAmount()).isEqualByComparingTo(amount);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentSessionStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("Session đã đóng hoặc đã PAID không được mở lại bởi một receipt mới")
    void terminalSessionNeverFundsAgain(PaymentSessionStatus status) {
        session.setStatus(status);
        storedPlan();
        persistReceipt();

        service.confirm(GROUP_ID, request(PROVIDER_ID, "in", "350000"));

        assertUnreconciledWithoutFunding();
        assertThat(session.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("Session và instruction cùng ngoại tệ vẫn phải đối soát, không xác nhận như giao dịch VND")
    void matchingForeignCurrencyPlanCannotProduceAVndCheckoutConfirmation() {
        session.setCurrency("USD");
        plan.forEach(instruction -> instruction.setCurrency("USD"));
        storedPlan();
        persistReceipt();

        service.confirm(GROUP_ID, request(PROVIDER_ID, "in", "350000"));

        assertUnreconciledWithoutFunding();
        assertThat(capturedReceipt().getCurrency()).isEqualTo("USD");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(InvalidPlan.class)
    @DisplayName("ST2: không xác nhận một kế hoạch thiếu đơn, sai danh tính hoặc đã có funding/hủy")
    void invalidStoredInstructionPlanCannotBePaidPartially(InvalidPlan fault) {
        fault.apply(plan);
        storedPlan();
        persistReceipt();
        var before = plan.stream().map(instruction -> new InstructionEvidence(instruction.getStatus(),
                instruction.getFundedTransaction(), instruction.getPaidAt())).toList();

        service.confirm(GROUP_ID, request(PROVIDER_ID, "in", "350000"));

        assertThat(capturedReceipt().getStatus()).isEqualTo(PaymentTransactionStatus.UNRECONCILED);
        assertThat(session.getStatus()).isEqualTo(PaymentSessionStatus.PENDING);
        assertThat(session.getPaidAt()).isNull();
        assertThat(plan.stream().map(instruction -> new InstructionEvidence(instruction.getStatus(),
                instruction.getFundedTransaction(), instruction.getPaidAt())).toList()).isEqualTo(before);
        verify(sessions, never()).saveAndFlush(any());
        verify(instructions, never()).saveAllAndFlush(any());
        verify(envelopes, never()).create(eq("CheckoutPaymentConfirmed"), any(), any());
        verifyNoInteractions(refunds);
    }

    @Test
    @DisplayName("Receipt đến sau deadline một microsecond chỉ được lưu để đối soát")
    void receiptAfterTheSessionDeadlineNeverFundsThePlan() {
        session.setDueAt(RECEIVED_AT.minusNanos(1000));
        storedPlan();
        persistReceipt();

        service.confirm(GROUP_ID, request(PROVIDER_ID, "in", "350000"));

        assertUnreconciledWithoutFunding();
    }

    @Test
    @DisplayName("Receipt đúng deadline vẫn hợp lệ với thời điểm UTC có độ chính xác microsecond")
    void receiptAtTheSessionAndInstructionDeadlineIsStillPayable() {
        session.setDueAt(RECEIVED_AT);
        plan.forEach(instruction -> instruction.setDueAt(RECEIVED_AT));
        storedPlan();
        persistReceipt();
        createEnvelope();

        service.confirm(GROUP_ID, request(PROVIDER_ID, "IN", "350000"));

        PaymentTransaction receipt = capturedReceipt();
        assertThat(receipt.getStatus()).isEqualTo(PaymentTransactionStatus.SUCCESS);
        assertFunded(receipt);
        verify(refunds).applyPendingCancellations(GROUP_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"350000", "350000.0", "350000.000000", "3.5E+5"})
    @DisplayName("Các cách biểu diễn cùng số tiền không bị coi là sai tiền hoặc sai độ chính xác")
    void numericallyEqualAmountsFundExactlyTheStoredSessionTotal(String amount) {
        storedPlan();
        persistReceipt();
        createEnvelope();

        service.confirm(GROUP_ID, request(PROVIDER_ID, "in", amount));

        assertThat(capturedReceipt().getStatus()).isEqualTo(PaymentTransactionStatus.SUCCESS);
        assertFunded(capturedReceipt());
        verify(outbox).write(eq("PaymentSession"), eq(Topics.PAYMENT), any());
    }

    @Test
    @DisplayName("Lỗi lưu receipt phải truyền ra ngoài trước khi cấp tiền hoặc gọi hook hoàn tiền")
    void receiptPersistenceFailureDoesNotStartFundingOrEmitConfirmation() {
        storedPlan();
        var failure = new DataIntegrityViolationException("receipt write failed");
        when(transactions.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.confirm(GROUP_ID, request(PROVIDER_ID, "in", "350000")))
                .isSameAs(failure);

        assertThat(session.getStatus()).isEqualTo(PaymentSessionStatus.PENDING);
        assertThat(plan).allSatisfy(instruction -> {
            assertThat(instruction.getStatus()).isEqualTo(PaymentInstructionStatus.PENDING);
            assertThat(instruction.getFundedTransaction()).isNull();
        });
        verify(sessions, never()).saveAndFlush(any());
        verify(instructions, never()).saveAllAndFlush(any());
        verifyNoInteractions(envelopes, outbox, refunds);
    }

    @Test
    @DisplayName("Lỗi outbox phải truyền ra ngoài và chưa được chạy hook refund")
    void confirmationOutboxFailureIsNotSwallowedAndDoesNotRunPendingRefunds() {
        storedPlan();
        persistReceipt();
        createEnvelope();
        var failure = new DataIntegrityViolationException("confirmation write failed");
        doThrow(failure).when(outbox).write(eq("PaymentSession"), eq(Topics.PAYMENT), any());

        assertThatThrownBy(() -> service.confirm(GROUP_ID, request(PROVIDER_ID, "in", "350000")))
                .isSameAs(failure);

        verifyNoInteractions(refunds);
    }

    @Test
    @DisplayName("Lỗi xử lý cancellation đang chờ phải truyền tới caller để transaction không được ACK thành công")
    void pendingRefundFailureIsNotSwallowedAfterTheConfirmationWasWritten() {
        storedPlan();
        persistReceipt();
        createEnvelope();
        var failure = new IllegalStateException("pending refund failed");
        doThrow(failure).when(refunds).applyPendingCancellations(GROUP_ID);

        assertThatThrownBy(() -> service.confirm(GROUP_ID, request(PROVIDER_ID, "in", "350000")))
                .isSameAs(failure);

        // Unit test chỉ kiểm tra exception đi ra caller; rollback cùng outbox được integration test kiểm chứng.
        var order = inOrder(outbox, refunds);
        order.verify(outbox).write(eq("PaymentSession"), eq(Topics.PAYMENT), any());
        order.verify(refunds).applyPendingCancellations(GROUP_ID);
    }

    private void storedPlan() {
        when(sessions.findLockedByOrderGroupId(GROUP_ID)).thenReturn(Optional.of(session));
        when(instructions.findSessionForUpdate(session.getId())).thenReturn(plan);
    }

    private void persistReceipt() {
        // Giả lập ID do persistence cấp; không giả lập transaction hay rollback của PostgreSQL.
        when(transactions.saveAndFlush(any())).thenAnswer(invocation -> {
            PaymentTransaction receipt = invocation.getArgument(0);
            receipt.setId(RECEIPT_ID);
            return receipt;
        });
    }

    private void createEnvelope() {
        when(envelopes.create(eq("CheckoutPaymentConfirmed"), eq(session.getId().toString()),
                any(CheckoutPaymentConfirmed.class))).thenAnswer(invocation -> new EventEnvelope<>(
                UUID.randomUUID(), invocation.getArgument(0), invocation.getArgument(1), NOW,
                "15600000000040008000000000000001", invocation.getArgument(2)));
    }

    private PaymentTransaction capturedReceipt() {
        var receipt = ArgumentCaptor.forClass(PaymentTransaction.class);
        verify(transactions).saveAndFlush(receipt.capture());
        return receipt.getValue();
    }

    private void assertFunded(PaymentTransaction receipt) {
        assertThat(session.getStatus()).isEqualTo(PaymentSessionStatus.PAID);
        assertThat(session.getPaidAt()).isEqualTo(RECEIVED_AT);
        assertThat(plan).hasSize(2).allSatisfy(instruction -> {
            assertThat(instruction.getStatus()).isEqualTo(PaymentInstructionStatus.PAID);
            assertThat(instruction.getFundedTransaction()).isSameAs(receipt);
            assertThat(instruction.getPaidAt()).isEqualTo(RECEIVED_AT);
        });
    }

    private void assertUnreconciledWithoutFunding() {
        assertThat(capturedReceipt().getStatus()).isEqualTo(PaymentTransactionStatus.UNRECONCILED);
        assertThat(session.getPaidAt()).isNull();
        assertThat(plan).hasSize(2).allSatisfy(instruction -> {
            assertThat(instruction.getStatus()).isEqualTo(PaymentInstructionStatus.PENDING);
            assertThat(instruction.getFundedTransaction()).isNull();
            assertThat(instruction.getPaidAt()).isNull();
        });
        verify(sessions, never()).saveAndFlush(any());
        verify(instructions, never()).saveAllAndFlush(any());
        verify(envelopes, never()).create(eq("CheckoutPaymentConfirmed"), any(), any());
        verifyNoInteractions(refunds);
    }

    private PaymentSession session() {
        PaymentSession result = new PaymentSession();
        result.setId(UUID.randomUUID());
        result.setOrderGroupId(GROUP_ID);
        result.setBuyerId(BUYER_ID);
        result.setTotalAmount(new BigDecimal("350000.00"));
        result.setDueAt(RECEIVED_AT.plusMinutes(15));
        result.setVersion(7L);
        return result;
    }

    private PaymentInstruction instruction(String amount) {
        PaymentInstruction result = new PaymentInstruction();
        result.setId(UUID.randomUUID());
        result.setOrderId(UUID.randomUUID());
        result.setSession(session);
        result.setBuyerId(BUYER_ID);
        result.setDueAt(RECEIVED_AT.plusMinutes(15));
        result.setAmount(new BigDecimal(amount));
        return result;
    }

    private static SepayWebhookRequest request(Long id, String direction, String amount) {
        return new SepayWebhookRequest(id, null, null, null, "NX156UNIT", null, direction,
                amount == null ? null : new BigDecimal(amount), null, null, null, null);
    }

    private record InstructionEvidence(PaymentInstructionStatus status, PaymentTransaction fundedBy,
                                       OffsetDateTime paidAt) { }

    private enum InvalidPlan {
        EMPTY, MISSING_SHARE, WRONG_BUYER, WRONG_CURRENCY, PAST_DEADLINE, CANCELED_AT,
        FUNDED, PAID, CANCELED, EXPIRED, NEGATIVE_SHARE, WRONG_TOTAL;

        void apply(List<PaymentInstruction> plan) {
            PaymentInstruction first = plan.getFirst();
            switch (this) {
                case EMPTY -> plan.clear();
                case MISSING_SHARE -> plan.removeLast();
                case WRONG_BUYER -> first.setBuyerId(UUID.randomUUID());
                case WRONG_CURRENCY -> first.setCurrency("USD");
                case PAST_DEADLINE -> first.setDueAt(RECEIVED_AT.minusNanos(1000));
                case CANCELED_AT -> first.setCanceledAt(RECEIVED_AT.minusMinutes(1));
                case FUNDED -> first.setFundedTransaction(new PaymentTransaction());
                case PAID -> first.setStatus(PaymentInstructionStatus.PAID);
                case CANCELED -> first.setStatus(PaymentInstructionStatus.CANCELED);
                case EXPIRED -> first.setStatus(PaymentInstructionStatus.EXPIRED);
                case NEGATIVE_SHARE -> {
                    first.setAmount(new BigDecimal("-1.00"));
                    plan.getLast().setAmount(new BigDecimal("350001.00"));
                }
                case WRONG_TOTAL -> first.setAmount(new BigDecimal("200000.01"));
            }
        }
    }
}
