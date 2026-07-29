--
-- product-service V5
--
-- content_hash 정규화 강도가 확정됐다(v1: 공백 축약+trim+소문자화,
-- ProductContentHash.normalize()). V4 백필은 원문 그대로 sha256을 계산했으므로 옛 규칙
-- 값이 됐다 — 새 규칙으로 다시 채운다. 트리거 생성 전에 실행해 아래 content_hash_at
-- 백필이 이 재계산을 "변경"으로 잘못 인식하지 않게 한다.
--
-- 정규식은 `\s` 대신 명시적 문자 클래스 [ \t\n\r\f\v]를 쓴다. Postgres의 `\s`는 UTF8 인코딩·
-- 로캘에 따라 U+3000(전각 공백) 같은 유니코드 공백까지 공백으로 묶어버려(실측 확인) Java의
-- ASCII 전용 `\s`(ProductContentHash.normalize())와 어긋난다. 명시적 문자 클래스는 로캘·
-- 인코딩과 무관하게 항상 이 6개 문자만 매칭해 Java와 항상 같은 결과를 낸다.
UPDATE product
   SET content_hash = encode(
           sha256(convert_to(lower(trim(regexp_replace(content, '[ \t\n\r\f\v]+', ' ', 'g'))), 'UTF8')),
           'hex')
 WHERE product_type = 'PROMPT'
   AND content IS NOT NULL;

-- 중복 판정 순서 기준 전용 컬럼. created_at/updated_at을 재사용하지 않는다 — 둘 다
-- 콘텐츠와 무관하게 밀려서 원본·복제 순서가 뒤집힐 수 있다(ADR-0011).
ALTER TABLE product ADD COLUMN IF NOT EXISTS content_hash_at TIMESTAMP;

-- 기존 로우 백필: 앞으로의 신규 제출은 항상 now()라 마이그레이션 시점보다 늦으므로,
-- 백필값은 과거 아무 값(created_at 등)이어도 순서 보장이 안 깨진다.
UPDATE product SET content_hash_at = created_at
 WHERE content_hash IS NOT NULL AND content_hash_at IS NULL;

CREATE OR REPLACE FUNCTION set_content_hash_at() RETURNS trigger AS $$
BEGIN
    -- 해시가 실제로 바뀐 분기에서만 시각을 민다.
    -- 무조건 갱신하면 원저작자의 무해한 재편집(정규화가 흡수하는 공백 수정)마다 시각이 밀려
    -- 순위가 뒤로 가고, 그새 슬쩍 통과한 표절본에 원본이 복제로 잡힌다.
    IF NEW.content_hash IS DISTINCT FROM OLD.content_hash THEN
        NEW.content_hash_at := now();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_content_hash_at
    BEFORE INSERT OR UPDATE ON product
    FOR EACH ROW EXECUTE FUNCTION set_content_hash_at();
