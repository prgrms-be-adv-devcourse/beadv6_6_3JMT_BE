-- audit_log: 결제/환불 종결 상태 전이(승인/실패/환불완료/환불실패) 이력을 append-only로 보존 (#484)

CREATE TABLE audit_log (
    id uuid NOT NULL,
    entity_type varchar(20) NOT NULL,
    entity_id uuid NOT NULL,
    event_type varchar(30) NOT NULL,
    actor_id uuid NOT NULL,
    new_status varchar(20) NOT NULL,
    detail text,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT audit_log_pkey PRIMARY KEY (id),
    CONSTRAINT audit_log_entity_type_check CHECK (entity_type IN ('PAYMENT', 'REFUND')),
    CONSTRAINT audit_log_event_type_check CHECK (event_type IN ('PAYMENT_APPROVED', 'PAYMENT_FAILED', 'PAYMENT_REFUNDED', 'PAYMENT_REFUND_FAILED'))
);

CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id);
