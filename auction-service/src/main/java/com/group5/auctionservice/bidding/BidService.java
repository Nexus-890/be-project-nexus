package com.group5.auctionservice.bidding;

import com.group5.auctionservice.auction.Auction;
import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.auctionservice.bidding.dto.BidResponse;
import com.group5.auctionservice.bidding.dto.PlaceBidRequest;
import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BidService {

    private final AuctionRepository auctions;
    private final BidRepository bids;
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;

    public record PlacementResult(boolean created, BidResponse response) {
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PlacementResult place(UUID auctionId, String key, PlaceBidRequest request, CurrentUser bidder) {
        if (key == null || key.isBlank()) {
            throw new DomainException(AuctionErrorCode.AUCTION_IDEMPOTENCY_KEY_REQUIRED);
        }
        if (!key.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new DomainException(AuctionErrorCode.AUCTION_INVALID_IDEMPOTENCY_KEY);
        }
        String canonicalKey = UUID.fromString(key).toString();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            return transaction.execute(status -> placeOrReplay(auctionId, canonicalKey, request, bidder));
        } catch (DataIntegrityViolationException violation) {
            if (!isIdempotencyKeyViolation(violation)) {
                throw violation;
            }
            // PostgreSQL aborts the UNIQUE loser; read the winner only after full rollback.
            transaction.setReadOnly(true);
            return transaction.execute(status -> bids.findByIdempotencyKey(canonicalKey)
                    .map(bid -> replay(auctionId, bidder, bid))
                    .orElseThrow(() -> violation));
        }
    }

    private PlacementResult placeOrReplay(UUID auctionId, String key, PlaceBidRequest request, CurrentUser bidder) {
        Optional<Bid> previous = bids.findByIdempotencyKey(key);
        if (previous.isPresent()) {
            return replay(auctionId, bidder, previous.get());
        }
        BigDecimal amount = request.amount().setScale(2);
        if (auctions.acceptBid(auctionId, bidder.id(), amount) == 0) {
            // An equal-amount retry may have waited for the first bid's auction row lock.
            previous = bids.findByIdempotencyKey(key);
            if (previous.isPresent()) {
                return replay(auctionId, bidder, previous.get());
            }
            Auction auction = auctions.findAvailableById(auctionId)
                    .orElseThrow(() -> new DomainException(AuctionErrorCode.AUCTION_NOT_FOUND));
            if (auction.getSellerId().equals(bidder.id())) {
                throw new DomainException(AuctionErrorCode.AUCTION_SELLER_CANNOT_BID);
            }
            if (auction.getStatus() != Auction.Status.ACTIVE) {
                throw new DomainException(AuctionErrorCode.AUCTION_NOT_ACTIVE);
            }
            BigDecimal minimum = auction.getCurrentPrice().add(auction.getBidIncrement());
            throw new DomainException(AuctionErrorCode.AUCTION_BID_TOO_LOW,
                    "Bid amount must be at least " + minimum.toPlainString(), Map.of("minimum", minimum));
        }

        Bid bid = bids.saveAndFlush(new Bid(auctionId, bidder.id(), amount,
                Instant.now(clock).truncatedTo(ChronoUnit.MICROS), key));

        // #149 anti-sniping hook: extend end_at here for this accepted bid, before publishing.
        // This transaction still holds the auction row lock; original_end_at must remain unchanged.

        BidPlaced payload = new BidPlaced(auctionId, bid.getId(), bidder.id(), amount.toPlainString(),
                "VND", bid.getPlacedAt());
        outbox.write("Auction", Topics.AUCTION, envelopes.create("BidPlaced", auctionId.toString(), payload));
        return new PlacementResult(true, response(bid));
    }

    private PlacementResult replay(UUID auctionId, CurrentUser bidder, Bid bid) {
        if (!bid.getAuctionId().equals(auctionId) || !bid.getBidderId().equals(bidder.id())) {
            throw new DomainException(AuctionErrorCode.AUCTION_IDEMPOTENCY_KEY_CONFLICT);
        }
        return new PlacementResult(false, response(bid));
    }

    private BidResponse response(Bid bid) {
        return new BidResponse(bid.getId(), bid.getAmount(), bid.getBidderId());
    }

    private boolean isIdempotencyKeyViolation(DataIntegrityViolationException violation) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint
                    && "23505".equals(constraint.getSQLState())
                    && "bids_idempotency_key_key".equals(constraint.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private record BidPlaced(UUID auctionId, UUID bidId, UUID bidderId, String amount,
                             String currency, Instant placedAt) {
    }
}
