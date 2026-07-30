--
-- product-service V5
--
-- AI 검수(ai-service) 가 PRODUCT_INSPECTION_COMPLETED 이벤트로 함께 보내는 체크리스트
-- 7개 필드(#671). 승인/반려 여부만으로는 드러나지 않는 세부 판정 근거를 보존한다.
--

ALTER TABLE product ADD COLUMN IF NOT EXISTS has_context BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE product ADD COLUMN IF NOT EXISTS has_objective BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE product ADD COLUMN IF NOT EXISTS has_nuance BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE product ADD COLUMN IF NOT EXISTS has_tone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE product ADD COLUMN IF NOT EXISTS has_examples BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE product ADD COLUMN IF NOT EXISTS has_execution BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE product ADD COLUMN IF NOT EXISTS has_role_assignment BOOLEAN NOT NULL DEFAULT false;
