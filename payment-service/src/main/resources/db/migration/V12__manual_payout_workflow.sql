ALTER TABLE payouts ADD COLUMN execution_mode varchar(6) NOT NULL DEFAULT 'AUTO'
    CHECK (execution_mode IN ('AUTO','MANUAL'));
ALTER TABLE payouts ADD COLUMN confirmation_source varchar(40);
ALTER TABLE payouts ADD COLUMN blocked_reason varchar(80);
ALTER TABLE payouts ADD COLUMN current_attempt_id uuid;
ALTER TABLE payment_transactions ADD COLUMN linked_transaction_id uuid REFERENCES payment_transactions(id);
CREATE INDEX idx_payment_transaction_link ON payment_transactions(linked_transaction_id);

CREATE TABLE manual_payout_attempts (
    id uuid PRIMARY KEY, payout_id uuid NOT NULL REFERENCES payouts(id),
    attempt_no integer NOT NULL CHECK (attempt_no>0),
    state varchar(32) NOT NULL CHECK (state IN ('CLAIMED','QR_ISSUED','SUCCEEDED','NOT_SENT','UNKNOWN','RETURNED','CONFIRMATION_CORRECTED')),
    version bigint NOT NULL DEFAULT 0 CHECK(version>=0), claimed_by uuid NOT NULL,
    seller_bank_id uuid NOT NULL REFERENCES seller_bank(id), bank_version bigint NOT NULL,
    bank_code varchar(20) NOT NULL, account_encrypted text NOT NULL, account_last4 char(4) NOT NULL,
    holder_name varchar(100) NOT NULL, net_amount numeric(19,2) NOT NULL CHECK(net_amount>0), currency char(3) NOT NULL,
    claim_expires_at timestamptz NOT NULL, qr_issued_at timestamptz, qr_expires_at timestamptz,
    reference varchar(25) NOT NULL UNIQUE,
    source_account_id varchar(80), bank_transfer_date date, bank_transfer_reference varchar(120), transferred_at timestamptz,
    confirmed_by uuid, confirmed_at timestamptz, evidence_ids uuid[] NOT NULL DEFAULT '{}',
    transaction_id uuid REFERENCES payment_transactions(id), correction_transaction_id uuid REFERENCES payment_transactions(id),
    return_transaction_id uuid REFERENCES payment_transactions(id),
    receipt_status varchar(24) NOT NULL DEFAULT 'NOT_REQUESTED'
        CHECK(receipt_status IN ('NOT_REQUESTED','AWAITING_CONFIRMATION','CONFIRMED','DISPUTED')),
    receipt_version bigint NOT NULL DEFAULT 0 CHECK(receipt_version>=0), seller_confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(payout_id,attempt_no), UNIQUE(id,payout_id),
    CHECK ((bank_transfer_reference IS NULL AND source_account_id IS NULL AND bank_transfer_date IS NULL)
        OR (bank_transfer_reference IS NOT NULL AND source_account_id IS NOT NULL AND bank_transfer_date IS NOT NULL)),
    CHECK(qr_issued_at IS NULL OR qr_expires_at IS NOT NULL)
);
CREATE UNIQUE INDEX uq_manual_live_attempt ON manual_payout_attempts(payout_id)
    WHERE state IN ('CLAIMED','QR_ISSUED','UNKNOWN');
CREATE UNIQUE INDEX uq_manual_bank_reference ON manual_payout_attempts(source_account_id,bank_transfer_date,bank_transfer_reference)
    WHERE bank_transfer_reference IS NOT NULL;
CREATE INDEX idx_manual_attempt_bank ON manual_payout_attempts(seller_bank_id);
CREATE INDEX idx_manual_attempt_transaction ON manual_payout_attempts(transaction_id);
CREATE INDEX idx_manual_attempt_correction ON manual_payout_attempts(correction_transaction_id);
CREATE INDEX idx_manual_attempt_return ON manual_payout_attempts(return_transaction_id);
CREATE INDEX idx_manual_attempt_owner ON manual_payout_attempts(claimed_by,created_at DESC);
CREATE INDEX idx_manual_attempt_expiry ON manual_payout_attempts(state,claim_expires_at,qr_expires_at);
ALTER TABLE payouts ADD CONSTRAINT fk_payout_current_attempt FOREIGN KEY(current_attempt_id,id)
    REFERENCES manual_payout_attempts(id,payout_id);
CREATE INDEX idx_payout_current_attempt ON payouts(current_attempt_id);
CREATE INDEX idx_manual_queue ON payouts(created_at DESC,id DESC) WHERE execution_mode='MANUAL';

CREATE TABLE manual_payout_evidence (
    id uuid PRIMARY KEY, payout_id uuid NOT NULL REFERENCES payouts(id), attempt_id uuid NOT NULL,
    uploader_id uuid NOT NULL, bucket varchar(100) NOT NULL, object_key varchar(250) NOT NULL UNIQUE,
    content_type varchar(40) NOT NULL CHECK(content_type IN ('image/jpeg','image/png','application/pdf')),
    size_bytes bigint NOT NULL CHECK(size_bytes>0), digest char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(attempt_id,payout_id) REFERENCES manual_payout_attempts(id,payout_id)
);
CREATE INDEX idx_manual_evidence_payout ON manual_payout_evidence(payout_id);
CREATE INDEX idx_manual_evidence_attempt ON manual_payout_evidence(attempt_id);

CREATE TABLE payout_review_cases (
    id uuid PRIMARY KEY, payout_id uuid NOT NULL REFERENCES payouts(id), attempt_id uuid NOT NULL,
    kind varchar(20) NOT NULL CHECK(kind IN ('SELLER_DISPUTE','STAFF_EXCEPTION')),
    status varchar(20) NOT NULL CHECK(status IN ('OPEN','UNDER_REVIEW','WAITING_SELLER','RESOLVED')),
    reason varchar(80) NOT NULL, explanation varchar(1000) NOT NULL, reported_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK(version>=0), evidence_ids uuid[] NOT NULL DEFAULT '{}',
    resolution varchar(40), reviewed_by uuid, resolved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(attempt_id,payout_id) REFERENCES manual_payout_attempts(id,payout_id)
);
CREATE UNIQUE INDEX uq_manual_active_seller_dispute ON payout_review_cases(attempt_id)
    WHERE kind='SELLER_DISPUTE' AND status IN ('OPEN','UNDER_REVIEW','WAITING_SELLER');
CREATE INDEX idx_manual_case_payout ON payout_review_cases(payout_id,status);
CREATE INDEX idx_manual_case_attempt ON payout_review_cases(attempt_id);

CREATE TABLE manual_payout_commands (
    idempotency_key uuid PRIMARY KEY, actor_id uuid NOT NULL, operation varchar(40) NOT NULL,
    payout_id uuid NOT NULL REFERENCES payouts(id), attempt_id uuid, case_id uuid,
    payload_hash char(64) NOT NULL, result jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_manual_command_payout ON manual_payout_commands(payout_id);

ALTER TABLE payout_history ADD COLUMN manual_attempt_id uuid REFERENCES manual_payout_attempts(id);
ALTER TABLE payout_history ADD COLUMN case_id uuid REFERENCES payout_review_cases(id);
ALTER TABLE payout_history ADD COLUMN actor_role varchar(20);
ALTER TABLE payout_history ADD COLUMN reason varchar(1000);
CREATE INDEX idx_history_manual_attempt ON payout_history(manual_attempt_id);
CREATE INDEX idx_history_case ON payout_history(case_id);

CREATE FUNCTION preserve_payout_execution_mode() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.execution_mode IS DISTINCT FROM OLD.execution_mode THEN
        RAISE EXCEPTION 'Payout execution mode is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER payout_mode_immutable BEFORE UPDATE ON payouts FOR EACH ROW EXECUTE FUNCTION preserve_payout_execution_mode();

CREATE FUNCTION preserve_issued_manual_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.reference IS DISTINCT FROM OLD.reference OR NEW.payout_id IS DISTINCT FROM OLD.payout_id
        OR NEW.attempt_no IS DISTINCT FROM OLD.attempt_no OR NEW.claimed_by IS DISTINCT FROM OLD.claimed_by
        OR ROW(NEW.seller_bank_id,NEW.bank_version,NEW.bank_code,NEW.account_encrypted,NEW.account_last4,NEW.holder_name,NEW.net_amount,NEW.currency)
           IS DISTINCT FROM ROW(OLD.seller_bank_id,OLD.bank_version,OLD.bank_code,OLD.account_encrypted,OLD.account_last4,OLD.holder_name,OLD.net_amount,OLD.currency)
        OR (OLD.qr_issued_at IS NOT NULL AND ROW(NEW.qr_issued_at,NEW.qr_expires_at)
           IS DISTINCT FROM ROW(OLD.qr_issued_at,OLD.qr_expires_at))
        OR (OLD.bank_transfer_reference IS NOT NULL AND ROW(NEW.source_account_id,NEW.bank_transfer_date,NEW.bank_transfer_reference,NEW.transferred_at)
           IS DISTINCT FROM ROW(OLD.source_account_id,OLD.bank_transfer_date,OLD.bank_transfer_reference,OLD.transferred_at)) THEN
        RAISE EXCEPTION 'Manual transfer instruction is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER manual_snapshot_immutable BEFORE UPDATE ON manual_payout_attempts FOR EACH ROW EXECUTE FUNCTION preserve_issued_manual_snapshot();
