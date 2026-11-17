package com.group5.commerceservice.promotion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.group5.commerceservice.promotion.VoucherPricing.*;
import static org.assertj.core.api.Assertions.assertThat;

class VoucherPricingTest {
    private final UUID seller = UUID.randomUUID();
    private final UUID otherSeller = UUID.randomUUID();
    private final Line large = new Line(UUID.randomUUID(), UUID.randomUUID(), seller, vnd(700));
    private final Line small = new Line(UUID.randomUUID(), UUID.randomUUID(), otherSeller, vnd(300));
    private final List<Line> cart = List.of(large, small);

    @Test
    void capsPercentAndKeepsTheAllocationRemainderOnTheLargestLine() {
        var offer = new Offer(UUID.randomUUID(), "CAP", Source.PLATFORM, null, Discount.PERCENT,
                vnd(25), vnd(101), vnd(0), false, Set.of(), Map.of(), null, null, null);
        var result = price(offer);
        assertThat(result.discountAmount()).isEqualByComparingTo("101");
        assertThat(result.applications()).singleElement().satisfies(application -> {
            assertThat(application.allocations()).extracting(Allocation::discountAmount)
                    .containsExactly(vnd(71), vnd(30));
            assertThat(application.allocations()).allSatisfy(row -> {
                assertThat(row.sellerFunded()).isEqualByComparingTo("0");
                assertThat(row.platformFunded()).isEqualByComparingTo(row.discountAmount());
            });
        });
    }

    @Test
    void fixedDiscountCannotExceedMerchandiseFromItsOwnShop() {
        var result = price(shop("SHOP", seller, 1000));
        assertThat(result.discountAmount()).isEqualByComparingTo("700");
        assertThat(result.applications().getFirst().allocations()).singleElement().satisfies(row -> {
            assertThat(row.line()).isEqualTo(large);
            assertThat(row.sellerFunded()).isEqualByComparingTo("700");
        });
    }

    @Test
    void selectedProductsDetermineTheMinimumAndMissingAmount() {
        var offer = new Offer(UUID.randomUUID(), "SCOPE", Source.PLATFORM, null, Discount.FIXED,
                vnd(100), null, vnd(550), true, Set.of(small.productId()), Map.of(), null, null, null);
        assertThat(price(offer).vouchers()).singleElement().satisfies(quote -> {
            assertThat(quote.reason()).isEqualTo(Reason.MIN_ORDER_NOT_MET);
            assertThat(quote.missingAmount()).isEqualByComparingTo("250");
        });
    }

    @Test
    void stacksOneShopPerSellerAndOnePlatformOrEventWithoutOverdiscounting() {
        var result = price(platform("P1", 500), shop("S1", seller, 700), shop("S2", seller, 10),
                shop("S3", otherSeller, 300), platform("P2", 1));
        assertThat(result.vouchers()).extracting(Quote::applied).containsExactly(true, true, false, true, false);
        assertThat(result.vouchers().get(2).reason()).isEqualTo(Reason.STACKING);
        assertThat(result.vouchers().get(4).reason()).isEqualTo(Reason.STACKING);
        assertThat(result.discountAmount()).isEqualByComparingTo("1000");
    }

    @Test
    void eventUsesApprovedParticipantFundingSnapshotForTheEligibleLine() {
        UUID participant = UUID.randomUUID();
        var offer = new Offer(UUID.randomUUID(), "EVENT", Source.EVENT, null, Discount.FIXED,
                vnd(101), null, vnd(0), false, Set.of(),
                Map.of(seller, new Contribution(participant, vnd(35))), null, null, null);
        assertThat(price(offer).applications().getFirst().allocations()).singleElement().satisfies(row -> {
            assertThat(row.line()).isEqualTo(large);
            assertThat(row.voucherSellerId()).isEqualTo(participant);
            assertThat(row.sellerFunded()).isEqualByComparingTo("35");
            assertThat(row.platformFunded()).isEqualByComparingTo("66");
        });
    }

    @Test
    void checksRemainingUsesPerSellerOrderAndRemainingBudget() {
        var limited = new Offer(UUID.randomUUID(), "USES", Source.PLATFORM, null, Discount.FIXED,
                vnd(100), null, vnd(0), false, Set.of(), Map.of(), null, 1, null);
        var budget = new Offer(UUID.randomUUID(), "BUDGET", Source.PLATFORM, null, Discount.FIXED,
                vnd(100), null, vnd(0), false, Set.of(), Map.of(), null, 2, vnd(99));
        assertThat(price(limited, budget).vouchers()).extracting(Quote::reason)
                .containsExactly(Reason.LIMIT_REACHED, Reason.LIMIT_REACHED);
    }

    private Result price(Offer... offers) {
        return VoucherPricing.price(cart, Arrays.stream(offers).map(Offer::code).toList(),
                Arrays.stream(offers).collect(Collectors.toMap(Offer::code, Function.identity())));
    }

    private static Offer shop(String code, UUID owner, long value) {
        return new Offer(UUID.randomUUID(), code, Source.SHOP, owner, Discount.FIXED, vnd(value), null, vnd(0),
                false, Set.of(), Map.of(), null, null, null);
    }

    private static Offer platform(String code, long value) {
        return new Offer(UUID.randomUUID(), code, Source.PLATFORM, null, Discount.FIXED, vnd(value), null, vnd(0),
                false, Set.of(), Map.of(), null, null, null);
    }

    private static BigDecimal vnd(long value) {
        return BigDecimal.valueOf(value);
    }
}
