package com.group5.auctionservice.auction;

import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static com.group5.auctionservice.auction.AuctionStatus.*;

@Component
public class AuctionStateMachine {
    private static final Map<AuctionStatus, Set<AuctionStatus>> ALLOWED = Map.of(
            CREATED, Set.of(SCHEDULED, CANCELLED),
            SCHEDULED, Set.of(ACTIVE, CANCELLED),
            ACTIVE, Set.of(ENDED, CANCELLED),
            ENDED, Set.of(WON, FAILED, CANCELLED),
            WON, Set.of(SETTLED, CANCELLED),
            SETTLED, Set.of(PAYMENT_TIMEOUT),
            FAILED, Set.of(),
            PAYMENT_TIMEOUT, Set.of(),
            CANCELLED, Set.of()
    );

    public boolean canTransition(AuctionStatus from, AuctionStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    public void transition(Auction auction, AuctionStatus to) {
        if (to == CANCELLED) {
            throw new DomainException(AuctionErrorCode.AUCTION_FORBIDDEN);
        }
        apply(auction, to);
    }

    public void cancel(Auction auction, CurrentUser actor) {
        if (!actor.hasRole("ADMIN") && !(actor.hasRole("SELLER") && actor.id().equals(auction.getSellerId()))) {
            throw new DomainException(AuctionErrorCode.AUCTION_FORBIDDEN);
        }
        apply(auction, CANCELLED);
    }

    boolean cancelForUnavailableStock(Auction auction) {
        return switch (auction.getStatus()) {
            case CREATED, SCHEDULED, ACTIVE -> {
                apply(auction, CANCELLED);
                yield true;
            }
            default -> false;
        };
    }

    private void apply(Auction auction, AuctionStatus to) {
        if (!canTransition(auction.getStatus(), to)) {
            throw new DomainException(AuctionErrorCode.AUCTION_ILLEGAL_TRANSITION);
        }
        auction.setStatus(to);
    }
}
