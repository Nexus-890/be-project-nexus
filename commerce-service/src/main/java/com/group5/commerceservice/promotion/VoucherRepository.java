package com.group5.commerceservice.promotion;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.group5.commerceservice.promotion.Voucher.*;
import static com.group5.commerceservice.promotion.VoucherPricing.Source;

@Repository
class VoucherRepository {
    private static final String TABLE = " FROM promo_vouchers v ";
    private static final String WRITE_FIELDS = """
            code = :code, name = :name, description = :description, event_id = :event, discount_type = :discountType,
            discount_value = :value, max_discount_amount = :cap, min_order_amount = :minimum, funding_type = :funding,
            seller_funding_percent = :sellerPercent, product_scope = :scope, claim_limit = :claims, usage_limit = :uses,
            discount_budget = :budget, claim_start_at = :claimStart, claim_end_at = :claimEnd, valid_from = :validFrom,
            valid_until = :validUntil, terms_text = :terms
            """;
    private static final String APPROVED = """
            EXISTS (SELECT 1 FROM promo_event_sellers es
                WHERE es.event_id = v.event_id AND es.seller_id = :seller AND es.status = 'APPROVED')
            """;
    private static final String PARTICIPATION = """
            id, voucher_id, event_seller_id, status, accepted_terms_version, accepted_terms_snapshot,
            seller_funding_percent_snapshot, accepted_at, withdrawn_at
            """;
    private final JdbcClient jdbc;

    VoucherRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Voucher> find(UUID id) {
        return jdbc.sql("SELECT v.*" + TABLE + "WHERE v.id = :id")
                .param("id", id).query(Voucher.class).optional();
    }

    Optional<Voucher> lock(UUID id) {
        return jdbc.sql("SELECT v.*" + TABLE + "WHERE v.id = :id FOR UPDATE")
                .param("id", id).query(Voucher.class).optional();
    }

    List<Voucher> forCodes(List<String> codes, boolean lock) {
        return jdbc.sql("SELECT v.*" + TABLE + "WHERE v.code IN (:codes) ORDER BY v.id" + (lock ? " FOR UPDATE" : ""))
                .param("codes", codes).query(Voucher.class).list();
    }

    Set<UUID> products(UUID voucher) {
        return jdbc.sql("SELECT product_id FROM promo_voucher_products WHERE voucher_id = :id")
                .param("id", voucher).query(UUID.class).set();
    }

    void replaceProducts(UUID voucher, Set<UUID> products, UUID actor) {
        jdbc.sql("DELETE FROM promo_voucher_products WHERE voucher_id = :id").param("id", voucher).update();
        for (UUID product : products) {
            jdbc.sql("""
                    INSERT INTO promo_voucher_products (voucher_id, product_id, created_by, updated_by)
                    VALUES (:id, :product, :actor, :actor)
                    """).param("id", voucher).param("product", product).param("actor", actor).update();
        }
    }

    Optional<UUID> create(Write request, UUID owner, UUID actor, BigDecimal fundingPercent) {
        return fields(jdbc.sql("""
                INSERT INTO promo_vouchers
                (code, name, description, source_type, owner_seller_id, event_id, discount_type, discount_value,
                 max_discount_amount, min_order_amount, funding_type, seller_funding_percent, product_scope,
                 claim_limit, usage_limit, discount_budget, claim_start_at, claim_end_at, valid_from, valid_until,
                 terms_text, status, created_by, updated_by)
                VALUES (:code, :name, :description, :source, :owner, :event, :discountType, :value, :cap, :minimum,
                        :funding, :sellerPercent, :scope, :claims, :uses, :budget, :claimStart, :claimEnd, :validFrom,
                        :validUntil, :terms, 'DRAFT', :actor, :actor)
                ON CONFLICT (code) DO NOTHING RETURNING id
                """), request, fundingPercent).param("source", request.sourceType().name())
                .param("owner", owner).param("actor", actor).query(UUID.class).optional();
    }

    void edit(Voucher voucher, Write request, UUID actor, BigDecimal fundingPercent, OffsetDateTime now) {
        fields(jdbc.sql("UPDATE promo_vouchers SET " + WRITE_FIELDS + """
                , terms_version = CASE WHEN terms_text IS DISTINCT FROM :terms
                    OR funding_type IS DISTINCT FROM :funding OR seller_funding_percent <> :sellerPercent
                    THEN terms_version + 1 ELSE terms_version END,
                  updated_at = :now, updated_by = :actor
                WHERE id = :id
                """), request, fundingPercent).param("id", voucher.id()).param("actor", actor)
                .param("now", now).update();
    }

    private static JdbcClient.StatementSpec fields(JdbcClient.StatementSpec stmt, Write request, BigDecimal fundingPercent) {
        return stmt.param("code", request.code().toUpperCase(Locale.ROOT)).param("name", request.name())
                .param("description", request.description()).param("event", request.eventId())
                .param("discountType", request.discountType().name()).param("value", request.discountValue())
                .param("cap", request.maxDiscountAmount())
                .param("minimum", request.minOrderAmount() == null ? BigDecimal.ZERO : request.minOrderAmount())
                .param("funding", request.fundingType().name()).param("sellerPercent", fundingPercent)
                .param("scope", request.productScope().name()).param("claims", request.claimLimit())
                .param("uses", request.usageLimit()).param("budget", request.discountBudget())
                .param("claimStart", request.claimStartAt()).param("claimEnd", request.claimEndAt())
                .param("validFrom", request.validFrom()).param("validUntil", request.validUntil())
                .param("terms", request.termsText());
    }

    void status(UUID voucher, Status status, UUID actor, OffsetDateTime now) {
        jdbc.sql("""
                UPDATE promo_vouchers SET status = CAST(:status AS promo_voucher_status),
                    updated_by = :actor, updated_at = :now WHERE id = :id
                """).param("status", status.name()).param("id", voucher).param("actor", actor).param("now", now).update();
    }

    Page<Voucher> manage(UUID seller, Source source, Status status, UUID event, OffsetDateTime now, PageRequest page) {
        String filter = """
                WHERE (CAST(:seller AS uuid) IS NULL OR (v.source_type = 'SHOP' AND v.owner_seller_id = :seller)
                    OR (v.source_type = 'EVENT' AND v.status = 'ACTIVE' AND v.valid_until > :now AND %s
                        AND EXISTS (SELECT 1 FROM promo_mega_sale_events e
                                    WHERE e.id = v.event_id AND e.status NOT IN ('CANCELED', 'ENDED'))))
                  AND (CAST(:source AS text) IS NULL OR v.source_type = :source)
                  AND (CAST(:status AS text) IS NULL OR v.status = CAST(:status AS promo_voucher_status))
                  AND (CAST(:event AS uuid) IS NULL OR v.event_id = :event)
                """.formatted(APPROVED);
        var rows = manageParams(jdbc.sql("SELECT v.*" + TABLE + filter + " ORDER BY v.created_at DESC, v.id LIMIT :size OFFSET :offset"),
                seller, source, status, event, now).param("size", page.getPageSize()).param("offset", page.getOffset())
                .query(Voucher.class).list();
        long total = manageParams(jdbc.sql("SELECT count(*)" + TABLE + filter), seller, source, status, event, now)
                .query(Long.class).single();
        return new PageImpl<>(rows, page, total);
    }

    private static JdbcClient.StatementSpec manageParams(JdbcClient.StatementSpec stmt, UUID seller, Source source,
                                                          Status status, UUID event, OffsetDateTime now) {
        return stmt.param("seller", seller).param("source", source == null ? null : source.name())
                .param("status", status == null ? null : status.name()).param("event", event).param("now", now);
    }

    Page<Voucher> claimable(UUID product, UUID seller, OffsetDateTime now, PageRequest page) {
        String filter = """
                WHERE v.status = 'ACTIVE' AND v.claim_start_at <= :now AND v.claim_end_at > :now AND v.valid_until > :now
                  AND (v.claim_limit IS NULL OR v.claim_count < v.claim_limit)
                  AND (CAST(:product AS uuid) IS NULL OR v.product_scope = 'ALL' OR EXISTS (
                       SELECT 1 FROM promo_voucher_products p WHERE p.voucher_id = v.id AND p.product_id = :product))
                  AND (CAST(:seller AS uuid) IS NULL OR v.source_type <> 'SHOP' OR v.owner_seller_id = :seller)
                  AND (v.source_type <> 'EVENT' OR (EXISTS (
                       SELECT 1 FROM promo_mega_sale_events e WHERE e.id = v.event_id AND e.status NOT IN ('CANCELED', 'ENDED'))
                       AND (CAST(:seller AS uuid) IS NULL OR EXISTS (
                       SELECT 1 FROM promo_voucher_sellers vs JOIN promo_event_sellers es ON es.id = vs.event_seller_id
                       WHERE vs.voucher_id = v.id AND es.event_id = v.event_id AND es.seller_id = :seller
                         AND vs.status = 'ACTIVE' AND es.status = 'APPROVED'))))
                """;
        var rows = jdbc.sql("SELECT v.*" + TABLE + filter + " ORDER BY v.claim_end_at, v.id LIMIT :size OFFSET :offset")
                .param("product", product).param("seller", seller).param("now", now)
                .param("size", page.getPageSize()).param("offset", page.getOffset()).query(Voucher.class).list();
        long total = jdbc.sql("SELECT count(*)" + TABLE + filter)
                .param("product", product).param("seller", seller).param("now", now).query(Long.class).single();
        return new PageImpl<>(rows, page, total);
    }

    Participation join(Voucher voucher, UUID registration, UUID seller, OffsetDateTime now) {
        return jdbc.sql("""
                INSERT INTO promo_voucher_sellers
                    (voucher_id, event_seller_id, status, accepted_terms_version, accepted_terms_snapshot,
                     seller_funding_percent_snapshot, accepted_by, accepted_at, created_by, updated_by, updated_at)
                VALUES (:voucher, :registration, 'ACTIVE', :version, :terms, :funding, :seller, :now, :seller, :seller, :now)
                ON CONFLICT (voucher_id, event_seller_id) DO UPDATE SET
                    status = 'ACTIVE', accepted_terms_version = EXCLUDED.accepted_terms_version,
                    accepted_terms_snapshot = EXCLUDED.accepted_terms_snapshot,
                    seller_funding_percent_snapshot = EXCLUDED.seller_funding_percent_snapshot,
                    accepted_at = :now, withdrawn_at = NULL, updated_by = :seller, updated_at = :now
                WHERE promo_voucher_sellers.status = 'WITHDRAWN'
                RETURNING
                """ + PARTICIPATION)
                .param("voucher", voucher.id()).param("registration", registration).param("version", voucher.termsVersion())
                .param("terms", voucher.termsText()).param("funding", voucher.sellerFundingPercent())
                .param("seller", seller).param("now", now).query(Participation.class).optional()
                .orElseGet(() -> jdbc.sql("SELECT " + PARTICIPATION + """
                        FROM promo_voucher_sellers WHERE voucher_id = :voucher AND event_seller_id = :registration
                        """).param("voucher", voucher.id()).param("registration", registration)
                        .query(Participation.class).single());
    }

    boolean withdraw(UUID voucher, UUID seller, OffsetDateTime now) {
        return jdbc.sql("""
                UPDATE promo_voucher_sellers v SET status = 'WITHDRAWN', withdrawn_at = :now,
                    updated_at = :now, updated_by = :seller
                FROM promo_event_sellers s WHERE s.id = v.event_seller_id AND s.seller_id = :seller
                    AND v.voucher_id = :voucher AND v.status = 'ACTIVE'
                """).param("voucher", voucher).param("seller", seller).param("now", now).update() == 1;
    }

    List<ParticipantFunding> participants(UUID voucher) {
        return jdbc.sql("""
                SELECT vs.id AS voucher_seller_id, es.seller_id, vs.seller_funding_percent_snapshot AS seller_percent
                FROM promo_voucher_sellers vs JOIN promo_event_sellers es ON es.id = vs.event_seller_id
                JOIN promo_vouchers v ON v.id = vs.voucher_id AND v.event_id = es.event_id
                WHERE vs.voucher_id = :voucher AND vs.status = 'ACTIVE' AND es.status = 'APPROVED'
                """).param("voucher", voucher).query(ParticipantFunding.class).list();
    }

    Optional<Claim> claim(UUID buyer, UUID voucher) {
        return jdbc.sql("""
                SELECT id, customer_id, voucher_id, status, claimed_at, expires_at
                FROM promo_customer_vouchers WHERE customer_id = :buyer AND voucher_id = :voucher
                """).param("buyer", buyer).param("voucher", voucher).query(Claim.class).optional();
    }

    Claim insertClaim(UUID buyer, Voucher voucher, OffsetDateTime now) {
        return jdbc.sql("""
                INSERT INTO promo_customer_vouchers
                    (customer_id, voucher_id, status, claimed_at, expires_at, updated_at, created_by, updated_by)
                VALUES (:buyer, :voucher, 'CLAIMED', :now, :expires, :now, :buyer, :buyer)
                RETURNING id, customer_id, voucher_id, status, claimed_at, expires_at
                """).param("buyer", buyer).param("voucher", voucher.id()).param("now", now)
                .param("expires", voucher.validUntil()).query(Claim.class).single();
    }

    Page<Claim> wallet(UUID buyer, WalletStatus status, OffsetDateTime now, PageRequest page) {
        String filter = """
                FROM promo_customer_vouchers c
                WHERE customer_id = :buyer AND (CAST(:status AS text) IS NULL OR
                    CASE WHEN c.status = 'CLAIMED' AND expires_at <= :now THEN 'EXPIRED' ELSE c.status::text END = :status)
                """;
        String state = status == null ? null : status.name();
        var rows = jdbc.sql("""
                SELECT id, customer_id, voucher_id,
                       CASE WHEN c.status = 'CLAIMED' AND expires_at <= :now THEN 'EXPIRED' ELSE c.status::text END AS status,
                       claimed_at, expires_at
                """ + filter + " ORDER BY claimed_at DESC, id LIMIT :size OFFSET :offset")
                .param("buyer", buyer).param("status", state).param("now", now)
                .param("size", page.getPageSize()).param("offset", page.getOffset()).query(Claim.class).list();
        long total = jdbc.sql("SELECT count(*) " + filter).param("buyer", buyer).param("status", state)
                .param("now", now).query(Long.class).single();
        return new PageImpl<>(rows, page, total);
    }

    Page<Usage> usages(UUID id, PageRequest page) {
        String from = """
                FROM promo_voucher_usages u JOIN promo_customer_vouchers c ON c.id = u.customer_voucher_id
                WHERE c.voucher_id = :id
                """;
        var rows = jdbc.sql("""
                SELECT u.id, order_id, u.status, eligible_amount, discount_amount, seller_funded_amount AS seller_funded,
                       platform_funded_amount AS platform_funded, reserved_at, reserved_until, redeemed_at, released_at
                """ + from + " ORDER BY reserved_at DESC, u.id LIMIT :size OFFSET :offset")
                .param("id", id).param("size", page.getPageSize()).param("offset", page.getOffset()).query(Usage.class).list();
        long total = jdbc.sql("SELECT count(*) " + from).param("id", id).query(Long.class).single();
        return new PageImpl<>(rows, page, total);
    }

    boolean hasReservation(UUID claimId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM promo_voucher_usages WHERE customer_voucher_id = :claim AND status = 'RESERVED')
                """).param("claim", claimId).query(Boolean.class).single();
    }

    int expireVouchers(OffsetDateTime now) {
        return jdbc.sql("""
                WITH expired AS (
                    SELECT id FROM promo_vouchers WHERE status IN ('ACTIVE', 'PAUSED') AND valid_until <= :now
                    ORDER BY id LIMIT 200 FOR UPDATE SKIP LOCKED
                )
                UPDATE promo_vouchers SET status = 'EXPIRED', updated_at = :now
                WHERE id IN (SELECT id FROM expired)
                """).param("now", now).update();
    }

    int expireClaims(OffsetDateTime now) {
        return jdbc.sql("""
                WITH expired AS (
                    SELECT id FROM promo_customer_vouchers WHERE status = 'CLAIMED' AND expires_at <= :now
                    ORDER BY id LIMIT 200 FOR UPDATE SKIP LOCKED
                )
                UPDATE promo_customer_vouchers SET status = 'EXPIRED', updated_at = :now
                WHERE id IN (SELECT id FROM expired)
                """).param("now", now).update();
    }

    record ParticipantFunding(UUID voucherSellerId, UUID sellerId, BigDecimal sellerPercent) { }
}
