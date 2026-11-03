package com.group5.commerceservice.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static com.group5.commerceservice.order.OrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    private static final Set<String> ALLOWED = Set.of(
            "PENDING->PAID",
            "PENDING->CANCELED",
            "PAID->CANCELED",
            "PAID->SHIPPED",
            "SHIPPED->DELIVERED",
            "DELIVERED->COMPLETED",
            "CANCELED->REFUND_REQUESTED",
            "REFUND_REQUESTED->REFUNDED");

    static Stream<Arguments> everyPair() {
        return Arrays.stream(OrderStatus.values())
                .flatMap(from -> Arrays.stream(OrderStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void onlyTheEightListedMovesAreAllowed(OrderStatus from, OrderStatus to) {
        assertThat(from.canMoveTo(to)).isEqualTo(ALLOWED.contains(from + "->" + to));
    }

    @Test
    void theTableAllowsEightOfTheSixtyFourPairs() {
        long allowed = Arrays.stream(OrderStatus.values())
                .flatMap(from -> Arrays.stream(OrderStatus.values()).filter(from::canMoveTo))
                .count();
        assertThat(everyPair().count()).isEqualTo(64);
        assertThat(allowed).isEqualTo(8);
    }

    @Test
    void aCanceledOrderCannotBecomePaid() {
        assertThat(CANCELED.canMoveTo(PAID)).isFalse();
    }
}
