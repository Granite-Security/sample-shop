--liquibase formatted sql

-- balance's outbox (docs/finance/accounting.md §4.2, step 3).
--
-- balance published nothing until now, and it owns the two facts with no other
-- source: gift issuance, and the funding split of every spend. The accounting
-- service cannot ask for them — it never calls another service (D25) — so they
-- have to ride a topic.
--
-- An outbox rather than a direct produce-on-write, and not because it is the house
-- style: the row commits in the same transaction as the ledger entries, so a
-- movement and its announcement cannot diverge. A ledger that moved money without
-- announcing it, or announced a movement it then rolled back, is exactly the class
-- of bug a general ledger cannot recover from — the books have no way to learn that
-- the second one never happened.
--
-- This is also why accounting may consume this topic at all. identity.events is
-- fire-and-forget with accepted loss, which is fine for a courtesy email and fatal
-- for a ledger (D27); every accounting-relevant fact must ride a topic whose
-- producer writes its outbox row in the same transaction as the state change.

--changeset moldo:004-create-outbox
CREATE TABLE outbox (
    id             UUID         PRIMARY KEY,
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,   -- the username; also the Kafka key
    event_type     VARCHAR(128) NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The relay polls PENDING oldest-first; the partial index is the whole query, and
-- it stays small because SENT rows fall out of it.
CREATE INDEX idx_outbox_pending ON outbox (created_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_created_at ON outbox (created_at);
--rollback DROP TABLE outbox;
