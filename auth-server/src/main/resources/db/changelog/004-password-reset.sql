--liquibase formatted sql

--changeset moldo:004-create-password-reset-token
CREATE TABLE password_reset_token (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64)  NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ  NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_password_reset_token_user_id ON password_reset_token(user_id);
--rollback DROP TABLE password_reset_token;
