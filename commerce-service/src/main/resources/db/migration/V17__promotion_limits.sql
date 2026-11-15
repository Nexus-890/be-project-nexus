ALTER TABLE promo_mega_sale_events
    ADD COLUMN registration_open_requested boolean NOT NULL DEFAULT false;

ALTER TABLE promo_vouchers
    ADD CONSTRAINT promo_vouchers_source_check CHECK (source_type IN ('PLATFORM', 'SHOP', 'EVENT')),
    ADD CONSTRAINT promo_vouchers_discount_check CHECK (discount_type IN ('PERCENT', 'FIXED')),
    ADD CONSTRAINT promo_vouchers_funding_check CHECK (funding_type IN ('PLATFORM', 'SELLER', 'CO_FUNDED')),
    ADD CONSTRAINT promo_vouchers_scope_check CHECK (product_scope IN ('ALL', 'SELECTED')),
    ADD COLUMN claim_count bigint NOT NULL DEFAULT 0 CHECK (claim_count >= 0),
    ADD COLUMN committed_uses bigint NOT NULL DEFAULT 0 CHECK (committed_uses >= 0),
    ADD COLUMN committed_discount numeric(19,2) NOT NULL DEFAULT 0 CHECK (committed_discount >= 0);

-- Keep counters correct if a database already has promotion data.
UPDATE promo_vouchers v SET claim_count = counts.claim_count
FROM (
    SELECT voucher_id, count(*) AS claim_count
    FROM promo_customer_vouchers GROUP BY voucher_id
) counts
WHERE v.id = counts.voucher_id;

UPDATE promo_vouchers v
SET committed_uses = totals.uses, committed_discount = totals.discount
FROM (
    SELECT c.voucher_id, count(*) AS uses, sum(u.discount_amount) AS discount
    FROM promo_voucher_usages u JOIN promo_customer_vouchers c ON c.id = u.customer_voucher_id
    WHERE u.status IN ('RESERVED', 'REDEEMED', 'REVERSED')
    GROUP BY c.voucher_id
) totals
WHERE v.id = totals.voucher_id;

CREATE INDEX idx_promo_customer_vouchers_voucher ON promo_customer_vouchers (voucher_id);
CREATE INDEX idx_promo_voucher_usages_customer ON promo_voucher_usages (customer_voucher_id);
