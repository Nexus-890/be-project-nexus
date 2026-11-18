ALTER TABLE orders ADD COLUMN offer_id uuid;
DROP INDEX uq_orders_auction_reference;
CREATE UNIQUE INDEX uq_orders_first_auction_reference ON orders(reference_id)
    WHERE order_type='AUCTION' AND offer_id IS NULL;
CREATE UNIQUE INDEX uq_orders_offer ON orders(offer_id) WHERE offer_id IS NOT NULL;
