package com.group5.auctionservice.auction;

import com.group5.auctionservice.shared.config.AuctionProperties;
import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class AuctionCreation {
    private static final String LIVE_SKU_INDEX = "uq_auctions_live_sku";

    private final AuctionRepository auctions;
    private final AuctionProperties properties;

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
            return auctions.saveAndFlush(auction);
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
    }
}
