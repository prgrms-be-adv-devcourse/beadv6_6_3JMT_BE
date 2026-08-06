-- PARTIAL_REFUNDED/ALL_REFUNDED 제거 — 환불 발생 여부는 refund 테이블로만 판단하고
-- payment.status는 승인 이후 PAID를 계속 유지한다.
UPDATE payment SET status = 'PAID' WHERE status IN ('PARTIAL_REFUNDED', 'ALL_REFUNDED');

ALTER TABLE payment DROP CONSTRAINT IF EXISTS payment_status_check;
ALTER TABLE payment
    ADD CONSTRAINT payment_status_check
    CHECK (status IN ('READY', 'REQUESTED', 'PAID', 'FAILED', 'UNKNOWN'));

ALTER TABLE payment DROP COLUMN IF EXISTS refunded_at;
