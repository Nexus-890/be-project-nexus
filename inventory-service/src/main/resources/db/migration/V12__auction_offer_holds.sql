ALTER TABLE inventory_reservations ADD COLUMN offer_id uuid;
DROP INDEX uq_reservation_auction;
CREATE UNIQUE INDEX uq_reservation_first_auction ON inventory_reservations(auction_id)
    WHERE auction_id IS NOT NULL AND offer_id IS NULL;
CREATE UNIQUE INDEX uq_reservation_offer ON inventory_reservations(offer_id) WHERE offer_id IS NOT NULL;
CREATE UNIQUE INDEX uq_reservation_live_auction ON inventory_reservations(auction_id)
    WHERE auction_id IS NOT NULL AND status='HELD';
CREATE TABLE inventory_offer_outcomes (
    offer_id uuid PRIMARY KEY,
    auction_id uuid NOT NULL,
    outcome varchar(20) NOT NULL CHECK (outcome IN ('DECLINED','EXPIRED','REJECTED'))
);
CREATE TABLE inventory_auction_order_outcomes (
    order_id uuid PRIMARY KEY,
    outcome varchar(20) NOT NULL CHECK(outcome IN ('PAID','CANCELLED'))
);
CREATE TABLE inventory_pending_offer_holds (
    offer_id uuid PRIMARY KEY,
    auction_id uuid NOT NULL,
    bidder_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    expires_at timestamptz NOT NULL
);
