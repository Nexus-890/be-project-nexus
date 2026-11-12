package com.group5.auctionservice.auction;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.group5.auctionservice.auction.AuctionStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AuctionStateMachineTest {
    private static final CurrentUser ADMIN = new CurrentUser(UUID.randomUUID(), Set.of("ADMIN"), Set.of());
    private final AuctionStateMachine machine = new AuctionStateMachine();

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("statusPairs")
    void enforcesEveryTransition(AuctionStatus from, AuctionStatus to) {
        var auction = new Auction();
        ReflectionTestUtils.setField(auction, "status", from);
        Runnable transition = to == CANCELLED ? () -> machine.cancel(auction, ADMIN) : () -> machine.transition(auction, to);

        boolean expected = allowed(from, to);
        assertThat(machine.canTransition(from, to)).isEqualTo(expected);
        if (expected) {
            transition.run();
            assertThat(auction.getStatus()).isEqualTo(to);
        } else {
            assertThatExceptionOfType(DomainException.class).isThrownBy(transition::run)
                    .satisfies(error -> {
                        assertThat(error.getCode()).isEqualTo(409);
                        assertThat(error.getErrorCode()).isEqualTo("AUCTION_ILLEGAL_TRANSITION");
                    });
            assertThat(auction.getStatus()).isEqualTo(from);
        }
    }

    @Test
    void onlyAdminOrOwningSellerCanCancelAndGenericTransitionCannotBypassActorCheck() {
        var auction = new Auction();
        var seller = new CurrentUser(UUID.randomUUID(), Set.of("SELLER"), Set.of("AUCTION.CANCEL"));

        assertThatExceptionOfType(DomainException.class).isThrownBy(() -> machine.cancel(auction, seller))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo(403);
                    assertThat(error.getErrorCode()).isEqualTo("AUCTION_FORBIDDEN");
                });
        assertThatExceptionOfType(DomainException.class).isThrownBy(() -> machine.transition(auction, CANCELLED))
                .satisfies(error -> assertThat(error.getCode()).isEqualTo(403));
        assertThat(auction.getStatus()).isEqualTo(CREATED);

        ReflectionTestUtils.setField(auction, "sellerId", seller.id());
        machine.cancel(auction, seller);
        assertThat(auction.getStatus()).isEqualTo(CANCELLED);
    }

    private static Stream<Arguments> statusPairs() {
        return Arrays.stream(AuctionStatus.values())
                .flatMap(from -> Arrays.stream(AuctionStatus.values()).map(to -> Arguments.of(from, to)));
    }

    private static boolean allowed(AuctionStatus from, AuctionStatus to) {
        return switch (to) {
            case CREATED -> false;
            case SCHEDULED -> from == CREATED;
            case ACTIVE -> from == SCHEDULED;
            case ENDED -> from == ACTIVE;
            case WON, FAILED -> from == ENDED;
            case SETTLED -> from == WON;
            case PAYMENT_TIMEOUT -> from == SETTLED;
            case CANCELLED -> Set.of(CREATED, SCHEDULED, ACTIVE, ENDED, WON).contains(from);
        };
    }
}
