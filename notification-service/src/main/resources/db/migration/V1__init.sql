CREATE TABLE event_logs (
    id             uuid         PRIMARY KEY DEFAULT uuidv7(),
    event_id       uuid         NOT NULL,
    event_type     varchar(80)  NOT NULL,
    message        varchar(500),
    operator_id    uuid,
    operator_name  varchar(150),
    aggregate_type varchar(50),
    aggregate_id   varchar(100),
    producer       varchar(50),
    trace_id       varchar(64),
    payload        jsonb,
    occurred_at    timestamptz  NOT NULL,
    received_at    timestamptz  NOT NULL DEFAULT now(),
    deleted_at     timestamptz
);
CREATE INDEX idx_event_logs_aggregate ON event_logs (aggregate_type, aggregate_id);
CREATE INDEX idx_event_logs_time      ON event_logs (occurred_at DESC);
CREATE INDEX idx_event_logs_trace     ON event_logs (trace_id);
CREATE INDEX idx_event_logs_type      ON event_logs (event_type, occurred_at DESC);

CREATE TABLE notification_templates (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    code       varchar(80) NOT NULL,
    channel    varchar(20) NOT NULL CHECK (channel IN ('EMAIL', 'PUSH', 'SMS')),
    locale     varchar(10) NOT NULL DEFAULT 'vi',
    subject    varchar(255),
    body       text        NOT NULL,
    version    integer     NOT NULL DEFAULT 1,
    is_active  boolean     NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (code, channel, locale)
);

CREATE TABLE notifications (
    id         uuid         PRIMARY KEY DEFAULT uuidv7(),
    event_id   uuid         REFERENCES event_logs (id),
    user_id    uuid         NOT NULL,
    type       varchar(60)  NOT NULL,
    title      varchar(255) NOT NULL,
    content    text         NOT NULL,
    read_at    timestamptz,
    created_at timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user   ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;

CREATE TABLE notification_deliveries (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    notification_id uuid        NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    channel         varchar(20) NOT NULL CHECK (channel IN ('EMAIL', 'PUSH', 'SMS')),
    status          varchar(20) NOT NULL DEFAULT 'PENDING'
                                CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    attempt_count   integer     NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_deliveries_notification ON notification_deliveries (notification_id);
CREATE INDEX idx_deliveries_pending      ON notification_deliveries (status, created_at);

CREATE TABLE notification_delivery_attempts (
    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),
    delivery_id         uuid        NOT NULL REFERENCES notification_deliveries (id) ON DELETE CASCADE,
    attempt_number      integer     NOT NULL CHECK (attempt_number > 0),
    status              varchar(20) NOT NULL
                                    CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED')),
    attempted_at        timestamptz NOT NULL DEFAULT now(),
    completed_at        timestamptz,
    provider_message_id varchar(120),
    error_code          varchar(60),
    error_message       text,
    retryable           boolean     NOT NULL DEFAULT true,
    UNIQUE (delivery_id, attempt_number)
);

CREATE TABLE notification_preferences (
    id                uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id           uuid        NOT NULL,
    notification_type varchar(60) NOT NULL,
    channel           varchar(20) NOT NULL CHECK (channel IN ('EMAIL', 'PUSH', 'SMS')),
    enabled           boolean     NOT NULL DEFAULT true,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, notification_type, channel)
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
