package com.group5.auctionservice.bidding;

import com.group5.auctionservice.auction.Auction;
import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.auctionservice.bidding.dto.BidHistoryItem;
import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BidHistoryQuery {
    private final AuctionRepository auctions;
    private final BidRepository bids;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<BidHistoryItem> list(UUID auctionId, int page, int size) {
        if (!auctions.existsByIdAndVisibilityAndDeletedAtIsNull(auctionId, Auction.Visibility.PUBLIC)) {
            throw new DomainException(AuctionErrorCode.AUCTION_NOT_FOUND);
        }
        return bids.findHistory(auctionId, PageRequest.of(page, size));
    }
}
