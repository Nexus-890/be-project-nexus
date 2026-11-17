ALTER TABLE user_reputations
    ADD COLUMN rating_count bigint NOT NULL DEFAULT 0 CHECK (rating_count >= 0),
    ADD COLUMN rating_score_sum bigint NOT NULL DEFAULT 0 CHECK (rating_score_sum >= 0),
    ADD COLUMN penalty_points bigint NOT NULL DEFAULT 0 CHECK (penalty_points >= 0),
    ADD COLUMN successful_auction_count integer NOT NULL DEFAULT 0 CHECK (successful_auction_count >= 0),
    ADD COLUMN failed_auction_count integer NOT NULL DEFAULT 0 CHECK (failed_auction_count >= 0),
    ADD COLUMN adjustment_offset numeric(38,12) NOT NULL DEFAULT 0,
    ADD COLUMN policy_version varchar(60);

CREATE TABLE reputation_outcomes (
    order_id uuid PRIMARY KEY,
    auction_id uuid UNIQUE,
    outcome varchar(30) NOT NULL CHECK (outcome IN ('COMPLETED', 'PAYMENT_TIMEOUT')),
    fingerprint jsonb NOT NULL
);

CREATE TABLE reputation_effects (
    change_type varchar(40) NOT NULL,
    source_id uuid NOT NULL,
    user_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    fingerprint jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (change_type, source_id, user_id)
);

CREATE TABLE reputation_sources (
    change_type varchar(40) NOT NULL,
    source_id uuid NOT NULL,
    fingerprint jsonb NOT NULL,
    PRIMARY KEY (change_type, source_id)
);

CREATE TABLE reputation_history (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    operation_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES user_reputations(user_id),
    source_id uuid,
    source_version bigint CHECK (source_version IS NULL OR source_version >= 0),
    event_id uuid,
    change_type varchar(40) NOT NULL CHECK (change_type IN ('USER_RATED','ORDER_COMPLETED','AUCTION_PAYMENT_TIMEOUT','ADMIN_ADJUSTMENT')),
    previous_score smallint NOT NULL CHECK (previous_score BETWEEN 0 AND 100),
    new_score smallint NOT NULL CHECK (new_score BETWEEN 0 AND 100),
    delta smallint NOT NULL CHECK (delta BETWEEN -100 AND 100),
    previous_trust_level trust_level NOT NULL,
    new_trust_level trust_level NOT NULL,
    actor_id uuid NOT NULL,
    reason text NOT NULL CHECK (btrim(reason) <> ''),
    policy_version varchar(60) NOT NULL CHECK (btrim(policy_version) <> ''),
    change_details jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    updated_by uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    CHECK (new_score = previous_score + delta),
    CHECK (change_type = 'ADMIN_ADJUSTMENT' OR source_id IS NOT NULL),
    CHECK (change_type <> 'USER_RATED' OR source_version IS NOT NULL),
    UNIQUE (operation_id, user_id, change_type)
);
CREATE INDEX idx_reputation_history_user_time ON reputation_history (user_id, created_at DESC, id DESC);
CREATE UNIQUE INDEX uq_reputation_history_source ON reputation_history (change_type, source_id, user_id) WHERE source_id IS NOT NULL;

CREATE TABLE reputation_adjustment_requests (
    user_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    operation_id uuid NOT NULL,
    fingerprint jsonb NOT NULL,
    result jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idempotency_key)
);
