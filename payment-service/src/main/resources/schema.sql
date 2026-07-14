-- Hibernate ddl-auto=update가 표현/수행하지 못하는 DDL을 멱등하게 처리한다(defer-datasource-initialization).
-- 모든 문장은 IF EXISTS / IF NOT EXISTS로 매 기동 반복 실행돼도 안전하다.

-- 금액 분해 컬럼 제거 (D4) — total_amount 단일화
ALTER TABLE payment DROP COLUMN IF EXISTS product_amount;
ALTER TABLE payment DROP COLUMN IF EXISTS discount_amount;

-- 멱등키 일원화 (D8) — idempotency_key 제거, pg_tx_id로 통합
ALTER TABLE payment DROP COLUMN IF EXISTS idempotency_key;
ALTER TABLE payment ALTER COLUMN pg_tx_id TYPE VARCHAR(255);                    -- paymentKey(≤약 200자) 수용
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_pg_tx_id ON payment (pg_tx_id);    -- 동일 paymentKey 이중 confirm 차단

-- 동시성 방어용 부분 유니크 인덱스 (§5.3) — 같은 주문의 두 번째 PAID 전이 차단
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_order_paid ON payment (order_id) WHERE status = 'PAID';

-- 부분 환불(OrderProduct 단위) 도입 (#15) — order_product_id 필수화 + 상품당 1회 환불만 허용
-- 기존 로컬 DB에 order_product_id가 NULL인 행이 남아있으면 아래 두 문장이 실패한다.
-- 로컬 개발 DB라면 `TRUNCATE TABLE refund;`로 비우고 재기동한다(Testcontainers 기반 테스트는 매번 ddl-auto=create라 영향 없음).
DELETE FROM refund WHERE order_product_id IS NULL;
ALTER TABLE refund ALTER COLUMN order_product_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_refund_payment_order_product ON refund (payment_id, order_product_id);
