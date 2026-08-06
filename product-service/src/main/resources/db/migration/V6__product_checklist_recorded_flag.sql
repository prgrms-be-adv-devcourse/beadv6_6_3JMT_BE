--
-- product-service V6
--
-- V5의 체크리스트 7개 컬럼은 DEFAULT false라 이 기능 배포 전에 이미 승인된 상품도 전부
-- false로 조회된다. "검수 기준 미달"과 "그냥 기록이 없음"을 구분하기 위해 체크리스트가
-- 실제로 기록됐는지 나타내는 플래그를 별도로 둔다(#671).
--

ALTER TABLE product ADD COLUMN IF NOT EXISTS checklist_recorded BOOLEAN NOT NULL DEFAULT false;
