-- V2: staging table for OrderGroupCancelled events that arrive before their
-- CheckoutPaymentRequested manifest. Lets the consumer acknowledge the record
-- (processed marker committed in the same transaction) instead of retrying
-- forever and blocking the whole partition. The stashed cancellation is
-- applied when the manifest arrives, in the same transaction that creates
-- the payment session and its instructions.

CREATE TABLE payment_pending_cancellations (
                                  id               uuid PRIMARY KEY DEFAULT uuidv7(),
                                  order_group_id   uuid NOT NULL UNIQUE,
                                  buyer_id         uuid NOT NULL,
                                  group_version    bigint NOT NULL CHECK (group_version >= 0),
                                  reason           varchar(500) NOT NULL,
                                  actor_id         uuid NOT NULL,
                                  cancelled_at     timestamptz NOT NULL,
                                  occurred_at      timestamptz NOT NULL,
                                  orders           jsonb NOT NULL,
                                  source_event_id  uuid NOT NULL,
                                  received_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_pending_cancellations_buyer
    ON payment_pending_cancellations (buyer_id, received_at);
