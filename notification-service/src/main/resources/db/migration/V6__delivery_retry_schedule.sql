-- MỖI THÔNG BÁO CHỈ CÓ 1 DELIVERY CHO MỖI KÊNH, CÁC LẦN GỬI LẠI NẰM Ở BẢNG ATTEMPTS
ALTER TABLE notification_deliveries
    ADD COLUMN next_attempt_at timestamptz,
    ADD CONSTRAINT uq_notification_deliveries_channel UNIQUE (notification_id, channel);

CREATE INDEX idx_deliveries_retry_due ON notification_deliveries (next_attempt_at)
    WHERE status = 'PENDING' AND next_attempt_at IS NOT NULL;
