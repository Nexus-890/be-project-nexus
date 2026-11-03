package com.group5.ratingservice.repository;

import com.group5.ratingservice.entity.RatingEligibility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface RatingEligibilityRepository
        extends JpaRepository<RatingEligibility, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO rating_eligibility
                (order_id, buyer_id, seller_id, completed_at, auction_id, source_event_id, source_version)
            VALUES (:orderId, :buyerId, :sellerId, :completedAt, :auctionId, :sourceEventId, :sourceVersion)
            ON CONFLICT (order_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("orderId") UUID orderId,
                       @Param("buyerId") UUID buyerId,
                       @Param("sellerId") UUID sellerId,
                       @Param("completedAt") OffsetDateTime completedAt,
                       @Param("auctionId") UUID auctionId,
                       @Param("sourceEventId") UUID sourceEventId,
                       @Param("sourceVersion") Long sourceVersion);
}
