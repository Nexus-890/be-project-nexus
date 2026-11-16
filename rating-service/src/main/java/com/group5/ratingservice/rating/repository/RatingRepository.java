package com.group5.ratingservice.rating.repository;

import com.group5.ratingservice.rating.entity.Rating;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RatingRepository extends JpaRepository<Rating, UUID> {
    Page<Rating> findByToUserId(UUID toUserId, Pageable pageable);
}
