CREATE INDEX idx_payouts_admin_created ON payouts(created_at DESC, id DESC);

CREATE INDEX idx_seller_bank_admin_review ON seller_bank
    ((CASE WHEN verification_status = 'UNVERIFIED' AND active THEN 0 ELSE 1 END), created_at ASC, id ASC);
