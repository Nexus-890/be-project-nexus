ALTER TABLE users ADD COLUMN phone_verified_at timestamptz;

CREATE TABLE phone_verifications (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id),
    phone       varchar(20) NOT NULL,
    code_hash   varchar(64) NOT NULL,
    expires_at  timestamptz NOT NULL,
    attempts    integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    verified_at timestamptz,
    created_at  timestamptz NOT NULL,
    created_by  uuid        NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    updated_by  uuid        NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'
);

CREATE INDEX ix_phone_verifications_user_created ON phone_verifications (user_id, created_at DESC);
