ALTER TABLE ratings ADD COLUMN hidden boolean NOT NULL DEFAULT false;

CREATE TABLE reputation_restrictions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    user_id uuid NOT NULL,
    action varchar(4) NOT NULL CHECK (action IN ('BID','SELL')),
    reason varchar(1000) NOT NULL CHECK (btrim(reason) <> ''),
    restricted_until timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    revoked_at timestamptz,
    revoked_by uuid,
    revocation_reason varchar(1000),
    CHECK (restricted_until > created_at),
    CHECK ((revoked_at IS NULL AND revoked_by IS NULL AND revocation_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL AND revocation_reason IS NOT NULL AND btrim(revocation_reason) <> ''))
);
CREATE INDEX idx_restriction_user_action ON reputation_restrictions(user_id,action,restricted_until)
    WHERE revoked_at IS NULL;

CREATE TABLE rating_disputes (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    rating_id uuid NOT NULL UNIQUE REFERENCES ratings(id),
    user_id uuid NOT NULL,
    reason varchar(1000) NOT NULL CHECK (btrim(reason) <> ''),
    status varchar(8) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    resolved_at timestamptz,
    resolved_by uuid,
    resolution_reason varchar(1000),
    CHECK ((status='PENDING' AND resolved_at IS NULL AND resolved_by IS NULL AND resolution_reason IS NULL)
        OR (status<>'PENDING' AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL AND resolution_reason IS NOT NULL AND btrim(resolution_reason) <> ''))
);
CREATE INDEX idx_rating_disputes_status_time ON rating_disputes(status,created_at DESC,id DESC);

CREATE TABLE reputation_moderation_audit (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    action varchar(40) NOT NULL,
    actor_id uuid NOT NULL,
    user_id uuid NOT NULL,
    resource_id uuid NOT NULL,
    reason varchar(1000) NOT NULL CHECK (btrim(reason) <> ''),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_reputation_moderation_user ON reputation_moderation_audit(user_id,created_at DESC);

ALTER TABLE reputation_history DROP CONSTRAINT reputation_history_change_type_check;
ALTER TABLE reputation_history ADD CONSTRAINT reputation_history_change_type_check
    CHECK (change_type IN ('USER_RATED','ORDER_COMPLETED','AUCTION_PAYMENT_TIMEOUT','ADMIN_ADJUSTMENT','RATING_DISPUTE_APPROVED'));
