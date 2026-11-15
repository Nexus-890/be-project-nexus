package com.group5.commerceservice.promotion;

import com.group5.commerceservice.order.event.OrderEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.group5.commerceservice.promotion.VoucherUsageRepository.Status.*;

@Service
public class VoucherLifecycleService {
    private final VoucherUsageRepository usages;
    private final VoucherLimitRepository limits;
    private final VoucherRepository vouchers;
    private final OrderEventPublisher events;

    VoucherLifecycleService(VoucherUsageRepository usages, VoucherLimitRepository limits,
                            VoucherRepository vouchers, OrderEventPublisher events) {
        this.usages = usages;
        this.limits = limits;
        this.vouchers = vouchers;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void redeem(List<UUID> orders, OffsetDateTime paidAt, UUID actor) {
        if (orders.isEmpty()) return;
        usages.lockVouchers(orders, RESERVED);
        Set<UUID> wallets = new HashSet<>();
        for (var change : usages.move(orders, RESERVED, REDEEMED, paidAt, actor, null)) {
            if (wallets.add(change.claimId())) usages.walletUsed(change.claimId(), paidAt, actor);
            events.voucherChanged("VoucherRedeemed", originalAmounts(change, REDEEMED, null));
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(List<UUID> orders, boolean expiry, OffsetDateTime now, UUID actor, String reason) {
        if (orders.isEmpty()) return;
        var status = expiry ? EXPIRED : RELEASED;
        usages.lockVouchers(orders, RESERVED);
        Set<UUID> wallets = new HashSet<>();
        for (var change : usages.move(orders, RESERVED, status, now, actor, reason)) {
            limits.release(change.voucherId(), 1, change.discountAmount(), now);
            if (wallets.add(change.claimId())) usages.walletReleased(change.claimId(), now, actor);
            events.voucherChanged("VoucherReleased", originalAmounts(change, status, reason));
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refund(UUID order, BigDecimal paidAmount, BigDecimal refundedAmount, OffsetDateTime now, UUID actor) {
        List<UUID> orders = List.of(order);
        usages.lockVouchers(orders, REDEEMED);
        for (var change : usages.move(orders, REDEEMED, REVERSED, now, actor, null)) {
            if (paidAmount.signum() <= 0 || refundedAmount.signum() <= 0 || refundedAmount.compareTo(paidAmount) > 0) {
                throw new IllegalArgumentException("Refund voucher reversal requires a positive amount within the paid order total");
            }
            List<VoucherUsageRepository.RefundAllocation> rows = usages.refundable(change.id());
            // Payment may refund an order by amount. Prorate that amount across the stored order allocations.
            BigDecimal reversed = shareOfRefund(change.discountAmount(), paidAmount, refundedAmount, RoundingMode.HALF_UP);
            List<BigDecimal> shares = rows.stream().map(row ->
                    shareOfRefund(row.discountAmount(), paidAmount, refundedAmount, RoundingMode.DOWN)).toList();
            BigDecimal remainder = reversed.subtract(shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
            BigDecimal sellerTotal = BigDecimal.ZERO;
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                BigDecimal base = shares.get(i);
                BigDecimal extra = remainder.min(row.discountAmount().subtract(base));
                BigDecimal amount = base.add(extra);
                remainder = remainder.subtract(extra);
                BigDecimal seller = row.discountAmount().signum() == 0 ? BigDecimal.ZERO
                        : shareOfRefund(row.sellerFundedAmount(), row.discountAmount(), amount, RoundingMode.HALF_UP)
                                .max(amount.subtract(row.platformFundedAmount())).min(row.sellerFundedAmount());
                usages.reverseAllocation(row.id(), amount, seller, now, actor);
                sellerTotal = sellerTotal.add(seller);
            }
            events.voucherChanged("VoucherReversed", new VoucherEvent(change.id(), order, change.voucherId(),
                    REVERSED.name(), reversed, sellerTotal, reversed.subtract(sellerTotal), null));
        }
    }

    @Transactional
    public void expireVoucherWindows(OffsetDateTime now) {
        vouchers.expireVouchers(now);
        vouchers.expireClaims(now);
    }

    private static BigDecimal shareOfRefund(BigDecimal amount, BigDecimal paid, BigDecimal refund, RoundingMode rounding) {
        return amount.multiply(refund).divide(paid, 0, rounding);
    }

    private static VoucherEvent originalAmounts(VoucherUsageRepository.Change change,
                                                  VoucherUsageRepository.Status status, String reason) {
        return new VoucherEvent(change.id(), change.orderId(), change.voucherId(), status.name(),
                change.discountAmount(), change.sellerFundedAmount(), change.platformFundedAmount(), reason);
    }

    public record VoucherEvent(UUID usageId, UUID orderId, UUID voucherId, String status,
                               String discountAmount, String sellerFunded, String platformFunded, String reason) {
        private VoucherEvent(UUID usageId, UUID orderId, UUID voucherId, String status,
                             BigDecimal discountAmount, BigDecimal sellerFunded, BigDecimal platformFunded, String reason) {
            this(usageId, orderId, voucherId, status, money(discountAmount), money(sellerFunded), money(platformFunded), reason);
        }

        private static String money(BigDecimal amount) {
            return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        }
    }
}
