CREATE TABLE categories (
    id            uuid         PRIMARY KEY DEFAULT uuidv7(),
    parent_id     uuid         REFERENCES categories (id),
    name          varchar(150) NOT NULL,
    slug          varchar(180) NOT NULL UNIQUE,
    description   text,
    level         smallint     NOT NULL DEFAULT 0,
    path          varchar(500),
    product_count integer      NOT NULL DEFAULT 0 CHECK (product_count >= 0),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    deleted_at    timestamptz
);
CREATE INDEX idx_categories_parent ON categories (parent_id);
CREATE INDEX idx_categories_path   ON categories (path);

CREATE TABLE products (
    id             uuid         PRIMARY KEY DEFAULT uuidv7(),
    category_id    uuid         NOT NULL REFERENCES categories (id),
    seller_id      uuid         NOT NULL,
    name           varchar(255) NOT NULL,
    slug           varchar(300) NOT NULL UNIQUE,
    description    text,
    status         varchar(20)  NOT NULL DEFAULT 'DRAFT'
                                CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE')),
    auction_enable boolean      NOT NULL DEFAULT false,
    has_order      boolean      NOT NULL DEFAULT false,
    in_auction     boolean      NOT NULL DEFAULT false,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    deleted_at     timestamptz
);
CREATE INDEX idx_products_category ON products (category_id, status);
CREATE INDEX idx_products_seller   ON products (seller_id, status);
CREATE INDEX idx_products_status   ON products (status, deleted_at);

CREATE TABLE skus (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    product_id uuid        NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    sku_code   varchar(60) NOT NULL UNIQUE,
    color      varchar(50),
    size       varchar(50),
    price      bigint      NOT NULL CHECK (price >= 0),
    currency   char(3)     NOT NULL DEFAULT 'VND',
    status     varchar(20) NOT NULL DEFAULT 'ACTIVE'
                           CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_skus_product ON skus (product_id);

CREATE TABLE product_images (
    id         uuid         PRIMARY KEY DEFAULT uuidv7(),
    product_id uuid         NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    image_url  varchar(500) NOT NULL,
    sort_order smallint     NOT NULL DEFAULT 0,
    created_at timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_product_images_product ON product_images (product_id, sort_order);

CREATE TABLE product_stats (
    product_id     uuid        PRIMARY KEY REFERENCES products (id) ON DELETE CASCADE,
    order_count    integer     NOT NULL DEFAULT 0 CHECK (order_count >= 0),
    view_count     integer     NOT NULL DEFAULT 0 CHECK (view_count >= 0),
    auction_end_at timestamptz,
    updated_at     timestamptz NOT NULL DEFAULT now()
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
