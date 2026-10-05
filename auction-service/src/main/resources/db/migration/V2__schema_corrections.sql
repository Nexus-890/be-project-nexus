ALTER TABLE auctions
ALTER COLUMN starting_price TYPE numeric(19,2)
        USING starting_price::numeric(19,2),
    ALTER COLUMN bid_increment TYPE numeric(19,2)
        USING bid_increment::numeric(19,2),
    ALTER COLUMN current_price TYPE numeric(19,2)
        USING current_price::numeric(19,2),
    ALTER COLUMN final_price TYPE numeric(19,2)
        USING final_price::numeric(19,2);

ALTER TABLE bids
ALTER COLUMN amount TYPE numeric(19,2)
        USING amount::numeric(19,2);