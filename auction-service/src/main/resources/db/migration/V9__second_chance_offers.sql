ALTER TABLE auctions ADD COLUMN offer_id uuid;
CREATE TABLE auction_offers (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    auction_id uuid NOT NULL REFERENCES auctions(id),
    bidder_id uuid NOT NULL,
    bidder_name varchar(255) NOT NULL,
    amount numeric(19,2) NOT NULL CHECK (amount > 0),
    status varchar(20) NOT NULL CHECK (status IN ('OFFERED','ACCEPTED','DECLINED','EXPIRED')),
    stock_ready boolean NOT NULL DEFAULT false,
    offered_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > offered_at),
    responded_at timestamptz,
    order_id uuid,
    payment_due_at timestamptz,
    created_by uuid NOT NULL,
    updated_by uuid NOT NULL,
    UNIQUE (auction_id, bidder_id)
);
CREATE UNIQUE INDEX uq_auction_open_offer ON auction_offers(auction_id) WHERE status='OFFERED';
CREATE INDEX idx_offers_buyer ON auction_offers(bidder_id, offered_at DESC, id DESC);
CREATE TABLE auction_payment_timeouts (
    order_id uuid PRIMARY KEY,
    auction_id uuid NOT NULL REFERENCES auctions(id),
    winner_id uuid NOT NULL,
    payment_due_at timestamptz NOT NULL,
    timed_out_at timestamptz NOT NULL
);
CREATE TABLE auction_pending_timeouts (order_id uuid PRIMARY KEY, payload jsonb NOT NULL);
