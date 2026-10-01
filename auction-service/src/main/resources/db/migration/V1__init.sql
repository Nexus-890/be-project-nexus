CREATE TABLE auctions (
    id               uuid         PRIMARY KEY DEFAULT uuidv7(),
    product_id       uuid         NOT NULL,
    sku_id           uuid         NOT NULL,
    seller_id        uuid         NOT NULL,
    title            varchar(255) NOT NULL,
    image_url        varchar(500),
    starting_price   bigint       NOT NULL CHECK (starting_price >= 0),
    bid_increment    bigint       NOT NULL CHECK (bid_increment > 0),
    current_price    bigint       NOT NULL CHECK (current_price >= 0),
    leader_bidder_id uuid,
    bid_count        integer      NOT NULL DEFAULT 0 CHECK (bid_count >= 0),
    currency         char(3)      NOT NULL DEFAULT 'VND',
    start_at         timestamptz  NOT NULL,
    end_at           timestamptz  NOT NULL,
    original_end_at  timestamptz  NOT NULL,
    extension_count  smallint     NOT NULL DEFAULT 0 CHECK (extension_count >= 0),
    visibility       varchar(20)  NOT NULL DEFAULT 'PUBLIC'
                                  CHECK (visibility IN ('PUBLIC', 'RESTRICTED')),
    status           varchar(30)  NOT NULL DEFAULT 'CREATED'
                                  CHECK (status IN ('CREATED', 'SCHEDULED', 'ACTIVE', 'ENDED',
                                                    'WON', 'SETTLED', 'FAILED',
                                                    'PAYMENT_TIMEOUT', 'CANCELLED')),
    winner_id        uuid,
    final_price      bigint       CHECK (final_price IS NULL OR final_price >= 0),
    payment_due_at   timestamptz,
    order_id         uuid,
    cancelled_by     varchar(20),
    cancelled_reason varchar(255),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    deleted_at       timestamptz,
    version          bigint       NOT NULL DEFAULT 0,
    CHECK (end_at > start_at)
);
CREATE INDEX idx_auctions_closing  ON auctions (status, end_at);
CREATE INDEX idx_auctions_upcoming ON auctions (status, start_at);
CREATE INDEX idx_auctions_seller   ON auctions (seller_id, status);
CREATE INDEX idx_auctions_product  ON auctions (product_id);

CREATE TABLE bids (
    id              uuid         PRIMARY KEY DEFAULT uuidv7(),
    auction_id      uuid         NOT NULL REFERENCES auctions (id) ON DELETE CASCADE,
    bidder_id       uuid         NOT NULL,
    amount          bigint       NOT NULL CHECK (amount > 0),
    status          varchar(20)  NOT NULL DEFAULT 'PLACED'
                                 CHECK (status IN ('PLACED', 'OUTBID', 'WON', 'CANCELLED')),
    idempotency_key varchar(100) NOT NULL UNIQUE,
    placed_at       timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_bids_auction_amount ON bids (auction_id, amount DESC);
CREATE INDEX idx_bids_auction_time   ON bids (auction_id, placed_at DESC);
CREATE INDEX idx_bids_bidder         ON bids (bidder_id, placed_at DESC);

CREATE TABLE auction_watchers (
    auction_id uuid        NOT NULL REFERENCES auctions (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (auction_id, user_id)
);
CREATE INDEX idx_watchers_user ON auction_watchers (user_id);

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
