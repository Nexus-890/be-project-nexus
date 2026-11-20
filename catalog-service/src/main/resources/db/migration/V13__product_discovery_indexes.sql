CREATE INDEX idx_product_stats_popular ON product_stats (order_count DESC, product_id) WHERE order_count > 0;
CREATE INDEX idx_products_active_newest ON products (created_at DESC, id) WHERE status = 'ACTIVE' AND deleted_at IS NULL;
