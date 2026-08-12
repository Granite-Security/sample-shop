--liquibase formatted sql

--changeset moldo:010-public-profile
-- No backfill: every existing profile starts private with no handle
-- (docs/profile/public-profile.md step 1).
ALTER TABLE user_profile
    ADD COLUMN handle         VARCHAR(32),
    ADD COLUMN bio            VARCHAR(500),
    ADD COLUMN public_profile BOOLEAN NOT NULL DEFAULT FALSE;

-- Unique regardless of public_profile (D2). Un-publishing must not release the
-- handle, or a URL already handed out later resolves to a different person.
-- Handles are stored lowercased, so a plain unique index is enough.
CREATE UNIQUE INDEX uq_user_profile_handle ON user_profile(handle) WHERE handle IS NOT NULL;
--rollback DROP INDEX uq_user_profile_handle; ALTER TABLE user_profile DROP COLUMN handle, DROP COLUMN bio, DROP COLUMN public_profile;
