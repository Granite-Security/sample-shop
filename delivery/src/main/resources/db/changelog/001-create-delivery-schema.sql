--liquibase formatted sql

--changeset adrian:001-create-delivery
CREATE TABLE delivery (
    id UUID PRIMARY KEY,
    order_id BIGINT NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    provider VARCHAR(64),
    tracking_number VARCHAR(128),
    recipient_name VARCHAR(255),
    address_line1 VARCHAR(255),
    address_line2 VARCHAR(255),
    city VARCHAR(128),
    state VARCHAR(64),
    zip_code VARCHAR(16),
    country VARCHAR(64),
    estimated_delivery_date TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_delivery_order_id ON delivery(order_id);
--rollback DROP TABLE delivery;

--changeset adrian:001-create-delivery-event
CREATE TABLE delivery_event (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_delivery_event_status ON delivery_event(status);
--rollback DROP TABLE delivery_event;

--changeset adrian:001-create-delivery-tracking
CREATE TABLE delivery_tracking (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES delivery(id),
    status VARCHAR(32) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    description VARCHAR(512)
);
CREATE INDEX idx_delivery_tracking_delivery_id ON delivery_tracking(delivery_id);
--rollback DROP TABLE delivery_tracking;

--changeset adrian:001-create-shipping-provider
CREATE TABLE shipping_provider (
    id UUID PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
--rollback DROP TABLE shipping_provider;

--changeset adrian:001-create-webhook-event
CREATE TABLE webhook_event (
    id UUID PRIMARY KEY,
    provider VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processing_status VARCHAR(32) NOT NULL DEFAULT 'PROCESSED',
    UNIQUE (provider, idempotency_key)
);
--rollback DROP TABLE webhook_event;
