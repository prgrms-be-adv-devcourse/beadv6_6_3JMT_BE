ALTER TABLE payment RENAME COLUMN pg_tx_id TO payment_key;
ALTER INDEX uk_payment_pg_tx_id RENAME TO uk_payment_payment_key;
