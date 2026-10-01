CREATE TABLE users (
    id                   uuid         PRIMARY KEY DEFAULT uuidv7(),
    email                varchar(255) NOT NULL UNIQUE,
    phone                varchar(20)  NOT NULL UNIQUE,
    password_hash        varchar(100) NOT NULL,
    full_name            varchar(150) NOT NULL,
    identity_number      varchar(255),
    gender               varchar(10)  CHECK (gender IN ('MALE', 'FEMALE', 'OTHER')),
    address              text,
    date_of_birth        date,
    status               varchar(20)  NOT NULL DEFAULT 'ACTIVE'
                                      CHECK (status IN ('ACTIVE', 'LOCKED', 'DELETED')),
    failed_login_count   smallint     NOT NULL DEFAULT 0,
    locked_until         timestamptz,
    last_login_at        timestamptz,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    deleted_at           timestamptz,
    version              bigint       NOT NULL DEFAULT 0
);
CREATE INDEX idx_users_status ON users (status, deleted_at);

CREATE TABLE roles (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    code        varchar(20) NOT NULL UNIQUE,
    name        varchar(100) NOT NULL,
    description varchar(255),
    is_system   boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz
);

CREATE TABLE permissions (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    code        varchar(80) NOT NULL UNIQUE,
    name        varchar(120) NOT NULL,
    description varchar(255)
);

CREATE TABLE user_roles (
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id     uuid        NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    assigned_at timestamptz NOT NULL DEFAULT now(),
    assigned_by uuid,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_user_roles_role ON user_roles (role_id);

CREATE TABLE role_permissions (
    role_id       uuid NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_id uuid NOT NULL REFERENCES permissions (id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);
CREATE INDEX idx_role_permissions_permission ON role_permissions (permission_id);

CREATE TABLE refresh_tokens (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  varchar(64) NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    replaced_by uuid        REFERENCES refresh_tokens (id),
    user_agent  varchar(255),
    ip_address  varchar(45),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user_active ON refresh_tokens (user_id, revoked_at);
CREATE INDEX idx_refresh_tokens_expires     ON refresh_tokens (expires_at);

CREATE TABLE password_reset_tokens (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_password_reset_user ON password_reset_tokens (user_id);

CREATE TABLE user_reputations (
    id                     uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id                uuid        NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    score                  smallint    NOT NULL DEFAULT 50 CHECK (score BETWEEN 0 AND 100),
    trust_level            varchar(20) NOT NULL DEFAULT 'NEW'
                                       CHECK (trust_level IN ('NEW', 'BASIC', 'TRUSTED', 'TOP')),
    total_transaction      integer     NOT NULL DEFAULT 0 CHECK (total_transaction >= 0),
    successful_transaction integer     NOT NULL DEFAULT 0 CHECK (successful_transaction >= 0),
    failed_transaction     integer     NOT NULL DEFAULT 0 CHECK (failed_transaction >= 0),
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    version                bigint      NOT NULL DEFAULT 0
);

CREATE TABLE ratings (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    from_user_id uuid        NOT NULL REFERENCES users (id),
    to_user_id   uuid        NOT NULL REFERENCES users (id),
    order_id     uuid,
    auction_id   uuid,
    score        smallint    NOT NULL CHECK (score BETWEEN 1 AND 5),
    comment      varchar(1000),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CHECK (from_user_id <> to_user_id)
);
CREATE INDEX        idx_ratings_to_user   ON ratings (to_user_id);
CREATE UNIQUE INDEX uq_ratings_from_order ON ratings (from_user_id, order_id) WHERE order_id IS NOT NULL;

CREATE TABLE outbox (
    id             uuid         PRIMARY KEY DEFAULT uuidv7(),
    aggregate_type varchar(50)  NOT NULL,
    aggregate_id   varchar(100) NOT NULL,
    event_type     varchar(80)  NOT NULL,
    event_version  integer      NOT NULL DEFAULT 1,
    topic          varchar(80)  NOT NULL,
    payload        jsonb        NOT NULL,
    trace_id       varchar(64),
    occurred_at    timestamptz  NOT NULL DEFAULT now(),
    published_at   timestamptz
);
CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate   ON outbox (aggregate_type, aggregate_id);

CREATE TABLE processed_events (
    event_id   uuid        NOT NULL,
    consumer   varchar(80) NOT NULL,
    handled_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);
CREATE INDEX idx_processed_events_cleanup ON processed_events (handled_at);

CREATE TABLE shedlock (
    name       varchar(64)  PRIMARY KEY,
    lock_until timestamptz  NOT NULL,
    locked_at  timestamptz  NOT NULL,
    locked_by  varchar(255) NOT NULL
);
