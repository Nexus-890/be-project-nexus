ALTER TABLE ratings ADD COLUMN rater_name varchar(16);

CREATE INDEX idx_ratings_recipient_created ON ratings (to_user_id, created_at DESC, id DESC);
