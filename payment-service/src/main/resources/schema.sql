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
