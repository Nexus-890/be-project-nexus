CREATE TYPE auction_status AS ENUM (
    'CREATED', 'SCHEDULED', 'ACTIVE', 'ENDED', 'WON', 'SETTLED', 'FAILED',
    'PAYMENT_TIMEOUT', 'CANCELLED'
);
CREATE TYPE auction_visibility AS ENUM ('PUBLIC', 'RESTRICTED');
CREATE TYPE bid_status AS ENUM ('PLACED', 'OUTBID', 'WON', 'CANCELLED');

ALTER TABLE auctions DROP CONSTRAINT auctions_status_check,
    DROP CONSTRAINT auctions_visibility_check,
    ALTER COLUMN status DROP DEFAULT,
    ALTER COLUMN visibility DROP DEFAULT;
ALTER TABLE auctions
    ALTER COLUMN status TYPE auction_status USING status::auction_status,
    ALTER COLUMN visibility TYPE auction_visibility USING visibility::auction_visibility;
ALTER TABLE auctions ALTER COLUMN status SET DEFAULT 'CREATED'::auction_status,
    ALTER COLUMN visibility SET DEFAULT 'PUBLIC'::auction_visibility;

ALTER TABLE bids DROP CONSTRAINT bids_status_check, ALTER COLUMN status DROP DEFAULT;
ALTER TABLE bids ALTER COLUMN status TYPE bid_status USING status::bid_status;
ALTER TABLE bids ALTER COLUMN status SET DEFAULT 'PLACED'::bid_status;
