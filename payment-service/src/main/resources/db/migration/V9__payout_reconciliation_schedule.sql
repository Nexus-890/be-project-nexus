-- Lịch đối soát độc lập với thời điểm gửi: payout chưa rõ kết quả phải nhường lượt cho các dòng phía sau.
ALTER TABLE payouts ADD COLUMN next_reconcile_at timestamptz;

CREATE INDEX idx_payouts_reconcile_schedule
    ON payouts(provider, coalesce(next_reconcile_at, attempt_started_at), id)
    WHERE status IN ('PROCESSING', 'UNKNOWN', 'SUCCEEDED');
