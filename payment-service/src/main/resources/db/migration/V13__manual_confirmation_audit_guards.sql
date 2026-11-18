ALTER TABLE payout_history ADD COLUMN evidence_ids uuid[] NOT NULL DEFAULT '{}';
CREATE UNIQUE INDEX uq_manual_compensation_once ON payment_transactions(type,linked_transaction_id)
    WHERE provider='manual' AND linked_transaction_id IS NOT NULL AND type IN ('PAYOUT_RETURN','PAYOUT_CORRECTION');

CREATE FUNCTION preserve_manual_confirmation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.confirmed_at IS NOT NULL AND (
        ROW(NEW.confirmed_by,NEW.confirmed_at,NEW.evidence_ids) IS DISTINCT FROM
        ROW(OLD.confirmed_by,OLD.confirmed_at,OLD.evidence_ids)
        OR (OLD.transaction_id IS NOT NULL AND NEW.transaction_id IS DISTINCT FROM OLD.transaction_id)) THEN
        RAISE EXCEPTION 'Manual confirmation evidence is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER manual_confirmation_immutable BEFORE UPDATE ON manual_payout_attempts
    FOR EACH ROW EXECUTE FUNCTION preserve_manual_confirmation();
