-- V4: OrderCancelled events for a paid order whose instruction is missing or
-- not funded yet. The consumer stores the event and acknowledges it in the same
-- transaction as its processed marker; the row is applied once the instruction
-- has funding evidence and deleted only after that succeeds.

CREATE TABLE payment_pending_order_cancellations (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    order_id         uuid NOT NULL UNIQUE,
    order_group_id   uuid NOT NULL,
    buyer_id         uuid NOT NULL,
    order_version    bigint NOT NULL CHECK (order_version >= 0),
    paid_amount      numeric(19,2) NOT NULL CHECK (paid_amount > 0),
    currency         char(3),
    reason           varchar(500) NOT NULL,
    actor_id         uuid NOT NULL,
    cancelled_at     timestamptz NOT NULL,
    occurred_at      timestamptz NOT NULL,
    source_event_id  uuid NOT NULL,
    received_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_pending_order_cancellations_group
    ON payment_pending_order_cancellations (order_group_id);
