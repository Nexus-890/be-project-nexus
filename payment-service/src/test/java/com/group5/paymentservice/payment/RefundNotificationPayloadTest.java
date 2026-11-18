package com.group5.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.dto.request.RefundRequest;
import com.group5.paymentservice.payment.entity.PaymentInstruction;
import com.group5.paymentservice.payment.entity.PaymentSession;
import com.group5.paymentservice.payment.entity.PaymentTransaction;
import com.group5.paymentservice.payment.entity.Refund;
import com.group5.paymentservice.payment.enums.PaymentInstructionStatus;
import com.group5.paymentservice.payment.enums.PaymentTransactionStatus;
import com.group5.paymentservice.payment.enums.PaymentTransactionType;
import com.group5.paymentservice.payment.event.payload.OrderRefundCompleted;
import com.group5.paymentservice.payment.event.payload.OrderRefundRequested;
import com.group5.paymentservice.payment.repository.PaymentInstructionRepository;
import com.group5.paymentservice.payment.repository.PaymentPendingOrderCancellationRepository;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import com.group5.paymentservice.payment.repository.PaymentTransactionRepository;
import com.group5.paymentservice.payment.repository.RefundRepository;
import com.group5.paymentservice.payment.service.RefundService;
import com.group5.paymentservice.payout.repository.PayoutRepository;

import jakarta.validation.Validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Kiểm tra người nhận hoàn tiền qua service thật; repository/outbox được thay bằng mock. */
class RefundNotificationPayloadTest {
    private final UUID buyer = UUID.randomUUID();
    private final CurrentUser support =
            new CurrentUser(UUID.randomUUID(), Set.of("SUPPORT_STAFF"), Set.of("ORDER.REFUND"));
    private final PaymentInstructionRepository instructions =
            mock(PaymentInstructionRepository.class);
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final PaymentTransactionRepository transactions =
            mock(PaymentTransactionRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final EventEnvelopeFactory envelopes = mock(EventEnvelopeFactory.class);
    private final PaymentInstruction instruction = new PaymentInstruction();
    private final PayoutRepository payouts = mock(PayoutRepository.class);
    private RefundService service;

    @BeforeEach
    void setup() {
        // Buyer của instruction khác Support và cả buyer ở session: service phải dùng đúng chủ đơn
        // đã lưu.
        PaymentSession session = new PaymentSession();
        session.setId(UUID.randomUUID());
        session.setOrderGroupId(UUID.randomUUID());
        session.setBuyerId(UUID.randomUUID());
        instruction.setId(UUID.randomUUID());
        instruction.setOrderId(UUID.randomUUID());
        instruction.setBuyerId(buyer);
        instruction.setSession(session);
        instruction.setAmount(new BigDecimal("300000.00"));
        instruction.setStatus(PaymentInstructionStatus.PAID);
        instruction.setCanceledAt(OffsetDateTime.now(ZoneOffset.UTC));
        instruction.setVersion(2L);
        PaymentTransaction funding = new PaymentTransaction();
        funding.setId(UUID.randomUUID());
        funding.setSessionId(session.getId());
        funding.setType(PaymentTransactionType.NORMAL);
        funding.setStatus(PaymentTransactionStatus.SUCCESS);
        funding.setCurrency("VND");
        instruction.setFundedTransaction(funding);
        when(instructions.findGroupIdByOrderId(instruction.getOrderId()))
                .thenReturn(Optional.of(session.getOrderGroupId()));
        when(instructions.findLockedByOrderId(instruction.getOrderId()))
                .thenReturn(Optional.of(instruction));
        when(refunds.saveAndFlush(any()))
                .thenAnswer(
                        invocation -> {
                            Refund refund = invocation.getArgument(0);
                            if (refund.getId() == null) refund.setId(UUID.randomUUID());
                            if (refund.getRequestedAt() == null)
                                refund.setRequestedAt(OffsetDateTime.now(ZoneOffset.UTC));
                            refund.setVersion(
                                    refund.getVersion() == null ? 0L : refund.getVersion() + 1);
                            return refund;
                        });
        when(envelopes.create(anyString(), anyString(), any()))
                .thenAnswer(
                        invocation ->
                                new EventEnvelope<>(
                                        UUID.randomUUID(),
                                        invocation.getArgument(0),
                                        invocation.getArgument(1),
                                        Instant.now(),
                                        "refund-notification-test",
                                        invocation.getArgument(2)));
        service =
                new RefundService(
                        mock(IdempotentConsumer.class),
                        mock(PaymentSessionRepository.class),
                        instructions,
                        transactions,
                        refunds,
                        mock(PaymentPendingOrderCancellationRepository.class),
                        outbox,
                        envelopes,
                        mock(Validator.class),
                        payouts);
    }

    @Test
    void manualPayoutReservationPreventsRefundAuthorizationAndFinancialPosting() {
        when(payouts.hasReservedManualProceeds(instruction.getOrderId())).thenReturn(true);
        assertThatThrownBy(
                        () ->
                                service.request(
                                        support,
                                        "blocked-refund",
                                        new RefundRequest(
                                                instruction.getOrderId(), null, "Cancelled")))
                .isInstanceOf(com.group5.common_core.exception.DomainException.class);
        verify(refunds, never()).saveAndFlush(any());
        verify(transactions, never()).saveAndFlush(any());
        verify(outbox, never()).write(anyString(), anyString(), any());
    }

    @Test
    void requestedEventTargetsStoredBuyerAndKeepsRefundFacts() {
        // Support tạo yêu cầu; event vẫn gửi buyer, giữ amount/reason và danh tính actor để audit.
        service.request(
                support,
                "notification-refund",
                new RefundRequest(instruction.getOrderId(), null, "Out of stock"));

        OrderRefundRequested payload =
                (OrderRefundRequested) emitted("OrderRefundRequested").payload();
        assertThat(payload.buyerId()).isEqualTo(buyer).isNotEqualTo(support.id());
        assertThat(payload.actorId()).isEqualTo(support.id());
        assertThat(payload.orderId()).isEqualTo(instruction.getOrderId());
        assertThat(payload.amount()).isEqualTo("300000.00");
        assertThat(payload.reason()).isEqualTo("Out of stock");
        assertThat(payload.status()).isEqualTo("REQUESTED");
    }

    @Test
    void completedEventTargetsStoredBuyerAndKeepsTransferIdentity() {
        Refund refund = new Refund();
        refund.setId(UUID.randomUUID());
        refund.setOrderId(instruction.getOrderId());
        refund.setAmount(instruction.getAmount());
        refund.setReason("Out of stock");
        refund.setRequestedAt(OffsetDateTime.now(ZoneOffset.UTC));
        refund.setVersion(0L);
        when(refunds.findOrderIdById(refund.getId()))
                .thenReturn(Optional.of(instruction.getOrderId()));
        when(refunds.findLockedById(refund.getId())).thenReturn(Optional.of(refund));
        UUID transferId = UUID.randomUUID();
        when(transactions.saveAndFlush(any()))
                .thenAnswer(
                        invocation -> {
                            PaymentTransaction transfer = invocation.getArgument(0);
                            transfer.setId(transferId);
                            return transfer;
                        });

        // Hoàn tất dùng cùng instruction, không đổi người nhận sang nhân viên xác nhận chuyển tiền.
        service.complete(support, refund.getId());

        OrderRefundCompleted payload =
                (OrderRefundCompleted) emitted("OrderRefundCompleted").payload();
        assertThat(payload.buyerId()).isEqualTo(buyer).isNotEqualTo(support.id());
        assertThat(payload.actorId()).isEqualTo(support.id());
        assertThat(payload.completedTransactionId()).isEqualTo(transferId);
        assertThat(payload.refundVersion()).isEqualTo(1);
        assertThat(payload.amount()).isEqualTo("300000.00");
        assertThat(payload.status()).isEqualTo("COMPLETED");
    }

    private EventEnvelope<?> emitted(String eventType) {
        ArgumentCaptor<EventEnvelope<?>> event = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outbox).write(eq("Refund"), eq(Topics.PAYMENT), event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(eventType);
        assertThat(event.getValue().aggregateId()).isEqualTo(instruction.getOrderId().toString());
        return event.getValue();
    }
}
