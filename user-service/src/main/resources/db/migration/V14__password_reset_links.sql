CREATE TABLE password_reset_links (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash varchar(64) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    CONSTRAINT ck_password_reset_links_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_password_reset_links_user ON password_reset_links (user_id, created_at DESC);
