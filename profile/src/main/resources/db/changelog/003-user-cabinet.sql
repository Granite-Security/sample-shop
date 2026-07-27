--liquibase formatted sql

--changeset moldo:003-add-display-name
ALTER TABLE user_profile ADD COLUMN display_name VARCHAR(64);
--rollback ALTER TABLE user_profile DROP COLUMN display_name;

--changeset moldo:003-create-user-file
CREATE TABLE user_file (
    id           BIGSERIAL    PRIMARY KEY,
    username     VARCHAR(64)  NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    object_key   VARCHAR(512) NOT NULL UNIQUE,
    url          VARCHAR(1024) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes   BIGINT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_file_username ON user_file(username);
--rollback DROP TABLE user_file;
