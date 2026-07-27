--liquibase formatted sql

--changeset moldo:004-add-user-file-content-hash
ALTER TABLE user_file ADD COLUMN content_hash VARCHAR(64);
-- NULLs are treated as distinct by a Postgres unique index, so existing
-- rows from before this column existed (content_hash = NULL) never
-- collide with each other or with new hashed rows.
CREATE UNIQUE INDEX uk_user_file_username_content_hash ON user_file(username, content_hash);
--rollback DROP INDEX uk_user_file_username_content_hash; ALTER TABLE user_file DROP COLUMN content_hash;
