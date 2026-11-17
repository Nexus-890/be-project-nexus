package com.group5.auctionservice.bidding;

import com.group5.auctionservice.bidding.dto.BidHistoryItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface BidRepository extends JpaRepository<Bid, UUID> {
    @Transactional(readOnly = true)
    Optional<Bid> findByIdempotencyKey(String idempotencyKey);

    // Earlier bids win ties. ID breaks ties at an identical placement timestamp.
    Optional<Bid> findFirstByAuctionIdAndStatusNotOrderByAmountDescPlacedAtAscIdAsc(
            UUID auctionId, Bid.Status excludedStatus);

    @Query("""
            select new com.group5.auctionservice.bidding.dto.BidHistoryItem(b.amount, b.bidderName, b.placedAt)
            from Bid b
            where b.auctionId = :auctionId
            order by b.amount desc, b.placedAt desc, b.id desc
            """)
    Page<BidHistoryItem> findHistory(UUID auctionId, Pageable pageable);
}
