--liquibase formatted sql

--changeset adrian:004-index-delivery-status
CREATE INDEX idx_delivery_status ON delivery(status);
CREATE INDEX idx_delivery_payment_status ON delivery(payment_status);
--rollback DROP INDEX idx_delivery_status; DROP INDEX idx_delivery_payment_status;
