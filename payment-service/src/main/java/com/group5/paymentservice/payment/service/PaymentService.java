package com.group5.paymentservice.payment.service;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.dto.response.PaymentInitResponse;
import com.group5.paymentservice.payment.provider.PaymentProviderRegistry;
import com.group5.paymentservice.payment.provider.dto.PaymentInitiationRequest;
import com.group5.paymentservice.payment.entity.PaymentInstruction;
import com.group5.paymentservice.payment.entity.PaymentSession;
import com.group5.paymentservice.payment.enums.PaymentInstructionStatus;
import com.group5.paymentservice.payment.enums.PaymentSessionStatus;
import com.group5.paymentservice.payment.repository.PaymentInstructionRepository;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import com.group5.paymentservice.shared.exception.PaymentErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentService {
    private final PaymentSessionRepository sessions;
    private final PaymentInstructionRepository instructions;
    private final PaymentProviderRegistry providers;
    private final Clock clock;

    public PaymentService(PaymentSessionRepository sessions, PaymentInstructionRepository instructions,
                          PaymentProviderRegistry providers, Clock clock) {
        this.sessions = sessions;
        this.instructions = instructions;
        this.providers = providers;
        this.clock = clock;
    }

    // Initiation reads one consistent snapshot and never changes the payment plan.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PaymentInitResponse initiate(CurrentUser user, UUID orderGroupId) {
        if (user == null || user.id() == null) {
            throw new DomainException(PaymentErrorCode.UNAUTHENTICATED);
        }
        if (!user.hasPrivilege("CHECKOUT.START")) {
            throw new DomainException(PaymentErrorCode.CHECKOUT_PRIVILEGE_REQUIRED);
        }
        if (orderGroupId == null) {
            throw new DomainException(PaymentErrorCode.ORDER_GROUP_ID_REQUIRED);
        }
        PaymentSession session = sessions.findByOrderGroupId(orderGroupId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.SESSION_NOT_FOUND));
        if (!user.id().equals(session.getBuyerId())) {
            throw new DomainException(PaymentErrorCode.SESSION_ACCESS_DENIED);
        }
        if (session.getStatus() != PaymentSessionStatus.PENDING
                || session.getDueAt() == null || !session.getDueAt().toInstant().isAfter(clock.instant())) {
            throw new DomainException(PaymentErrorCode.SESSION_NOT_PAYABLE);
        }

        verifyInstructions(session, instructions.findBySessionIdOrderByOrderId(session.getId()));
        var result = providers.getProvider().initiate(new PaymentInitiationRequest(
                session.getId(), session.getTotalAmount(), session.getCurrency(),
                session.getMemoCode(), session.getDueAt().toInstant()));
        return new PaymentInitResponse(session.getId(), result.qrImageUrl(), result.bankAccount(),
                session.getTotalAmount(), session.getMemoCode(), session.getDueAt().toInstant());
    }

    private void verifyInstructions(PaymentSession session, List<PaymentInstruction> plan) {
        if (plan.isEmpty() || session.getTotalAmount() == null || session.getCurrency() == null
                || plan.stream().anyMatch(instruction ->
                instruction.getSession() == null || !session.getId().equals(instruction.getSession().getId())
                        || !session.getBuyerId().equals(instruction.getBuyerId())
                        || !session.getCurrency().equals(instruction.getCurrency())
                        || instruction.getDueAt() == null || instruction.getDueAt().isBefore(session.getDueAt())
                        || instruction.getStatus() != PaymentInstructionStatus.PENDING
                        || instruction.getFundedTransaction() != null
                        || instruction.getAmount() == null || instruction.getAmount().signum() < 0)
                || plan.stream().map(PaymentInstruction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(session.getTotalAmount()) != 0) {
            throw new DomainException(PaymentErrorCode.INVALID_INSTRUCTIONS);
        }
    }
}
