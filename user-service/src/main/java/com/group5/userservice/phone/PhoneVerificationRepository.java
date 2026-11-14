package com.group5.userservice.phone;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PhoneVerificationRepository extends JpaRepository<PhoneVerification, UUID> {

    long countByUserIdAndCreatedAtAfter(UUID userId, OffsetDateTime after);

    Optional<PhoneVerification> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);
}
