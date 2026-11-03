package com.group5.ratingservice.repository;

import com.group5.ratingservice.entity.Rating;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RatingRepository extends JpaRepository<Rating, UUID> {
}
