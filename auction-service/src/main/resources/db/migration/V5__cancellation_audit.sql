-- Nullable for historical cancellations whose actor/time were not recorded.
ALTER TABLE auctions ADD COLUMN cancelled_actor_id uuid;
ALTER TABLE auctions ADD COLUMN cancelled_at timestamptz;
