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
    Page<Auction> findBySellerIdAndDeletedAtIsNull(UUID sellerId, Pageable pageable);

    Page<Auction> findBySellerIdAndStatusAndDeletedAtIsNull(UUID sellerId, AuctionStatus status, Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE auctions
            SET current_price = :amount, leader_bidder_id = :bidderId,
                bid_count = bid_count + 1, version = version + 1,
                updated_at = now(), updated_by = :bidderId
            WHERE id = :id AND status = 'ACTIVE' AND deleted_at IS NULL
                AND end_at > now() AND start_at <= now()
                AND seller_id <> :bidderId AND current_price + bid_increment <= :amount
            """, nativeQuery = true)
    int acceptBid(UUID id, UUID bidderId, BigDecimal amount);

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

    Page<Auction> findByVisibilityAndStatusAndDeletedAtIsNull(
            Auction.Visibility visibility, AuctionStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Auction> findForUpdateByIdAndDeletedAtIsNull(UUID id);

    // The caller holds the auction row lock also taken by the conditional bid UPDATE.
    @Query(value = "SELECT EXISTS (SELECT 1 FROM bids WHERE auction_id = :id AND status <> 'CANCELLED')",
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
            ORDER BY end_at, id LIMIT :limit FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> lockAuctionsDueToEnd(@Param("now") Instant now, @Param("limit") int limit);
}
