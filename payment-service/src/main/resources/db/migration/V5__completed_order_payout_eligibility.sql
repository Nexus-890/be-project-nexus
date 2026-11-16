ALTER TABLE payment_instructions ADD COLUMN seller_id uuid;

-- Eligibility is a durable handoff to #261, never a completed bank transfer.
-- A completion arriving before funding stays here until its prerequisites exist.
CREATE TABLE payment_order_completions (
    order_id uuid PRIMARY KEY,
    order_group_id uuid NOT NULL,
    buyer_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    order_version bigint NOT NULL CHECK (order_version >= 0),
    completed_at timestamptz NOT NULL,
    order_number varchar(100) NOT NULL,
    source_event_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    CHECK (buyer_id <> seller_id)
);

CREATE TABLE payout_eligibility (
    order_id uuid PRIMARY KEY REFERENCES payment_instructions(order_id),
    seller_id uuid NOT NULL,
    buyer_id uuid NOT NULL,
    order_group_id uuid NOT NULL,
    funded_transaction_id uuid NOT NULL REFERENCES payment_transactions(id),
    amount numeric(19,2) NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL,
    completed_at timestamptz NOT NULL,
    source_event_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_payout_eligibility_seller ON payout_eligibility(seller_id, created_at DESC);
