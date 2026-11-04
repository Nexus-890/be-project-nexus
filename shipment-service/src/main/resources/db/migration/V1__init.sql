-- shipment-service owns shipment_db and these four tables.
-- PostgreSQL 18 provides the native uuidv7() default used by the source schema.
-- External order, warehouse, buyer and actor UUIDs are logical references only.
CREATE TYPE shipment_status AS ENUM (
    'CREATED', 'PENDING_TRACKING', 'PICKING', 'PACKED', 'READY_TO_SHIP',
    'SHIPPED', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED',
    'DELIVERY_FAILED', 'RETURNING', 'RETURNED', 'CANCELLED'
);

CREATE TABLE shipments (
    id                     uuid PRIMARY KEY DEFAULT uuidv7(),
    order_id               uuid NOT NULL UNIQUE,
    warehouse_id           uuid NOT NULL,
    carrier                varchar(40) NOT NULL,
    tracking_code          varchar(80),
    shipment_status        shipment_status NOT NULL DEFAULT 'CREATED'::shipment_status,
    service_type           varchar(40),
    shipping_fee           numeric(19,2) NOT NULL DEFAULT 0 CHECK (shipping_fee >= 0),
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    buyer_id               uuid NOT NULL,
    shipping_info          jsonb NOT NULL CHECK (jsonb_typeof(shipping_info) = 'object'),
    idempotency_key        varchar(100) UNIQUE,
    version                bigint NOT NULL DEFAULT 0,
    tracking_retry_count   integer NOT NULL DEFAULT 0 CHECK (tracking_retry_count >= 0),
    next_tracking_retry_at timestamptz,
    created_by             uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,
    updated_by             uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,
    cancel_request_id      uuid,
    cancel_fenced_at       timestamptz,
    handed_over_at         timestamptz
);

CREATE INDEX idx_shipments_status ON shipments (shipment_status, created_at DESC);
CREATE INDEX idx_shipments_tracking ON shipments (tracking_code);
CREATE INDEX idx_shipments_buyer ON shipments (buyer_id, created_at DESC);
CREATE INDEX idx_shipments_tracking_retry ON shipments (next_tracking_retry_at, id)
    WHERE shipment_status = 'PENDING_TRACKING' AND next_tracking_retry_at IS NOT NULL;

CREATE TABLE shipment_history (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    shipment_id      uuid NOT NULL REFERENCES shipments (id)
                         ON DELETE CASCADE ON UPDATE NO ACTION,
    shipment_status  shipment_status NOT NULL,
    description      varchar(255),
    created_at       timestamptz NOT NULL DEFAULT now(),
    carrier_event_id varchar(160),
    source           varchar(30) NOT NULL DEFAULT 'SYSTEM'
                         CHECK (source IN ('SYSTEM', 'CARRIER_WEBHOOK', 'POLLER', 'MANUAL')),
    changed_by       uuid,
    created_by       uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,
    updated_by       uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'::uuid
);

CREATE INDEX idx_shipment_history ON shipment_history (shipment_id, created_at DESC);
CREATE UNIQUE INDEX uq_shipment_history_carrier_event
    ON shipment_history (shipment_id, carrier_event_id) WHERE carrier_event_id IS NOT NULL;

-- Fresh service-owned delivery state; do not import inventory/fulfillment markers.
CREATE TABLE outbox (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    aggregate_type varchar(50) NOT NULL,
    aggregate_id   varchar(100) NOT NULL,
    event_type     varchar(80) NOT NULL,
    event_version  integer NOT NULL DEFAULT 1,
    topic          varchar(80) NOT NULL,
    payload        jsonb NOT NULL,
    trace_id       varchar(64),
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz
);

CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate ON outbox (aggregate_type, aggregate_id);

CREATE TABLE processed_events (
    event_id   uuid NOT NULL,
    consumer   varchar(80) NOT NULL,
    handled_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

CREATE INDEX idx_processed_events_cleanup ON processed_events (handled_at);
