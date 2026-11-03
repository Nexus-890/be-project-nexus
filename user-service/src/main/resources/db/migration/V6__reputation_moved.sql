-- Flyway runs this cutover in one PostgreSQL transaction. Block concurrent
-- reputation writes while checking that the legacy tables are safe to remove.
LOCK TABLE ratings, user_reputations IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM ratings) THEN
        RAISE EXCEPTION
            'Existing ratings require verified backfill to rating_db before cutover';
    END IF;

    IF EXISTS (SELECT 1 FROM user_reputations) THEN
        RAISE EXCEPTION
            'Existing user reputations require verified backfill to rating_db before cutover';
    END IF;
END;
$$;

DROP TABLE ratings;
DROP TABLE user_reputations;
DROP TYPE trust_level;
