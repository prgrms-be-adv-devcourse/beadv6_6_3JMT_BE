# 상품 본문 복제 탐지 — 구현 plan (content_hash)

> 이건 **구현용 스펙**이다. "왜 블록체인·임베딩·비대칭키가 아니라 해시인가", FM1/FM2를
> 4라운드 검토한 근거·경위는 **ADR-0011**(`docs/adr/0011-content-similarity-over-blockchain-nft.md`)에
> 있다. 여기선 결론만 옮겨 무엇을 어떤 순서로 짤지에 집중한다. 결정 근거가 필요하면 ADR을 본다.

한 줄 목적: **같은 프롬프트 본문을 띄어쓰기 하나만 바꿔 남의 상품으로 다시 올리는 것을 등록
시점에 차단한다.** PROMPT 유형 전용.

---

## 현재 상태

| 항목 | 상태 |
| --- | --- |
| `content_hash` 컬럼·인덱스·백필 (`V4`) | ✅ 됨 (#632) |
| `ProductContentHash.of()` / `Product.contentHash` 계산 | ✅ 됨 (create·update(MAJOR)·nextVersion(MAJOR) 세 경로) |
| `ProductContentHash.normalize()` | ⚠️ 스텁 (원문 그대로 반환 — 지금은 완전 바이트 일치만 잡힘) |
| `content_hash_at` 컬럼 + 트리거 | ❌ 없음 |
| 중복 조회 메서드 | ❌ 없음 (해시는 채워지는데 아무도 안 읽음) |
| `duplicateOfProductId` 통합 (#597 흐름) | ❌ 없음 |

---

## 구현 순서

### 1. 정규화 — `ProductContentHash.normalize()` 채우기

- 파일: `product-service/.../domain/model/vo/ProductContentHash.java`
- 지금 `return text;` 스텁 → 공백 전체 축약(연속 공백·개행·탭 → 단일) + 앞뒤 trim + 소문자화.
- **이 메서드만 고치면 계산 3경로가 자동으로 탄다** — 호출부 수정 불필요(코드 주석에 명시됨).
- ⚠️ 고치는 순간 **기존 저장 해시가 전부 옛 규칙 값**이 돼 매칭 안 된다 → 전 상품 해시 재계산
  (백필 UPDATE 재실행). `V4` 백필과 같은 식으로 `V5`에서 다시 채우거나 별도 배치.
- 정규화 강도는 **공백·대소문자만으로 확정**(v1). 문장부호·마크다운·단어편집 회피는 실측 후
  필요하면 shingling으로 승격 — 지금은 안 한다. (아래 "확정 결정" 참고)

### 2. `content_hash_at` 컬럼 + 트리거 마이그레이션 (`V5`)

해시가 확정된 시각만 담는 전용 컬럼. **순서 판정의 기준**이라 `created_at`/`updatedAt` 재사용
금지(둘 다 콘텐츠와 무관하게 밀려서 원본·복제가 뒤집힘 — ADR 참고).

값은 **앱이 아니라 DB 단일 시계**로 찍는다. `Product`는 시간·해시를 전부 앱에서 찍는데
(`LocalDateTime.now()`) 멀티 파드면 시계 스큐로 나중 제출자가 더 이른 시각을 받아 오판이
고착된다. 컬럼 `DEFAULT now()`나 엔티티 필드로 두면 JPA 더티체크가 앱 값을 보내 안 먹으니,
**`BEFORE INSERT OR UPDATE` 트리거**로 조건부 갱신 + DB 시각을 한곳에서 처리한다.

```sql
-- V5__product_content_hash_at.sql
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
```

- INSERT 시 `OLD`가 없어 `NEW.content_hash IS DISTINCT FROM NULL` → 해시 있으면 찍고, PROMPT
  아닌(해시 null) 로우는 자연히 제외된다.
- 엔티티엔 `content_hash_at`을 `@Column(insertable=false, updatable=false)` 읽기 전용으로 매핑하고,
  값이 필요하면 flush 후 재조회(또는 아래 조회 쿼리처럼 서브쿼리로 참조).

### 3. 중복 조회 메서드 — `ProductRepository`

같은 해시를 가진 **다른 판매자의**, **이 상품보다 먼저 해시가 확정된** PROMPT 상품을 찾는다.
있으면 그 id가 `duplicateOfProductId`.

```sql
SELECT p.id
  FROM product p
 WHERE p.product_type = 'PROMPT'
   AND p.content_hash = :thisHash
   AND p.seller_id <> :thisSellerId          -- 자기 버전 계열·재제출 자기매칭 제외
   AND p.status IN ('ON_SALE', 'PENDING_REVIEW')
   AND p.content_hash_at < (SELECT content_hash_at FROM product WHERE id = :thisId)
 ORDER BY p.content_hash_at ASC
 LIMIT 1
```

- 비교 대상 `content_hash_at`은 트리거가 찍은 **DB 값**이라 앱이 든 `LocalDateTime`이 아니라
  서브쿼리(방금 persist된 로우)로 참조한다.

### 4. #597 흐름에 신호 얹기 (새 이벤트 없음)

1. `PENDING_REVIEW` 전이 3지점(`submitForReview()` / `update(isMajor=true)` /
   `nextVersion(isMajor=true)`) 각각에서 위 조회로 `duplicateOfProductId`(nullable UUID)를 구한다.
2. `ProductReviewRequestedPayload`에 `duplicateOfProductId` 추가 + ai-service
   `ProductInspectionRequest`도 확장(양쪽 다 작성자 소유).
3. ai-service 검수 로직이 `duplicateOfProductId != null`이면 자동 반려("기존 상품과 동일한 본문").
   완전일치는 이진이라 임계값 없음.

- 신호가 검수 이벤트 하나에 합쳐져서 "두 신호 합류 실패" 경쟁조건이 없고, 이벤트 유실 시
  상품은 `PENDING_REVIEW`에 머물 뿐이라 조용히 승인되는 fail-open이 없다(#597 멱등·상태가드 상속).

---

## 알려진 한계 — 지금 안 막는다

**FM2 (동시 제출 미탐).** 서로 다른 판매자가 거의 동시에 같은 본문을 올리면, 나중 제출의 조회가
먼저 제출의 커밋보다 먼저 실행돼(tx 가시성 창, 보통 수십~수백 ms) **둘 다 통과**할 수 있다.
값 비교로는 못 닫고 직렬화(유니크 제약 선점 테이블 / 커밋 후 재조회)가 필요하다. **실제 동시
표절 제출이 관측되면 그때 추가한다.** (주 보호 대상인 "오래 팔린 인기 프롬프트 복제"는 원본이
이미 확실히 커밋돼 있어 FM2와 무관하게 잡히므로 우선순위 낮음.)

> 선점 테이블로 승격할 땐 유니크 키를 `content_hash` 단독으로 걸면 `seller_id<>본인` 예외가
> 죽는다(본인 major 업·재제출이 자기에게 선점당함). INSERT 충돌 시 기존 선점자의 `seller_id`가
> 본인이면 통과, 남이면 그 선점자를 `duplicateOfProductId`로 쓰는 분기가 필요하다. — ADR 참고.

---

## 최소 검증 (테스트 3개)

1. **정규화 동치**: 공백/대소문자만 다른 두 content가 같은 해시. — 현재 `ProductContentHashTest`는
   반대("한 글자만 달라도 다른 해시")만 고정 → 정규화 구현 시 갱신 대상.
2. **이른 쪽만 통과**: 같은 해시 두 상품 중 `content_hash_at` 이른 쪽만 통과, 늦은 쪽만
   `duplicateOfProductId` 채워짐. (비교가 대칭으로 퇴행하는 걸 막음)
3. **무해 재편집 불변**: 정규화 후 해시가 그대로인 `update()`는 `content_hash_at`을 안 바꾼다.
   (원저작자 순위 역전 방지)

---

## 확정 결정 (2026-07-29)

> ADR-0011엔 이 둘이 "미정"으로 남아있다(블로그용 아카이브라 안 건드림). **구현 기준은 여기다.**

- **정규화 강도 = 공백·대소문자만 (v1 확정).** 연속 공백·개행·탭 → 단일 + 앞뒤 trim + 소문자화까지.
  "띄어쓰기 하나" 타겟에 정확하고 오탐≈0. 문장부호·마크다운 제거는 서로 다른 본문을 같은 해시로
  뭉쳐 오탐을 올리므로 v1에서 안 한다. 단어단위 편집 회피가 실측으로 확인되면 그때
  shingling(MinHash/LSH, 임계값은 ai-service)으로 승격 — 문장부호 정규화를 거치지 않고 바로 간다.
- **판정 주체 = ai-service (확정).** 위 "구현 순서 4번" 흐름 그대로 — product-service가
  `duplicateOfProductId`를 구해 #597 검수 이벤트에 실어 보내고 ai-service가 non-null이면 자동 반려.
  admin-service 직접읽기(`V4` 주석 메모)는 **채택 안 함**. 완전일치는 이진이라 사람 판단이 불필요하고,
  admin에 `contentHash` 매핑·타 서비스 테이블 직접읽기 결합을 새로 만들 이유가 없다. 작성자가
  product·ai 양쪽을 소유해 조율 비용도 0.
