--liquibase formatted sql

--changeset adrian:004-create-refund-table
CREATE TABLE refund (
    id               UUID           PRIMARY KEY,
    order_id         BIGINT         NOT NULL,
    payment_id       UUID           NOT NULL,
    stripe_refund_id VARCHAR(64),
    amount           NUMERIC(10,2)  NOT NULL,
    status           VARCHAR(32)    NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_refund_order_id ON refund(order_id);
--rollback DROP TABLE refund;
