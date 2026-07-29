CREATE TABLE settlement_delivery (
    settlement_delivery_id UUID PRIMARY KEY,
    delivery_request_id UUID NOT NULL UNIQUE,
    settlement_id UUID NOT NULL UNIQUE,
    settlement_batch_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    status_reason TEXT NULL,
    first_attempt_at TIMESTAMP(6) WITHOUT TIME ZONE NULL,
    last_attempt_at TIMESTAMP(6) WITHOUT TIME ZONE NULL,
    reconciled_at TIMESTAMP(6) WITHOUT TIME ZONE NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE,
    CONSTRAINT fk_settlement_delivery_settlement
        FOREIGN KEY (settlement_id) REFERENCES settlement(settlement_id),
    CONSTRAINT chk_settlement_delivery_status
        CHECK (status IN ('CALCULATED', 'RECONCILED', 'DELIVERY_FAILED', 'MISMATCH')),
    CONSTRAINT chk_settlement_delivery_attempt_count
        CHECK (attempt_count >= 0)
);

CREATE INDEX idx_settlement_delivery_batch_status
    ON settlement_delivery (settlement_batch_id, status);

DROP TABLE settlement_outbox_event;
