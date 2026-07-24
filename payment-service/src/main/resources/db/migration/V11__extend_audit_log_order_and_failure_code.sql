-- audit_log: order_id/failure_code 확장, event_type 값 집합을 결제/환불 대칭 구조로 정리 (#539)

ALTER TABLE audit_log ADD COLUMN order_id uuid;
ALTER TABLE audit_log ADD COLUMN failure_code varchar(50);

UPDATE audit_log a
SET order_id = p.order_id
FROM payment p
WHERE a.entity_type = 'PAYMENT' AND a.entity_id = p.id;

UPDATE audit_log a
SET order_id = p.order_id
FROM refund r
JOIN payment p ON r.payment_id = p.id
WHERE a.entity_type = 'REFUND' AND a.entity_id = r.id;

UPDATE audit_log SET event_type = 'REFUND_COMPLETED' WHERE event_type = 'PAYMENT_REFUNDED';
UPDATE audit_log SET event_type = 'REFUND_FAILED' WHERE event_type = 'PAYMENT_REFUND_FAILED';

ALTER TABLE audit_log ALTER COLUMN order_id SET NOT NULL;

ALTER TABLE audit_log DROP CONSTRAINT audit_log_event_type_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_event_type_check
    CHECK (event_type IN (
        'PAYMENT_REQUESTED', 'PAYMENT_APPROVED', 'PAYMENT_FAILED',
        'REFUND_REQUESTED', 'REFUND_COMPLETED', 'REFUND_FAILED'
    ));

CREATE INDEX idx_audit_log_order ON audit_log (order_id);
