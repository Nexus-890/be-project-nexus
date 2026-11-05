-- Preserve poison records before advancing the Kafka offset. The source position
-- is the idempotency key, since a malformed envelope may not contain an eventId.
CREATE TABLE event_log_rejections (
    source_topic     text        NOT NULL,
    source_partition integer     NOT NULL,
    source_offset    bigint      NOT NULL,
    record_key       text,
    raw_envelope     text,
    failure_type     varchar(150) NOT NULL,
    rejected_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source_topic, source_partition, source_offset)
);
