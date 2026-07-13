-- 배포 DB에 사용자가 직접 적용 (로컬/테스트는 JPA ddl-auto가 자동 생성)
-- 이슈: #306 상품 유형별 필드(file_url, content_file_url)
ALTER TABLE product ADD COLUMN file_url TEXT;
ALTER TABLE product ADD COLUMN content_file_url TEXT;
