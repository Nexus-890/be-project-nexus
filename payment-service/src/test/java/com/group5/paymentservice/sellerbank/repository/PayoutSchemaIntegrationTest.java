package com.group5.paymentservice.sellerbank.repository;

import com.group5.paymentservice.payment.PaymentDatabaseTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Kiểm tra ràng buộc do Flyway tạo trực tiếp tại database, độc lập với xử lý trạng thái trong service. */
class PayoutSchemaIntegrationTest extends PaymentDatabaseTestSupport {
    @Autowired private JdbcTemplate jdbc;

    @Test
    void onePayoutPerOrderAndFeeEquationAreEnforcedByTheDatabase() {
        var fixture = fixture();
        UUID payout = insertPayout(fixture);
        assertThatThrownBy(() -> insertPayout(fixture)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payouts SET net_amount=98 WHERE id=?", payout))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payouts SET platform_fee_rate=1.0001 WHERE id=?", payout))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payouts SET platform_fee=2,net_amount=88 WHERE id=?", payout))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT net_amount FROM payouts WHERE id=?", java.math.BigDecimal.class, payout))
                .isEqualByComparingTo("90");
    }

    @Test
    void historyCannotBeUpdatedDeletedOrTruncatedAndSequenceNumbersCannotBeReused() {
        var fixture = fixture();
        UUID payout = insertPayout(fixture);
        jdbc.update("""
                INSERT INTO payout_history(payout_id,event_seq,event,to_status,attempt_no,amount,simulated,actor_id)
                VALUES (?,1,'CREATED','ON_HOLD',0,90,true,?)
                """, payout, fixture.seller());
        assertThatThrownBy(() -> jdbc.update("UPDATE payout_history SET amount=1 WHERE payout_id=?", payout))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM payout_history WHERE payout_id=?", payout))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE payout_history"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payout_history(payout_id,event_seq,event,to_status,attempt_no,amount,simulated,actor_id)
                VALUES (?,1,'HELD','ON_HOLD',0,90,true,?)
                """, payout, fixture.seller())).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payout_history WHERE payout_id=?", Integer.class, payout)).isEqualTo(1);
    }

    @Test
    void bankUniquenessCoversUnverifiedDefaultsAndVerificationRequiresReviewerMetadata() {
        UUID seller = UUID.randomUUID();
        UUID bank = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO seller_bank(id,seller_id,bank_code,account_number_encrypted,account_last4,account_holder_name,is_default)
                VALUES (?,?,'970415','test-ciphertext','1234','SCHEMA TEST',true)
                """, bank, seller);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO seller_bank(seller_id,bank_code,account_number_encrypted,account_last4,account_holder_name,is_default)
                VALUES (?,'970416','test-ciphertext','5678','SCHEMA TEST',true)
                """, seller)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE seller_bank SET verification_status='VERIFIED' WHERE id=?", bank))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE seller_bank SET verification_status='REJECTED',verified_by=?,verified_at=now(),rejection_reason=' ' WHERE id=?
                """, UUID.randomUUID(), bank)).isInstanceOf(DataAccessException.class);
        jdbc.update("UPDATE seller_bank SET active=false WHERE id=?", bank);
        jdbc.update("""
                INSERT INTO seller_bank(seller_id,bank_code,account_number_encrypted,account_last4,account_holder_name,is_default)
                VALUES (?,'970416','test-ciphertext','5678','SCHEMA TEST',true)
                """, seller);
    }

    private Fixture fixture() {
        UUID session = UUID.randomUUID(), instruction = UUID.randomUUID(), order = UUID.randomUUID();
        UUID seller = UUID.randomUUID(), buyer = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_sessions(id,order_group_id,buyer_id,total_amount,currency,memo_code,due_at,source_event_id,idempotency_key)
                VALUES (?,?,?,100,'VND',?,now()+interval '1 hour',?,?)
                """, session, UUID.randomUUID(), buyer, "NX" + session.toString().replace("-", "").substring(0, 24).toUpperCase(),
                UUID.randomUUID(), "schema-session:" + session);
        jdbc.update("""
                INSERT INTO payment_instructions(id,order_id,session_id,buyer_id,seller_id,amount,currency,due_at)
                VALUES (?,?,?,?,?,100,'VND',now()+interval '1 hour')
                """, instruction, order, session, buyer, seller);
        return new Fixture(order, session, instruction, seller);
    }

    private UUID insertPayout(Fixture fixture) {
        return jdbc.queryForObject("""
                INSERT INTO payouts(order_id,session_id,instruction_id,seller_id,order_amount,shipping_fee,
                    platform_fee_rate,platform_fee,net_amount,currency,status,source_event_id)
                VALUES (?,?,?,?,100,10,0,0,90,'VND','ON_HOLD',?) RETURNING id
                """, UUID.class, fixture.order(), fixture.session(), fixture.instruction(), fixture.seller(), UUID.randomUUID());
    }

    private record Fixture(UUID order, UUID session, UUID instruction, UUID seller) { }
}
