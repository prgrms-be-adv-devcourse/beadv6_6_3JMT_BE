--
-- product-service V2
--
-- 1) file_url, external_url: #306 상품 유형별 필드. 운영 DB에는 수동 ALTER로 이미 존재하지만
--    이 마이그레이션 이력엔 기록된 적이 없어 IF NOT EXISTS로 멱등하게 추가한다(신규 환경 대비).
-- 2) thumbnail_url: VARCHAR(500) 초과 URL 지원을 위해 TEXT로 확장.
--

ALTER TABLE product ADD COLUMN IF NOT EXISTS file_url TEXT;
ALTER TABLE product ADD COLUMN IF NOT EXISTS external_url TEXT;
ALTER TABLE product ALTER COLUMN thumbnail_url TYPE TEXT;
