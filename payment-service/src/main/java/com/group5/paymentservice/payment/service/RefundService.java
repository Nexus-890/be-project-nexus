package com.group5.paymentservice.payment.service;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.dto.request.RefundRequest;
import com.group5.paymentservice.payment.dto.response.RefundResult;
import com.group5.paymentservice.payment.dto.response.RefundView;
import com.group5.paymentservice.payment.entity.PaymentInstruction;
import com.group5.paymentservice.payment.entity.PaymentTransaction;
import com.group5.paymentservice.payment.entity.Refund;
import com.group5.paymentservice.payment.enums.PaymentInstructionStatus;
import com.group5.paymentservice.payment.enums.PaymentTransactionStatus;
import com.group5.paymentservice.payment.enums.PaymentTransactionType;
import com.group5.paymentservice.payment.event.payload.OrderCancelled;
import com.group5.paymentservice.payment.event.payload.OrderRefundRequested;
import com.group5.paymentservice.payment.repository.PaymentInstructionRepository;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import com.group5.paymentservice.payment.repository.RefundRepository;
import com.group5.paymentservice.shared.exception.PaymentErrorCode;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class RefundService {
    public static final String CANCELLATION_CONSUMER = "payment-order-cancelled";
    static final UUID SYSTEM_ACTOR = new UUID(0, 0);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[\\x21-\\x7E]{1,100}");
    private static final String SYSTEM_KEY_PREFIX = "system:";

    private final IdempotentConsumer consumer;
    private final PaymentSessionRepository sessions;
    private final PaymentInstructionRepository instructions;
    private final RefundRepository refunds;
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;
    private final Validator validator;

    public RefundService(IdempotentConsumer consumer, PaymentSessionRepository sessions,
                         PaymentInstructionRepository instructions, RefundRepository refunds,
                         OutboxWriter outbox, EventEnvelopeFactory envelopes, Validator validator) {
        this.consumer = consumer;
        this.sessions = sessions;
        this.instructions = instructions;
        this.refunds = refunds;
        this.outbox = outbox;
        this.envelopes = envelopes;
        this.validator = validator;
    }

    public void cancelled(EventEnvelope<OrderCancelled> event) {
        OrderCancelled cancellation = event.payload();
        require(validator.validate(cancellation).isEmpty(), "Invalid OrderCancelled payload");
        require(event.aggregateId().equals(cancellation.orderId().toString()), "Order and aggregate must match");
        consumer.handle(CANCELLATION_CONSUMER, event, this::refundCancelledOrder);
    }

    private void refundCancelledOrder(EventEnvelope<OrderCancelled> event) {
        OrderCancelled cancellation = event.payload();
        Optional<PaymentInstruction> known = instructions.findByOrderId(cancellation.orderId());
        if (known.isEmpty()) {
            // ĐƠN CHƯA TRẢ TIỀN MÀ CHƯA CÓ MANIFEST THÌ KHÔNG CÓ GÌ ĐỂ HOÀN -> BỎ QUA
            // COMMERCE NÓI ĐÃ TRẢ MÀ PAYMENT CHƯA CÓ INSTRUCTION = SAI THỨ TỰ -> NÉM LỖI ĐỂ KAFKA THỬ LẠI
            if (!isZero(cancellation.paidAmount())) {
                throw new IllegalStateException("Paid order has no payment instruction yet");
            }
            return;
        }
        // KHÓA CÙNG THỨ TỰ VỚI #121: GROUP -> INSTRUCTION -> REFUND, TRÁNH DEADLOCK VỚI WEBHOOK/HỦY NHÓM
        sessions.lockKey("payment-group:" + known.get().getSession().getOrderGroupId());
        PaymentInstruction instruction = instructions.findLockedByOrderId(cancellation.orderId()).orElseThrow();
        Optional<Refund> existing = refunds.findLockedByOrderId(cancellation.orderId());
        if (cancellation.orderVersion() < instruction.getLastOrderVersion() || !isFunded(instruction)) {
            return;
        }
        instruction.setCanceledAt(cancellation.cancelledAt().atOffset(ZoneOffset.UTC));
        instruction.setLastOrderVersion(cancellation.orderVersion());
        instruction.setLastOrderEventAt(event.occurredAt().atOffset(ZoneOffset.UTC));
        instruction.setUpdatedBy(cancellation.actorId());
        instructions.saveAndFlush(instruction);
        if (existing.isPresent()) {
            return;
        }

        Refund refund = new Refund();
        refund.setOrderId(cancellation.orderId());
        refund.setAmount(instruction.getAmount());
        refund.setCurrency(instruction.getCurrency());
        refund.setReason(cancellation.reason());
        refund.setIdempotencyKey("system:cancel-refund:" + cancellation.orderId());
        refund.setRequestedBy(SYSTEM_ACTOR);
        refund.setCreatedBy(SYSTEM_ACTOR);
        refund.setUpdatedBy(SYSTEM_ACTOR);
        publishRequested(refunds.saveAndFlush(refund), instruction, SYSTEM_ACTOR);
    }

    @Transactional
    public RefundResult request(CurrentUser user, String idempotencyKey, RefundRequest body) {
        if (user == null || user.id() == null) {
            throw new DomainException(PaymentErrorCode.UNAUTHENTICATED);
        }
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()
                || idempotencyKey.startsWith(SYSTEM_KEY_PREFIX)) {
            throw new DomainException(PaymentErrorCode.INVALID_REFUND_REQUEST);
        }
        // KHÓA THEO KEY TRƯỚC: 2 REQUEST CÙNG KEY CHẠY SONG SONG THÌ REQUEST SAU CHỜ RỒI NHẬN LẠI ĐÚNG REFUND CŨ
        // KIỂM TRA REPLAY TRƯỚC MỌI ĐIỀU KIỆN KHÁC -> GỬI LẠI SAU KHI ĐÃ COMPLETED VẪN TRẢ CÙNG refundId
        sessions.lockKey("refund-key:" + idempotencyKey);
        Optional<Refund> replay = refunds.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            if (!sameRequest(replay.get(), body)) {
                throw new DomainException(PaymentErrorCode.REFUND_IDEMPOTENCY_CONFLICT);
            }
            return result(replay.get());
        }

        PaymentInstruction known = instructions.findByOrderId(body.orderId())
                .orElseThrow(() -> new DomainException(PaymentErrorCode.ORDER_NOT_FOUND));
        sessions.lockKey("payment-group:" + known.getSession().getOrderGroupId());
        PaymentInstruction instruction = instructions.findLockedByOrderId(body.orderId()).orElseThrow();
        // PHẢI ĐÃ TRẢ TIỀN VÀ PAYMENT ĐÃ NHẬN OrderCancelled, KHÔNG THÌ COMMERCE KHÔNG CHUYỂN ĐƯỢC TRẠNG THÁI ĐƠN
        if (!isFunded(instruction) || instruction.getCanceledAt() == null) {
            throw new DomainException(PaymentErrorCode.ORDER_NOT_REFUNDABLE);
        }
        if (refunds.findLockedByOrderId(body.orderId()).isPresent()) {
            throw new DomainException(PaymentErrorCode.REFUND_EXISTS);
        }
        BigDecimal amount = body.amount() == null ? instruction.getAmount() : body.amount();
        // KHÔNG HOÀN QUÁ SỐ TIỀN ĐƠN NÀY ĐÃ TRẢ (CHỈ PHẦN CỦA ĐƠN NÀY, KHÔNG PHẢI CẢ SESSION)
        if (amount.compareTo(instruction.getAmount()) > 0) {
            throw new DomainException(PaymentErrorCode.REFUND_AMOUNT_EXCEEDED);
        }

        Refund refund = new Refund();
        refund.setOrderId(body.orderId());
        refund.setAmount(amount);
        refund.setCurrency(instruction.getCurrency());
        refund.setReason(body.reason());
        refund.setIdempotencyKey(idempotencyKey);
        refund.setRequestedBy(user.id());
        refund.setCreatedBy(user.id());
        refund.setUpdatedBy(user.id());
        Refund saved;
        try {
            saved = refunds.saveAndFlush(refund);
        } catch (DataIntegrityViolationException exception) {
            throw new DomainException(PaymentErrorCode.REFUND_EXISTS);
        }
        publishRequested(saved, instruction, user.id());
        return result(saved);
    }

    @Transactional(readOnly = true)
    public List<RefundView> list(UUID orderId) {
        return refunds.findByOrderId(orderId).map(RefundService::view).stream().toList();
    }

    private static boolean sameRequest(Refund stored, RefundRequest body) {
        return stored.getOrderId().equals(body.orderId())
                && stored.getReason().equals(body.reason())
                && (body.amount() == null || stored.getAmount().compareTo(body.amount()) == 0);
    }

    private static RefundResult result(Refund refund) {
        return new RefundResult(refund.getId(), refund.getStatus());
    }

    static RefundView view(Refund refund) {
        return new RefundView(refund.getId(), refund.getOrderId(), refund.getAmount(), refund.getCurrency(),
                refund.getStatus(), refund.getReason(), refund.getRequestedAt(), refund.getCompletedAt());
    }

    // ĐÃ TRẢ TIỀN HAY CHƯA LÀ DO DỮ LIỆU CỦA PAYMENT QUYẾT ĐỊNH, KHÔNG TIN paidAmount CỦA COMMERCE
    // PHẢI CÓ GIAO DỊCH NORMAL/SUCCESS CÙNG SESSION, CÙNG TIỀN TỆ THÌ MỚI ĐƯỢC HOÀN
    boolean isFunded(PaymentInstruction instruction) {
        PaymentTransaction funding = instruction.getFundedTransaction();
        return instruction.getStatus() == PaymentInstructionStatus.PAID
                && funding != null
                && funding.getType() == PaymentTransactionType.NORMAL
                && funding.getStatus() == PaymentTransactionStatus.SUCCESS
                && funding.getSessionId().equals(instruction.getSession().getId())
                && funding.getCurrency().equals(instruction.getCurrency())
                && instruction.getAmount().signum() > 0;
    }

    void publishRequested(Refund refund, PaymentInstruction instruction, UUID actorId) {
        outbox.write("Refund", Topics.PAYMENT, envelopes.create("OrderRefundRequested",
                refund.getOrderId().toString(), requested(refund, instruction, actorId)));
    }

    private static OrderRefundRequested requested(Refund refund, PaymentInstruction instruction, UUID actorId) {
        return new OrderRefundRequested(refund.getId(), refund.getOrderId(),
                instruction.getSession().getOrderGroupId(), instruction.getSession().getId(), instruction.getId(),
                instruction.getFundedTransaction().getId(), money(refund.getAmount()), refund.getCurrency(),
                refund.getStatus().name(), refund.getReason(), actorId, refund.getVersion(),
                instruction.getVersion(), refund.getRequestedAt().toInstant().toString());
    }

    static String money(BigDecimal amount) {
        return amount.setScale(2).toPlainString();
    }

    private static boolean isZero(String amount) {
        return amount == null || new BigDecimal(amount).signum() == 0;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
