package com.group5.paymentservice.payment.service;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.dto.response.PaymentView;
import com.group5.paymentservice.payment.entity.PaymentInstruction;
import com.group5.paymentservice.payment.enums.PaymentTransactionStatus;
import com.group5.paymentservice.payment.repository.PaymentInstructionRepository;
import com.group5.paymentservice.payment.repository.PaymentTransactionRepository;
import com.group5.paymentservice.shared.exception.PaymentErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PaymentQueryService {
    private static final String REFUND_PRIVILEGE = "ORDER.REFUND";

    private final PaymentTransactionRepository transactions;
    private final PaymentInstructionRepository instructions;

    public PaymentQueryService(PaymentTransactionRepository transactions, PaymentInstructionRepository instructions) {
        this.transactions = transactions;
        this.instructions = instructions;
    }

    @Transactional(readOnly = true)
    public Page<PaymentView> list(CurrentUser user, PaymentTransactionStatus status, int page, int size) {
        requireUser(user);
        if (!user.hasPrivilege(REFUND_PRIVILEGE)) {
            throw new DomainException(PaymentErrorCode.REFUND_PRIVILEGE_REQUIRED);
        }
        PageRequest request = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("receivedAt"), Sort.Order.desc("id")));
        Page<PaymentView> views = status == null ? transactions.findViews(request)
                : transactions.findViewsByStatus(status, request);
        return views.map(PaymentQueryService::masked);
    }

    @Transactional(readOnly = true)
    public List<PaymentView> forOrder(CurrentUser user, UUID orderId) {
        requireUser(user);
        PaymentInstruction instruction = instructions.findByOrderId(orderId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.ORDER_NOT_FOUND));
        boolean staff = user.hasRole("ADMIN") || user.hasRole("SUPPORT_STAFF");
        if (!staff && !instruction.getBuyerId().equals(user.id())) {
            throw new DomainException(PaymentErrorCode.ACCESS_DENIED);
        }
        return transactions.findViewsBySession(instruction.getSession().getId()).stream()
                .map(PaymentQueryService::masked)
                .toList();
    }

    private static PaymentView masked(PaymentView view) {
        return new PaymentView(view.transactionId(), view.orderGroupId(), view.provider(),
                mask(view.providerReference()), view.amount(), view.currency(), view.type(), view.status(),
                view.receivedAt());
    }

    static String mask(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        String visible = reference.length() <= 4 ? "" : reference.substring(reference.length() - 4);
        return "****" + visible;
    }

    private static void requireUser(CurrentUser user) {
        if (user == null || user.id() == null) {
            throw new DomainException(PaymentErrorCode.UNAUTHENTICATED);
        }
    }
}
