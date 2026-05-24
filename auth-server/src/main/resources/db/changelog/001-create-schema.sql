--liquibase formatted sql

--changeset junie:001-create-users-table
CREATE TABLE users (
    id          BIGSERIAL    PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL UNIQUE,
    password    VARCHAR(72)  NOT NULL,
    email       VARCHAR(255) NOT NULL UNIQUE,
    first_name  VARCHAR(64),
    last_name   VARCHAR(64),
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_users_username ON users(username);
--rollback DROP TABLE users;

--changeset junie:001-create-authorities-table
CREATE TABLE authorities (
    id        BIGSERIAL   PRIMARY KEY,
    user_id   BIGINT      NOT NULL,
    authority VARCHAR(64) NOT NULL,
    CONSTRAINT fk_authorities_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_authorities_user_authority UNIQUE (user_id, authority)
);
CREATE INDEX idx_authorities_user_id ON authorities(user_id);
--rollback DROP TABLE authorities;
