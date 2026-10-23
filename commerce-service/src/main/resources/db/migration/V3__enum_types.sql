-- Keep V1/V2 intact: existing rows are cast without changing their values.
CREATE TYPE cart_status AS ENUM ('ACTIVE', 'CHECKED_OUT', 'EXPIRED', 'MERGED');
CREATE TYPE order_status AS ENUM (
    'PENDING', 'PAID', 'SHIPPED', 'DELIVERED', 'COMPLETED', 'CANCELED',
    'REFUND_REQUESTED', 'REFUNDED'
);
CREATE TYPE order_type AS ENUM ('STANDARD', 'AUCTION');
CREATE TYPE payment_transaction_status AS ENUM ('PENDING', 'SUCCESS', 'FAILED');
CREATE TYPE payment_transaction_type AS ENUM ('NORMAL', 'REFUND');

-- Promotion workflows have no Java entities yet. Keep their status contracts
-- explicit here; future workflow changes must add labels in a new migration.
CREATE TYPE promo_mega_sale_event_status AS ENUM (
    'DRAFT', 'REGISTRATION_OPEN', 'REGISTRATION_CLOSED', 'ACTIVE', 'ENDED', 'CANCELED'
);
CREATE TYPE promo_voucher_status AS ENUM ('DRAFT', 'ACTIVE', 'PAUSED', 'EXPIRED', 'CANCELED');
CREATE TYPE promo_event_seller_status AS ENUM ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN');
CREATE TYPE promo_customer_voucher_status AS ENUM ('CLAIMED', 'USED', 'EXPIRED');
CREATE TYPE promo_voucher_seller_status AS ENUM ('ACTIVE', 'WITHDRAWN');
CREATE TYPE promo_voucher_usage_status AS ENUM ('RESERVED', 'REDEEMED', 'RELEASED', 'EXPIRED', 'REVERSED');

-- The partial predicate is varchar-specific and must be rebuilt for the enum.
DROP INDEX idx_carts_one_active;
ALTER TABLE carts DROP CONSTRAINT carts_status_check;
ALTER TABLE carts ALTER COLUMN status DROP DEFAULT;
ALTER TABLE carts ALTER COLUMN status TYPE cart_status USING status::cart_status;
ALTER TABLE carts ALTER COLUMN status SET DEFAULT 'ACTIVE'::cart_status;
CREATE UNIQUE INDEX idx_carts_one_active ON carts (user_id)
    WHERE status = 'ACTIVE'::cart_status AND user_id IS NOT NULL;

ALTER TABLE orders DROP CONSTRAINT orders_status_check;
ALTER TABLE orders DROP CONSTRAINT orders_order_type_check;
ALTER TABLE orders ALTER COLUMN status DROP DEFAULT;
ALTER TABLE orders ALTER COLUMN status TYPE order_status USING status::order_status;
ALTER TABLE orders ALTER COLUMN status SET DEFAULT 'PENDING'::order_status;
ALTER TABLE orders ALTER COLUMN order_type TYPE order_type USING order_type::order_type;
ALTER TABLE order_status_history
    ALTER COLUMN previous_status TYPE order_status USING previous_status::order_status,
    ALTER COLUMN new_status TYPE order_status USING new_status::order_status;

ALTER TABLE payment_transactions DROP CONSTRAINT payment_transactions_status_check;
ALTER TABLE payment_transactions DROP CONSTRAINT payment_transactions_type_check;
ALTER TABLE payment_transactions
    ALTER COLUMN status TYPE payment_transaction_status USING status::payment_transaction_status,
    ALTER COLUMN type TYPE payment_transaction_type USING type::payment_transaction_type;

ALTER TABLE promo_mega_sale_events ALTER COLUMN status TYPE promo_mega_sale_event_status
    USING status::promo_mega_sale_event_status;
ALTER TABLE promo_vouchers ALTER COLUMN status TYPE promo_voucher_status
    USING status::promo_voucher_status;
ALTER TABLE promo_event_sellers ALTER COLUMN status TYPE promo_event_seller_status
    USING status::promo_event_seller_status;
ALTER TABLE promo_customer_vouchers ALTER COLUMN status TYPE promo_customer_voucher_status
    USING status::promo_customer_voucher_status;
ALTER TABLE promo_voucher_sellers ALTER COLUMN status TYPE promo_voucher_seller_status
    USING status::promo_voucher_seller_status;
ALTER TABLE promo_voucher_usages ALTER COLUMN status TYPE promo_voucher_usage_status
    USING status::promo_voucher_usage_status;
