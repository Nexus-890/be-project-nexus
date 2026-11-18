-- The recorded response may contain Admin-only case details. Replays must retain that access requirement.
ALTER TABLE manual_payout_commands ADD COLUMN actor_role varchar(20) NOT NULL DEFAULT 'UNKNOWN'
    CHECK(actor_role IN ('ADMIN','SUPPORT_STAFF','SELLER','UNKNOWN'));
