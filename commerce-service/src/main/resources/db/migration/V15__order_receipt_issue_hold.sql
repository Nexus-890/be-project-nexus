ALTER TABLE orders ADD COLUMN receipt_issue_reason varchar(500);
ALTER TABLE orders ADD COLUMN receipt_issue_reported_at timestamptz;
ALTER TABLE orders ADD CONSTRAINT order_receipt_issue_check CHECK (
    (receipt_issue_reason IS NULL AND receipt_issue_reported_at IS NULL)
    OR (receipt_issue_reason IS NOT NULL AND btrim(receipt_issue_reason) <> '' AND receipt_issue_reported_at IS NOT NULL)
);
