--
-- product-service V10
--
-- 동시에 들어온 두 수정 요청이 같은 family의 같은 다음 version(major.patch) row를 함께 만들 수
-- 있다(PR 3). application 검증만으로는 두 요청이 동시에 통과할 수 있으므로 DB unique index로
-- 최종 방어한다. parent_id가 없는 family root는 coalesce로 자기 자신을 family key로 취급한다.
--
-- 기존에 중복된 family/version이 있으면 이 CREATE UNIQUE INDEX 자체가 실패해 migration이
-- 멈춘다 — 데이터를 임의로 지우거나 고치지 않고 그대로 확인하게 둔다.
--
CREATE UNIQUE INDEX uk_product_family_version
    ON product (coalesce(parent_id, id), major_version, patch_version);
