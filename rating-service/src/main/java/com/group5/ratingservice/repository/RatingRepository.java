package com.group5.ratingservice.repository;

import com.group5.ratingservice.entity.Rating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface RatingRepository extends JpaRepository<Rating, UUID> {
    boolean existsByFromUserIdAndOrderId(UUID fromUserId, UUID orderId);
    Page<Rating> findByToUserId(UUID toUserId, Pageable pageable);
}
