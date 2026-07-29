ALTER TABLE settlement_batch
    DROP CONSTRAINT IF EXISTS settlement_batch_status_check;

ALTER TABLE settlement_batch
    ADD CONSTRAINT settlement_batch_status_check
    CHECK (status IN (
        'PROCESSING',
        'COMPLETED',
        'FAILED',
        'RECONCILIATION_FAILED',
        'RETRY_REQUESTED',
        'CANCELLED'
    ));
