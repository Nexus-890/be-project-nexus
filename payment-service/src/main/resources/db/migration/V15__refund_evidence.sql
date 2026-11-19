CREATE TABLE refund_evidence (
    id             uuid PRIMARY KEY,
    refund_id      uuid NOT NULL REFERENCES refunds (id) ON DELETE NO ACTION ON UPDATE NO ACTION,
    uploader_id    uuid NOT NULL,
    bucket         varchar(63) NOT NULL,
    object_key     varchar(512) NOT NULL UNIQUE,
    content_type   varchar(32) NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),
    size_bytes     integer NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
    digest         char(64) NOT NULL,
    bank_reference varchar(100) NOT NULL CHECK (length(btrim(bank_reference)) > 0),
    uploaded_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT refund_evidence_digest_unique UNIQUE (refund_id, digest)
);

CREATE INDEX idx_refund_evidence_refund ON refund_evidence (refund_id, uploaded_at, id);
