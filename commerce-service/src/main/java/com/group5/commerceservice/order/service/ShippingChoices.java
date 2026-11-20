package com.group5.commerceservice.order.service;

import com.group5.commerceservice.checkout.dto.CheckoutPreviewResponse.ShippingOption;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class ShippingChoices {
    private ShippingChoices() { }

    public static List<ShippingOption> common(List<OrderShippingService.Offer> offers) {
        if (offers.isEmpty()) return List.of();
        List<ShippingOption> totals = new ArrayList<>();
        for (OrderShippingService.Option first : offers.getFirst().options()) {
            BigDecimal fee = BigDecimal.ZERO;
            OffsetDateTime arrival = first.estimatedDeliveryAt();
            boolean recommendedEverywhere = true;
            boolean everywhere = true;
            for (OrderShippingService.Offer offer : offers) {
                Optional<OrderShippingService.Option> same = offer.options().stream()
                        .filter(option -> option.carrier().equals(first.carrier())).findFirst();
                if (same.isEmpty()) { everywhere = false; break; }
                fee = fee.add(same.get().fee());
                if (same.get().estimatedDeliveryAt().isAfter(arrival)) arrival = same.get().estimatedDeliveryAt();
                recommendedEverywhere &= same.get().recommended();
            }
            if (everywhere) {
                totals.add(new ShippingOption(first.carrier(), first.carrierName(), fee.setScale(2), arrival,
                        recommendedEverywhere));
            }
        }
        if (totals.isEmpty() || totals.stream().anyMatch(ShippingOption::recommended)) return List.copyOf(totals);
        ShippingOption cheapest = totals.stream().min(Comparator.comparing(ShippingOption::fee)
                .thenComparing(ShippingOption::estimatedDeliveryAt).thenComparing(ShippingOption::carrier)).orElseThrow();
        return totals.stream().map(option -> option == cheapest ? new ShippingOption(option.carrier(),
                option.carrierName(), option.fee(), option.estimatedDeliveryAt(), true) : option).toList();
    }

    public static String selected(List<ShippingOption> options, String requested) {
        if (requested != null && options.stream().anyMatch(option -> option.carrier().equals(requested))) {
            return requested;
        }
        return options.stream().filter(ShippingOption::recommended).map(ShippingOption::carrier).findFirst().orElse(null);
    }
}
