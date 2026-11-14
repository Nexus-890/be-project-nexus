ALTER TABLE auctions ADD COLUMN leader_bidder_name text;
ALTER TABLE bids ADD COLUMN bidder_name text NOT NULL DEFAULT '***';

UPDATE auctions SET leader_bidder_name = '***' WHERE leader_bidder_id IS NOT NULL;
