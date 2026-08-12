--
-- product-service V11
--
-- 검수 요청 발행이 broker 전송 단계에서 조용히 실패하면(PR 4 관측 도입 전에는 로그조차 없었다)
-- 상품이 PENDING_REVIEW에 영구히 머물 수 있다. 현재 검수 회차 시작 시각과 자동 재발행 횟수를
-- 저장해, stale-after를 넘긴 상품을 최대 1회 자동 재발행할 수 있게 한다.
--
-- updated_at은 재사용하지 않는다 — 재발행 때문에 ES 재대사 대상이 불필요하게 바뀌는 부작용과
-- "일반 수정"·"검수 대기 시작"의 의미가 섞이는 걸 피한다.
--
ALTER TABLE product
    ADD COLUMN inspection_requested_at timestamp,
    ADD COLUMN inspection_request_retry_count integer NOT NULL DEFAULT 0;

-- stale 재발행 스케줄러가 60초마다 이 세 조건으로 후보를 찾는다. status 컬럼에는 이미 인덱스가
-- 없어(다른 PENDING_REVIEW 조회들도 마찬가지) 테이블이 커지면 매 tick이 풀스캔이 된다.
-- 부분 인덱스로 PENDING_REVIEW·미삭제 row만 좁혀 인덱스 크기를 줄인다.
CREATE INDEX idx_product_stale_inspection_request
    ON product (inspection_request_retry_count, inspection_requested_at)
    WHERE status = 'PENDING_REVIEW' AND deleted_at IS NULL;
