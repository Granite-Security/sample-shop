--liquibase formatted sql

-- Step 2 of docs/payment/refactor-payment.md: make the schema provider-neutral.
-- The columns were always meant to be generic (001 created provider /
-- provider_payment_id); 003 shadowed them with Stripe-specific ones that became
-- the columns actually used. This consolidates onto the generic set.

--changeset adrian:005-backfill-provider-payment-id
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'payment' AND column_name = 'stripe_payment_intent_id'
UPDATE payment SET provider_payment_id = stripe_payment_intent_id
  WHERE provider_payment_id IS NULL AND stripe_payment_intent_id IS NOT NULL;
--rollback UPDATE payment SET stripe_payment_intent_id = provider_payment_id WHERE stripe_payment_intent_id IS NULL AND provider_payment_id IS NOT NULL;

--changeset adrian:005-drop-stripe-payment-intent-id
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'payment' AND column_name = 'stripe_payment_intent_id'
ALTER TABLE payment DROP COLUMN stripe_payment_intent_id;
--rollback ALTER TABLE payment ADD COLUMN stripe_payment_intent_id VARCHAR(128);

-- client_secret becomes provider_payload: whatever the frontend needs to complete
-- the payment. A redirect provider hands over a URL, not a secret, so the column
-- is widened to TEXT, kept nullable, and its contents become JSON rather than a
-- bare string — transformed here so the column never holds two formats at once.
--changeset adrian:005-rename-client-secret-to-provider-payload
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'payment' AND column_name = 'client_secret'
ALTER TABLE payment RENAME COLUMN client_secret TO provider_payload;
ALTER TABLE payment ALTER COLUMN provider_payload TYPE TEXT;
UPDATE payment SET provider_payload = json_build_object('clientSecret', provider_payload)::text
  WHERE provider_payload IS NOT NULL AND provider_payload NOT LIKE '{%';
--rollback UPDATE payment SET provider_payload = provider_payload::json ->> 'clientSecret' WHERE provider_payload LIKE '{%';
--rollback ALTER TABLE payment ALTER COLUMN provider_payload TYPE VARCHAR(255);
--rollback ALTER TABLE payment RENAME COLUMN provider_payload TO client_secret;

--changeset adrian:005-rename-stripe-refund-id
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'refund' AND column_name = 'stripe_refund_id'
ALTER TABLE refund RENAME COLUMN stripe_refund_id TO provider_refund_id;
--rollback ALTER TABLE refund RENAME COLUMN provider_refund_id TO stripe_refund_id;

--changeset adrian:005-rename-stripe-event-table
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'stripe_event'
ALTER TABLE stripe_event RENAME TO provider_event;
ALTER TABLE provider_event RENAME COLUMN stripe_event_id TO provider_event_id;
ALTER INDEX idx_stripe_event_stripe_event_id RENAME TO idx_provider_event_provider_event_id;
--rollback ALTER INDEX idx_provider_event_provider_event_id RENAME TO idx_stripe_event_stripe_event_id;
--rollback ALTER TABLE provider_event RENAME COLUMN provider_event_id TO stripe_event_id;
--rollback ALTER TABLE provider_event RENAME TO stripe_event;

-- Every row in this table today came from Stripe, so the default is a statement of
-- fact rather than a guess. Dedupe becomes (provider, event_id): two providers can
-- legitimately issue the same event id string.
--changeset adrian:005-add-provider-to-provider-event
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'provider_event' AND column_name = 'provider'
ALTER TABLE provider_event ADD COLUMN provider VARCHAR(32) NOT NULL DEFAULT 'stripe';
DROP INDEX IF EXISTS idx_provider_event_provider_event_id;
CREATE UNIQUE INDEX idx_provider_event_provider_id ON provider_event(provider, provider_event_id);
--rollback DROP INDEX idx_provider_event_provider_id;
--rollback CREATE UNIQUE INDEX idx_provider_event_provider_event_id ON provider_event(provider_event_id);
--rollback ALTER TABLE provider_event DROP COLUMN provider;

-- One row per attempt to take the money, so "declined at Stripe, retried at PayPal"
-- keeps an audit trail instead of overwriting provider_payment_id in place. Also
-- what makes "refundable for the amount actually paid" answerable: the succeeded
-- attempt records what was captured, which payment.amount does not after a retry
-- at a different amount.
--changeset adrian:005-create-payment-attempt
CREATE TABLE payment_attempt (
    id                  UUID           PRIMARY KEY,
    payment_id          UUID           NOT NULL REFERENCES payment(id) ON DELETE CASCADE,
    order_id            BIGINT         NOT NULL,
    provider            VARCHAR(32)    NOT NULL,
    provider_payment_id VARCHAR(128),
    provider_payload    TEXT,
    amount              NUMERIC(10,2)  NOT NULL,
    currency            VARCHAR(8)     NOT NULL,
    status              VARCHAR(32)    NOT NULL,
    decline_reason      VARCHAR(256),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_attempt_order ON payment_attempt(order_id);
CREATE INDEX idx_payment_attempt_payment ON payment_attempt(payment_id);

-- Double-charge guard: an order may have many attempts but at most one that took money.
CREATE UNIQUE INDEX idx_payment_attempt_one_success
    ON payment_attempt(order_id) WHERE status = 'SUCCEEDED';
--rollback DROP TABLE payment_attempt;

--changeset adrian:005-add-current-attempt-id
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'payment' AND column_name = 'current_attempt_id'
ALTER TABLE payment ADD COLUMN current_attempt_id UUID REFERENCES payment_attempt(id);
--rollback ALTER TABLE payment DROP COLUMN current_attempt_id;

-- One attempt per existing payment, carrying that payment's own state forward.
-- gen_random_uuid() is pgcrypto/core since PG13; this deployment runs 17.
--changeset adrian:005-backfill-payment-attempts
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM payment_attempt
INSERT INTO payment_attempt (id, payment_id, order_id, provider, provider_payment_id,
                             provider_payload, amount, currency, status, created_at, updated_at)
SELECT gen_random_uuid(), p.id, p.order_id, p.provider, p.provider_payment_id,
       p.provider_payload, p.amount, p.currency, p.status, p.created_at, p.updated_at
FROM payment p;

UPDATE payment p SET current_attempt_id = a.id
FROM payment_attempt a WHERE a.payment_id = p.id;
--rollback UPDATE payment SET current_attempt_id = NULL;
--rollback DELETE FROM payment_attempt;
