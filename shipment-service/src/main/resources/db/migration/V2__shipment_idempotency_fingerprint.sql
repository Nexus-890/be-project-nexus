-- HTTP replay metadata belongs to the shipment identified by its unique key.
ALTER TABLE shipments ADD COLUMN idempotency_fingerprint varchar(64);
