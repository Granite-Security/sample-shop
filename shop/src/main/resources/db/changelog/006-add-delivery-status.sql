--liquibase formatted sql

--changeset adrian:006-add-delivery-status
ALTER TABLE customer_order ADD COLUMN delivery_status VARCHAR(32);
--rollback ALTER TABLE customer_order DROP COLUMN delivery_status;
