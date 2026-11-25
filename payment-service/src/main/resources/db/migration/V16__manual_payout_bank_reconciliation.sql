ALTER TYPE payout_history_event ADD VALUE 'BANK_MATCHED';
ALTER TYPE payout_history_event ADD VALUE 'BANK_MISMATCH';

CREATE TABLE manual_payout_bank_transactions (
    provider varchar(20) NOT NULL,
    provider_transaction_id varchar(64) NOT NULL,
    account_number varchar(32),
    amount numeric(19,2),
    content varchar(500),
    bank_reference varchar(120),
    transaction_at timestamptz,
    payout_id uuid REFERENCES payouts(id),
    attempt_id uuid REFERENCES manual_payout_attempts(id),
    outcome varchar(32) NOT NULL CHECK (outcome IN
        ('CONFIRMED','VERIFIED','RECORDED','REVIEW_REQUIRED','UNMATCHED','WRONG_ACCOUNT')),
    detail varchar(200),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, provider_transaction_id),
    CHECK ((attempt_id IS NULL) = (payout_id IS NULL))
);
CREATE INDEX idx_manual_bank_txn_attempt ON manual_payout_bank_transactions(attempt_id, created_at);
CREATE INDEX idx_manual_bank_txn_unmatched ON manual_payout_bank_transactions(created_at DESC)
    WHERE attempt_id IS NULL;
