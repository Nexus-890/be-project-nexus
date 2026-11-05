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
import com.group5.paymentservice.payment.entity.PaymentPendingOrderCancellation;
import com.group5.paymentservice.payment.entity.PaymentTransaction;
import com.group5.paymentservice.payment.entity.Refund;
import com.group5.paymentservice.payment.enums.PaymentInstructionStatus;
import com.group5.paymentservice.payment.enums.PaymentTransactionStatus;
import com.group5.paymentservice.payment.enums.PaymentTransactionType;
import com.group5.paymentservice.payment.event.payload.OrderCancelled;
import com.group5.paymentservice.payment.enums.RefundStatus;
import com.group5.paymentservice.payment.event.payload.OrderRefundCompleted;
import com.group5.paymentservice.payment.event.payload.OrderRefundRequested;
import com.group5.paymentservice.payment.repository.PaymentInstructionRepository;
import com.group5.paymentservice.payment.repository.PaymentPendingOrderCancellationRepository;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import com.group5.paymentservice.payment.repository.PaymentTransactionRepository;
import com.group5.paymentservice.payment.repository.RefundRepository;
import com.group5.paymentservice.shared.exception.PaymentErrorCode;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class RefundService {
    public static final String CANCELLATION_CONSUMER = "payment-order-cancelled";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[\\x21-\\x7E]{1,100}");
    static final UUID SYSTEM_ACTOR = new UUID(0, 0);
    private static final String SYSTEM_KEY_PREFIX = "system:";
    private static final String GROUP_LOCK = "payment-group:";
    private static final String MANUAL_TRANSFER = "MANUAL_TRANSFER";

    private final IdempotentConsumer consumer;
    private final PaymentSessionRepository sessions;
    private final PaymentInstructionRepository instructions;
    private final PaymentTransactionRepository transactions;
    private final RefundRepository refunds;
    private final PaymentPendingOrderCancellationRepository pendingOrders;
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;
    private final Validator validator;

    public RefundService(IdempotentConsumer consumer, PaymentSessionRepository sessions,
                         PaymentInstructionRepository instructions, PaymentTransactionRepository transactions,
                         RefundRepository refunds, PaymentPendingOrderCancellationRepository pendingOrders,
                         OutboxWriter outbox, EventEnvelopeFactory envelopes, Validator validator) {
        this.consumer = consumer;
        this.sessions = sessions;
        this.instructions = instructions;
        this.transactions = transactions;
        this.refunds = refunds;
        this.pendingOrders = pendingOrders;
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
        boolean paid = !isZero(cancellation.paidAmount());
        Optional<UUID> groupId = instructions.findGroupIdByOrderId(cancellation.orderId());
        if (groupId.isEmpty() && !paid) {
            return;
        }
        // KHÓA CÙNG THỨ TỰ VỚI #121: GROUP -> INSTRUCTION -> REFUND, TRÁNH DEADLOCK VỚI WEBHOOK/HỦY NHÓM
        UUID lockedGroup = groupId.orElse(cancellation.orderGroupId());
        sessions.lockKey(GROUP_LOCK + lockedGroup);
        Optional<PaymentInstruction> instruction = instructions.findLockedByOrderId(cancellation.orderId());
        if (instruction.isPresent() && cancellation.orderVersion() < instruction.get().getLastOrderVersion()) {
            return;
        }
        if (instruction.isPresent() && isFunded(instruction.get())) {
            pendingOrders.findLockedByOrderId(cancellation.orderId()).ifPresent(pendingOrders::delete);
            applyCancellation(instruction.get(), cancellation.orderVersion(), cancellation.reason(),
                    cancellation.actorId(), cancellation.cancelledAt().atOffset(ZoneOffset.UTC),
                    event.occurredAt().atOffset(ZoneOffset.UTC));
            return;
        }
        // COMMERCE NÓI ĐÃ TRẢ MÀ PAYMENT CHƯA CÓ INSTRUCTION HOẶC CHƯA CÓ BẰNG CHỨNG FUNDING
        // -> LƯU PENDING CÙNG TRANSACTION VỚI MARKER, KHÔNG NÉM LỖI ĐỂ KHÔNG CHẶN PARTITION
        if (paid) {
            stash(event, lockedGroup);
        }
    }

    // GỌI KHI INSTRUCTION CỦA NHÓM ĐÃ CÓ BẰNG CHỨNG FUNDING (WEBHOOK, MANIFEST GỬI LẠI)
    // MANIFEST MỚI TẠO CHƯA ĐỦ ĐỂ COI LÀ ĐÃ TRẢ -> ĐƠN CHƯA FUNDED VẪN NẰM PENDING
    @Transactional
    public void applyPendingCancellations(UUID groupId) {
        sessions.lockKey(GROUP_LOCK + groupId);
        for (PaymentPendingOrderCancellation pending : pendingOrders.findLockedByOrderGroupId(groupId)) {
            Optional<PaymentInstruction> instruction = instructions.findLockedByOrderId(pending.getOrderId());
            if (instruction.isEmpty() || !isFunded(instruction.get())) {
                continue;
            }
            if (pending.getOrderVersion() >= instruction.get().getLastOrderVersion()) {
                applyCancellation(instruction.get(), pending.getOrderVersion(), pending.getReason(),
                        pending.getActorId(), pending.getCancelledAt(), pending.getOccurredAt());
            }
            // CHỈ XÓA SAU KHI ÁP DỤNG THÀNH CÔNG (HOẶC BẢN PENDING ĐÃ CŨ HƠN INSTRUCTION)
            pendingOrders.delete(pending);
        }
    }

    private void applyCancellation(PaymentInstruction instruction, long orderVersion, String reason, UUID actorId,
                                   OffsetDateTime cancelledAt, OffsetDateTime occurredAt) {
        Optional<Refund> existing = refunds.findLockedByOrderId(instruction.getOrderId());
        instruction.setCanceledAt(cancelledAt);
        instruction.setLastOrderVersion(orderVersion);
        instruction.setLastOrderEventAt(occurredAt);
        instruction.setUpdatedBy(actorId);
        instructions.saveAndFlush(instruction);
        // ĐÃ CÓ REFUND (EVENT GỬI LẠI VỚI eventId MỚI, HOẶC SUPPORT ĐÃ TẠO) -> GIỮ NGUYÊN, KHÔNG TẠO THÊM
        if (existing.isPresent()) {
            return;
        }
        // TỰ TẠO REFUND TOÀN PHẦN BẰNG ACTOR HỆ THỐNG, KEY CỐ ĐỊNH THEO ĐƠN
        // REFUND + OrderRefundRequested + MARKER CÙNG 1 TRANSACTION
        Refund refund = new Refund();
        refund.setOrderId(instruction.getOrderId());
        refund.setAmount(instruction.getAmount());
        refund.setCurrency(instruction.getCurrency());
        refund.setReason(reason);
        refund.setIdempotencyKey(SYSTEM_KEY_PREFIX + "cancel-refund:" + instruction.getOrderId());
        refund.setRequestedBy(SYSTEM_ACTOR);
        refund.setCreatedBy(SYSTEM_ACTOR);
        refund.setUpdatedBy(SYSTEM_ACTOR);
        publishRequested(refunds.saveAndFlush(refund), instruction, SYSTEM_ACTOR);
    }

    private void stash(EventEnvelope<OrderCancelled> event, UUID groupId) {
        OrderCancelled cancellation = event.payload();
        PaymentPendingOrderCancellation pending = pendingOrders.findLockedByOrderId(cancellation.orderId())
                .orElseGet(PaymentPendingOrderCancellation::new);
        // GIỮ BẢN CÓ VERSION MỚI NHẤT
        if (pending.getOrderVersion() != null && pending.getOrderVersion() >= cancellation.orderVersion()) {
            return;
        }
        pending.setOrderId(cancellation.orderId());
        pending.setOrderGroupId(groupId);
        pending.setBuyerId(cancellation.buyerId());
        pending.setOrderVersion(cancellation.orderVersion());
        pending.setPaidAmount(new BigDecimal(cancellation.paidAmount()));
        pending.setCurrency(cancellation.currency());
        pending.setReason(cancellation.reason());
        pending.setActorId(cancellation.actorId());
        pending.setCancelledAt(cancellation.cancelledAt().atOffset(ZoneOffset.UTC));
        pending.setOccurredAt(event.occurredAt().atOffset(ZoneOffset.UTC));
        pending.setSourceEventId(event.eventId());
        pendingOrders.saveAndFlush(pending);
    }

    @Transactional
    public RefundResult request(CurrentUser user, String idempotencyKey, RefundRequest body) {
        if (user == null || user.id() == null) {
            throw new DomainException(PaymentErrorCode.UNAUTHENTICATED);
        }
        // PREFIX system: DÀNH CHO REFUND TỰ ĐỘNG -> API CÔNG KHAI KHÔNG ĐƯỢC DÙNG ĐỂ TRÁNH TRÙNG KEY NỘI BỘ
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()
                || idempotencyKey.startsWith(SYSTEM_KEY_PREFIX)) {
            throw new DomainException(PaymentErrorCode.INVALID_REFUND_REQUEST);
        }
        // KHÓA THEO KEY TRƯỚC: 2 REQUEST CÙNG KEY CHẠY SONG SONG THÌ REQUEST SAU CHỜ RỒI NHẬN LẠI ĐÚNG REFUND CŨ
        // KIỂM TRA REPLAY TRƯỚC MỌI ĐIỀU KIỆN KHÁC -> GỬI LẠI SAU KHI ĐÃ COMPLETED VẪN TRẢ CÙNG refundId
        sessions.lockKey("refund-key:" + idempotencyKey);
        Optional<Refund> replay = refunds.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            if (!sameRequest(replay.get(), body, effectiveAmount(replay.get(), body))) {
                throw new DomainException(PaymentErrorCode.REFUND_IDEMPOTENCY_CONFLICT);
            }
            return result(replay.get());
        }

        UUID groupId = instructions.findGroupIdByOrderId(body.orderId())
                .orElseThrow(() -> new DomainException(PaymentErrorCode.ORDER_NOT_FOUND));
        sessions.lockKey(GROUP_LOCK + groupId);
        PaymentInstruction instruction = instructions.findLockedByOrderId(body.orderId()).orElseThrow();
        // PHẢI ĐÃ TRẢ TIỀN VÀ PAYMENT ĐÃ NHẬN OrderCancelled, KHÔNG THÌ COMMERCE KHÔNG CHUYỂN ĐƯỢC TRẠNG THÁI ĐƠN
        if (!isFunded(instruction) || instruction.getCanceledAt() == null) {
            throw new DomainException(PaymentErrorCode.ORDER_NOT_REFUNDABLE);
        }
        // HỦY ĐƠN ĐÃ TỰ TẠO REFUND -> 409, SUPPORT DÙNG REFUND CÓ SẴN ĐỂ COMPLETE
        // API NÀY CHỈ CHO ĐƠN ĐÃ HỦY + ĐÃ TRẢ MÀ CHƯA CÓ REFUND (KHÔI PHỤC DỮ LIỆU)
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

    @Transactional
    public RefundView complete(CurrentUser user, UUID refundId) {
        if (user == null || user.id() == null) {
            throw new DomainException(PaymentErrorCode.UNAUTHENTICATED);
        }
        // TRƯỚC KHI KHÓA CHỈ ĐỌC orderId VÀ groupId (SCALAR), KHÔNG LOAD ENTITY -> SAU KHÓA ĐỌC BẢN MỚI NHẤT
        // ĐỌC ENTITY TRƯỚC KHÓA THÌ NGƯỜI BẤM SAU GIỮ BẢN CŨ -> LỖI OPTIMISTIC LOCK -> 500
        UUID orderId = refunds.findOrderIdById(refundId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.REFUND_NOT_FOUND));
        UUID groupId = instructions.findGroupIdByOrderId(orderId).orElseThrow();
        // CÙNG THỨ TỰ KHÓA GROUP -> INSTRUCTION -> REFUND: 2 SUPPORT BẤM CÙNG LÚC THÌ NGƯỜI SAU CHỜ
        sessions.lockKey(GROUP_LOCK + groupId);
        PaymentInstruction instruction = instructions.findLockedByOrderId(orderId).orElseThrow();
        Refund refund = refunds.findLockedById(refundId).orElseThrow();
        // BẤM LẠI SAU KHI ĐÃ XONG -> TRẢ KẾT QUẢ CŨ, KHÔNG TẠO GIAO DỊCH HAY EVENT THỨ 2
        if (refund.getStatus() == RefundStatus.COMPLETED) {
            return view(refund);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        // TIỀN ĐÃ CHUYỂN TAY NGOÀI HỆ THỐNG -> GHI 1 GIAO DỊCH REFUND ĐỂ ĐỐI SOÁT
        // KEY "refund:{id}" UNIQUE -> 1 REFUND KHÔNG BAO GIỜ CÓ 2 GIAO DỊCH
        PaymentTransaction transfer = new PaymentTransaction();
        transfer.setSessionId(instruction.getSession().getId());
        transfer.setProvider(MANUAL_TRANSFER);
        transfer.setAmount(refund.getAmount());
        transfer.setCurrency(refund.getCurrency());
        transfer.setStatus(PaymentTransactionStatus.SUCCESS);
        transfer.setType(PaymentTransactionType.REFUND);
        transfer.setIdempotencyKey("refund:" + refund.getId());
        transfer.setReceivedAt(now);
        transfer.setCreatedBy(user.id());
        transfer.setUpdatedBy(user.id());
        PaymentTransaction saved = transactions.saveAndFlush(transfer);

        refund.setStatus(RefundStatus.COMPLETED);
        refund.setCompletedBy(user.id());
        refund.setCompletedAt(now);
        refund.setCompletedTransactionId(saved.getId());
        refund.setUpdatedBy(user.id());
        Refund completed;
        try {
            completed = refunds.saveAndFlush(refund);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new DomainException(PaymentErrorCode.REFUND_CONFLICT);
        }
        outbox.write("Refund", Topics.PAYMENT, envelopes.create("OrderRefundCompleted",
                completed.getOrderId().toString(), completedEvent(completed, instruction, user.id())));
        return view(completed);
    }

    @Transactional(readOnly = true)
    public List<RefundView> list(UUID orderId) {
        return refunds.findByOrderId(orderId).map(RefundService::view).stream().toList();
    }

    // KHÔNG GỬI amount = HOÀN ĐỦ TIỀN ĐƠN -> SO SÁNH SỐ TIỀN SAU KHI ÁP MẶC ĐỊNH, KHÔNG BỎ QUA
    private BigDecimal effectiveAmount(Refund stored, RefundRequest body) {
        if (body.amount() != null || !stored.getOrderId().equals(body.orderId())) {
            return body.amount();
        }
        return instructions.findAmountByOrderId(stored.getOrderId()).orElseThrow();
    }

    private static boolean sameRequest(Refund stored, RefundRequest body, BigDecimal amount) {
        return stored.getOrderId().equals(body.orderId())
                && stored.getReason().equals(body.reason())
                && amount != null
                && stored.getAmount().compareTo(amount) == 0;
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

    private void publishRequested(Refund refund, PaymentInstruction instruction, UUID actorId) {
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

    // refundVersion = 1 KHI COMPLETED -> COMMERCE BỎ QUA EVENT CŨ HƠN BẢN ĐÃ ÁP DỤNG
    private static OrderRefundCompleted completedEvent(Refund refund, PaymentInstruction instruction, UUID actorId) {
        return new OrderRefundCompleted(refund.getId(), refund.getOrderId(),
                instruction.getSession().getOrderGroupId(), instruction.getSession().getId(), instruction.getId(),
                instruction.getFundedTransaction().getId(), money(refund.getAmount()), refund.getCurrency(),
                refund.getStatus().name(), refund.getReason(), actorId, refund.getVersion(),
                instruction.getVersion(), refund.getRequestedAt().toInstant().toString(),
                refund.getCompletedTransactionId(), refund.getCompletedAt().toInstant().toString());
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
