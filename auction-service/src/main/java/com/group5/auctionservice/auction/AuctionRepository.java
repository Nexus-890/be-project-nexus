package com.group5.auctionservice.auction;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuctionRepository extends JpaRepository<Auction, UUID> {
    /** Cho các POST cùng auction đi song song, chỉ phối hợp với lifecycle đang đóng phiên. */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock_shared(hashtextextended('bid-admission:' || CAST(:id AS text), 0))", nativeQuery = true)
    int lockBidAdmission(UUID id);

    /** Consumer chờ cohort POST đang nhận commit trước khi drain theo requestedAt bền vững. */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('bid-admission:' || CAST(:id AS text), 0))", nativeQuery = true)
    int lockBidProcessingAdmission(UUID id);

    /** Không đợi POST chưa commit; scheduler sẽ thử lại tick sau để tránh deadlock với FK. */
    @Query(value = "SELECT pg_try_advisory_xact_lock(hashtextextended('bid-admission:' || CAST(:id AS text), 0))", nativeQuery = true)
    boolean tryCloseBidAdmission(UUID id);

    /** Đọc lại sau khi giữ khóa admission để thấy mọi QUEUED đã commit trước lúc đóng phiên. */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM bids b JOIN auctions a ON a.id = b.auction_id
                WHERE a.id = :id AND b.status = 'QUEUED'
                  AND b.requested_at >= a.start_at AND b.requested_at < a.end_at)
            """, nativeQuery = true)
    boolean hasEligibleQueuedBids(UUID id);

    /** Thời gian DB thực sau khi lấy advisory lock, không phải transaction-start time bị cũ. */
    @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
    Instant currentWallTime();

    /** Snapshot the old leader under the same row lock as the accepted bid update. */
    @Query(value = "SELECT leader_bidder_id FROM auctions WHERE id = :id AND deleted_at IS NULL FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockLeaderForOutbid(UUID id);

    /** Conditional UPDATE vẫn chống lost update khi hai consumer cùng nhận partition lúc rebalance. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE auctions SET current_price = :amount, leader_bidder_id = :bidderId,
                leader_bidder_name = :bidderName, bid_count = bid_count + 1, version = version + 1,
                updated_at = clock_timestamp(), updated_by = :bidderId
            WHERE id = :id AND status = 'ACTIVE' AND deleted_at IS NULL
                AND end_at > :requestedAt AND start_at <= :requestedAt
                AND seller_id <> :bidderId AND current_price + bid_increment <= :amount
            """, nativeQuery = true)
    int acceptQueuedBid(UUID id, UUID bidderId, BigDecimal amount, String bidderName, Instant requestedAt);

    /** Gia hạn theo requestedAt của bid, kể cả khi consumer xử lý sau deadline cũ. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE auctions
            SET end_at = LEAST(end_at + (:extensionMinutes * interval '1 minute'),
                               original_end_at + (:maxExtensionMinutes * interval '1 minute')),
                extension_count = extension_count + 1, version = version + 1,
                updated_at = clock_timestamp(), updated_by = :bidderId
            WHERE id = :id AND status = 'ACTIVE' AND deleted_at IS NULL
                AND end_at > :requestedAt
                AND end_at <= CAST(:requestedAt AS timestamptz) + (:extensionMinutes * interval '1 minute')
                AND end_at < original_end_at + (:maxExtensionMinutes * interval '1 minute')
            """, nativeQuery = true)
    int extendQueuedEndAt(UUID id, UUID bidderId, Instant requestedAt, int extensionMinutes, int maxExtensionMinutes);

    @Query(value = """
            SELECT id FROM auctions
            WHERE status = 'ENDED' AND deleted_at IS NULL
                AND (CAST(:afterId AS uuid) IS NULL OR id > :afterId)
            ORDER BY id LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findEndedIds(UUID afterId, int limit);

    Page<Auction> findBySellerIdAndDeletedAtIsNull(UUID sellerId, Pageable pageable);

    Page<Auction> findByDeletedAtIsNull(Pageable pageable);

    Page<Auction> findByStatusAndDeletedAtIsNull(AuctionStatus status, Pageable pageable);

    Optional<Auction> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByIdAndDeletedAtIsNull(UUID id);

    Page<Auction> findBySellerIdAndStatusAndDeletedAtIsNull(UUID sellerId, AuctionStatus status, Pageable pageable);

    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('auction-create:' || CAST(:sellerId AS text), 0))",
            nativeQuery = true)
    int lockSellerForCreate(UUID sellerId);

    @Query(value = """
            SELECT count(*) FROM auctions
            WHERE seller_id = :sellerId AND deleted_at IS NULL
                AND status IN ('CREATED', 'SCHEDULED', 'ACTIVE')
            """, nativeQuery = true)
    long countLiveBySeller(UUID sellerId);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE auctions
            SET current_price = :amount, leader_bidder_id = :bidderId, leader_bidder_name = :bidderName,
                bid_count = bid_count + 1, version = version + 1,
                updated_at = now(), updated_by = :bidderId
            WHERE id = :id AND status = 'ACTIVE' AND deleted_at IS NULL
                AND end_at > now() AND start_at <= now()
                AND seller_id <> :bidderId AND current_price + bid_increment <= :amount
            """, nativeQuery = true)
    int acceptBid(UUID id, UUID bidderId, BigDecimal amount, String bidderName);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE auctions
            SET end_at = LEAST(end_at + (:extensionMinutes * interval '1 minute'),
                               original_end_at + (:maxExtensionMinutes * interval '1 minute')),
                extension_count = extension_count + 1, version = version + 1,
                updated_at = now(), updated_by = :bidderId
            WHERE id = :id AND status = 'ACTIVE' AND deleted_at IS NULL
                AND end_at > now()
                AND end_at <= now() + (:extensionMinutes * interval '1 minute')
                AND end_at < original_end_at + (:maxExtensionMinutes * interval '1 minute')
            """, nativeQuery = true)
    int extendEndAt(UUID id, UUID bidderId, int extensionMinutes, int maxExtensionMinutes);

    @Query(value = "SELECT * FROM auctions WHERE id = :id AND deleted_at IS NULL", nativeQuery = true)
    Optional<Auction> findAvailableById(UUID id);

    // Dùng cùng mốc thời gian transaction với UPDATE khi xác định lý do từ chối bid.
    @Query(value = "SELECT now()", nativeQuery = true)
    Instant currentTransactionTime();

    Optional<Auction> findByIdAndVisibilityAndDeletedAtIsNull(UUID id, Auction.Visibility visibility);

    boolean existsByIdAndVisibilityAndDeletedAtIsNull(UUID id, Auction.Visibility visibility);

    Page<Auction> findByVisibilityAndStatusAndDeletedAtIsNull(
            Auction.Visibility visibility, AuctionStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Auction> findForUpdateByIdAndDeletedAtIsNull(UUID id);

    // The caller holds the auction row lock also taken by the conditional bid UPDATE.
    @Query(value = "SELECT EXISTS (SELECT 1 FROM bids WHERE auction_id = :id AND status NOT IN ('CANCELLED', 'REJECTED'))",
            nativeQuery = true)
    boolean hasValidBids(@Param("id") UUID id);

    @Query(value = """
            SELECT id FROM auctions
            WHERE status = 'SCHEDULED' AND start_at <= :now AND deleted_at IS NULL
            ORDER BY start_at, id LIMIT :limit FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> lockAuctionsDueToStart(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = """
            SELECT id FROM auctions
            WHERE status = 'ACTIVE' AND end_at <= :now AND deleted_at IS NULL
                AND NOT EXISTS (SELECT 1 FROM bids b WHERE b.auction_id = auctions.id
                    AND b.status = 'QUEUED' AND b.requested_at >= auctions.start_at
                    AND b.requested_at < auctions.end_at)
            ORDER BY end_at, id LIMIT :limit FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> lockAuctionsDueToEnd(@Param("now") Instant now, @Param("limit") int limit);
}
