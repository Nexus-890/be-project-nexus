package com.group5.paymentservice.payment.repository;

import com.group5.paymentservice.payment.entity.PaymentPendingCancellation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface PaymentPendingCancellationRepository extends JpaRepository<PaymentPendingCancellation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentPendingCancellation> findLockedByOrderGroupId(@Param("orderGroupId") UUID orderGroupId);
}
