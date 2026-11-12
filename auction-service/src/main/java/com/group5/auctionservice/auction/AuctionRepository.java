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
    Page<Auction> findBySellerIdAndDeletedAtIsNull(UUID sellerId, Pageable pageable);

    Page<Auction> findBySellerIdAndStatusAndDeletedAtIsNull(UUID sellerId, AuctionStatus status, Pageable pageable);
    Optional<Auction> findByIdAndVisibilityAndDeletedAtIsNull(UUID id, Auction.Visibility visibility);

    Page<Auction> findByVisibilityAndStatusAndDeletedAtIsNull(
            Auction.Visibility visibility, AuctionStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Auction> findForUpdateByIdAndDeletedAtIsNull(UUID id);

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
