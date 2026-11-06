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

    // CÙNG ĐIỀU KIỆN VỚI RefundService.isFunded -> DÒNG NÀO ĐƯỢC CHỌN CŨNG BỊ XỬ LÝ VÀ XÓA, KHÔNG QUÉT LẠI MÃI
    @Query(value = """
            SELECT pending.order_group_id
            FROM payment_pending_order_cancellations pending
            JOIN payment_instructions instruction ON instruction.order_id = pending.order_id
            JOIN payment_transactions funding ON funding.id = instruction.funded_transaction_id
            WHERE instruction.status = 'PAID'
              AND funding.type = 'NORMAL'
              AND funding.status = 'SUCCESS'
              AND funding.session_id = instruction.session_id
              AND funding.currency = instruction.currency
              AND instruction.amount > 0
            GROUP BY pending.order_group_id
            ORDER BY min(pending.received_at)
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findFundedGroupIds(@Param("limit") int limit);
}
