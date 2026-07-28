--liquibase formatted sql

-- The record of who did what. Lives with the orchestrator (profile), not the
-- executor (auth-server) — profile is where the admin is authenticated and
-- where the decision to block-instead-of-delete is made, so it is the only
-- place that knows the whole story. See docs/users/blocking-users.md D6.
--changeset moldo:005-create-admin-action
CREATE TABLE admin_action (
    id          BIGSERIAL    PRIMARY KEY,
    actor       VARCHAR(64)  NOT NULL,   -- admin username from the JWT
    action      VARCHAR(32)  NOT NULL,   -- BLOCK | UNBLOCK | DELETE
    target_user VARCHAR(64)  NOT NULL,
    outcome     VARCHAR(32)  NOT NULL,   -- DONE | BLOCKED_INSTEAD | FAILED
    order_count INT,
    reason      TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_action_target ON admin_action(target_user);
--rollback DROP TABLE admin_action;
