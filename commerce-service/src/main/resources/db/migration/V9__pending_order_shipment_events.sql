-- Keep accepted shipment facts durable while their commerce prerequisites arrive.
CREATE TABLE pending_order_shipment_events (
    event_id   uuid PRIMARY KEY,
    envelope   jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_pending_order_shipment_created
    ON pending_order_shipment_events (created_at, event_id);
