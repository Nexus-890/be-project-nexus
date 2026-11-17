-- Guard business identity even when AuctionWon is delivered under a new eventId.
CREATE UNIQUE INDEX uq_orders_auction_reference ON orders (order_type, reference_id)
    WHERE order_type = 'AUCTION' AND reference_id IS NOT NULL;
