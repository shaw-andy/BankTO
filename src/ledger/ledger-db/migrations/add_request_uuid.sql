-- BANKTO-2091: persist client request identity for durable duplicate protection.
-- Apply to existing ledger-db volumes before deploying application changes.
-- New environments receive this column from initdb/0_init_tables.sql.

ALTER TABLE TRANSACTIONS ADD COLUMN IF NOT EXISTS REQUEST_UUID VARCHAR(255);
CREATE UNIQUE INDEX IF NOT EXISTS TRANSACTIONS_REQUEST_UUID_KEY
    ON TRANSACTIONS (REQUEST_UUID);
