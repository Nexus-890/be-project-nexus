CREATE TYPE seller_bank_verification_status AS ENUM ('UNVERIFIED', 'VERIFIED', 'REJECTED');
CREATE TYPE payout_status AS ENUM ('ON_HOLD', 'PENDING', 'PROCESSING', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'RETURNED');
CREATE TYPE payout_history_event AS ENUM (
    'CREATED', 'HELD', 'RELEASED', 'SENT', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'RECONCILED', 'RETRIED', 'RETURNED'
);

CREATE TABLE seller_bank (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    seller_id uuid NOT NULL,
    bank_code varchar(20) NOT NULL CHECK (bank_code ~ '^[0-9]{6}$'),
    account_number_encrypted text NOT NULL CHECK (length(account_number_encrypted) > 0),
    account_last4 char(4) NOT NULL CHECK (account_last4 ~ '^[0-9]{4}$'),
    account_holder_name varchar(100) NOT NULL CHECK (length(btrim(account_holder_name)) > 0),
    verification_status seller_bank_verification_status NOT NULL DEFAULT 'UNVERIFIED',
    verified_by uuid,
    verified_at timestamptz,
    rejection_reason text,
    is_default boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,
    updated_by uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,
    CONSTRAINT seller_bank_verification_check CHECK (
        (verification_status = 'UNVERIFIED' AND verified_by IS NULL AND verified_at IS NULL AND rejection_reason IS NULL)
        OR (verification_status = 'VERIFIED' AND verified_by IS NOT NULL AND verified_at IS NOT NULL AND rejection_reason IS NULL)
        OR (verification_status = 'REJECTED' AND verified_by IS NOT NULL AND verified_at IS NOT NULL
            AND rejection_reason IS NOT NULL AND length(btrim(rejection_reason)) > 0)
    )
);
CREATE UNIQUE INDEX uq_seller_bank_active_default ON seller_bank(seller_id) WHERE is_default AND active;
CREATE INDEX idx_seller_bank_seller ON seller_bank(seller_id, created_at DESC);
CREATE INDEX idx_seller_bank_verification ON seller_bank(verification_status, created_at DESC) WHERE active;

CREATE TABLE payouts (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    order_id uuid NOT NULL UNIQUE,
    session_id uuid NOT NULL REFERENCES payment_sessions(id),
    instruction_id uuid NOT NULL REFERENCES payment_instructions(id),
    seller_id uuid NOT NULL,
    seller_bank_id uuid REFERENCES seller_bank(id),
    recipient_bank_code varchar(20),
    recipient_account_encrypted text,
    recipient_account_last4 char(4),
    recipient_holder_name varchar(100),
    order_amount numeric(19,2) NOT NULL CHECK (order_amount > 0),
    shipping_fee numeric(19,2) NOT NULL CHECK (shipping_fee >= 0),
    platform_fee_rate numeric(5,4) NOT NULL CHECK (platform_fee_rate BETWEEN 0 AND 1),
    platform_fee numeric(19,2) NOT NULL CHECK (platform_fee >= 0),
    net_amount numeric(19,2) NOT NULL,
    currency char(3) NOT NULL,
    status payout_status NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz,
    attempt_started_at timestamptz,
    provider varchar(40),
    provider_reference varchar(120),
    transaction_id uuid REFERENCES payment_transactions(id),
    return_transaction_id uuid REFERENCES payment_transactions(id),
    source_event_id uuid NOT NULL,
    succeeded_at timestamptz,
    returned_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,
    updated_by uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,
    CONSTRAINT payouts_amount_check CHECK (
        platform_fee = round((order_amount - shipping_fee) * platform_fee_rate, 2)
        AND net_amount = order_amount - shipping_fee - platform_fee AND net_amount > 0
    ),
    CONSTRAINT payouts_snapshot_check CHECK (
        (recipient_bank_code IS NULL AND recipient_account_encrypted IS NULL
            AND recipient_account_last4 IS NULL AND recipient_holder_name IS NULL)
        OR (recipient_bank_code IS NOT NULL AND recipient_account_encrypted IS NOT NULL
            AND recipient_account_last4 IS NOT NULL AND recipient_holder_name IS NOT NULL AND seller_bank_id IS NOT NULL)
    )
);
CREATE UNIQUE INDEX uq_payout_provider_reference ON payouts(provider, provider_reference) WHERE provider_reference IS NOT NULL;
CREATE INDEX idx_payouts_session ON payouts(session_id);
CREATE INDEX idx_payouts_instruction ON payouts(instruction_id);
CREATE INDEX idx_payouts_bank ON payouts(seller_bank_id);
CREATE INDEX idx_payouts_transaction ON payouts(transaction_id);
CREATE INDEX idx_payouts_return_transaction ON payouts(return_transaction_id);
CREATE INDEX idx_payouts_seller ON payouts(seller_id, created_at DESC);
CREATE INDEX idx_payouts_admin ON payouts(status, created_at DESC);
CREATE INDEX idx_payouts_dispatch ON payouts(next_attempt_at, id) WHERE status = 'PENDING';
CREATE INDEX idx_payouts_reconcile ON payouts(attempt_started_at, id) WHERE status IN ('PROCESSING', 'UNKNOWN');

CREATE TABLE payout_history (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    payout_id uuid NOT NULL REFERENCES payouts(id),
    event_seq bigint NOT NULL CHECK (event_seq > 0),
    event payout_history_event NOT NULL,
    from_status payout_status,
    to_status payout_status NOT NULL,
    attempt_no integer NOT NULL CHECK (attempt_no >= 0),
    amount numeric(19,2) NOT NULL CHECK (amount > 0),
    seller_bank_id uuid REFERENCES seller_bank(id),
    recipient_bank_code varchar(20),
    recipient_account_encrypted text,
    recipient_account_last4 char(4),
    recipient_holder_name varchar(100),
    provider varchar(40),
    provider_reference varchar(120),
    provider_idempotency_key varchar(100),
    provider_status varchar(80),
    error_code varchar(100),
    error_message text,
    transaction_id uuid REFERENCES payment_transactions(id),
    request_idempotency_key varchar(100) UNIQUE,
    simulated boolean NOT NULL,
    actor_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(payout_id, event_seq)
);
CREATE INDEX idx_payout_history_bank ON payout_history(seller_bank_id);
CREATE INDEX idx_payout_history_transaction ON payout_history(transaction_id);
CREATE INDEX idx_payout_history_provider_key ON payout_history(provider, provider_idempotency_key);
CREATE INDEX idx_payout_history_provider_reference ON payout_history(provider, provider_reference);

CREATE FUNCTION reject_payout_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'payout_history is append-only' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER payout_history_immutable BEFORE UPDATE OR DELETE ON payout_history
    FOR EACH ROW EXECUTE FUNCTION reject_payout_history_mutation();
CREATE TRIGGER payout_history_no_truncate BEFORE TRUNCATE ON payout_history
    FOR EACH STATEMENT EXECUTE FUNCTION reject_payout_history_mutation();
