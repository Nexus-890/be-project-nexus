package com.group5.auctionservice.auction;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuctionRepository extends JpaRepository<Auction, UUID> {
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
