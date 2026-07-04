--liquibase formatted sql

--changeset adrian:003-add-stripe-payment-columns
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'payment' AND column_name = 'stripe_payment_intent_id'
ALTER TABLE payment ADD COLUMN stripe_payment_intent_id VARCHAR(128);
--rollback ALTER TABLE payment DROP COLUMN stripe_payment_intent_id;

--changeset adrian:003-add-client-secret-column
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'payment' AND column_name = 'client_secret'
ALTER TABLE payment ADD COLUMN client_secret VARCHAR(255);
--rollback ALTER TABLE payment DROP COLUMN client_secret;

--changeset adrian:003-create-stripe-event-table
CREATE TABLE stripe_event (
    id               UUID         PRIMARY KEY,
    stripe_event_id  VARCHAR(128) NOT NULL,
    type             VARCHAR(64)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at     TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_stripe_event_stripe_event_id ON stripe_event(stripe_event_id);
--rollback DROP TABLE stripe_event;
