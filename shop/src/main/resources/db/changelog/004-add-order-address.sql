--liquibase formatted sql

--changeset junie:004-add-order-address
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'customer_order' AND column_name = 'recipient_name'
ALTER TABLE customer_order
    ADD COLUMN recipient_name VARCHAR(128) NOT NULL DEFAULT '',
    ADD COLUMN address_line1  VARCHAR(256) NOT NULL DEFAULT '',
    ADD COLUMN address_line2  VARCHAR(256) NOT NULL DEFAULT '',
    ADD COLUMN city           VARCHAR(128) NOT NULL DEFAULT '',
    ADD COLUMN state          VARCHAR(64)  NOT NULL DEFAULT '',
    ADD COLUMN zip_code       VARCHAR(20)  NOT NULL DEFAULT '',
    ADD COLUMN country        VARCHAR(64)  NOT NULL DEFAULT '';
--rollback ALTER TABLE customer_order DROP COLUMN recipient_name, DROP COLUMN address_line1, DROP COLUMN address_line2, DROP COLUMN city, DROP COLUMN state, DROP COLUMN zip_code, DROP COLUMN country;
