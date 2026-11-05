package com.group5.paymentservice.payment.repository;

import com.group5.paymentservice.payment.entity.PaymentPendingOrderCancellation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentPendingOrderCancellationRepository
        extends JpaRepository<PaymentPendingOrderCancellation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pending from PaymentPendingOrderCancellation pending where pending.orderId = :orderId")
    Optional<PaymentPendingOrderCancellation> findLockedByOrderId(@Param("orderId") UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pending from PaymentPendingOrderCancellation pending"
            + " where pending.orderGroupId = :orderGroupId order by pending.orderId")
    List<PaymentPendingOrderCancellation> findLockedByOrderGroupId(@Param("orderGroupId") UUID orderGroupId);
}
