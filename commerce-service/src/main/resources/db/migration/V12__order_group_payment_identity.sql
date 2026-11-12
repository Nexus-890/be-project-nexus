-- Chỉ lưu tham chiếu logic; session và transaction gốc thuộc DB của payment-service.
-- Giữ null cho nhóm lịch sử khi Commerce không có đủ dữ liệu để xác định danh tính thanh toán.
-- Hai ID phải cùng có hoặc cùng thiếu; mỗi session/receipt chỉ được gắn với một nhóm đơn.
ALTER TABLE order_groups
    ADD COLUMN payment_session_id uuid,
    ADD COLUMN payment_transaction_id uuid,
    ADD CONSTRAINT uq_order_groups_payment_session UNIQUE (payment_session_id),
    ADD CONSTRAINT uq_order_groups_payment_transaction UNIQUE (payment_transaction_id),
    ADD CONSTRAINT ck_order_groups_payment_identity CHECK (
        (payment_session_id IS NULL) = (payment_transaction_id IS NULL)
    );
