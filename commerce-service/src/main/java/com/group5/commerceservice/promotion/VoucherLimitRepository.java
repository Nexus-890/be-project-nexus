package com.group5.commerceservice.promotion;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Updates the voucher row before inserting claims/usages in the same transaction. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class VoucherLimitRepository {
    private final JdbcClient jdbc;

    public VoucherLimitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean takeClaim(UUID voucherId, OffsetDateTime now) {
        return jdbc.sql("""
                UPDATE promo_vouchers SET claim_count = claim_count + 1, updated_at = :now
                WHERE id = :id AND status = 'ACTIVE'
                  AND :now >= claim_start_at AND :now < claim_end_at AND :now < valid_until
                  AND (claim_limit IS NULL OR claim_count < claim_limit)
                """)
                .param("id", voucherId).param("now", now)
                .update() == 1;
    }

    public boolean reserve(UUID voucherId, long uses, BigDecimal discount, OffsetDateTime now) {
        return jdbc.sql("""
                UPDATE promo_vouchers
                SET committed_uses = committed_uses + :uses,
                    committed_discount = committed_discount + :discount, updated_at = :now
                WHERE id = :id AND status = 'ACTIVE'
                  AND :now >= valid_from AND :now < valid_until
                  AND (usage_limit IS NULL OR committed_uses + :uses <= usage_limit)
                  AND (discount_budget IS NULL OR committed_discount + :discount <= discount_budget)
                """)
                .param("id", voucherId).param("uses", uses).param("discount", discount).param("now", now)
                .update() == 1;
    }

    /** Released reservations free capacity. Paid or refunded usages stay counted. */
    public void release(UUID voucherId, long uses, BigDecimal discount, OffsetDateTime now) {
        int updated = jdbc.sql("""
                UPDATE promo_vouchers
                SET committed_uses = committed_uses - :uses,
                    committed_discount = committed_discount - :discount, updated_at = :now
                WHERE id = :id
                """)
                .param("id", voucherId).param("uses", uses).param("discount", discount).param("now", now)
                .update();
        if (updated != 1) throw new IllegalStateException("Voucher for a released usage is missing: " + voucherId);
    }
}
