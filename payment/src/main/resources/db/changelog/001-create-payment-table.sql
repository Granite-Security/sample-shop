--liquibase formatted sql

--changeset adrian:001-create-payment-table
CREATE TABLE payment (
    id                 UUID           PRIMARY KEY,
    order_id           BIGINT         NOT NULL,
    amount             NUMERIC(10,2)  NOT NULL,
    currency           VARCHAR(8)     NOT NULL DEFAULT 'USD',
    provider           VARCHAR(32)    NOT NULL,
    provider_payment_id VARCHAR(128),
    status             VARCHAR(32)    NOT NULL DEFAULT 'PENDING',
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_payment_order_id ON payment(order_id);
CREATE INDEX idx_payment_status ON payment(status);
--rollback DROP TABLE payment;
