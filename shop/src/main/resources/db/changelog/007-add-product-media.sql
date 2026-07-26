--liquibase formatted sql

--changeset adrian:007-add-product-media
ALTER TABLE product ADD COLUMN media TEXT;
--rollback ALTER TABLE product DROP COLUMN media;
