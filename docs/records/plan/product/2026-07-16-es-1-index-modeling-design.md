# ES 검색·추천 설계 1 — products 인덱스 모델링

> 전체 그림·결정 로그는 [0 — 개요](2026-07-16-es-0-overview-design.md) 참고.
>
> ⚠️ **일부 대체됨 (2026-07-27)** — **임베딩 저장 위치**가 바뀌었다. 이 문서는 임베딩을
> ES `dense_vector`에만 두는 것을 전제하지만, 확정 설계는 **Postgres(pgvector)가 원본이고
> ES는 검색용 복사본**이다(D12). 매핑의 `embedding`·`embeddingSourceHash` 필드 자체는
> 유효하며, 값의 출처가 OpenAI 직접 호출 → Postgres 컬럼 복사로 바뀐다.
> 상세: [2026-07-26 설계 변경](2026-07-26-search-recommendation-api-ownership-design.md)
>
> ⚠️ **매핑 필드 2건 차이 (2026-08-04 대조)** — 실제
> `product-service/src/main/resources/es/products-v1-mapping.json`과 다음이 다르다.
>
> | 필드 | 이 문서 | 실제 매핑 | 사유 |
> | --- | --- | --- | --- |
> | `extractedText` | 있음 | **없음** | PPT·EXCEL·NOTION 본문 텍스트 추출(#602)이 보류되어 색인 대상이 없다 |
> | `familyRootId` | 없음 | **있음** | 정렬 tiebreaker로 필요해 구현 중 추가 |
>
> 나머지 필드(19개)는 문서와 실제가 일치한다.

## 1. 문서 단위 — family당 1문서, `_id` = familyRootId

RDB는 버전마다 row가 늘어나지만(v1.0, v1.1, v2.0…) 검색에 나와야 하는 건
**각 상품의 "현재 ON_SALE 버전" 하나**다. ES에는 family(상품 계보)당 문서 1개만 두고
`_id`를 `familyRootId`(`parentId ?? id`)로 고정한다.

이 선택이 해결하는 것:

- **버전 교체 = 덮어쓰기.** 새 버전이 ON_SALE이 되면 같은 `_id`로 재색인 → 이전 버전
  문서가 자동 교체. 삭제/삽입 순서 문제, 두 버전 동시 노출 문제가 원천적으로 없다.
- **중복 노출 불가능.** 한 상품이 검색 결과에 두 번 나올 수 없는 구조.
- **카운트는 family 합산값**을 싣는다(버전 row별 카운트는 리셋되지만 family 집계는 유지).
  메이저 업데이트가 나와도 인기 실적은 누적 유지된다.

문서에는 `productId`(현재 ON_SALE row의 UUID — FE 상세 이동용)를 별도 필드로 담는다.

**status 필드는 두지 않는다.** ON_SALE인 것만 색인하고, family에 ON_SALE이 없어지면
문서를 삭제한다. "필터로 거르기"가 아니라 "존재 자체"로 거른다 — 필터를 빼먹는 사고가
구조적으로 불가능하고, 죽은 문서가 디스크·캐시·집계를 오염시키지 않는다.
RDB(원본)는 soft delete로 역사를 보존하므로 ES에서 지워도 잃는 것이 없다.
재판매 복귀 시 이벤트로 다시 upsert되고, 언제든 풀 리인덱스로 재구성 가능하다.

## 2. 필드 매핑

```jsonc
// 물리 인덱스 products-v1, alias "products". 1 shard / 0 replica (단일 노드)
{
  "productId":     "keyword",            // 현재 ON_SALE row id (FE 이동용)
  "sellerId":      "keyword",
  "name":          "text(nori) + keyword",   // 자동완성 서브필드는 문서 3 (v1 매핑에 처음부터 포함)
  "description":   "text(nori)",
  "content":       "text(nori)",         // PROMPT 본문 (다른 타입은 없음)
  "extractedText": "text(nori)",         // PPT/EXCEL 추출 텍스트 — 자리만 예약, 추출은 스코프 외
  "tags":          "keyword + tags.text(nori)",  // 정확 필터 + 형태소 검색
  "productType":   "keyword",            // PROMPT/NOTION/PPT/EXCEL — RDB enum 그대로
  "model":         "keyword",            // GPT/Claude 등
  "amount":        "integer",
  "amountType":    "keyword",
  "thumbnailUrl":  "keyword(index:false)",   // 검색 안 함, 결과 표시용
  "badge":         "keyword",
  "salesCount":    "integer",            // family 집계값
  "viewCount":     "integer",            // family 집계값
  "reviewCount":   "integer",
  "ratingAvg":     "float",              // Review(rating, ACTIVE만) 집계
  "firstPublishedAt": "date",            // family 최초 판매 시작 — "최신순" 기준
  "currentVersionAt": "date",            // 현재 버전 반영 시점
  "embedding":     "dense_vector(dims:1536, similarity:cosine, index_options:int8_hnsw)",
  "embeddingSourceHash": "keyword"       // 임베딩 원문 해시 — 동일하면 재임베딩 스킵
}
```

- `productType`은 RDB enum을 **그대로** 쓴다. "prompt/file/link" 그룹핑은 저장 구조로
  만들지 않고 쿼리에서 `terms: [PPT, EXCEL]`처럼 해석한다 — 저장은 원본에 충실, 해석은 쿼리에서.
- `wishCount`는 넣지 않는다 — RDB `wish_count`가 미구현(항상 0)이라서. 찜 신호가
  활성화되면(문서 5의 조건부 계약) 필드 추가는 무비용(매핑 additive 변경)이고,
  카운트 배치가 자동으로 실어 온다.
- **"최신순" 기준은 `firstPublishedAt`** — 패치 버전 등록으로 신상품 자리에 재등장하는
  것을 막는다. 인기 누적(family 카운트)과는 무관.
- text(nori)는 검색할 4개 필드에만. 정렬·필터·집계는 전부 keyword/integer/date
  (text 필드 정렬·집계는 fielddata로 heap을 폭식하므로 금지).

## 3. 임베딩 — 타입 무관 통일 규칙

색인 시점에 컨슈머가 임베딩 대상 텍스트를 조립해 OpenAI로 임베딩한다:

```
임베딩 원문 = name + tags + description
              (+ PROMPT면 content 앞 2,000자)
              (+ 추후 extractedText 도입 시 그 앞부분)
```

- 타입마다 재료가 다를 뿐 "색인 대상 텍스트를 임베딩한다"는 규칙은 하나 —
  타입 무관 kNN이 성립한다.
- `int8_hnsw` 양자화로 벡터 메모리를 1/4로 (수천 상품이면 수 MB 수준).
- `embeddingSourceHash`(원문 SHA-256)가 기존 문서와 같으면 **OpenAI 호출 스킵** —
  가격 변경 등 텍스트 무관 재색인에서 재임베딩하지 않는다.
- 모델 교체는 기존 벡터 전부와 호환이 깨지는 작업(전 상품 재임베딩 + 리인덱스) —
  노브 등급 C (문서 7).

## 4. 이벤트 → 색인 동작

| product-events | ES 동작 |
|---|---|
| `PRODUCT_ON_SALE` (신규) — 버전이 현재 ON_SALE이 되는 모든 경우(신규 승인·메이저 승인·패치 반영·재판매 복귀). payload = 색인에 필요한 전체 스냅샷 | family 문서 upsert (+임베딩, 해시 스킵 판단) |
| `PRODUCT_STOPPED` / `PRODUCT_DELETED` (기존) | family에 ON_SALE 없으면 문서 delete |
| `PRODUCT_PRICE_CHANGED` (기존) | partial update (amount만, 재임베딩 없음) |

- `PRODUCT_ON_SALE`은 product 패키지가 새로 발행한다(문서 6 — product는 소비자를 모름).
  기존 발행 3종(PriceChanged/Stopped/Deleted)은 그대로 재사용.
- 색인 컨슈머는 upsert 시 family 집계 카운트(sales/view/review/rating)를 product의
  읽기 포트로 조회해 채운다 (search→product 방향, 허용).
- 다른 서비스 컨슈머들은 미지원 eventType을 로그만 남기고 Ack(컨벤션 §7)하므로
  토픽에 이벤트 추가는 안전하다.

## 5. 카운트 동기화 배치 (10분)

판매/조회/평점 카운트는 이벤트마다 문서를 두드리지 않는다 — ES 업데이트는 내부적으로
문서 전체 재작성이라, 조회수처럼 초 단위로 튀는 값을 건건이 반영하면 소형 노드가
그것만 하다 끝난다. 대신:

```
@Scheduled(10분) → "지난 주기에 카운트 변한 family" 목록을 RDB 집계로 조회
               → _bulk 한 방으로 해당 문서들 partial update
```

인기순이 최대 10분 늦는 대가는 체감 불가. 주기는 노브 등급 A.

## 6. 인덱스 수명 관리 — alias 전환

- 앱은 항상 alias `products`만 바라본다.
- 매핑 변경(edge_ngram 길이, 분석기, 벡터 차원 등 노브 등급 B)이 필요하면:
  `products-v2` 생성 → 풀 리인덱스 배치(RDB 전체 → v2) → alias 원자적 전환 → v1 삭제.
  무중단.
- 초기 적재도 같은 풀 리인덱스 배치를 쓴다. 이 배치는 이벤트 유실 보정(비상시 수동 실행)
  역할도 겸한다.
