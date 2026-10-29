-- Flyway executes this migration in one PostgreSQL transaction.
DROP TABLE payment_transactions;

ALTER TABLE orders
DROP COLUMN memo_code;