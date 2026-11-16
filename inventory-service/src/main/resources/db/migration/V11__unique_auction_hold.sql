CREATE UNIQUE INDEX uq_reservation_auction
    ON inventory_reservations (auction_id)
    WHERE auction_id IS NOT NULL;
ALTER TABLE inventory_reservations ADD COLUMN auction_winner_id uuid;
