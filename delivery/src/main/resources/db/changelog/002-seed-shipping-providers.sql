--liquibase formatted sql

--changeset adrian:002-seed-shipping-providers
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM shipping_provider WHERE name = 'internal'
INSERT INTO shipping_provider (id, name, display_name, enabled)
VALUES
    (gen_random_uuid(), 'internal', 'Internal Simulator', true),
    (gen_random_uuid(), 'fedex',    'FedEx',    false),
    (gen_random_uuid(), 'ups',      'UPS',      false),
    (gen_random_uuid(), 'dhl',      'DHL',      false);
--rollback TRUNCATE shipping_provider;
