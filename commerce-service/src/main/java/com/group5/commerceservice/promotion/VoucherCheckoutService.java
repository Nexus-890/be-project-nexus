package com.group5.commerceservice.promotion;

import com.group5.common_core.exception.DomainException;
import com.group5.commerceservice.order.entity.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.stream.Collectors;

import static com.group5.commerceservice.promotion.VoucherPricing.*;
import static com.group5.commerceservice.shared.exception.CommerceErrorCode.VOUCHER_NOT_APPLICABLE;

@Service
public class VoucherCheckoutService {
    private final VoucherRepository vouchers;
    private final MegaSaleEventRepository events;
    private final VoucherLimitRepository limits;
    private final VoucherUsageRepository usages;

    VoucherCheckoutService(VoucherRepository vouchers, MegaSaleEventRepository events, VoucherLimitRepository limits,
                           VoucherUsageRepository usages) {
        this.vouchers = vouchers;
        this.events = events;
        this.limits = limits;
        this.usages = usages;
    }

    @Transactional(readOnly = true)
    public Result quote(UUID buyer, List<Line> lines, List<String> codes) {
        return evaluate(buyer, lines, codes, MegaSaleEventService.now(), false);
    }

    /** Revalidates under the current clock and takes quota in database ID order after stock is held. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result reserveQuota(UUID buyer, List<Line> lines, List<String> codes, OffsetDateTime now) {
        Result result = evaluate(buyer, lines, codes, now, true);
        requireApplicable(result);

        for (Application application : result.applications().stream()
                .sorted(Comparator.comparing(item -> item.offer().id().toString())).toList()) {
            long count = application.allocations().stream().map(row -> row.line().sellerId()).distinct().count();
            BigDecimal amount = application.allocations().stream().map(Allocation::discountAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (!limits.reserve(application.offer().id(), count, amount, now)) {
                throw new DomainException(VOUCHER_NOT_APPLICABLE, VOUCHER_NOT_APPLICABLE.message(),
                        Map.of("vouchers", List.of(new Quote(application.offer().code(), application.offer().sourceType(),
                                false, BigDecimal.ZERO, Reason.LIMIT_REACHED, null))));
            }
        }
        return result;
    }

    public static void requireApplicable(Result result) {
        if (result.vouchers().stream().anyMatch(quote -> !quote.applied())) {
            throw new DomainException(VOUCHER_NOT_APPLICABLE, VOUCHER_NOT_APPLICABLE.message(),
                    Map.of("vouchers", result.vouchers()));
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void persistReservations(UUID buyer, List<Order> orders, Result result, OffsetDateTime now) {
        Map<UUID, Order> sellerOrders = orders.stream().collect(Collectors.toMap(Order::getSellerId, order -> order));
        for (Application application : result.applications()) {
            UUID claimId = vouchers.claim(buyer, application.offer().id()).orElseThrow().id();
            Map<UUID, List<Allocation>> allocationBySeller = application.allocations().stream()
                    .collect(Collectors.groupingBy(row -> row.line().sellerId()));
            allocationBySeller.forEach((seller, rows) ->
                    usages.insert(claimId, sellerOrders.get(seller), application.offer(), rows, now));
        }
    }

    public List<OrderVoucher> summary(UUID orderId) {
        return usages.summary(orderId);
    }

    public Map<UUID, List<OrderVoucher>> summaries(List<UUID> orderIds) {
        return usages.summaries(orderIds);
    }

    public record OrderVoucher(String code, BigDecimal discountAmount, BigDecimal sellerFunded,
                               BigDecimal platformFunded) { }

    private Result evaluate(UUID buyer, List<Line> lines, List<String> codes, OffsetDateTime now, boolean lock) {
        if (codes.isEmpty()) return VoucherPricing.price(lines, codes, Map.of());
        Map<String, Offer> offers = new HashMap<>();
        for (Voucher voucher : vouchers.forCodes(codes)) {
            Reason unavailable = unavailable(buyer, voucher, now, lock);
            boolean restrictedProducts = voucher.productScope() == Voucher.Scope.SELECTED;
            Set<UUID> products = restrictedProducts ? vouchers.products(voucher.id()) : Set.of();
            Map<UUID, Contribution> participants = voucher.sourceType() == Source.EVENT && unavailable == null
                    ? vouchers.participants(voucher.id()).stream().collect(Collectors.toMap(
                            VoucherRepository.ParticipantFunding::sellerId,
                            entry -> new Contribution(entry.voucherSellerId(), entry.sellerPercent())))
                    : Map.of();
            Integer remainingUses = voucher.usageLimit() == null ? null
                    : Math.toIntExact(Math.max(0, voucher.usageLimit() - voucher.committedUses()));
            BigDecimal remainingBudget = voucher.discountBudget() == null ? null
                    : voucher.discountBudget().subtract(voucher.committedDiscount());
            offers.put(voucher.code(), new Offer(voucher.id(), voucher.code(), voucher.sourceType(), voucher.ownerSellerId(),
                    voucher.discountType(), voucher.discountValue(), voucher.maxDiscountAmount(), voucher.minOrderAmount(),
                    restrictedProducts, products, participants, unavailable, remainingUses, remainingBudget));
        }
        return VoucherPricing.price(lines, codes, offers);
    }

    private Reason unavailable(UUID buyer, Voucher voucher, OffsetDateTime now, boolean lock) {
        if (!now.isBefore(voucher.validUntil()) || voucher.status() == Voucher.Status.EXPIRED) return Reason.EXPIRED;
        var claim = vouchers.claim(buyer, voucher.id()).orElse(null);
        if (claim == null) return Reason.NOT_CLAIMED;
        if (claim.status() == Voucher.WalletStatus.EXPIRED || !now.isBefore(claim.expiresAt())) return Reason.EXPIRED;
        if (claim.status() == Voucher.WalletStatus.USED || vouchers.hasReservation(claim.id())) return Reason.LIMIT_REACHED;
        if (voucher.status() != Voucher.Status.ACTIVE || now.isBefore(voucher.validFrom())) return Reason.NOT_ELIGIBLE;
        if (voucher.sourceType() == Source.EVENT) {
            var event = (lock ? events.share(voucher.eventId()) : events.visible(voucher.eventId(), null, now)).orElse(null);
            if (event == null || event.status() == MegaSaleEvent.Status.CANCELED || now.isBefore(event.startAt())
                    || !now.isBefore(event.endAt()) || (event.status() == MegaSaleEvent.Status.DRAFT)) {
                return Reason.NOT_ELIGIBLE;
            }
        }
        return null;
    }
}
