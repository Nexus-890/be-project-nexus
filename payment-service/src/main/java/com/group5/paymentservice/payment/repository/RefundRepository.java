package com.group5.paymentservice.payment.repository;

import com.group5.paymentservice.payment.entity.Refund;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    Optional<Refund> findByOrderId(UUID orderId);

    @Query("select refund.orderId from Refund refund where refund.id = :id")
    Optional<UUID> findOrderIdById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select refund from Refund refund where refund.orderId = :orderId")
    Optional<Refund> findLockedByOrderId(@Param("orderId") UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select refund from Refund refund where refund.id = :id")
    Optional<Refund> findLockedById(@Param("id") UUID id);
}
