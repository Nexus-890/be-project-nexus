package com.group5.paymentservice.payment.repository;

import com.group5.paymentservice.payment.entity.PaymentSession;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface PaymentSessionRepository extends JpaRepository<PaymentSession, UUID> {
    Optional<PaymentSession> findByOrderGroupId(UUID orderGroupId);

    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    Integer lockKey(@Param("key") String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentSession> findLockedByOrderGroupId(UUID orderGroupId);
}
