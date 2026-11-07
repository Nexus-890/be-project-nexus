package com.group5.auctionservice.auction;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface AuctionRepository extends JpaRepository<Auction, UUID> {
    Optional<Auction> findByIdAndVisibilityAndDeletedAtIsNull(UUID id, Auction.Visibility visibility);

    Page<Auction> findByVisibilityAndStatusAndDeletedAtIsNull(
            Auction.Visibility visibility, AuctionStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Auction> findForUpdateByIdAndDeletedAtIsNull(UUID id);
}
