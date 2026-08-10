--liquibase formatted sql

-- ROLE_MANAGER, which did not exist anywhere in the system (docs/finance/accounting.md §15.2).
--
-- Spring's hasAnyRole("ADMIN","MANAGER") expands to ROLE_ADMIN / ROLE_MANAGER, but the
-- seed granted the manager user ROLE_USER, USER, ROLE_ADMIN, ADMIN and a bare MANAGER —
-- no ROLE_MANAGER. So every "admin or manager" gate in ShopSec, DeliverySec and StorageSec
-- has been letting the manager user through *via ROLE_ADMIN*, and the MANAGER authority has
-- been decorative.
--
-- That works until someone creates a manager who is not also an admin, at which point they
-- are silently locked out of every endpoint that appears to be theirs. Fixed in the seed
-- rather than worked around at each gate: gating on the bare MANAGER authority instead
-- would work here and diverge from the three services that already use hasAnyRole.
--
-- The bare MANAGER authority is left in place. Nothing is known to read it, but removing an
-- authority is a different and riskier change than adding one, and this changeset's job is
-- to make the role mean what it says.

--changeset moldo:006-grant-role-manager
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM authorities WHERE authority = 'ROLE_MANAGER'
INSERT INTO authorities (user_id, authority)
VALUES ((SELECT id FROM users WHERE username = 'manager'), 'ROLE_MANAGER');
--rollback DELETE FROM authorities WHERE authority = 'ROLE_MANAGER';
