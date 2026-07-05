--liquibase formatted sql

--changeset adrian:001-create-user-profile
CREATE TABLE user_profile (
    id         BIGSERIAL    PRIMARY KEY,
    username   VARCHAR(64)  NOT NULL UNIQUE,
    email      VARCHAR(255),
    first_name VARCHAR(64),
    last_name  VARCHAR(64),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_profile_username ON user_profile(username);
--rollback DROP TABLE user_profile;

--changeset adrian:001-create-delivery-address
CREATE TABLE delivery_address (
    id             BIGSERIAL    PRIMARY KEY,
    username       VARCHAR(64)  NOT NULL,
    label          VARCHAR(128),
    recipient_name VARCHAR(255) NOT NULL,
    address_line1  VARCHAR(255) NOT NULL,
    address_line2  VARCHAR(255),
    city           VARCHAR(128) NOT NULL,
    state          VARCHAR(64),
    zip_code       VARCHAR(16)  NOT NULL,
    country        VARCHAR(64)  NOT NULL,
    is_default     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_delivery_address_username ON delivery_address(username);
--rollback DROP TABLE delivery_address;
