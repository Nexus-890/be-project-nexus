-- rating_db owns these tables. User, commerce and auction IDs are logical references only.
CREATE TYPE trust_level AS ENUM ('NEW', 'BASIC', 'TRUSTED', 'TOP');

CREATE TABLE user_reputations (
    id                     uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id                uuid        NOT NULL UNIQUE,
    score                  smallint    NOT NULL DEFAULT 50 CHECK (score BETWEEN 0 AND 100),
    trust_level            trust_level NOT NULL DEFAULT 'NEW'::trust_level,
    total_transaction      integer     NOT NULL DEFAULT 0 CHECK (total_transaction >= 0),
    successful_transaction integer     NOT NULL DEFAULT 0 CHECK (successful_transaction >= 0),
    failed_transaction     integer     NOT NULL DEFAULT 0 CHECK (failed_transaction >= 0),
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    version                bigint      NOT NULL DEFAULT 0,
    created_by             uuid        NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    updated_by             uuid        NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'
);

CREATE TABLE ratings (
    id           uuid          PRIMARY KEY DEFAULT uuidv7(),
    from_user_id uuid          NOT NULL,
    to_user_id   uuid          NOT NULL,
    order_id     uuid,
    auction_id   uuid,
    score        smallint      NOT NULL CHECK (score BETWEEN 1 AND 5),
    comment      varchar(1000),
    created_at   timestamptz   NOT NULL DEFAULT now(),
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   uuid          NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    updated_by   uuid          NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    CHECK (from_user_id <> to_user_id)
);
CREATE INDEX        idx_ratings_to_user    ON ratings (to_user_id);
CREATE UNIQUE INDEX uq_ratings_from_order ON ratings (from_user_id, order_id) WHERE order_id IS NOT NULL;

CREATE TABLE rating_eligibility (
    order_id        uuid        PRIMARY KEY,
    buyer_id        uuid        NOT NULL,
    seller_id       uuid        NOT NULL,
    completed_at    timestamptz NOT NULL,
    auction_id      uuid,
    source_event_id uuid        NOT NULL,
    source_version  bigint      CHECK (source_version IS NULL OR source_version >= 0),
    created_by      uuid        NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    updated_by      uuid        NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    CHECK (buyer_id <> seller_id)
);

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
