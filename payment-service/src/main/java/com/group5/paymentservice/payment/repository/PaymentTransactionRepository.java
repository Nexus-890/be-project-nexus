package com.group5.paymentservice.payment.repository;

import com.group5.paymentservice.payment.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {
    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    // Chống ghi trùng theo mã giao dịch thực tế, kể cả receipt cũ dùng quy ước idempotency key khác.
    boolean existsByProviderAndTxnReference(String provider, String txnReference);
}
