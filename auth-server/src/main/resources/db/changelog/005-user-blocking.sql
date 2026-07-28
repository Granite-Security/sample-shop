--liquibase formatted sql

-- No deleted_at: deletion removes the row entirely (see
-- docs/users/blocking-users.md D1). `enabled` stays the block mechanism —
-- JpaUserDetailsService already maps it to .disabled(!enabled) — and these two
-- columns only record who blocked the user and when.
--changeset moldo:005-add-user-blocking-columns
ALTER TABLE users ADD COLUMN blocked_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN blocked_by VARCHAR(64);
--rollback ALTER TABLE users DROP COLUMN blocked_at;
--rollback ALTER TABLE users DROP COLUMN blocked_by;
