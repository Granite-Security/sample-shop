--liquibase formatted sql

-- The public contact form (docs/users/messaging.md §11) writes into the same
-- user_message table the inbox already reads, so the manager sees a contact
-- submission next to everything else rather than in a second inbox nobody
-- remembers to open (D10).
--
-- A visitor who is not signed in has no username, and inventing a reserved one
-- the way order notices do (`system`) would lose the only way to answer them.
-- So sender_username becomes nullable — NULL means "nobody was signed in" — and
-- the two columns below carry what the visitor typed instead.
--
-- Nothing queries on sender_username IS NULL: the inbox selects by recipient,
-- and the Sent folder selects by an equality that NULL simply never matches,
-- which is the correct answer for a row with no account behind it.
--changeset moldo:009-contact-guest-sender
ALTER TABLE user_message ALTER COLUMN sender_username DROP NOT NULL;
ALTER TABLE user_message ADD COLUMN sender_name  VARCHAR(120);
ALTER TABLE user_message ADD COLUMN sender_email VARCHAR(255);
--rollback ALTER TABLE user_message DROP COLUMN sender_email;
--rollback ALTER TABLE user_message DROP COLUMN sender_name;
--rollback DELETE FROM user_message WHERE sender_username IS NULL;
--rollback ALTER TABLE user_message ALTER COLUMN sender_username SET NOT NULL;
