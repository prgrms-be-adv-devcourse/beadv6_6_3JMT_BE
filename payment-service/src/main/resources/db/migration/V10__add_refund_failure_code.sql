-- refund: PG 환불 실패 코드(또는 내부 사유 코드) 보존 (#539)

ALTER TABLE refund ADD COLUMN failure_code varchar(50);
