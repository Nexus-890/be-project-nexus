package com.group5.userservice.phone;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PhoneVerificationRepository extends JpaRepository<PhoneVerification, UUID> {

    long countByUserIdAndCreatedAtAfter(UUID userId, OffsetDateTime after);

    Optional<PhoneVerification> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    @Modifying(flushAutomatically = true)
    @Query("update PhoneVerification v set v.expiresAt = v.createdAt where v.userId = :userId and v.verifiedAt is null")
    int expirePending(@Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query("delete from PhoneVerification v where v.userId = :userId")
    int deleteAllOfUser(@Param("userId") UUID userId);
}
