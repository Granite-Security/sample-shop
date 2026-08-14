--liquibase formatted sql

--changeset adrian:005-index-delivery-created-at
-- The back offices filter shipments by a created-at date range and sort by it,
-- both server-side since pagination landed. Without this the range scan reads
-- every row before discarding, which is the cost pagination was meant to remove.
CREATE INDEX idx_delivery_created_at ON delivery(created_at);
--rollback DROP INDEX idx_delivery_created_at;
