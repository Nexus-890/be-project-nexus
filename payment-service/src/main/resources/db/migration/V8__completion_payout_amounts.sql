-- Giữ nguyên dữ liệu completion/eligibility của V5 đã triển khai.
-- NULL nghĩa là event cũ chưa có tiền, không tự suy đoán phí vận chuyển.
ALTER TABLE payment_order_completions
    ADD COLUMN order_amount numeric(19,2),
    ADD COLUMN shipping_fee numeric(19,2),
    ADD CONSTRAINT completion_money_check CHECK (
        (order_amount IS NULL AND shipping_fee IS NULL)
        OR (order_amount IS NOT NULL AND shipping_fee IS NOT NULL AND order_amount > 0 AND shipping_fee >= 0)
    );
