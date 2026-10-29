CREATE TYPE payment_session_status AS ENUM (
    'PENDING', 'PAID', 'CANCELED', 'EXPIRED'
    );

CREATE TYPE payment_instruction_status AS ENUM (
    'PENDING', 'PAID', 'CANCELED', 'EXPIRED'
    );

CREATE TYPE payment_transaction_status AS ENUM (
    'PENDING', 'SUCCESS', 'FAILED', 'UNRECONCILED'
    );

CREATE TYPE payment_transaction_type AS ENUM (
    'NORMAL', 'REFUND'
    );

CREATE TABLE payment_sessions (
                                  id               uuid PRIMARY KEY DEFAULT uuidv7(),
                                  order_group_id   uuid NOT NULL UNIQUE,
                                  buyer_id         uuid NOT NULL,

                                  total_amount     numeric(19,2) NOT NULL
                                      CHECK (total_amount > 0),
                                  currency         char(3) NOT NULL,
                                  memo_code        varchar(32) NOT NULL UNIQUE
                                      CHECK (memo_code ~ '^[A-Z0-9]+$'),
                                  due_at           timestamptz NOT NULL,

                                  status           payment_session_status NOT NULL DEFAULT 'PENDING',
                                  version          bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
                                  source_event_id  uuid NOT NULL,
                                  idempotency_key  varchar(100) NOT NULL UNIQUE,
                                  paid_at          timestamptz,

                                  created_at       timestamptz NOT NULL DEFAULT now(),
                                  updated_at       timestamptz NOT NULL DEFAULT now(),
                                  created_by       uuid NOT NULL
                                      DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,
                                  updated_by       uuid NOT NULL
                                      DEFAULT '00000000-0000-0000-0000-000000000000'::uuid
);

CREATE INDEX idx_payment_sessions_buyer
    ON payment_sessions (buyer_id, created_at DESC);

CREATE INDEX idx_payment_sessions_due
    ON payment_sessions (status, due_at);

CREATE TABLE payment_transactions (
                                      id                    uuid PRIMARY KEY DEFAULT uuidv7(),
                                      session_id            uuid NOT NULL
                                          REFERENCES payment_sessions (id)
                                              ON DELETE NO ACTION ON UPDATE NO ACTION,

                                      provider              varchar(40) NOT NULL,
                                      txn_reference         varchar(120),
                                      amount                numeric(19,2) NOT NULL CHECK (amount >= 0),
                                      currency              char(3) NOT NULL DEFAULT 'VND',
                                      status                payment_transaction_status NOT NULL,
                                      type                  payment_transaction_type NOT NULL,
                                      idempotency_key       varchar(100) NOT NULL UNIQUE,
                                      raw_response          jsonb,

                                      created_at            timestamptz NOT NULL DEFAULT now(),
                                      updated_at            timestamptz NOT NULL DEFAULT now(),
                                      created_by            uuid NOT NULL
                                          DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,
                                      updated_by            uuid NOT NULL
                                          DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,

                                      received_at           timestamptz NOT NULL,
                                      provider_occurred_at  timestamptz
);

CREATE INDEX idx_payment_session
    ON payment_transactions (session_id, created_at);

CREATE INDEX idx_payment_txn_ref
    ON payment_transactions (txn_reference);

CREATE INDEX idx_payment_status
    ON payment_transactions (status, created_at);

CREATE UNIQUE INDEX uq_payment_provider_txn
    ON payment_transactions (provider, txn_reference)
    WHERE txn_reference IS NOT NULL;

CREATE TABLE payment_instructions (
                                      id                     uuid PRIMARY KEY DEFAULT uuidv7(),
                                      order_id               uuid NOT NULL UNIQUE,
                                      session_id             uuid NOT NULL
                                          REFERENCES payment_sessions (id)
                                              ON DELETE NO ACTION ON UPDATE NO ACTION,
                                      funded_transaction_id  uuid
                                          REFERENCES payment_transactions (id)
                                              ON DELETE NO ACTION ON UPDATE NO ACTION,

                                      buyer_id               uuid NOT NULL,
                                      amount                 numeric(19,2) NOT NULL CHECK (amount >= 0),
                                      currency               char(3) NOT NULL DEFAULT 'VND',
                                      due_at                 timestamptz NOT NULL,
                                      status                 payment_instruction_status NOT NULL DEFAULT 'PENDING',

                                      paid_at                timestamptz,
                                      canceled_at            timestamptz,
                                      last_order_event_at    timestamptz,
                                      last_order_version     bigint NOT NULL DEFAULT 0
                                          CHECK (last_order_version >= 0),

                                      version                bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
                                      created_at             timestamptz NOT NULL DEFAULT now(),
                                      updated_at             timestamptz NOT NULL DEFAULT now(),
                                      created_by             uuid NOT NULL
                                          DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,
                                      updated_by             uuid NOT NULL
                                          DEFAULT '00000000-0000-0000-0000-000000000000'::uuid
);

CREATE INDEX idx_payment_instructions_session
    ON payment_instructions (session_id, order_id);

CREATE INDEX idx_payment_instructions_funded_transaction
    ON payment_instructions (funded_transaction_id);

CREATE INDEX idx_payment_instructions_buyer
    ON payment_instructions (buyer_id, created_at DESC);

CREATE INDEX idx_payment_instructions_due
    ON payment_instructions (status, due_at);

CREATE TABLE outbox (
                        id              uuid PRIMARY KEY DEFAULT uuidv7(),
                        aggregate_type  varchar(50) NOT NULL,
                        aggregate_id    varchar(100) NOT NULL,
                        event_type      varchar(80) NOT NULL,
                        event_version   integer NOT NULL DEFAULT 1,
                        topic           varchar(80) NOT NULL,
                        payload         jsonb NOT NULL,
                        trace_id        varchar(64),
                        occurred_at     timestamptz NOT NULL DEFAULT now(),
                        published_at    timestamptz
);

CREATE INDEX idx_outbox_unpublished
    ON outbox (occurred_at)
    WHERE published_at IS NULL;

CREATE INDEX idx_outbox_aggregate
    ON outbox (aggregate_type, aggregate_id);

CREATE TABLE processed_events (
                                  event_id    uuid NOT NULL,
                                  consumer    varchar(80) NOT NULL,
                                  handled_at  timestamptz NOT NULL DEFAULT now(),
                                  PRIMARY KEY (event_id, consumer)
);

CREATE INDEX idx_processed_events_cleanup
    ON processed_events (handled_at);