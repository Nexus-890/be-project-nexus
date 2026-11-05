package com.group5.paymentservice.payment;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.service.PaymentService;
import com.group5.paymentservice.payment.repository.PaymentInstructionRepository;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentInitiationDatabaseTest extends PaymentDatabaseTestSupport {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentService payments;
    @Autowired private PaymentSessionRepository sessions;
    @Autowired private PaymentInstructionRepository instructions;
    @Autowired private Clock clock;
    private UUID sessionId;
    private UUID groupId;
    private UUID buyerId;

    @BeforeEach
    void seed() {
        sessionId = UUID.randomUUID();
        groupId = UUID.randomUUID();
        buyerId = UUID.randomUUID();
        String memo = "NX" + sessionId.toString().replace("-", "").substring(0, 30).toUpperCase();
        OffsetDateTime due = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).plusMinutes(15);
        jdbc.update("""
                INSERT INTO payment_sessions
                    (id, order_group_id, buyer_id, total_amount, currency, memo_code,
                     due_at, source_event_id, idempotency_key)
                VALUES (?, ?, ?, 350000, 'VND', ?, ?, ?, ?)
                """, sessionId, groupId, buyerId, memo, due, UUID.randomUUID(), groupId.toString());
        for (int amount : new int[]{200000, 150000}) {
            jdbc.update("""
                    INSERT INTO payment_instructions (order_id, session_id, buyer_id, amount, due_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), sessionId, buyerId, amount, due);
        }
    }

    @AfterEach
    void cleanUp() {
        if (sessionId != null) {
            jdbc.update("DELETE FROM payment_instructions WHERE session_id = ?", sessionId);
            jdbc.update("DELETE FROM payment_sessions WHERE id = ?", sessionId);
        }
    }

    @Test
    void readsNativeEnumsAndTwoInstructionsWithoutChangingAnyStoredFieldOnRepeat() {
        var beforeSession = jdbc.queryForMap("SELECT * FROM payment_sessions WHERE id = ?", sessionId);
        var beforeInstructions = jdbc.queryForList(
                "SELECT * FROM payment_instructions WHERE session_id = ? ORDER BY order_id", sessionId);
        var first = payments.initiate(buyer(buyerId), groupId);
        assertThat(first.amount()).isEqualByComparingTo("350000");
        assertThat(first.memoCode()).isEqualTo(beforeSession.get("memo_code"));
        assertThat(payments.initiate(buyer(buyerId), groupId)).isEqualTo(first);
        assertThat(sessions.findByOrderGroupId(groupId)).isPresent();
        assertThat(instructions.findBySessionIdOrderByOrderId(sessionId)).hasSize(2);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_sessions WHERE id = ?", sessionId))
                .isEqualTo(beforeSession);
        assertThat(jdbc.queryForList("SELECT * FROM payment_instructions WHERE session_id = ? ORDER BY order_id",
                sessionId)).isEqualTo(beforeInstructions);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_sessions WHERE order_group_id = ?",
                Integer.class, groupId)).isEqualTo(1);
    }

    @Test
    void rejectsOtherBuyersCanceledSessionsAndExpiredDeadlinesFromDatabase() {
        assertStatus(403, buyer(UUID.randomUUID()));
        jdbc.update("UPDATE payment_sessions SET status = 'CANCELED' WHERE id = ?", sessionId);
        assertStatus(409, buyer(buyerId));
        // Use the same clock as PaymentService; Docker's DB clock may differ from the host JVM.
        jdbc.update("UPDATE payment_sessions SET status = 'PENDING', due_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(clock.instant().minusSeconds(1), ZoneOffset.UTC), sessionId);
        assertStatus(409, buyer(buyerId));
    }

    private void assertStatus(int status, CurrentUser user) {
        assertThatThrownBy(() -> payments.initiate(user, groupId)).isInstanceOfSatisfying(
                DomainException.class, exception -> assertThat(exception.getCode()).isEqualTo(status));
    }

    private CurrentUser buyer(UUID id) {
        return new CurrentUser(id, Set.of("BUYER"), Set.of("CHECKOUT.START"));
    }
}
