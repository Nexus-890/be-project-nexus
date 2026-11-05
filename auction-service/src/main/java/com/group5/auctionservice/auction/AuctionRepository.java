package com.group5.auctionservice.auction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

public interface AuctionRepository extends JpaRepository<Auction, UUID> {
    Optional<Auction> findByIdAndVisibilityAndDeletedAtIsNull(UUID id, Auction.Visibility visibility);

    Page<Auction> findByVisibilityAndDeletedAtIsNull(Auction.Visibility visibility, Pageable pageable);

    Page<Auction> findByVisibilityAndStatusAndDeletedAtIsNull(
            Auction.Visibility visibility, Auction.Status status, Pageable pageable);
}
