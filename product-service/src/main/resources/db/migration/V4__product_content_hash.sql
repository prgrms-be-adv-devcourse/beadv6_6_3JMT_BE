--
-- product-service V4
--
-- 상품 본문 복제 탐지용 해시(ADR-0011). 같은 프롬프트를 띄어쓰기 하나만 바꿔 남의 상품으로
-- 다시 올리는 것을 막기 위한 값이다.
--
-- product-service는 이 값을 채우기만 한다. 같은 해시를 가진 타 판매자 상품을 찾아 반려하는
-- 판정은 검수 주체(admin-service)가 한다 — 같은 테이블을 복사본 엔티티로 직접 읽는다.
--
-- embedding_source_hash(V3)와 다른 값이다. 그쪽은 "이 상품 글이 바뀌었나"(같은 상품의 과거와
-- 비교), 이쪽은 "같은 글이 이미 있나"(다른 상품과 비교)를 묻는다.
--

-- PROMPT만 값이 있다. PPT·EXCEL은 파일, NOTION은 외부 링크라 본문이 없다.
ALTER TABLE product ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);

-- 부분 인덱스로 좁히지 않는다.
--
-- 임베딩 인덱스(V3)는 ON_SALE만 넣지만 이쪽은 PENDING_REVIEW도 비교 대상이어야 한다.
-- 거의 동시에 올라온 복제본 두 개(둘 다 PENDING_REVIEW)를 서로 잡으려면 필요하다.
CREATE INDEX IF NOT EXISTS idx_product_content_hash ON product (content_hash);

-- 기존 상품 백필.
--
-- 안 채우면 신규 제출물이 기존 상품을 복제해도 대조 대상이 없어 기능이 조용히 무력화된다.
-- 에러는 나지 않고 그냥 아무것도 안 걸린다.
--
-- Postgres sha256()이 Java SHA-256 hex와 완전히 같은 값을 낸다(실측 확인). 현재
-- ProductContentHash.normalize()가 원문을 그대로 돌려주므로 두 결과가 일치한다.
-- 정규화가 추가되면 이 백필 결과는 옛 규칙 값이 되므로 그때 새 규칙으로 다시 채워야 한다.
UPDATE product
   SET content_hash = encode(sha256(convert_to(content, 'UTF8')), 'hex')
 WHERE product_type = 'PROMPT'
   AND content IS NOT NULL
   AND content_hash IS NULL;
