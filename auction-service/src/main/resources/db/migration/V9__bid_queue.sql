-- Bổ sung trạng thái; không sử dụng literal enum mới trong chính transaction migration này.
ALTER TYPE bid_status ADD VALUE IF NOT EXISTS 'QUEUED';
ALTER TYPE bid_status ADD VALUE IF NOT EXISTS 'REJECTED';

ALTER TABLE bids
    ADD COLUMN requested_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN processed_at timestamptz,
    ADD COLUMN reject_reason varchar(80),
    ADD COLUMN reject_minimum numeric(19,2),
    ADD COLUMN result_current_price numeric(19,2),
    ADD COLUMN result_leader_bidder_id uuid,
    ALTER COLUMN placed_at DROP NOT NULL;

-- Giữ thời gian lịch sử của bid đã tồn tại; bid mới nhận QUEUED chưa có placed_at.
UPDATE bids SET requested_at = placed_at, processed_at = placed_at,
    result_current_price = amount, result_leader_bidder_id = bidder_id;
CREATE INDEX idx_bids_queue ON bids (auction_id, status, requested_at, id);
