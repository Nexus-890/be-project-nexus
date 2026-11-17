package com.group5.ratingservice.reputation.repository;

import com.group5.ratingservice.reputation.entity.UserReputation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserReputationRepository
        extends JpaRepository<UserReputation, UUID> {

    Optional<UserReputation> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);
}
