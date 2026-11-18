package com.group5.paymentservice.payout.service;

import static com.group5.paymentservice.shared.exception.PaymentErrorCode.*;

import com.group5.common_core.constant.CommonErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.PageInfo;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payout.dto.request.PayoutListRequest;
import com.group5.paymentservice.payout.dto.response.PayoutPage;
import com.group5.paymentservice.payout.dto.response.PayoutView;
import com.group5.paymentservice.payout.repository.PayoutQueryRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PayoutQueryService {
    private final PayoutQueryRepository payouts;
    private final ManualPayoutQueryService manualQueries;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PayoutPage admin(CurrentUser user, PayoutListRequest request) {
        requireIdentity(user);
        if (!user.hasRole("ADMIN")) throw new DomainException(CommonErrorCode.FORBIDDEN);
        if (request.page() < 0
                || request.size() < 1
                || request.size() > 100
                || (request.from() != null
                        && request.to() != null
                        && request.from().isAfter(request.to())))
            throw new DomainException(
                    PAYOUT_INVALID_REQUEST, "Invalid pagination or payout creation-time range");
        long total = payouts.countAdmin(request);
        return new PayoutPage(
                payouts.findAdmin(request),
                new PageInfo(
                        request.page(),
                        request.size(),
                        total,
                        (int) ((total + request.size() - 1) / request.size())));
    }

    public PayoutPage mine(CurrentUser user, int page, int size) {
        requireIdentity(user);
        if (page < 0 || size < 1 || size > 100) {
            throw new DomainException(PAYOUT_INVALID_REQUEST, "Invalid page or size");
        }
        long total = payouts.countBySeller(user.id());
        var data =
                payouts.findBySeller(user.id(), page, size).stream()
                        .map(p -> withManual(user, p))
                        .toList();
        return new PayoutPage(
                data, new PageInfo(page, size, total, (int) ((total + size - 1) / size)));
    }

    /** Kiểm tra chủ sở hữu trước khi đọc lịch sử để seller khác luôn nhận 404. */
    public PayoutView detail(CurrentUser user, UUID id) {
        requireIdentity(user);
        PayoutView payout =
                payouts.findById(id)
                        .filter(p -> p.sellerId().equals(user.id()) || user.hasRole("ADMIN"))
                        .orElseThrow(() -> new DomainException(PAYOUT_NOT_FOUND));
        var history = payouts.findHistory(id);
        return new PayoutView(
                payout.id(),
                payout.orderId(),
                payout.sellerId(),
                payout.orderAmount(),
                payout.shippingFee(),
                payout.platformFeeRate(),
                payout.platformFee(),
                payout.netAmount(),
                payout.currency(),
                payout.status(),
                payout.attemptCount(),
                payout.recipientAccountLast4(),
                payout.providerReference(),
                payout.simulated(),
                payout.createdAt(),
                payout.updatedAt(),
                history,
                payout.executionMode(),
                payout.confirmationSource(),
                sellerProjection(user, payout));
    }

    private PayoutView withManual(CurrentUser user, PayoutView p) {
        return new PayoutView(
                p.id(),
                p.orderId(),
                p.sellerId(),
                p.orderAmount(),
                p.shippingFee(),
                p.platformFeeRate(),
                p.platformFee(),
                p.netAmount(),
                p.currency(),
                p.status(),
                p.attemptCount(),
                p.recipientAccountLast4(),
                p.providerReference(),
                p.simulated(),
                p.createdAt(),
                p.updatedAt(),
                p.history(),
                p.executionMode(),
                p.confirmationSource(),
                sellerProjection(user, p));
    }

    private com.group5.paymentservice.payout.dto.response.ManualReceiptView sellerProjection(
            CurrentUser user, PayoutView p) {
        return "MANUAL".equals(p.executionMode())
                        && user.hasRole("SELLER")
                        && user.id().equals(p.sellerId())
                ? manualQueries.sellerProjection(user, p.id())
                : null;
    }

    private static void requireIdentity(CurrentUser user) {
        if (user == null || user.id() == null) throw new DomainException(UNAUTHENTICATED);
    }
}
