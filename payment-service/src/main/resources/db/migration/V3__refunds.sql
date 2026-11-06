-- MỖI ĐƠN CHỈ CÓ 1 REFUND (UNIQUE order_id), GỬI LẠI CÙNG Idempotency-Key KHÔNG TẠO THÊM (UNIQUE idempotency_key)
CREATE TYPE refund_status AS ENUM ('REQUESTED', 'COMPLETED');

CREATE TABLE refunds (
    id                        uuid PRIMARY KEY DEFAULT uuidv7(),
    order_id                  uuid NOT NULL UNIQUE
        REFERENCES payment_instructions (order_id)
            ON DELETE NO ACTION ON UPDATE NO ACTION,
    amount                    numeric(19,2) NOT NULL CHECK (amount > 0),
    currency                  char(3) NOT NULL DEFAULT 'VND',
    reason                    text NOT NULL CHECK (length(btrim(reason)) > 0),
    status                    refund_status NOT NULL DEFAULT 'REQUESTED',
    idempotency_key           varchar(100) NOT NULL UNIQUE,
    requested_by              uuid NOT NULL,
    requested_at              timestamptz NOT NULL DEFAULT now(),
    completed_by              uuid,
    completed_at              timestamptz,
    completed_transaction_id  uuid UNIQUE
        REFERENCES payment_transactions (id)
            ON DELETE NO ACTION ON UPDATE NO ACTION,
    version                   bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at                timestamptz NOT NULL DEFAULT now(),
    created_by                uuid NOT NULL
        DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,
    updated_by                uuid NOT NULL
        DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,
    CONSTRAINT refunds_completion_check CHECK (
        (status = 'REQUESTED' AND completed_by IS NULL AND completed_at IS NULL
            AND completed_transaction_id IS NULL)
        OR (status = 'COMPLETED' AND completed_by IS NOT NULL AND completed_at IS NOT NULL
            AND completed_transaction_id IS NOT NULL AND completed_at >= requested_at))
);

CREATE INDEX idx_refunds_status
    ON refunds (status, requested_at);
