ALTER TABLE shipments ADD COLUMN last_carrier_update_at timestamptz;
ALTER TABLE shipments ADD COLUMN next_status_poll_at timestamptz;
ALTER TABLE shipments ADD COLUMN status_poll_failures integer NOT NULL DEFAULT 0
    CHECK (status_poll_failures >= 0);

CREATE UNIQUE INDEX uq_shipments_carrier_tracking ON shipments (carrier, tracking_code)
    WHERE tracking_code IS NOT NULL;
CREATE INDEX idx_shipments_status_poll ON shipments (next_status_poll_at, id)
    WHERE tracking_code IS NOT NULL AND shipment_status NOT IN ('DELIVERED', 'CANCELLED', 'RETURNED');

CREATE TABLE carrier_webhook_receipts (
    carrier varchar(40) NOT NULL,
    delivery_id varchar(160) NOT NULL,
    shipment_id uuid NOT NULL REFERENCES shipments(id),
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (carrier, delivery_id)
);

CREATE TABLE cancelled_shipment_orders (
    order_id uuid PRIMARY KEY,
    event_id uuid NOT NULL,
    reason varchar(500) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
