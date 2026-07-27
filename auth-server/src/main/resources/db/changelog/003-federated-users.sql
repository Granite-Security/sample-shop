--liquibase formatted sql

--changeset moldo:003-users-provider-columns
ALTER TABLE users ADD COLUMN provider    VARCHAR(32)  NOT NULL DEFAULT 'LOCAL';
ALTER TABLE users ADD COLUMN provider_id VARCHAR(255);
CREATE UNIQUE INDEX uk_users_provider_id ON users(provider, provider_id)
    WHERE provider_id IS NOT NULL;
--rollback DROP INDEX uk_users_provider_id; ALTER TABLE users DROP COLUMN provider_id; ALTER TABLE users DROP COLUMN provider;
