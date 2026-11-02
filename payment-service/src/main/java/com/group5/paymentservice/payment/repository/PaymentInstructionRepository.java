package com.group5.paymentservice.payment.repository;

import com.group5.paymentservice.payment.entity.PaymentInstruction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.UUID;

public interface PaymentInstructionRepository extends JpaRepository<PaymentInstruction, UUID> {
    List<PaymentInstruction> findBySessionIdOrderByOrderId(UUID sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select instruction from PaymentInstruction instruction where instruction.session.id = :sessionId order by instruction.orderId")
    List<PaymentInstruction> findSessionForUpdate(@Param("sessionId") UUID sessionId);
}
