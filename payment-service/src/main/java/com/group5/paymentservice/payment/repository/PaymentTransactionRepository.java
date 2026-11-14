package com.group5.paymentservice.payment.repository;

import com.group5.paymentservice.payment.dto.response.PaymentView;
import com.group5.paymentservice.payment.entity.PaymentTransaction;
import com.group5.paymentservice.payment.enums.PaymentTransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {
    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    // Chống ghi trùng theo mã giao dịch thực tế, kể cả receipt cũ dùng quy ước idempotency key khác.
    boolean existsByProviderAndTxnReference(String provider, String txnReference);

    @Query(value = """
            select new com.group5.paymentservice.payment.dto.response.PaymentView(transaction.id,
                   session.orderGroupId, transaction.provider, transaction.txnReference, transaction.amount,
                   transaction.currency, transaction.type, transaction.status, transaction.receivedAt)
            from PaymentTransaction transaction, PaymentSession session
            where session.id = transaction.sessionId
            """, countQuery = "select count(transaction) from PaymentTransaction transaction")
    Page<PaymentView> findViews(Pageable pageable);

    @Query(value = """
            select new com.group5.paymentservice.payment.dto.response.PaymentView(transaction.id,
                   session.orderGroupId, transaction.provider, transaction.txnReference, transaction.amount,
                   transaction.currency, transaction.type, transaction.status, transaction.receivedAt)
            from PaymentTransaction transaction, PaymentSession session
            where session.id = transaction.sessionId and transaction.status = :status
            """, countQuery = """
            select count(transaction) from PaymentTransaction transaction where transaction.status = :status
            """)
    Page<PaymentView> findViewsByStatus(@Param("status") PaymentTransactionStatus status, Pageable pageable);

    @Query("""
            select new com.group5.paymentservice.payment.dto.response.PaymentView(transaction.id,
                   session.orderGroupId, transaction.provider, transaction.txnReference, transaction.amount,
                   transaction.currency, transaction.type, transaction.status, transaction.receivedAt)
            from PaymentTransaction transaction, PaymentSession session
            where session.id = transaction.sessionId and transaction.sessionId = :sessionId
            order by transaction.receivedAt, transaction.id
            """)
    List<PaymentView> findViewsBySession(@Param("sessionId") UUID sessionId);
}
