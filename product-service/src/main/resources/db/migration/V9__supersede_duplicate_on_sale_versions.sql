--
-- product-service V9
--
-- 메이저 버전 검수 승인이 같은 가족의 기존 ON_SALE 행을 SUPERSEDED로 전환하지 않아(#699)
-- 승인이 반복될수록 한 가족에 ON_SALE 행이 누적됐다(dev 실측: 10개 가족이 2행씩). 코드
-- 수정으로 앞으로는 승인 순간 교대되므로, 이미 오염된 행을 정리한다 — 가족 안에 ON_SALE이
-- 여럿이면 버전이 가장 높은 행만 남기고 나머지를 SUPERSEDED로 전환한다.
--
-- updated_at을 함께 밀어 ES 재조정이 이 가족들을 다시 색인하게 한다(승인·supersede와 같은 경로).
UPDATE product p
   SET status = 'SUPERSEDED',
       updated_at = now()
 WHERE p.status = 'ON_SALE'
   AND p.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM product q
        WHERE coalesce(q.parent_id, q.id) = coalesce(p.parent_id, p.id)
          AND q.status = 'ON_SALE'
          AND q.deleted_at IS NULL
          AND (q.major_version, q.patch_version, q.created_at) > (p.major_version, p.patch_version, p.created_at)
   );
