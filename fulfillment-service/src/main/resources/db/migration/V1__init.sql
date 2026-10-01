CREATE TABLE warehouses (
    id         uuid         PRIMARY KEY DEFAULT uuidv7(),
    code       varchar(40)  NOT NULL UNIQUE,
    name       varchar(150) NOT NULL,
    address    text,
    status     varchar(20)  NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE', 'INACTIVE', 'MAINTENANCE')),
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE inventory_balances (
    id                   uuid        PRIMARY KEY DEFAULT uuidv7(),
    sku_id               uuid        NOT NULL,
    warehouse_id         uuid        NOT NULL REFERENCES warehouses (id),
    total_quantity       integer     NOT NULL DEFAULT 0 CHECK (total_quantity >= 0),
    available_quantity   integer     NOT NULL DEFAULT 0 CHECK (available_quantity >= 0),
    reserved_quantity    integer     NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    unavailable_quantity integer     NOT NULL DEFAULT 0 CHECK (unavailable_quantity >= 0),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    version              bigint      NOT NULL DEFAULT 0,
    UNIQUE (sku_id, warehouse_id),
    CHECK (total_quantity = available_quantity + reserved_quantity + unavailable_quantity)
);
CREATE INDEX idx_inventory_sku ON inventory_balances (sku_id);

CREATE TABLE inventory_reservations (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    sku_id       uuid        NOT NULL,
    warehouse_id uuid        NOT NULL REFERENCES warehouses (id),
    order_id     uuid,
    cart_id      uuid,
    quantity     integer     NOT NULL CHECK (quantity > 0),
    status       varchar(20) NOT NULL DEFAULT 'HELD'
                             CHECK (status IN ('HELD', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    expires_at   timestamptz NOT NULL,
    released_at  timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_reservations_expiring ON inventory_reservations (status, expires_at);
CREATE INDEX idx_reservations_order    ON inventory_reservations (order_id);
CREATE INDEX idx_reservations_sku      ON inventory_reservations (sku_id, warehouse_id);

CREATE TABLE inventory_movements (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    sku_id        uuid        NOT NULL,
    warehouse_id  uuid        NOT NULL REFERENCES warehouses (id),
    movement_type varchar(30) NOT NULL
                              CHECK (movement_type IN ('IMPORT', 'EXPORT', 'RESERVE', 'RELEASE',
                                                       'SALE', 'RETURN', 'ADJUSTMENT', 'TRANSFER')),
    quantity      integer     NOT NULL,
    reference_id  uuid,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_movements_sku       ON inventory_movements (sku_id, warehouse_id, created_at DESC);
CREATE INDEX idx_movements_reference ON inventory_movements (reference_id);

CREATE TABLE shipments (
    id              uuid          PRIMARY KEY DEFAULT uuidv7(),
    order_id        uuid          NOT NULL UNIQUE,
    warehouse_id    uuid          NOT NULL REFERENCES warehouses (id),
    carrier         varchar(40)   NOT NULL,
    tracking_code   varchar(80),
    shipment_status varchar(30)   NOT NULL DEFAULT 'CREATED'
                                  CHECK (shipment_status IN ('CREATED', 'PICKING', 'PACKED',
                                                             'READY_TO_SHIP', 'SHIPPED', 'IN_TRANSIT',
                                                             'OUT_FOR_DELIVERY', 'DELIVERED',
                                                             'DELIVERY_FAILED', 'RETURNING',
                                                             'RETURNED', 'CANCELLED')),
    service_type    varchar(40),
    shipping_fee    numeric(19,2) NOT NULL DEFAULT 0 CHECK (shipping_fee >= 0),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_shipments_status   ON shipments (shipment_status, created_at DESC);
CREATE INDEX idx_shipments_tracking ON shipments (tracking_code);

CREATE TABLE shipment_history (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    shipment_id     uuid        NOT NULL REFERENCES shipments (id) ON DELETE CASCADE,
    shipment_status varchar(30) NOT NULL,
    description     varchar(255),
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_shipment_history ON shipment_history (shipment_id, created_at DESC);

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
