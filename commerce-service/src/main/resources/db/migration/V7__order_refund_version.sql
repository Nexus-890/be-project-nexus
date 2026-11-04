-- VERSION CỦA REFUND ĐÃ ÁP DỤNG CHO ĐƠN (0 = REQUESTED, 1 = COMPLETED) -> EVENT REFUND CŨ ĐẾN MUỘN THÌ BỎ QUA
ALTER TABLE orders
    ADD COLUMN last_refund_version bigint NOT NULL DEFAULT 0
        CONSTRAINT orders_last_refund_version_check CHECK (last_refund_version >= 0);
