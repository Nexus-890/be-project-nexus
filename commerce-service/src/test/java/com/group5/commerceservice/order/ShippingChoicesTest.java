package com.group5.commerceservice.order;

import com.group5.commerceservice.checkout.dto.CheckoutPreviewResponse.ShippingOption;
import com.group5.commerceservice.order.service.OrderShippingService;
import com.group5.commerceservice.order.service.ShippingChoices;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShippingChoicesTest {
    private static final OffsetDateTime DAY1 = OffsetDateTime.parse("2031-02-01T00:00:00Z");
    private static final OffsetDateTime DAY3 = OffsetDateTime.parse("2031-02-03T00:00:00Z");
    private static final OrderShippingService.Parcel PARCEL = new OrderShippingService.Parcel(1200, 30, 20, 15);

    @Test
    void onlyCarriersEverySellerCanUseAreOfferedWithSummedFeesAndTheLatestArrival() {
        var first = offer(option("GHN", "45000", DAY1, false), option("FAKE", "30000", DAY3, true),
                option("VNPOST", "20000", DAY1, false));
        var second = offer(option("FAKE", "25000", DAY1, true), option("GHN", "40000", DAY3, false));

        List<ShippingOption> options = ShippingChoices.common(List.of(first, second));

        assertThat(options).extracting(ShippingOption::carrier).containsExactly("GHN", "FAKE");
        assertThat(options.getFirst().fee()).isEqualByComparingTo("85000.00");
        assertThat(options.getFirst().estimatedDeliveryAt()).isEqualTo(DAY3);
        assertThat(options.get(1).fee()).isEqualByComparingTo("55000.00");
        assertThat(options).extracting(ShippingOption::recommended).containsExactly(false, true);
        assertThat(ShippingChoices.selected(options, null)).isEqualTo("FAKE");
        assertThat(ShippingChoices.selected(options, "GHN")).isEqualTo("GHN");
        assertThat(ShippingChoices.selected(options, "VNPOST")).isEqualTo("FAKE");
    }

    @Test
    void whenSellersDisagreeOnTheRecommendationTheCheapestTotalIsRecommended() {
        var first = offer(option("GHN", "45000", DAY1, true), option("FAKE", "30000", DAY3, false));
        var second = offer(option("GHN", "40000", DAY1, false), option("FAKE", "25000", DAY3, true));

        List<ShippingOption> options = ShippingChoices.common(List.of(first, second));

        assertThat(options).filteredOn(ShippingOption::recommended).singleElement()
                .extracting(ShippingOption::carrier).isEqualTo("FAKE");
    }

    @Test
    void noOfferOrNoCommonCarrierLeavesNothingToChoose() {
        assertThat(ShippingChoices.common(List.of())).isEmpty();
        var options = ShippingChoices.common(List.of(offer(option("GHN", "1", DAY1, true)),
                offer(option("FAKE", "1", DAY1, true))));
        assertThat(options).isEmpty();
        assertThat(ShippingChoices.selected(options, "GHN")).isNull();
    }

    private static OrderShippingService.Offer offer(OrderShippingService.Option... options) {
        return new OrderShippingService.Offer(PARCEL, "STANDARD", List.of(options));
    }

    private static OrderShippingService.Option option(String carrier, String fee, OffsetDateTime eta, boolean recommended) {
        return new OrderShippingService.Option(carrier, carrier, new BigDecimal(fee).setScale(2), eta, recommended);
    }
}
