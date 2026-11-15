package com.group5.auctionservice.bidding;

import com.group5.auctionservice.bidding.dto.BidHistoryItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface BidRepository extends JpaRepository<Bid, UUID> {
    Optional<Bid> findByIdempotencyKey(String idempotencyKey);

    // Lấy mọi người từng bid, kể cả bid cũ/outbid; mỗi người chỉ xuất hiện một lần trong email hủy.
    @Query("select distinct b.bidderId from Bid b where b.auctionId = :auctionId order by b.bidderId")
    List<UUID> findDistinctBidderIdsByAuctionId(UUID auctionId);

    @Query("""
            select new com.group5.auctionservice.bidding.dto.BidHistoryItem(b.amount, b.bidderName, b.placedAt)
            from Bid b
            where b.auctionId = :auctionId
            order by b.amount desc, b.placedAt desc, b.id desc
            """)
    Page<BidHistoryItem> findHistory(UUID auctionId, Pageable pageable);
}
