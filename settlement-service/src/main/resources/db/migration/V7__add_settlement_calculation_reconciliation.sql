ALTER TABLE settlement_detail
    ADD COLUMN settlement_source_line_id uuid;

WITH candidates AS (
    SELECT
        detail.settlement_detail_id,
        source.settlement_source_line_id,
        count(*) OVER (
            PARTITION BY detail.settlement_detail_id
        ) AS detail_candidate_count,
        count(*) OVER (
            PARTITION BY source.settlement_source_line_id
        ) AS source_candidate_count
    FROM settlement_detail detail
    JOIN settlement_source_line source
      ON source.settlement_id = detail.settlement_id
     AND source.order_product_id = detail.order_product_id
     AND (
         (source.line_type = 'PAID' AND detail.line_type = 'SALE')
         OR (source.line_type = 'REFUND' AND detail.line_type = 'REFUND')
     )
     AND source.occurred_at = detail.occurred_at
     AND source.line_amount = abs(detail.line_amount)
)
UPDATE settlement_detail detail
SET settlement_source_line_id = candidate.settlement_source_line_id
FROM candidates candidate
WHERE detail.settlement_detail_id = candidate.settlement_detail_id
  AND candidate.detail_candidate_count = 1
  AND candidate.source_candidate_count = 1;

ALTER TABLE settlement_detail
    ADD CONSTRAINT fk_settlement_detail_source_line
        FOREIGN KEY (settlement_source_line_id)
        REFERENCES settlement_source_line(settlement_source_line_id);

ALTER TABLE settlement_detail
    ADD CONSTRAINT uk_settlement_detail_source_line
        UNIQUE (settlement_source_line_id);

CREATE TABLE settlement_calculation_reconciliation (
    reconciliation_id uuid NOT NULL,
    settlement_batch_id uuid NOT NULL,
    settlement_id uuid NOT NULL,
    status character varying(20) NOT NULL,
    expected_product_count integer NOT NULL,
    actual_product_count integer NOT NULL,
    expected_total_amount numeric(12,2) NOT NULL,
    actual_total_amount numeric(12,2) NOT NULL,
    expected_refund_amount numeric(12,2) NOT NULL,
    actual_refund_amount numeric(12,2) NOT NULL,
    expected_fee_total_amount numeric(12,2) NOT NULL,
    actual_fee_total_amount numeric(12,2) NOT NULL,
    expected_settlement_total_amount numeric(12,2) NOT NULL,
    actual_settlement_total_amount numeric(12,2) NOT NULL,
    expected_source_line_count integer NOT NULL,
    actual_detail_count integer NOT NULL,
    actual_linked_source_line_count integer NOT NULL,
    failure_reason character varying(2000),
    verified_at timestamp(6) without time zone NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    CONSTRAINT settlement_calculation_reconciliation_pkey
        PRIMARY KEY (reconciliation_id),
    CONSTRAINT settlement_calculation_reconciliation_status_check
        CHECK (status IN ('MATCHED', 'MISMATCHED'))
);

CREATE INDEX idx_settlement_calc_reconciliation_batch_verified
    ON settlement_calculation_reconciliation (
        settlement_batch_id,
        verified_at
    );
