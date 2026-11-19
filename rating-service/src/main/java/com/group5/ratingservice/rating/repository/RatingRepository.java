package com.group5.ratingservice.rating.repository;

import com.group5.ratingservice.rating.entity.Rating;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface RatingRepository extends JpaRepository<Rating, UUID> {
    @Query("select r from Rating r where r.toUserId=:toUserId and r.hidden=false")
    Page<Rating> findByToUserId(UUID toUserId, Pageable pageable);
}
