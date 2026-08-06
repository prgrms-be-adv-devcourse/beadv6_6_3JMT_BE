-- 환불 dedup 키를 (payment_id, order_product_id)에서 refundRequestId로 전환.
-- 동일 order_product에 대한 재환불(복수 부분환불)을 허용하고, 죽은 컬럼(order_product_id, user_id)을 정리한다(#398).

ALTER TABLE refund ADD COLUMN refund_request_id uuid;
UPDATE refund SET refund_request_id = gen_random_uuid() WHERE refund_request_id IS NULL;
ALTER TABLE refund ALTER COLUMN refund_request_id SET NOT NULL;
CREATE UNIQUE INDEX uk_refund_request_id ON refund (refund_request_id);

DROP INDEX IF EXISTS uk_refund_payment_order_product;
ALTER TABLE refund DROP COLUMN order_product_id;
ALTER TABLE refund DROP COLUMN user_id;
