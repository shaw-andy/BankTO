-- Copyright 2026 BankTO
--
-- Additive migration for existing ledger-db deployments (BANKTO-2091).
-- New installs receive REQUEST_UUID from 0_init_tables.sql.
-- PostgreSQL UNIQUE allows multiple NULL request identities.

ALTER TABLE TRANSACTIONS
    ADD COLUMN IF NOT EXISTS REQUEST_UUID VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS transactions_request_uuid_key
    ON TRANSACTIONS (REQUEST_UUID);
