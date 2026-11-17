-- V15 already owns delivered_at and the persisted receipt deadline. Keep existing
-- deadlines (including historical seven-day windows) when changing runtime defaults.
CREATE INDEX idx_orders_status_delivered_at ON orders (status, delivered_at);
