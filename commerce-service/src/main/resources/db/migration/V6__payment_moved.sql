-- Flyway executes this migration in one PostgreSQL transaction.
LOCK TABLE orders, payment_transactions IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM payment_transactions) THEN
        RAISE EXCEPTION
            'Payment transactions require verified backfill before cutover';
END IF;

    IF EXISTS (SELECT 1 FROM orders) THEN
        RAISE EXCEPTION
            'Existing orders require verified order-group/payment backfill before cutover';
END IF;
END;
$$;

DROP TABLE payment_transactions;

ALTER TABLE orders
DROP COLUMN memo_code;