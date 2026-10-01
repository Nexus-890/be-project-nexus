CREATE TABLE carts (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id     uuid,
    guest_token varchar(255),
    status      varchar(20) NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE', 'CHECKED_OUT', 'EXPIRED', 'MERGED')),
    promo_code  varchar(50),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (user_id IS NOT NULL OR guest_token IS NOT NULL)
);
CREATE INDEX idx_carts_user   ON carts (user_id);
CREATE INDEX idx_carts_guest  ON carts (guest_token);
CREATE INDEX idx_carts_expiry ON carts (status, updated_at);

CREATE TABLE cart_items (
    id         uuid          PRIMARY KEY DEFAULT uuidv7(),
    cart_id    uuid          NOT NULL REFERENCES carts (id) ON DELETE CASCADE,
    product_id uuid          NOT NULL,
    sku_id     uuid          NOT NULL,
    quantity   integer       NOT NULL CHECK (quantity > 0),
    price      numeric(19,2) NOT NULL CHECK (price >= 0),
    UNIQUE (cart_id, sku_id)
);
CREATE INDEX idx_cart_items_cart ON cart_items (cart_id);

CREATE TABLE orders (
    id              uuid          PRIMARY KEY DEFAULT uuidv7(),
    user_id         uuid          NOT NULL,
    seller_id       uuid          NOT NULL,
    order_number    varchar(40)   NOT NULL UNIQUE,
    order_type      varchar(20)   NOT NULL CHECK (order_type IN ('STANDARD', 'AUCTION')),
    reference_id    uuid,
    status          varchar(30)   NOT NULL DEFAULT 'PENDING'
                                  CHECK (status IN ('PENDING', 'PAID', 'SHIPPED', 'DELIVERED',
                                                    'COMPLETED', 'CANCELED', 'REFUND_REQUESTED',
                                                    'REFUNDED')),
    subtotal        numeric(19,2) NOT NULL CHECK (subtotal >= 0),
    discount_amount numeric(19,2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    promo_code      varchar(50),
    tax_amount      numeric(19,2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
    shipping_fee    numeric(19,2) NOT NULL DEFAULT 0 CHECK (shipping_fee >= 0),
    total_amount    numeric(19,2) NOT NULL CHECK (total_amount >= 0),
    shipping_info   jsonb,
    idempotency_key varchar(100)  NOT NULL UNIQUE,
    paid_at         timestamptz,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    version         bigint        NOT NULL DEFAULT 0
);
CREATE INDEX idx_orders_user   ON orders (user_id, created_at DESC);
CREATE INDEX idx_orders_seller ON orders (seller_id, status);
CREATE INDEX idx_orders_status ON orders (status, created_at);

CREATE TABLE order_items (
    id           uuid          PRIMARY KEY DEFAULT uuidv7(),
    order_id     uuid          NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id   uuid          NOT NULL,
    sku_id       uuid          NOT NULL,
    product_name varchar(255)  NOT NULL,
    sku_code     varchar(60),
    image_url    varchar(500),
    quantity     integer       NOT NULL CHECK (quantity > 0),
    unit_price   numeric(19,2) NOT NULL CHECK (unit_price >= 0),
    total_price  numeric(19,2) NOT NULL CHECK (total_price >= 0)
);
CREATE INDEX idx_order_items_order   ON order_items (order_id);
CREATE INDEX idx_order_items_product ON order_items (product_id);

CREATE TABLE order_status_history (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    order_id        uuid        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    previous_status varchar(30),
    new_status      varchar(30) NOT NULL,
    reason          text,
    changed_by      uuid,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_history_order ON order_status_history (order_id, created_at DESC);

CREATE TABLE payment_transactions (
    id              uuid          PRIMARY KEY DEFAULT uuidv7(),
    order_id        uuid          NOT NULL REFERENCES orders (id),
    provider        varchar(40)   NOT NULL,
    txn_reference   varchar(120),
    amount          numeric(19,2) NOT NULL CHECK (amount >= 0),
    currency        char(3)       NOT NULL DEFAULT 'VND',
    status          varchar(30)   NOT NULL CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED')),
    type            varchar(20)   NOT NULL CHECK (type IN ('NORMAL', 'REFUND')),
    idempotency_key varchar(100)  NOT NULL UNIQUE,
    raw_response    jsonb,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_order   ON payment_transactions (order_id);
CREATE INDEX idx_payment_txn_ref ON payment_transactions (txn_reference);
CREATE INDEX idx_payment_status  ON payment_transactions (status, created_at);

CREATE TABLE promo_mega_sale_events (
    id                    uuid         PRIMARY KEY DEFAULT uuidv7(),
    name                  varchar(200) NOT NULL,
    description           text,
    banner_url            varchar(500),
    registration_start_at timestamptz  NOT NULL,
    registration_end_at   timestamptz  NOT NULL,
    start_at              timestamptz  NOT NULL,
    end_at                timestamptz  NOT NULL,
    status                varchar(20)  NOT NULL,
    created_by            uuid         NOT NULL,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CHECK (end_at > start_at),
    CHECK (registration_end_at > registration_start_at)
);
CREATE INDEX idx_promo_events_status ON promo_mega_sale_events (status, start_at);

CREATE TABLE promo_vouchers (
    id                     uuid          PRIMARY KEY DEFAULT uuidv7(),
    code                   varchar(50)   NOT NULL UNIQUE,
    name                   varchar(200)  NOT NULL,
    description            text,
    source_type            varchar(20)   NOT NULL,
    owner_seller_id        uuid,
    event_id               uuid          REFERENCES promo_mega_sale_events (id),
    discount_type          varchar(20)   NOT NULL,
    discount_value         numeric(19,2) NOT NULL CHECK (discount_value >= 0),
    max_discount_amount    numeric(19,2) CHECK (max_discount_amount IS NULL OR max_discount_amount >= 0),
    min_order_amount       numeric(19,2) NOT NULL DEFAULT 0 CHECK (min_order_amount >= 0),
    currency_code          char(3)       NOT NULL DEFAULT 'VND',
    funding_type           varchar(20)   NOT NULL,
    seller_funding_percent numeric(5,2)  NOT NULL DEFAULT 0
                                         CHECK (seller_funding_percent BETWEEN 0 AND 100),
    product_scope          varchar(20)   NOT NULL,
    claim_limit            integer       CHECK (claim_limit IS NULL OR claim_limit > 0),
    usage_limit            integer       CHECK (usage_limit IS NULL OR usage_limit > 0),
    discount_budget        numeric(19,2) CHECK (discount_budget IS NULL OR discount_budget >= 0),
    claim_start_at         timestamptz   NOT NULL,
    claim_end_at           timestamptz   NOT NULL,
    valid_from             timestamptz   NOT NULL,
    valid_until            timestamptz   NOT NULL,
    terms_text             text,
    terms_version          integer       NOT NULL DEFAULT 1,
    status                 varchar(20)   NOT NULL,
    created_by             uuid          NOT NULL,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    updated_at             timestamptz   NOT NULL DEFAULT now(),
    CHECK (valid_until > valid_from),
    CHECK (claim_end_at > claim_start_at)
);
CREATE INDEX idx_vouchers_active ON promo_vouchers (status, valid_from, valid_until);
CREATE INDEX idx_vouchers_event  ON promo_vouchers (event_id);

CREATE TABLE promo_event_sellers (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    event_id      uuid        NOT NULL REFERENCES promo_mega_sale_events (id),
    seller_id     uuid        NOT NULL,
    status        varchar(20) NOT NULL,
    registered_at timestamptz NOT NULL DEFAULT now(),
    reviewed_by   uuid,
    reviewed_at   timestamptz,
    review_note   text,
    withdrawn_at  timestamptz,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (event_id, seller_id)
);

CREATE TABLE promo_customer_vouchers (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    customer_id uuid        NOT NULL,
    voucher_id  uuid        NOT NULL REFERENCES promo_vouchers (id),
    status      varchar(20) NOT NULL,
    claimed_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (customer_id, voucher_id)
);
CREATE INDEX idx_customer_vouchers_status ON promo_customer_vouchers (customer_id, status);

CREATE TABLE promo_voucher_products (
    voucher_id uuid        NOT NULL REFERENCES promo_vouchers (id) ON DELETE CASCADE,
    product_id uuid        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (voucher_id, product_id)
);

CREATE TABLE promo_voucher_sellers (
    id                              uuid         PRIMARY KEY DEFAULT uuidv7(),
    voucher_id                      uuid         NOT NULL REFERENCES promo_vouchers (id),
    event_seller_id                 uuid         NOT NULL REFERENCES promo_event_sellers (id),
    status                          varchar(20)  NOT NULL,
    accepted_terms_version          integer      NOT NULL,
    accepted_terms_snapshot         text,
    seller_funding_percent_snapshot numeric(5,2) NOT NULL
                                                 CHECK (seller_funding_percent_snapshot BETWEEN 0 AND 100),
    accepted_by                     uuid         NOT NULL,
    accepted_at                     timestamptz  NOT NULL DEFAULT now(),
    withdrawn_at                    timestamptz,
    updated_at                      timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (voucher_id, event_seller_id)
);

CREATE TABLE promo_voucher_usages (
    id                     uuid          PRIMARY KEY DEFAULT uuidv7(),
    customer_voucher_id    uuid          NOT NULL REFERENCES promo_customer_vouchers (id),
    order_id               uuid          NOT NULL REFERENCES orders (id),
    idempotency_key        varchar(100)  NOT NULL UNIQUE,
    status                 varchar(25)   NOT NULL,
    eligible_amount        numeric(19,2) NOT NULL CHECK (eligible_amount >= 0),
    discount_amount        numeric(19,2) NOT NULL
                                         CHECK (discount_amount >= 0 AND discount_amount <= eligible_amount),
    seller_funded_amount   numeric(19,2) NOT NULL DEFAULT 0 CHECK (seller_funded_amount >= 0),
    platform_funded_amount numeric(19,2) NOT NULL DEFAULT 0 CHECK (platform_funded_amount >= 0),
    currency_code          char(3)       NOT NULL DEFAULT 'VND',
    voucher_snapshot       jsonb         NOT NULL,
    reserved_at            timestamptz   NOT NULL DEFAULT now(),
    reserved_until         timestamptz   NOT NULL,
    redeemed_at            timestamptz,
    released_at            timestamptz,
    release_reason         varchar(100),
    updated_at             timestamptz   NOT NULL DEFAULT now(),
    CHECK (discount_amount = seller_funded_amount + platform_funded_amount)
);
CREATE INDEX idx_voucher_usages_order    ON promo_voucher_usages (order_id);
CREATE INDEX idx_voucher_usages_expiring ON promo_voucher_usages (status, reserved_until);

CREATE TABLE promo_voucher_usage_allocations (
    id                       uuid          PRIMARY KEY DEFAULT uuidv7(),
    voucher_usage_id         uuid          NOT NULL REFERENCES promo_voucher_usages (id) ON DELETE CASCADE,
    order_item_id            uuid          NOT NULL REFERENCES order_items (id),
    seller_id                uuid          NOT NULL,
    voucher_seller_id        uuid          REFERENCES promo_voucher_sellers (id),
    eligible_amount          numeric(19,2) NOT NULL CHECK (eligible_amount >= 0),
    discount_amount          numeric(19,2) NOT NULL CHECK (discount_amount >= 0),
    seller_funded_amount     numeric(19,2) NOT NULL DEFAULT 0 CHECK (seller_funded_amount >= 0),
    platform_funded_amount   numeric(19,2) NOT NULL DEFAULT 0 CHECK (platform_funded_amount >= 0),
    reversed_discount_amount numeric(19,2) NOT NULL DEFAULT 0 CHECK (reversed_discount_amount >= 0),
    reversed_seller_amount   numeric(19,2) NOT NULL DEFAULT 0 CHECK (reversed_seller_amount >= 0),
    reversed_platform_amount numeric(19,2) NOT NULL DEFAULT 0 CHECK (reversed_platform_amount >= 0),
    created_at               timestamptz   NOT NULL DEFAULT now(),
    updated_at               timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (voucher_usage_id, order_item_id),
    CHECK (discount_amount = seller_funded_amount + platform_funded_amount),
    CHECK (reversed_discount_amount = reversed_seller_amount + reversed_platform_amount),
    CHECK (reversed_discount_amount <= discount_amount),
    CHECK (reversed_seller_amount <= seller_funded_amount),
    CHECK (reversed_platform_amount <= platform_funded_amount)
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

CREATE TABLE shedlock (
    name       varchar(64)  PRIMARY KEY,
    lock_until timestamptz  NOT NULL,
    locked_at  timestamptz  NOT NULL,
    locked_by  varchar(255) NOT NULL
);
