ALTER TYPE notification_channel ADD VALUE IF NOT EXISTS 'IN_APP';
ALTER TABLE notifications ADD COLUMN in_app_enabled boolean NOT NULL DEFAULT true;

-- The cursor is committed with the inbox write, including reads made on another instance.
CREATE TABLE notification_inbox_versions (
    user_id uuid PRIMARY KEY,
    revision bigint NOT NULL DEFAULT 0
);
INSERT INTO notification_inbox_versions (user_id, revision)
SELECT user_id, 1 FROM notifications GROUP BY user_id;

CREATE FUNCTION advance_notification_inbox() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF NOT OLD.in_app_enabled THEN RETURN OLD; END IF;
        INSERT INTO notification_inbox_versions (user_id, revision) VALUES (OLD.user_id, 1)
        ON CONFLICT (user_id) DO UPDATE SET revision = notification_inbox_versions.revision + 1;
        RETURN OLD;
    END IF;
    IF NOT NEW.in_app_enabled THEN RETURN NEW; END IF;
    IF TG_OP = 'UPDATE' AND NEW.read_at IS NOT DISTINCT FROM OLD.read_at THEN RETURN NEW; END IF;
    INSERT INTO notification_inbox_versions (user_id, revision) VALUES (NEW.user_id, 1)
    ON CONFLICT (user_id) DO UPDATE SET revision = notification_inbox_versions.revision + 1;
    RETURN NEW;
END $$;
CREATE TRIGGER notification_inbox_changed AFTER INSERT OR UPDATE OF read_at OR DELETE ON notifications
FOR EACH ROW EXECUTE FUNCTION advance_notification_inbox();
CREATE INDEX idx_deliveries_admin_time ON notification_deliveries (created_at DESC, id DESC);
CREATE INDEX idx_delivery_attempts_error ON notification_delivery_attempts (error_code, delivery_id)
WHERE error_code IS NOT NULL;
