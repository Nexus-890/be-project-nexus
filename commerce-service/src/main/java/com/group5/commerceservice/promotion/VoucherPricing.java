package com.group5.commerceservice.promotion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static java.math.BigDecimal.ZERO;

/** Prices claimed vouchers using server-validated merchandise amounts. */
public final class VoucherPricing {
    public enum Source { PLATFORM, SHOP, EVENT }
    public enum Discount { PERCENT, FIXED }
    public enum Reason { MIN_ORDER_NOT_MET, NOT_CLAIMED, EXPIRED, LIMIT_REACHED, NOT_ELIGIBLE, STACKING }

    public record Line(UUID skuId, UUID productId, UUID sellerId, BigDecimal amount) { }
    public record Contribution(UUID voucherSellerId, BigDecimal sellerPercent) { }

    public record Offer(UUID id, String code, Source sourceType, UUID ownerSellerId,
                        Discount discountType, BigDecimal value, BigDecimal cap, BigDecimal minimum,
                        boolean selectedProducts, Set<UUID> productIds, Map<UUID, Contribution> participants,
                        Reason unavailable, Integer remainingUses, BigDecimal remainingBudget) {
        boolean eligible(Line line) {
            if (selectedProducts && !productIds.contains(line.productId())) return false;
            return switch (sourceType) {
                case PLATFORM -> true;
                case SHOP -> ownerSellerId.equals(line.sellerId());
                case EVENT -> participants.containsKey(line.sellerId());
            };
        }

        Contribution contribution(UUID sellerId) {
            return switch (sourceType) {
                case PLATFORM -> new Contribution(null, ZERO);
                case SHOP -> new Contribution(null, BigDecimal.valueOf(100));
                case EVENT -> participants.get(sellerId);
            };
        }
    }

    public record Allocation(Line line, UUID voucherSellerId, BigDecimal discountAmount,
                             BigDecimal sellerFunded, BigDecimal platformFunded) { }
    public record Quote(String code, Source sourceType, boolean applied, BigDecimal discountAmount,
                        Reason reason, BigDecimal missingAmount) { }
    public record Application(Offer offer, List<Allocation> allocations) { }
    public record Result(BigDecimal discountAmount, List<Quote> vouchers, List<Application> applications) { }

    private VoucherPricing() { }

    public static Result price(List<Line> lines, List<String> codes, Map<String, Offer> offers) {
        Map<UUID, BigDecimal> appliedBySku = new HashMap<>();
        Set<UUID> shopSlots = new HashSet<>();
        List<Quote> quotes = new ArrayList<>();
        List<Application> applications = new ArrayList<>();
        boolean platformSlot = false;
        BigDecimal total = ZERO;
        for (String code : codes) {
            Offer offer = offers.get(code);
            if (offer == null || offer.unavailable() != null) {
                quotes.add(offer == null ? new Quote(code, null, false, ZERO, Reason.NOT_ELIGIBLE, null)
                        : rejected(offer, offer.unavailable(), null));
                continue;
            }

            boolean shop = offer.sourceType() == Source.SHOP;
            if (shop ? shopSlots.contains(offer.ownerSellerId()) : platformSlot) {
                quotes.add(rejected(offer, Reason.STACKING, null));
                continue;
            }
            List<Line> eligible = lines.stream().filter(offer::eligible)
                    .filter(line -> line.amount().signum() > 0).toList();
            if (eligible.isEmpty()) {
                quotes.add(rejected(offer, Reason.NOT_ELIGIBLE, null));
                continue;
            }
            BigDecimal eligibleAmount = eligible.stream().map(Line::amount).reduce(ZERO, BigDecimal::add);
            if (eligibleAmount.compareTo(offer.minimum()) < 0) {
                quotes.add(rejected(offer, Reason.MIN_ORDER_NOT_MET, offer.minimum().subtract(eligibleAmount)));
                continue;
            }

            List<LineCapacity> capacity = eligible.stream()
                    .map(line -> new LineCapacity(line, line.amount().subtract(appliedBySku.getOrDefault(line.skuId(), ZERO))
                            .setScale(0, RoundingMode.DOWN)))
                    .sorted(Comparator.<LineCapacity, BigDecimal>comparing(part -> part.line().amount()).reversed()
                            .thenComparing(part -> part.line().skuId()))
                    .toList();
            BigDecimal available = capacity.stream().map(LineCapacity::amount).reduce(ZERO, BigDecimal::add);
            BigDecimal discount = discount(offer, eligibleAmount).min(available);
            if (discount.signum() == 0) {
                quotes.add(rejected(offer, Reason.NOT_ELIGIBLE, null));
                continue;
            }
            long uses = eligible.stream().map(Line::sellerId).distinct().count();
            if ((offer.remainingUses() != null && uses > offer.remainingUses())
                    || (offer.remainingBudget() != null && discount.compareTo(offer.remainingBudget()) > 0)) {
                quotes.add(rejected(offer, Reason.LIMIT_REACHED, null));
                continue;
            }

            List<Allocation> allocations = allocate(offer, capacity, discount, available);
            allocations.forEach(row -> appliedBySku.merge(row.line().skuId(), row.discountAmount(), BigDecimal::add));
            if (shop) shopSlots.add(offer.ownerSellerId());
            else platformSlot = true;
            quotes.add(new Quote(code, offer.sourceType(), true, discount, null, null));
            applications.add(new Application(offer, allocations));
            total = total.add(discount);
        }
        return new Result(total, List.copyOf(quotes), List.copyOf(applications));
    }

    private static BigDecimal discount(Offer offer, BigDecimal eligibleAmount) {
        if (offer.discountType() == Discount.FIXED) return offer.value().setScale(0, RoundingMode.HALF_UP);
        BigDecimal discount = eligibleAmount.multiply(offer.value()).movePointLeft(2).setScale(0, RoundingMode.HALF_UP);
        return offer.cap() == null ? discount : discount.min(offer.cap().setScale(0, RoundingMode.DOWN));
    }

    private static List<Allocation> allocate(Offer offer, List<LineCapacity> capacity, BigDecimal discount,
                                             BigDecimal available) {
        List<BigDecimal> shares = new ArrayList<>();
        BigDecimal remainder = discount;
        for (LineCapacity part : capacity) {
            BigDecimal share = discount.multiply(part.amount()).divide(available, 0, RoundingMode.DOWN);
            shares.add(share);
            remainder = remainder.subtract(share);
        }

        List<Allocation> allocations = new ArrayList<>();
        for (int i = 0; i < capacity.size(); i++) {
            LineCapacity part = capacity.get(i);
            BigDecimal extra = remainder.min(part.amount().subtract(shares.get(i)));
            BigDecimal share = shares.get(i).add(extra);
            remainder = remainder.subtract(extra);
            Contribution fund = offer.contribution(part.line().sellerId());
            BigDecimal seller = share.multiply(fund.sellerPercent()).movePointLeft(2)
                    .setScale(0, RoundingMode.HALF_UP);
            allocations.add(new Allocation(part.line(), fund.voucherSellerId(), share, seller, share.subtract(seller)));
        }
        return List.copyOf(allocations);
    }

    private static Quote rejected(Offer offer, Reason reason, BigDecimal missing) {
        return new Quote(offer.code(), offer.sourceType(), false, ZERO, reason, missing);
    }

    private record LineCapacity(Line line, BigDecimal amount) { }
}
