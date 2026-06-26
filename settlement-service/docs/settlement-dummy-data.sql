-- =====================================================================
-- 정산 서비스 더미 데이터 (소스 라인만 — "정산하기"(배치)로 정산 생성하는 플로우용)
-- =====================================================================
-- 목적: Settlement(정산) 객체를 직접 만들지 않는다. 대신 미정산
--       settlement_source_line(PAID/REFUND 원천 이벤트)만 넣어두고,
--       "정산하기" 배치(POST /admin/settlements/batch)를 실행하면
--       실제 플로우대로 판매자별 Settlement 가 생성되게 한다.
--
-- 이 스크립트가 하는 일:
--   1) settlement_detail / settlement / settlement_batch 를 모두 비운다.
--   2) 기존 settlement_source_line 을 모두 비운다.
--   3) 미정산 소스 라인을 새로 넣는다. (settlement_id = NULL)
--
-- 생성량: 판매자 6명 × 3개월(2026-04~06) × 6라인 = 108건
--         각 (판매자,월) 묶음 = PAID 5 + REFUND 1
--   → 한 달치 배치를 돌리면 판매자 6명 → 정산 6건 생성됨
--
-- 사용법:
--   1) settlement-service 앱을 띄운다(테이블 생성 / 배치 API 가용).
--   2) 이 스크립트 실행:
--        psql -h localhost -U promptHub -d promptHub -f settlement-dummy-data.sql
--   3) "정산하기" 배치를 월별로 실행해 정산을 생성한다(아래 4번 참고).
--   * 재실행해도 안전: 매번 정산/소스라인을 싹 지우고 다시 넣는다.
--
-- 테스트용 판매자 ID:
--   판매자A : 11111111-1111-1111-1111-111111111111
--   판매자B : 22222222-2222-2222-2222-222222222222
--   판매자C : 33333333-3333-3333-3333-333333333333
--   판매자D : 44444444-4444-4444-4444-444444444444
--   판매자E : 55555555-5555-5555-5555-555555555555
--   판매자F : 66666666-6666-6666-6666-666666666666
--
-- 배치가 소스 라인을 집어가는 조건(코드 기준):
--   - settlement_id IS NULL  (미정산)
--   - occurred_at >= 해당월 1일 00:00  AND  occurred_at < 다음달 1일 00:00
--   - event_type PAID/REFUND 모두 처리, seller_id 별로 묶어 정산 1건 생성
--   - 수수료율 15% 고정, REFUND 는 배치에서 음수로 반영
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 정산 객체/상세/배치 이력 + 기존 소스 라인 전부 정리
--    (settlement_detail → settlement 순서로 지운다: FK 때문)
-- ---------------------------------------------------------------------
DELETE FROM settlement_detail;
DELETE FROM settlement;
DELETE FROM settlement_batch;
DELETE FROM settlement_source_line;

-- ---------------------------------------------------------------------
-- 2. 미정산 소스 라인 적재 (settlement_id = NULL)
--    6 sellers × 3 periods × 6 lines = 108건
-- ---------------------------------------------------------------------
INSERT INTO settlement_source_line (
  settlement_source_line_id, event_id, event_type, order_id, order_product_id, seller_id,
  line_amount, occurred_at, settlement_id, created_at, updated_at
)
WITH sellers(idx, seller_id) AS (
  VALUES
    (1, '11111111-1111-1111-1111-111111111111'::uuid),
    (2, '22222222-2222-2222-2222-222222222222'::uuid),
    (3, '33333333-3333-3333-3333-333333333333'::uuid),
    (4, '44444444-4444-4444-4444-444444444444'::uuid),
    (5, '55555555-5555-5555-5555-555555555555'::uuid),
    (6, '66666666-6666-6666-6666-666666666666'::uuid)
),
periods(idx, period_start) AS (
  VALUES
    (1, DATE '2026-04-01'),
    (2, DATE '2026-05-01'),
    (3, DATE '2026-06-01')
),
gen AS (
  SELECT
    row_number() OVER (ORDER BY s.idx, p.idx, g) AS i,
    s.seller_id,
    p.period_start,
    g AS line_no                       -- 1~6 (6번째는 REFUND)
  FROM sellers s
  CROSS JOIN periods p
  CROSS JOIN generate_series(1, 6) AS g
)
SELECT
  gen_random_uuid(),                                                  -- settlement_source_line_id
  gen_random_uuid(),                                                  -- event_id (UNIQUE)
  CASE WHEN line_no = 6 THEN 'REFUND' ELSE 'PAID' END,                -- event_type
  gen_random_uuid(),                                                  -- order_id
  gen_random_uuid(),                                                  -- order_product_id
  seller_id,
  CASE WHEN line_no = 6
       THEN 30000.00
       ELSE (60000 + ((i * 7) % 8) * 10000)::numeric(12,2)            -- 60,000 ~ 130,000
  END,                                                               -- line_amount (항상 양수)
  (period_start + (line_no - 1)) + TIME '10:00:00',                   -- occurred_at (해당월 1~6일)
  NULL,                                                              -- settlement_id (미정산)
  (period_start + (line_no - 1)) + TIME '10:00:00',                   -- created_at
  (period_start + (line_no - 1)) + TIME '10:00:00'                    -- updated_at
FROM gen;

-- ---------------------------------------------------------------------
-- 3. 확인용 조회 (선택)
-- ---------------------------------------------------------------------
-- 월별 미정산 라인 수:
--   SELECT date_trunc('month', occurred_at) AS month, event_type, count(*)
--     FROM settlement_source_line
--    WHERE settlement_id IS NULL
--    GROUP BY 1, 2 ORDER BY 1, 2;
--
-- 정산 객체가 비었는지(0 기대):
--   SELECT count(*) FROM settlement;

-- ---------------------------------------------------------------------
-- 4. "정산하기" 배치 실행 (월별로 실행 → 판매자별 정산 생성)
-- ---------------------------------------------------------------------
-- curl -X POST http://localhost:8080/api/v1/admin/settlements/batch \
--   -H "Content-Type: application/json" \
--   -H "X-User-Id: 11111111-1111-1111-1111-111111111111" \
--   -H "X-User-Role: ADMIN" \
--   -d '{"period": "2026-06"}'
--
-- period 를 2026-04 / 2026-05 / 2026-06 로 각각 실행하면
-- 그 달의 미정산 라인이 판매자별로 묶여 정산(PENDING_APPROVAL)으로 생성된다.
-- 한 번 정산된 라인은 settlement_id 가 채워져 다시 정산되지 않는다(멱등).
