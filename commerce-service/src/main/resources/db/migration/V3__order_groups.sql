-- A purchase has one payment group and one order per seller. V1/V2 remain intact.
CREATE TABLE order_groups (
    id              uuid          PRIMARY KEY DEFAULT uuidv7(),
    buyer_id        uuid          NOT NULL,
    idempotency_key varchar(100)  NOT NULL UNIQUE,
    status          varchar(20)   NOT NULL DEFAULT 'PENDING'
                                  CHECK (status IN ('PENDING', 'PAID', 'CANCELED', 'EXPIRED')),
    total_amount    numeric(19,2) NOT NULL CHECK (total_amount >= 0),
    currency        char(3)       NOT NULL DEFAULT 'VND',
    due_at          timestamptz,
    paid_at         timestamptz,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      uuid          NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,
    updated_by      uuid          NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,
    version         bigint        NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT ck_order_groups_pending_deadline CHECK (status <> 'PENDING' OR due_at IS NOT NULL)
);

CREATE INDEX idx_order_groups_buyer ON order_groups (buyer_id, created_at DESC);
CREATE INDEX idx_order_groups_expiring ON order_groups (due_at, id) WHERE status = 'PENDING';

-- Do not invent a historical payment deadline to make the constraint pass.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM orders WHERE status = 'PENDING' AND payment_due_at IS NULL) THEN
        RAISE EXCEPTION 'Cannot backfill order groups: PENDING orders have no payment_due_at. Reconcile their deadlines before retrying migration V3.';
    END IF;
END
$$;

ALTER TABLE orders ADD COLUMN order_group_id uuid;

-- With no trustworthy grouping evidence, preserve every old order as a singleton.
-- Reuse the order UUID in the separate group table to make the backfill unambiguous.
INSERT INTO order_groups (
    id, buyer_id, idempotency_key, status, total_amount, currency,
    due_at, paid_at, created_at, updated_at
)
SELECT id, user_id, 'legacy-order:' || id::text,
       CASE
           WHEN paid_at IS NOT NULL THEN 'PAID'
           WHEN status = 'PENDING' THEN 'PENDING'
           WHEN status = 'CANCELED' THEN 'CANCELED'
           ELSE 'PAID'
       END,
       total_amount, 'VND', payment_due_at, paid_at, created_at, updated_at
FROM orders;

UPDATE orders SET order_group_id = id;

ALTER TABLE orders
    ALTER COLUMN order_group_id SET NOT NULL,
    ADD CONSTRAINT fk_orders_order_group FOREIGN KEY (order_group_id)
        REFERENCES order_groups (id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    ADD CONSTRAINT uq_orders_group_seller UNIQUE (order_group_id, seller_id);

-- A database sequence avoids order-number collisions across threads and instances.
CREATE SEQUENCE order_number_seq AS bigint START WITH 1;
SELECT setval('order_number_seq', GREATEST(
    COALESCE((SELECT MAX(substring(order_number FROM '^NX-[0-9]{8}-([0-9]+)$')::bigint)
              FROM orders), 0) + 1,
    1
), false);
