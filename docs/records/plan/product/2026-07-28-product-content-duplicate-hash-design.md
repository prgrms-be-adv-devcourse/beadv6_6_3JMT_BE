# 상품 본문 복제 탐지 해시 (product-service 몫)

## 배경

같은 프롬프트 본문을 **띄어쓰기 하나만 바꿔** 남의 상품으로 다시 올리는 것을 등록 시점에
막고 싶다(ADR-0011). 현재 검수는 상품 **하나만** 보고 정책 위반을 판단할 뿐, 다른 판매자의
기존 상품과 대조하지 않아 표절을 구조적으로 못 잡는다.

**이 설계의 범위는 product-service가 해시를 만들어 저장하는 것까지다.** 복제 판정은 검수
주체가 한다 — admin-service가 `product_service.product` 테이블을 복사본 엔티티로 직접
읽으므로(`admin/product/entity/Product.java:28`, `approveProduct`/`rejectProduct` 보유),
같은 컬럼을 조회하면 된다.

| 단계 | 담당 |
|---|---|
| 해시 컬럼 · 계산 · 백필 | **이 설계 (product-service)** |
| 복제 조회와 반려 판정 | 검수 주체 (admin-service 등) |
| `normalize()` 본문 채우기 | 별도 담당자 |

책임 분리가 이 형태인 이유: **product-service는 데이터를 소유하고, 판정은 검수가 한다.**
product-service가 조회·판정까지 하면 검수 로직이 두 서비스에 나뉜다.

## 결정 사항

### 1. PROMPT만 해시한다

| 유형 | 해시 대상 | 근거 |
|---|---|---|
| PROMPT | `content` 전문 | 본문이 그 상품의 알맹이다 |
| NOTION · PPT · EXCEL | **없음 (null)** | 아래 |

비PROMPT의 비교 재료는 `name + description` 정도인데, 이건 "본문 복제"가 아니라 "상품 설명
복제"다. 베끼는 사람은 어차피 자기 제목·소개글을 쓰므로 거의 안 걸린다. 컬럼·인덱스·분기를
얹을 값이 없다. 파일·링크 본문 비교는 #602(파일 텍스트 추출) 이후에나 가능하다.

`ProductContent.validateTypeFields:44`상 `content`를 갖는 유형은 PROMPT뿐이다.

### 2. 기존 `embedding_source_hash`를 재사용하지 않는다

둘 다 SHA-256이지만 **묻는 질문이 반대**라 한 값으로 못 쓴다.

| | `embedding_source_hash` (#378) | `content_hash` (이번) |
|---|---|---|
| 질문 | 이 상품 글이 **바뀌었나** (같은 상품의 과거와 비교) | 같은 글이 **이미 있나** (다른 상품과 비교) |
| 대상 | name + tags + description + content(**2,000자 절단**) | content **전문** |
| 정규화 | 없어야 함 — 공백이 바뀌면 임베딩도 실제로 달라짐 | 있어야 함 |

결정적 충돌: **제목만 바꾸고 본문은 그대로인 경우**, 임베딩 해시는 바뀌어야 하고(벡터 재생성
필요) 복제 해시는 안 바뀌어야 한다(본문이 같으니 여전히 복제). 한 값이 둘을 동시에 만족할 수
없다.

"기존 해시를 비PROMPT에만 재사용" 안도 검토했으나 더 비쌌다 — 컬럼 수는 같은데(PROMPT용은
어차피 필요) `embedding_source_hash`에 인덱스가 없어 하나 더 만들어야 하고, 조회 시 유형을
보고 컬럼을 고르는 분기가 생기며, `EmbeddingSource`가 바뀌면 복제 판정이 조용히 바뀐다.

SHA-256 → hex 변환 5줄은 각자 갖는다. 표준 API 호출이고, 잘 도는 `EmbeddingSource`를 지금
건드릴 이유가 없다.

### 3. 계산은 `applyContent()`에서 한다 — `submitForReview()`가 아니라

`PENDING_REVIEW`가 되는 경로가 셋인데 `submitForReview()`는 그중 하나뿐이다.

| 경로 | `Product.java` | `submitForReview()` | `applyContent()` |
|---|---|---|---|
| 직접 검수 요청 | `submitForReview` :223 | ✅ | ✅ |
| MAJOR 수정 | `update(isMajor)` :138 | ❌ | ✅ |
| MAJOR 버전업 | `nextVersion(isMajor)` :196 | ❌ | ✅ |

`applyContent`는 `ProductContent`가 엔티티에 꽂히는 **유일한 지점**이라 셋을 다 덮는다.
`submitForReview()`에만 두면 뒤의 둘이 새서, 그 경로로 올라온 상품은 (a) 검사를 안 받고
(b) 해시가 없어 나중에 남이 복제해도 대조에 안 걸린다.

#378에서 정확히 이 함정을 밟았다 — 이슈 본문은 "쓰기 흐름에서 생성"이었는데 실제로는 두
경로가 이벤트를 발행하지 않아 그 상품들의 임베딩이 영영 안 생길 뻔했다.

부수 효과로 **정기 배치가 필요 없다.** 신규·수정이 전부 이 지점을 지나므로 항상 최신이다.
남는 건 기존 상품 일회성 백필뿐이다.

### 4. 정규화는 자리만 비워둔다

`normalize()`를 지금은 원문 그대로 반환하게 두고, 공백 축약·소문자화는 별도 담당자가 그
메서드 본문만 채운다. 호출부는 손대지 않아도 자동으로 탄다.

**규칙 버전 접두어를 두지 않는다.** 정규화를 넣으면 저장된 해시가 전부 옛 규칙 값이 되지만,
그 시점에 데이터를 다시 만들기로 했다. 지금은 복제 판정 기능 자체가 없어 당장 피해가 없다.

## 변경 대상

**신규**

`product-service/src/main/java/com/prompthub/product/domain/model/vo/ProductContentHash.java`

```java
public final class ProductContentHash {

    /** PROMPT가 아니면 null — 본문이 없어 비교 대상이 아니다. */
    public static String of(ProductContent content) {
        if (content.productType() != ProductType.PROMPT) {
            return null;
        }
        return sha256(normalize(content.content()));
    }

    /**
     * 정규화 지점. 지금은 원문 그대로 돌려준다.
     * 공백 축약·소문자화가 여기 추가되며, 채우면 모든 호출부가 자동으로 탄다.
     * 주의: 고치면 저장된 해시가 전부 옛 규칙 값이라 전 상품 재계산이 필요하다.
     */
    private static String normalize(String text) { return text; }
}
```

`product-service/src/main/resources/db/migration/V4__product_content_hash.sql`

```sql
ALTER TABLE product ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_product_content_hash ON product (content_hash);
```

인덱스를 부분 인덱스로 좁히지 않는다. 임베딩과 달리 `PENDING_REVIEW` 상품도 비교 대상이라
(거의 동시에 올라온 복제본 두 개를 서로 잡으려면) `ON_SALE`만 넣으면 안 된다.

**수정**
- `Product.applyContent()` — 끝에 `this.contentHash = ProductContentHash.of(productContent);`
- `Product` — `contentHash` 필드 + 게터
- `docs/erd/schema.md` — product 테이블에 컬럼 1줄

**백필** — 기존 상품의 `content_hash`가 비어 있다. 안 채우면 신규 제출물이 기존 상품을
복제해도 대조 대상이 없어 **기능이 조용히 무력화된다.** 일회성으로 채운다.

## 검수 주체에게 넘길 것

product-service는 조회를 구현하지 않는다. 검수 쪽이 같은 테이블에서 아래 조건으로 조회하면
된다.

```sql
select p.id from product p
 where p.content_hash = :contentHash
   and p.seller_id <> :sellerId
   and p.status in ('ON_SALE', 'PENDING_REVIEW')
   and p.deleted_at is null
 limit 1
```

- `content_hash`가 PROMPT에만 있으므로 `product_type` 조건은 불필요하다
- `seller_id <>` 로 본인 버전 계열의 자기매칭과 재제출 자기매칭을 함께 막는다
- `PENDING_REVIEW`를 포함해 **거의 동시에 올라온 복제본 두 개도 서로 잡는다**
- `limit 1` — 누구와 겹쳤는지 하나만 알면 반려에 충분하다

**admin-service는 `product` 테이블의 복사본 엔티티를 쓰므로, JPA로 읽으려면 그쪽
`Product`에도 `contentHash` 필드를 추가해야 한다.** 스키마는 product-service Flyway가
소유하므로 마이그레이션은 이 V4 하나뿐이다.

## 검증

**단위**
- PROMPT 상품의 해시가 64자다
- 같은 content면 같은 해시, 한 글자만 달라도 다른 해시
- NOTION·PPT·EXCEL은 `null`이다
- **`applyContent`를 타는 세 경로(create · update(MAJOR) · nextVersion(MAJOR)) 모두 해시가
  채워진다** ← #378에서 밟은 함정의 회귀 방지. 이게 이 PR에서 가장 중요한 테스트다

**통합 (Testcontainers Postgres)**
- 컬럼과 인덱스가 마이그레이션으로 만들어진다
- 저장 후 `content_hash`로 조회가 된다

## 이 설계가 하지 않는 것

- **단어 하나만 바꾼 회피를 못 잡는다.** 정규화가 지우는 차이(공백·대소문자)만 흡수한다.
  실제로 그런 회피가 관측되면 shingling(MinHash/LSH)으로 승격한다.
- **의역은 전혀 못 잡는다.** 임베딩 코사인의 영역이지만, 현재 상품 데이터로는 정직한
  상품끼리도 유사도가 높아 오탐이 쏟아진다(#378 배포 후 실측).
- 비PROMPT 유형 복제 탐지 — #602 이후.
- 복제 조회와 반려 판정 — 검수 주체 담당.

## 선행 조건

**없다.** `ProductReviewRequestedPayload`를 건드리지 않으므로 #597 머지를 기다릴 필요가 없고,
develop에서 바로 작업할 수 있다.
