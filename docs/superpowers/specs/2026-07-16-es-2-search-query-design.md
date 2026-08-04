# ES 검색·추천 설계 2 — 검색 쿼리 (하이브리드)

> 전체 그림·결정 로그는 [0 — 개요](2026-07-16-es-0-overview-design.md) 참고.
> 용어: **lexical** = 단어 일치 검색(BM25), **semantic** = 의미 벡터 검색(kNN).
> 하이브리드 = 둘을 같이 실행해 합치기. lexical이 뼈대(Phase 1), semantic은 그 위에 얹는다(Phase 2).
>
> ⚠️ **§2 매칭 필드·분석기 대체됨 (2026-07-30)** — 구현 후 두 번 바뀌었다.
>
> | | 이 문서(원안) | 현행 (`ProductSearchQueryBuilder.MATCH_FIELDS`) |
> | --- | --- | --- |
> | 매칭 필드 | `name^3` `tags.text^2` `description^1.5` **`content`** | `name^3` `tags.text^2` `description^1.5` **`model.text`** |
> | 분석기 | nori(형태소) | **기본 분석기** (nori 미사용) |
> | minimum_should_match | 미지정(OR) | **`2<75%`** |
>
> - **`content`(본문) 제거 (#689)** — 본문이 "예: 일별 주식 지수 데이터 수집" 같은 placeholder
>   예시로 가득해, 무관한 검색어가 예시 문구에 걸려 오탐을 냈다(dev 실측으로 확인).
>   같은 PR에서 유형 단어를 productType 필터로 해석하는 `SearchKeywordTypeParser`를 추가했다.
> - **`model.text` 최하 가중치 추가 (#699)** — 화면에 모델 뱃지가 보이는데 "gpt" 검색이
>   0건이면 사용자에겐 고장이다. 대부분이 GPT 계열이라 가중치를 최하로 두었다.
> - **nori 제거 (#592)** — 실측 결과 nori가 이 서비스의 어휘를 오히려 망가뜨렸다.
>   붙여쓴 복합어는 글자 매칭이 안 되는 한계를 안고 기본 분석기를 쓴다.
> - **`minimum_should_match: 2<75%` 추가 (#689)** — 기본값(OR)이면 대부분의 상품명에 들어가는
>   "프롬프트" 한 단어가 만능 열쇠가 된다.
>
> 아래 §1의 하이브리드 흐름·RRF 병합(K=60)·kNN 유사도 하한 도입 방향은 그대로 유효하다.

## 1. q(검색어)가 있을 때 — 전체 흐름

```
검색어 "발표자료 이쁘게 만들어주는 거"
  ├─ ① 검색어 임베딩: Redis 캐시 조회 → 없으면 OpenAI 1회 호출 후 캐시 저장
  │     (벡터 검색은 "벡터끼리 거리 비교"라 검색어도 그 순간 벡터로 변환해야 한다.
  │      상품 벡터는 색인 때 만든 걸 재사용 — 검색 시 재생성하지 않음)
  ├─ ② ES msearch 한 번에 두 레그 동시 실행
  │     ├─ [lexical]  BM25 multi_match + function_score(인기·신선도) → 상위 50
  │     └─ [semantic] knn(embedding, 검색어 벡터, 동일 filter) → 상위 50
  ├─ ③ 앱에서 RRF로 두 랭킹 병합 → 최종 순위
  └─ ④ 페이지 잘라 반환 + searchId 발급 + SEARCH_EXECUTED 발행 (문서 5)
```

## 2. 하이브리드 결합 — 앱에서 RRF 직접 계산 (결정 D7)

BM25 점수(상한 없음)와 코사인 유사도(0~1)는 단위가 달라 직접 더할 수 없다.

- 점수 덧셈(bool should + knn): boost 상수 튜닝이 데이터 바뀔 때마다 어긋남 — 기각.
- ES 내장 RRF retriever: **유료(Platinum)** — Basic 노드에서 불가 — 기각.
- **앱 RRF** (채택): 점수를 버리고 **등수만** 사용. 각 레그에서의 순위 r에
  `1/(k + r)` (k=60)을 부여하고 합산해 재정렬. 코드 ~10줄, msearch로 왕복 1회.
  양쪽 레그 모두 상위인 문서가 이기고, 한쪽에만 있는 문서(단어는 안 겹치나 의미가
  통하는 상품)도 살아남는다 — "의도로 검색"이 이걸로 성립.

## 3. 하이브리드를 언제 켜나 — 항상, 안전장치와 함께

검색어별 켜기/끄기 휴리스틱은 UX가 들쭉날쭉해지고 판단 로직이 유지보수 대상이 된다.
**모든 검색에 항상 하이브리드**, 대신:

- **쿼리 임베딩 캐시(Redis)**: key = 정규화된 검색어, TTL 7일. 검색어는 인기어에
  몰리므로 첫 사용자만 miss — OpenAI 호출은 "새 검색어 첫 등장"에만 발생.
  비용: 검색어 1건 10~20토큰, 캐시 0%여도 10만 검색 ≈ $0.03.
- **타임아웃 폴백**: 임베딩 호출 300ms 초과 시 해당 요청은 lexical만으로 응답.
  검색이 외부 API에 인질 잡히지 않는다.
- Phase 1은 lexical 레그만 구현. Phase 2에서 semantic 레그+RRF만 추가
  (msearch 구조라 레그 추가가 자연스러움).

## 4. lexical 레그

```jsonc
{
  "query": {
    "function_score": {
      "query": {
        "bool": {
          "must": [{ "multi_match": {
            "query": "<q>", "type": "best_fields", "tie_breaker": 0.3,
            "fields": ["name^3", "tags.text^2", "description^1.5", "content"]
          }}],
          "filter": [ /* productType terms, amount range, model — 점수 무관, 캐시됨 */ ]
        }
      },
      "functions": [
        { "field_value_factor": { "field": "salesCount", "modifier": "log1p" }, "weight": 0.3 },
        { "field_value_factor": { "field": "viewCount",  "modifier": "log1p" }, "weight": 0.1 },
        { "field_value_factor": { "field": "ratingAvg",  "missing": 0 },        "weight": 0.1 },
        { "gauss": { "firstPublishedAt": { "scale": "30d", "decay": 0.7 } },    "weight": 0.2 }
      ],
      "score_mode": "sum", "boost_mode": "sum"
    }
  }
}
```

관련도(BM25)가 주역, 실적·신선도는 동점 근처에서만 순서를 바꾸는 약한 가산점.
가중치는 전부 노브 등급 A (문서 7) — 행동 로그가 쌓이면 검색 품질 지표(문서 5)를 보고 조정.

semantic 레그는 같은 filter를 단 `knn` 쿼리 하나(k=50, num_candidates=200).

## 5. q가 없을 때 — 목록/정렬 (같은 인덱스, 레그 없이 한 방)

| 정렬 | 쿼리 |
|---|---|
| 인기순(기본) | `match_all` + 위 function_score를 정렬 점수로 사용 |
| 판매순 | `sort: [salesCount desc, _id]` |
| 최신순 | `sort: [firstPublishedAt desc, _id]` |

- 페이징: **search_after** (정렬 키 + `_id` tiebreaker). 정렬 변경 시마다 ES 재쿼리.
- 검색 결과(q 있음)는 깊게 가지 않으므로 from/size + 상한 100페이지.
- 콜드스타트 인기순: 판매 0인 초기에도 조회·평점·신선도 가산으로 자연스러운 순서가 나온다.

## 6. API 계약

- 기존 `GET /api/v1/products`(q, productType, sort, page, size) **URL·응답 계약 유지**,
  구현 소유만 search 패키지로 이관 (문서 6). 응답에 `searchId` 필드 추가(q 있을 때).
- ON_SALE만 노출 규칙은 인덱스 구조가 보장 (문서 1 — ON_SALE만 존재).

## 7. 장애 처리

ES 장애 시 검색·목록 API는 **기존 RDB 조회 경로로 폴백** (현행 getProducts 구현을
폴백으로 보존). 시맨틱·오타교정은 빠지지만 서비스는 유지 — "ES는 사본" 원칙의 쿼리 버전.
폴백 검색도 searchId 발급과 SEARCH_EXECUTED 발행은 그대로 한다(Kafka는 ES와 무관하게
동작) — 퍼널이 끊기지 않고, 로그에 `fallback: true, hybridUsed: false`로 구분해 기록한다.
