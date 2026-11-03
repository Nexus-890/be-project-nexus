package com.group5.paymentservice.payment;

import com.group5.paymentservice.payment.entity.PaymentTransaction;
import com.group5.paymentservice.payment.enums.PaymentTransactionStatus;
import com.group5.paymentservice.payment.enums.PaymentTransactionType;
import com.group5.paymentservice.payment.repository.PaymentTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PaymentTransactionRepositoryTest {
    @Autowired
    private PaymentTransactionRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private UUID sessionId;

    @BeforeEach
    void createSession() {
        sessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_sessions
                    (id, order_group_id, buyer_id, total_amount, currency, memo_code,
                     due_at, source_event_id, idempotency_key)
                VALUES (?, ?, ?, 350000.00, 'VND', ?, now() + interval '1 hour', ?, ?)
                """, sessionId, UUID.randomUUID(), UUID.randomUUID(),
                "NX" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase(),
                UUID.randomUUID(), "session-" + sessionId);
    }

    @Test
    void roundTripsNamedEnumsJsonAndSessionReference() {
        var receivedAt = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        var transaction = transaction("roundtrip-" + sessionId);
        transaction.setReceivedAt(receivedAt);
        transaction.setRawResponse(Map.of("reference", "sample", "amount", 350000));
        repository.saveAndFlush(transaction);
        entityManager.clear();

        var found = repository.findByIdempotencyKey(transaction.getIdempotencyKey()).orElseThrow();
        assertThat(found.getSessionId()).isEqualTo(sessionId);
        assertThat(found.getStatus()).isEqualTo(PaymentTransactionStatus.PENDING);
        assertThat(found.getType()).isEqualTo(PaymentTransactionType.NORMAL);
        assertThat(found.getCurrency()).isEqualTo("VND");
        assertThat(found.getAmount()).isEqualByComparingTo("350000.00");
        assertThat(found.getRawResponse()).containsEntry("reference", "sample");
        assertThat(found.getReceivedAt().toInstant()).isEqualTo(receivedAt.toInstant());
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(found.getCreatedBy()).isEqualTo(new UUID(0, 0));
    }

    @Test
    void databaseRejectsDuplicateIdempotencyKey() {
        String key = "duplicate-" + sessionId;
        repository.saveAndFlush(transaction(key));
        assertThatThrownBy(() -> repository.saveAndFlush(transaction(key)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private PaymentTransaction transaction(String key) {
        var transaction = new PaymentTransaction();
        transaction.setSessionId(sessionId);
        transaction.setProvider("SEPAY");
        transaction.setAmount(new BigDecimal("350000.00"));
        transaction.setStatus(PaymentTransactionStatus.PENDING);
        transaction.setType(PaymentTransactionType.NORMAL);
        transaction.setIdempotencyKey(key);
        transaction.setReceivedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return transaction;
    }
}