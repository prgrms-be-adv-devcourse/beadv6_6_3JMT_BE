-- payment.status CHECK 제약을 현재 PaymentStatus 엔티티(REFUNDING/REFUNDED → PARTIAL_REFUNDED/ALL_REFUNDED)에 맞춘다.
ALTER TABLE payment DROP CONSTRAINT IF EXISTS payment_status_check;
ALTER TABLE payment
    ADD CONSTRAINT payment_status_check
    CHECK (status IN ('READY', 'REQUESTED', 'PAID', 'FAILED', 'PARTIAL_REFUNDED', 'ALL_REFUNDED', 'UNKNOWN'));

-- 부분 환불(OrderProduct 단위) 도입 — order_product_id 필수화 + 상품당 1회 환불만 허용.
DELETE FROM refund WHERE order_product_id IS NULL;
ALTER TABLE refund ALTER COLUMN order_product_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_refund_payment_order_product ON refund (payment_id, order_product_id);
