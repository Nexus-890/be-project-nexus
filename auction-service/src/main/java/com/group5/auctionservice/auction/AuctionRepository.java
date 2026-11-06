package com.group5.auctionservice.auction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface AuctionRepository extends JpaRepository<Auction, UUID> {

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE auctions
            SET current_price = :amount, leader_bidder_id = :bidderId,
                bid_count = bid_count + 1, version = version + 1,
                updated_at = now(), updated_by = :bidderId
            WHERE id = :id AND status = 'ACTIVE' AND deleted_at IS NULL
                AND seller_id <> :bidderId AND current_price + bid_increment <= :amount
            """, nativeQuery = true)
    int acceptBid(UUID id, UUID bidderId, BigDecimal amount);

    @Query(value = "SELECT * FROM auctions WHERE id = :id AND deleted_at IS NULL", nativeQuery = true)
    Optional<Auction> findAvailableById(UUID id);

    Optional<Auction> findByIdAndVisibilityAndDeletedAtIsNull(UUID id, Auction.Visibility visibility);

    Page<Auction> findByVisibilityAndStatusAndDeletedAtIsNull(
            Auction.Visibility visibility, Auction.Status status, Pageable pageable);
}
