package com.group5.auctionservice.auction;

import com.group5.auctionservice.shared.config.AuctionProperties;
import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Component
@RequiredArgsConstructor
class AuctionCreation {
    private static final String LIVE_SKU_INDEX = "uq_auctions_live_sku";

    private final AuctionRepository auctions;
    private final AuctionProperties properties;
    private final EventEnvelopeFactory envelopes;
    private final OutboxWriter outbox;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Auction save(Auction auction) {
        var sellerId = auction.getSellerId();
        // Count in a separate statement after acquiring the lock so it sees the previous create commit.
        auctions.lockSellerForCreate(sellerId);
        int sellerLimit = properties.getMaxActivePerSeller();
        if (auctions.countLiveBySeller(sellerId) >= sellerLimit) {
            throw new DomainException(AuctionErrorCode.AUCTION_SELLER_LIMIT_REACHED,
                    "The seller has reached the live auction limit of %d".formatted(sellerLimit));
        }

        try {
            auctions.saveAndFlush(auction);
        } catch (DataIntegrityViolationException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "23505".equals(violation.getSQLState())
                        && LIVE_SKU_INDEX.equals(violation.getConstraintName())) {
                    throw new DomainException(AuctionErrorCode.AUCTION_SKU_ALREADY_LIVE);
                }
            }
            throw failure;
        }

        outbox.write("Auction", Topics.AUCTION, envelopes.createWithTimestamp(
                "AuctionCreated", auction.getId().toString(), createdAt -> Map.of(
                        "auctionId", auction.getId(),
                        "sellerId", auction.getSellerId(),
                        "productId", auction.getProductId(),
                        "skuId", auction.getSkuId(),
                        "quantity", 1,
                        "startAt", auction.getStartAt(),
                        "endAt", auction.getEndAt(),
                        "auctionVersion", auction.getVersion(),
                        "createdAt", createdAt)));
        return auction;
    }
}
