package com.group5.paymentservice.payout;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.PageInfo;
import com.group5.common_security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.group5.paymentservice.shared.exception.PaymentErrorCode.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PayoutQueryService {
    private final PayoutQueryRepository payouts;

    public PayoutPage mine(CurrentUser user, int page, int size) {
        requireIdentity(user);
        if (page < 0 || size < 1 || size > 100) {
            throw new DomainException(PAYOUT_INVALID_REQUEST, "Invalid page or size");
        }
        long total = payouts.countBySeller(user.id());
        var data = payouts.findBySeller(user.id(), page, size);
        return new PayoutPage(data, new PageInfo(page, size, total, (int) ((total + size - 1) / size)));
    }

    /** Kiểm tra chủ sở hữu trước khi đọc lịch sử để seller khác luôn nhận 404. */
    public PayoutView detail(CurrentUser user, UUID id) {
        requireIdentity(user);
        PayoutView payout = payouts.findById(id)
                .filter(p -> p.sellerId().equals(user.id()) || user.hasRole("ADMIN"))
                .orElseThrow(() -> new DomainException(PAYOUT_NOT_FOUND));
        var history = payouts.findHistory(id);
        return new PayoutView(payout.id(), payout.orderId(), payout.sellerId(), payout.orderAmount(),
                payout.shippingFee(), payout.platformFeeRate(), payout.platformFee(), payout.netAmount(),
                payout.currency(), payout.status(), payout.attemptCount(), payout.recipientAccountLast4(),
                payout.providerReference(), payout.simulated(), payout.createdAt(), history);
    }

    private static void requireIdentity(CurrentUser user) {
        if (user == null || user.id() == null) throw new DomainException(UNAUTHENTICATED);
    }

    public record PayoutPage(List<PayoutView> content, PageInfo page) { }
}
