package com.group5.auctionservice.bidding;

import com.group5.auctionservice.bidding.dto.BidHistoryItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BidRepository extends JpaRepository<Bid, UUID> {
    @Transactional(readOnly = true)
    Optional<Bid> findByIdempotencyKey(String idempotencyKey);

    /** Truy vấn có ownership ngay trong DB để không tiết lộ request của bidder khác. */
    Optional<Bid> findByIdAndAuctionIdAndBidderId(UUID id, UUID auctionId, UUID bidderId);

    /** Chỉ consumer của cùng auction tranh khóa; POST không lấy khóa xử lý này. */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('bid-process:' || CAST(:id AS text), 0))", nativeQuery = true)
    int lockProcessing(UUID id);

    /** Drain theo thứ tự bền vững để relay gửi đảo command không làm mất bước giá cũ. */
    @Query(value = """
            SELECT * FROM bids WHERE auction_id = :auctionId AND status = 'QUEUED'
              AND (requested_at < :requestedAt OR (requested_at = :requestedAt AND id <= :bidId))
            ORDER BY requested_at, id FOR UPDATE
            """, nativeQuery = true)
    List<Bid> lockQueuedThrough(UUID auctionId, Instant requestedAt, UUID bidId);

    /** Winner chỉ xét bid thật sự được áp dụng, không chọn request QUEUED hoặc REJECTED. */
    Optional<Bid> findFirstByAuctionIdAndStatusInOrderByAmountDescPlacedAtAscIdAsc(UUID auctionId, List<Bid.Status> statuses);

    /** Chỉ người đã đặt bid hợp lệ được đưa vào danh sách nhận email hủy. */
    @Query("select distinct b.bidderId from Bid b where b.auctionId = :auctionId and cast(b.status as string) not in ('QUEUED', 'REJECTED') order by b.bidderId")
    List<UUID> findDistinctBidderIdsByAuctionId(UUID auctionId);

    @Query("""
            select new com.group5.auctionservice.bidding.dto.BidHistoryItem(b.amount, b.bidderName, b.placedAt)
            from Bid b
            where b.auctionId = :auctionId and cast(b.status as string) not in ('QUEUED', 'REJECTED')
            order by b.amount desc, b.placedAt desc, b.id desc
            """)
    Page<BidHistoryItem> findHistory(UUID auctionId, Pageable pageable);
}
