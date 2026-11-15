package com.group5.paymentservice.payment.service;

import com.group5.common_core.exception.DomainException;
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
import com.group5.paymentservice.payment.event.payload.PaymentUnreconciled;
import com.group5.paymentservice.payment.repository.PaymentInstructionRepository;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import com.group5.paymentservice.payment.repository.PaymentTransactionRepository;
import com.group5.paymentservice.shared.exception.PaymentErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Ghi nhận tiền về và xác nhận toàn bộ nhóm đơn trong cùng một transaction với outbox và hoàn tiền. */
@Service
public class PaymentConfirmationService {
    private static final Logger log = LoggerFactory.getLogger(PaymentConfirmationService.class);
    private final PaymentSessionRepository sessions;
    private final PaymentInstructionRepository instructions;
    private final PaymentTransactionRepository transactions;
    private final RefundService refunds;
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;
    private final Clock clock;

    public PaymentConfirmationService(PaymentSessionRepository sessions, PaymentInstructionRepository instructions,
                                      PaymentTransactionRepository transactions, RefundService refunds,
                                      OutboxWriter outbox, EventEnvelopeFactory envelopes, Clock clock) {
        this.sessions = sessions;
        this.instructions = instructions;
        this.transactions = transactions;
        this.refunds = refunds;
        this.outbox = outbox;
        this.envelopes = envelopes;
        this.clock = clock;
    }

    /** Xử lý một giao dịch SePay đúng một lần; giao dịch không đủ điều kiện được giữ để đối soát. */
    @Transactional
    public void confirm(UUID groupId, SepayWebhookRequest request) {
        // Tiền chuyển ra khỏi tài khoản không thể dùng để thanh toán nhóm đơn.
        if ("out".equalsIgnoreCase(request.transferType())) return;
        validate(request);
        String identity = "sepay:" + request.id();
        // Khóa theo mã giao dịch SePay trước để hai webhook đồng thời chỉ ghi nhận một lần,
        // kể cả khi webhook lặp đổi memo hoặc bản ghi cũ dùng idempotency key khác.
        sessions.lockKey("payment-receipt:" + identity);
        if (transactions.findByIdempotencyKey(identity).isPresent()
                || transactions.existsByProviderAndTxnReference("SEPAY", request.id().toString())) return;
        // Giữ thứ tự khóa nhóm -> instruction giống luồng hủy và hoàn tiền để tránh deadlock.
        sessions.lockKey("payment-group:" + groupId);
        PaymentSession session = sessions.findLockedByOrderGroupId(groupId).orElseThrow();
        List<PaymentInstruction> plan = instructions.findSessionForUpdate(session.getId());
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        // Chỉ xác nhận khi session còn hạn và số tiền khớp toàn bộ kế hoạch thanh toán đã lưu.
        boolean payable = session.getStatus() == PaymentSessionStatus.PENDING
                && !session.getDueAt().isBefore(now)
                && session.getTotalAmount().signum() > 0
                && "VND".equals(session.getCurrency())
                && request.transferAmount().compareTo(session.getTotalAmount()) == 0
                && validPlan(session, plan, now);

        // Lưu bằng chứng tiền về trước; sai tiền hoặc hết điều kiện thì UNRECONCILED, không phát xác nhận.
        PaymentTransaction receipt = new PaymentTransaction();
        receipt.setSessionId(session.getId());
        receipt.setProvider("SEPAY");
        receipt.setTxnReference(request.id().toString());
        receipt.setIdempotencyKey(identity);
        receipt.setAmount(request.transferAmount());
        receipt.setCurrency(session.getCurrency());
        receipt.setType(PaymentTransactionType.NORMAL);
        receipt.setStatus(payable ? PaymentTransactionStatus.SUCCESS : PaymentTransactionStatus.UNRECONCILED);
        receipt.setReceivedAt(now);
        PaymentTransaction stored = transactions.saveAndFlush(receipt);
        if (!payable) {
            Unreconciled reason = reasonOf(session, plan, now);
            log.warn("SePay receipt requires reconciliation: sessionId={}, sepayTransactionId={}, orderGroupId={}, "
                    + "amount={}, reasonCode={}", session.getId(), request.id(), groupId, request.transferAmount(),
                    reason.name());
            if (request.transferAmount().signum() > 0 && !plan.isEmpty()) {
                PaymentUnreconciled unreconciled = new PaymentUnreconciled(session.getId(), groupId, stored.getId(),
                        request.id().toString(), session.getBuyerId(), money(request.transferAmount()),
                        session.getCurrency(), plan.stream().map(PaymentInstruction::getOrderId).distinct().toList(),
                        reason.name(), reason.text, now.toInstant().toString(), null);
                outbox.write("PaymentSession", Topics.PAYMENT,
                        envelopes.create("PaymentUnreconciled", session.getId().toString(), unreconciled));
            }
            return;
        }

        // Cùng một receipt cấp tiền cho toàn bộ instruction; flush để payload lấy version đã cập nhật.
        session.setStatus(PaymentSessionStatus.PAID);
        session.setPaidAt(now);
        sessions.saveAndFlush(session);
        for (PaymentInstruction instruction : plan) {
            instruction.setStatus(PaymentInstructionStatus.PAID);
            instruction.setFundedTransaction(stored);
            instruction.setPaidAt(now);
        }
        instructions.saveAllAndFlush(plan);
        // Phát một manifest đầy đủ qua outbox; aggregate là session, Kafka key được chọn theo nhóm đơn.
        CheckoutPaymentConfirmed confirmed = new CheckoutPaymentConfirmed(session.getId(), groupId, stored.getId(),
                session.getBuyerId(), session.getCurrency(), money(session.getTotalAmount()), now.toInstant().toString(),
                session.getVersion(), plan.stream().map(instruction -> new CheckoutPaymentConfirmed.Instruction(
                        instruction.getId(), instruction.getOrderId(), money(instruction.getAmount()))).toList());
        outbox.write("PaymentSession", Topics.PAYMENT,
                envelopes.create("CheckoutPaymentConfirmed", session.getId().toString(), confirmed));
        // Áp dụng các yêu cầu hủy đến trước khi tiền về; funding, event và refund cùng commit hoặc rollback.
        refunds.applyPendingCancellations(groupId);
    }

    /** Chặn thanh toán một phần: mọi instruction phải còn chờ, đúng người mua/tiền tệ và cộng đủ tổng session. */
    private static boolean validPlan(PaymentSession session, List<PaymentInstruction> plan, OffsetDateTime now) {
        return !plan.isEmpty() && plan.stream().allMatch(instruction ->
                instruction.getStatus() == PaymentInstructionStatus.PENDING
                        && instruction.getFundedTransaction() == null && instruction.getCanceledAt() == null
                        && instruction.getBuyerId().equals(session.getBuyerId())
                        && instruction.getCurrency().equals(session.getCurrency())
                        && !instruction.getDueAt().isBefore(now) && instruction.getAmount().signum() >= 0)
                && plan.stream().map(PaymentInstruction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .compareTo(session.getTotalAmount()) == 0;
    }

    private static Unreconciled reasonOf(PaymentSession session, List<PaymentInstruction> plan, OffsetDateTime now) {
        if (session.getStatus() == PaymentSessionStatus.PAID) return Unreconciled.ALREADY_PAID;
        if (session.getStatus() == PaymentSessionStatus.CANCELED) return Unreconciled.CANCELLED_ORDER_GROUP;
        if (session.getStatus() == PaymentSessionStatus.EXPIRED || session.getDueAt().isBefore(now)) {
            return Unreconciled.LATE_PAYMENT;
        }
        if (plan.stream().anyMatch(instruction -> instruction.getCanceledAt() != null
                || instruction.getStatus() == PaymentInstructionStatus.CANCELED)) {
            return Unreconciled.CANCELLED_ORDER;
        }
        if (plan.stream().anyMatch(instruction -> instruction.getStatus() == PaymentInstructionStatus.EXPIRED
                || instruction.getDueAt().isBefore(now))) {
            return Unreconciled.LATE_PAYMENT;
        }
        if (!"VND".equals(session.getCurrency())) return Unreconciled.CURRENCY_MISMATCH;
        return Unreconciled.AMOUNT_MISMATCH;
    }

    private enum Unreconciled {
        LATE_PAYMENT("Payment arrived after the payment deadline. Support will contact you about a refund."),
        CANCELLED_ORDER_GROUP("Payment arrived after this checkout was cancelled. Support will contact you about a refund."),
        CANCELLED_ORDER("Payment arrived after an order in this checkout was cancelled. Support will contact you about a refund."),
        AMOUNT_MISMATCH("The amount transferred does not match the order total. Support will contact you."),
        CURRENCY_MISMATCH("The payment currency does not match the order. Support will contact you."),
        ALREADY_PAID("This checkout was already paid. Support will contact you about the extra transfer.");

        private final String text;

        Unreconciled(String text) {
            this.text = text;
        }
    }

    /** Kiểm tra mã giao dịch, chiều tiền vào và giới hạn numeric(19,2) trước khi ghi dữ liệu. */
    private static void validate(SepayWebhookRequest request) {
        BigDecimal amount = request.transferAmount();
        if (request.id() == null || request.id() <= 0 || !"in".equalsIgnoreCase(request.transferType())
                || amount == null || amount.signum() < 0 || amount.stripTrailingZeros().scale() > 2
                || amount.compareTo(new BigDecimal("99999999999999999.99")) > 0) {
            throw new DomainException(PaymentErrorCode.INVALID_WEBHOOK_REQUEST);
        }
    }

    /** Giữ tiền trong event dưới dạng chuỗi thập phân đúng hai chữ số, không tự làm tròn. */
    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
