ALTER TABLE reputation_outcomes DROP CONSTRAINT reputation_outcomes_auction_id_key;
CREATE INDEX idx_reputation_outcome_auction ON reputation_outcomes(auction_id);
-- Preserve already applied timeout effects while changing their source from auction to order.
UPDATE reputation_sources s SET source_id=(s.fingerprint->>'orderId')::uuid
    WHERE change_type='AUCTION_PAYMENT_TIMEOUT';
UPDATE reputation_effects e SET source_id=(e.fingerprint->>'orderId')::uuid
    WHERE change_type='AUCTION_PAYMENT_TIMEOUT';
UPDATE reputation_history h SET source_id=o.order_id
    FROM reputation_outcomes o WHERE h.change_type='AUCTION_PAYMENT_TIMEOUT'
      AND h.source_id=o.auction_id AND o.outcome='PAYMENT_TIMEOUT';
