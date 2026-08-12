--liquibase formatted sql

--changeset moldo:011-user-file-shared
-- "Publish to profile" (docs/profile/public-profile.md §11). A flag on the file
-- row, not a copy of the URL onto the profile: one source of truth, so deleting
-- the file removes it from the public page with no cleanup path to maintain.
ALTER TABLE user_file ADD COLUMN shared BOOLEAN NOT NULL DEFAULT FALSE;

-- Serves the public listing, which is always "this user's shared files".
CREATE INDEX idx_user_file_shared ON user_file(username) WHERE shared;
--rollback DROP INDEX idx_user_file_shared; ALTER TABLE user_file DROP COLUMN shared;
