--liquibase formatted sql

--changeset moldo:006-user-avatar
-- No backfill: existing rows start at NONE and populate themselves. A Google
-- user's next /api/profiles/me call carries the `picture` claim, which writes
-- google_picture_url and flips them to GOOGLE (docs/users/user-pic.md §5).
ALTER TABLE user_profile
    ADD COLUMN avatar_object_key   VARCHAR(512),
    ADD COLUMN uploaded_avatar_url VARCHAR(1024),
    ADD COLUMN google_picture_url  VARCHAR(1024),
    ADD COLUMN avatar_source       VARCHAR(16) NOT NULL DEFAULT 'NONE';
--rollback ALTER TABLE user_profile DROP COLUMN avatar_object_key, DROP COLUMN uploaded_avatar_url, DROP COLUMN google_picture_url, DROP COLUMN avatar_source;
