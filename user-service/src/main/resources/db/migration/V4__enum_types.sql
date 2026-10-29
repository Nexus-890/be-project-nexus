CREATE TYPE user_status AS ENUM ('ACTIVE', 'LOCKED', 'DELETED');
CREATE TYPE gender AS ENUM ('MALE', 'FEMALE', 'OTHER');
CREATE TYPE trust_level AS ENUM ('NEW', 'BASIC', 'TRUSTED', 'TOP');

ALTER TABLE users DROP CONSTRAINT users_status_check;
ALTER TABLE users DROP CONSTRAINT users_gender_check;
ALTER TABLE users ALTER COLUMN status DROP DEFAULT;
ALTER TABLE users ALTER COLUMN status TYPE user_status USING status::user_status;
ALTER TABLE users ALTER COLUMN status SET DEFAULT 'ACTIVE'::user_status;
ALTER TABLE users ALTER COLUMN gender TYPE gender USING gender::gender;

ALTER TABLE user_reputations DROP CONSTRAINT user_reputations_trust_level_check;
ALTER TABLE user_reputations ALTER COLUMN trust_level DROP DEFAULT;
ALTER TABLE user_reputations ALTER COLUMN trust_level TYPE trust_level USING trust_level::trust_level;
ALTER TABLE user_reputations ALTER COLUMN trust_level SET DEFAULT 'NEW'::trust_level;
