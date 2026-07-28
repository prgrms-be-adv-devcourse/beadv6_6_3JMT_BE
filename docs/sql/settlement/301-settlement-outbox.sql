CREATE TABLE IF NOT EXISTS settlement_outbox_event (
    event_id UUID PRIMARY KEY,
    settlement_batch_id UUID NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    topic VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    occurred_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    last_attempted_at TIMESTAMP WITHOUT TIME ZONE,
    last_failure_reason VARCHAR(1000),
    failed_at TIMESTAMP WITHOUT TIME ZONE,
    published_at TIMESTAMP WITHOUT TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT chk_settlement_outbox_status
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT chk_settlement_outbox_retry_count
        CHECK (retry_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_settlement_outbox_status_attempted_occurred
    ON settlement_outbox_event (status, last_attempted_at, occurred_at, event_id);

CREATE INDEX IF NOT EXISTS idx_settlement_outbox_batch_status_occurred
    ON settlement_outbox_event (settlement_batch_id, status, occurred_at, event_id);

CREATE INDEX IF NOT EXISTS idx_settlement_outbox_aggregate_id
    ON settlement_outbox_event (aggregate_id);
