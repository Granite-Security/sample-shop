--liquibase formatted sql

-- Balance's equivalent of a Stripe PaymentIntent or a PayPal order: the object
-- payment holds an id for, so balance can be an ordinary PaymentProvider rather
-- than a special case wired into checkout (docs/finance/finance.md §4.1).
--
-- CREATED means funds were checked and NOTHING moved. Only CAPTURED has ledger
-- rows behind it (D7) — an abandoned checkout leaves a CREATED row and no money
-- moved, which is why there is no hold to expire.
--changeset moldo:002-create-balance-intent
CREATE TABLE balance_intent (
    id            UUID         PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    amount_minor  BIGINT       NOT NULL,
    order_id      BIGINT,
    status        VARCHAR(16)  NOT NULL,   -- CREATED | CAPTURED | FAILED | REFUNDED
    transfer_id   UUID,                    -- set on capture; links to ledger_entry
    refund_id     UUID,                    -- set on refund
    decline_reason VARCHAR(200),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_balance_intent_order ON balance_intent(order_id);
CREATE INDEX idx_balance_intent_username ON balance_intent(username);
--rollback DROP TABLE balance_intent;
