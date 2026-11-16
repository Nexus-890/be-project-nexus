-- Commit giá trị enum mới trước khi migration tiếp theo sử dụng.
ALTER TYPE payment_transaction_type ADD VALUE 'PAYOUT';
ALTER TYPE payment_transaction_type ADD VALUE 'PAYOUT_RETURN';
