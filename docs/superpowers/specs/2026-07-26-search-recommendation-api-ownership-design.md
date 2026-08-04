# 검색·추천 Phase 2/3 설계 변경 — API 소유권·벡터 저장소·범위 축소

> 최초 작성 2026-07-26, 최종 개정 2026-07-27.
>
> 2026-07-16 작성된 `es-0`~`es-7` 설계 중 **컨트롤러 소유권**과 **벡터 저장·조회 위치**를
> 뒤집고, Phase 2/3의 범위를 축소한다. 인덱스 모델링(es-1), 하이브리드 쿼리 전략(es-2),
> 이벤트 계약(es-5)의 골자는 유효하다.
>
> **이 문서는 5개 이슈에 걸친 결정 기록이며, 단일 구현 계획이 아니다.** es-6 §3의 방침대로
> 구현 계획(`-plan.md`)은 이슈별로 따로 작성한다 — 이 문서는 그 계획들이 공통으로 참조하는
> 기준선이다.

## 배경 — 왜 원안을 바꾸는가

원안(es-6)은 `recommendation → search → product` 방향으로, 공개 엔드포인트를 기능 패키지가
소유하도록 설계했다. 근거는 "나중에 서비스로 분리할 때 잘리는 선을 미리 만든다"였다.

그런데 Phase 1(#376, #377)이 실제 구현될 때 이 이관은 일어나지 않았다. 컨트롤러는
`product.presentation.controller.ProductController`에 그대로 남고, ES 조회는
`search.application.ProductSearchQueryService` **포트를 호출하는 방식**으로 붙었다.
결과적으로 잘 동작하고 리뷰·머지를 통과했다.

여기에 사용자 판단이 더해졌다: **recommendation은 추천 로직을 담는 단위이지, 공개 API의
주체가 되어선 안 된다.** 상품 리소스(`/products/**`)의 대외 계약 주체는 product 하나로
유지하는 것이 REST 리소스 관점에서도 자연스럽다.

즉 이 변경은 새 방향의 도입이 아니라, **Phase 1이 이미 검증한 패턴을 Phase 2/3에도
일관되게 적용하는 것**이다.

## 결정

### D11 — 공개 API 주체는 product, 기능 패키지는 포트로만 참여

| 항목 | 결정 |
|---|---|
| 공개 엔드포인트 소유 | `ProductController` 단독. search/recommendation 패키지에 컨트롤러를 두지 않는다 |
| URL 네임스페이스 | 상품 리소스는 `/products/**` 아래로 유지 |
| 의존 방향 | `product → search`, `product → recommendation` |
| 기능 패키지의 역할 | ES 클라이언트·임베딩·kNN 등 기술 구현을 포트 뒤에 숨긴다 |
| 패키지 분리 자체 | 유지 (D1 모듈러 모놀리스). 바뀌는 건 컨트롤러 위치뿐 |

es-6 §2의 "product는 검색·추천의 존재를 모른다"는 전제는 폐기한다. product는 두 포트
인터페이스를 알지만, ES·임베딩·kNN 같은 기술 세부는 여전히 모른다 — Phase 1의
`ProductSearchQueryService` 의존과 동일한 깊이의 관계다.

**서비스 분리 시 비용**은 원안 대비 늘어난다(포트 구현을 원격 호출로 교체 + 컨트롤러가
원격 호출로 바뀜). 현재 분리 계획이 없고, 원안의 이관 비용을 지금 지불하는 것보다 이 비용을
나중에 지불하는 편이 낫다고 판단해 수용한다.

**내부 이벤트 발행은 D11의 적용 대상이 아니다.** `SEARCH_EXECUTED`처럼 요청 처리 중
발생하는 이벤트는 실제로 그 일을 수행하는 패키지(search)가 발행한다. D11은 *공개 API의
주체*를 정하는 규칙이다.

### D12 — 임베딩은 Postgres가 원본, ES는 검색용 복사본

| 용도 | 저장소 |
|---|---|
| 임베딩 원본 | **Postgres** `product.embedding vector(1536)` + `embedding_source_hash` |
| 비슷한 상품 추천(#380) kNN | **Postgres** (pgvector, SQL 1회) |
| 하이브리드 검색(#378) kNN 레그 | **ES** `dense_vector` (PG에서 복사) |
| 목록·정렬(#377), 자동완성(#379) | **ES** |

**Postgres를 원본으로 두는 이유**

- ES 인덱스가 유실돼도 임베딩이 남아, 전체 재색인이 OpenAI 재호출 0회로 끝난다.
  설계 문서의 "ES는 전부 재구성 가능한 사본"이라는 전제가 임베딩에도 실제로 성립한다
- 임베딩이 특별한 ES 전용 산출물이 아니라 `name`·`amount`처럼 평범한 컬럼이 된다.
  쓰는 곳 1개(product 쓰기 흐름), 복사하는 곳 1개(ES 색인기)

**#380이 Postgres에서 조회하는 이유**

- SQL 1회로 끝난다(ES는 문서에서 벡터를 꺼낸 뒤 다시 kNN — 왕복 2회)
- ON_SALE·자기 family 제외가 실제 컬럼 조건이라 색인 동기화 정확성에 의존하지 않는다
- 색인 지연(최대 20초)과 ES 장애에 영향받지 않는다. 폴백 이중 경로가 불필요해진다

**#378이 ES를 유지하는 이유**

RRF는 lexical과 벡터 두 순위를 병합하므로 두 레그가 같은 시스템에 있어야 `msearch` 한 번으로
끝난다. 벡터만 Postgres에 두면 두 시스템을 각각 조회해 교차 병합해야 하고 왕복·실패 지점·
페이징이 모두 복잡해진다.

**대가**: pgvector 확장이 필요해 커스텀 Postgres 이미지를 만들어야 한다(#586). Postgres는
전 서비스 공유 인프라라 blast radius가 ES(#583)보다 크다.

### 그 외 확정 사항

- **`related` → `recommended`** 전면 통일 (URL은 #496에서 이미 `/recommends`)
- **#380 재정렬**: `코사인유사도 + (같은 productType이면 typeBonus 0.05)`.
  코사인이 0~1로 유계라 이 값은 "다른 유형이 앞서려면 유사도가 얼마나 더 높아야 하는가"로
  직접 해석된다. `salesCount` 항은 스케일이 무계여서 넣지 않는다
- **#585 재조정 주기 20초 유지**, 근거를 신규 기록: *admin 승인 → 검색 노출 지연 예산이며,
  관리자가 승인 후 확인하는 작업 루프(5~15초)를 크게 넘기지 않아야 한다. 증분 전환 후
  사이클당 비용이 0건 쿼리 2개이므로 비용이 아니라 UX가 기준이다.*
- **admin ON_SALE 아웃박스 이벤트는 진행하지 않는다.** #585의 증분 전환으로 폴링 비용이
  무의미해져, 남는 이득이 "지연 20초 → 즉시"뿐이다

## 이슈별 최종 범위

| 이슈 | 범위 | 상태 |
|---|---|---|
| #378 시맨틱 하이브리드 | PG 임베딩 컬럼·부분 HNSW 인덱스, product 쓰기 흐름에서 생성, ES로 복사, msearch+RRF, in-process LRU 캐시 | 진행 |
| #379 자동완성 | `GET /products/suggest` — 기존 `name` 필드에 `match_phrase_prefix` 1회 + `salesCount desc` | 진행(축소) |
| #380 비슷한 상품 | pgvector SQL kNN, 타입 보너스 재정렬, `recommended` 리네임, `recommendation` 패키지 신설(컨트롤러 없음) | 진행 |
| #381 행동 로그 | `behavior-events` 토픽, `behavior-logs` data stream, 기존 order 컨슈머 확장 | 진행 |
| #382 히스토리·인기검색어 | — | **보류** |
| #383 개인화 홈 추천 | — | **close** |
| #582 ES 인증 전환 | HTTPS·인증 지원, 전환 가이드, 계정 분리 | 신규 |
| #583 nori ES 이미지 | 커스텀 이미지 GHCR 퍼블리시 + k8s 적용 | 신규 |
| #585 재조정 증분 전환 | N+1 제거, 증분 조회, 일 1회 전체 스윕 | 신규 |
| #586 pgvector PG 이미지 | alpine 유지 커스텀 이미지 | 신규 |
| FE #25 | 자동완성 드롭다운 | 신규 |
| FE #26 | `searchId` 전달 + 추천 섹션 라벨 | 신규 |

## 검토 과정·기각된 대안

이 절은 결론뿐 아니라 **기각된 선택지와 그 이유**를 남긴다. 목적은 같은 논의를 다시 하지 않는
것이며, 판단이 틀렸던 기록도 함께 남긴다.

### 설계 방향이 뒤집힌 것

| 쟁점 | 원안 | 결론 | 뒤집힌 이유 |
|---|---|---|---|
| 컨트롤러 소유권 | recommendation/search가 소유 | product 단독 | #377이 이미 포트 호출 방식으로 구현·검증됨. recommendation은 추천 로직 단위이지 공개 API 주체가 아니다 |
| 벡터 저장·조회 | ES `dense_vector` + kNN | PG(pgvector) 원본 + ES 복사본 | ES 유실 시 임베딩 재생성 불가, #380의 ES 장애 독립성, 색인 지연 없는 필터링 |
| `related` 네이밍 | 유지 | `recommended`로 통일 | #383 미진행으로 "item-to-item vs personalized" 구분 대상이 사라짐 |
| #379 범위 | 자모·초성·오타교정 포함 | `match_phrase_prefix` 단독 | prompthub는 "아는 물건 검색"이 아니라 "의도 검색" — 사용자가 상품명을 모른다. 초성 검색은 발동 상황 자체가 없다 |
| #380 재정렬 | 가중합(타입 가산 + log1p(salesCount)) | 타입 보너스 1개만 | salesCount는 스케일이 무계여서 가중치의 근거·해석이 없다. 코사인 공간에서 해석되는 항만 남긴다 |
| #382 | Phase 3-2로 진행 | 보류 | 최근 검색어는 브라우저 autofill이 이미 제공, 인기 검색어는 집계할 트래픽이 없음 |
| #383 | Phase 3-3으로 진행 | close | 행동 데이터 없이는 콜드스타트 폴백으로만 동작 |
| admin 아웃박스 | 폴링의 근본 원인 대응 | 진행 안 함 | #585 증분 전환으로 폴링이 무료가 되어 남는 이득이 지연 20초뿐 |

### 판단이 틀렸던 기록

| 주장 | 실제 | 교훈 |
|---|---|---|
| "recommendation 패키지는 과설계, search에 넣으면 포트·impl·의존선이 삭제된다" | **절감 과장.** 포트와 impl은 양쪽 다 존재하고 실제 차이는 디렉터리 1개와 호출 1홉뿐 | 절감을 주장하기 전에 양쪽을 실제로 세어볼 것 |
| "ES 필터링은 문제없다 — 인덱스에 ON_SALE만 들어가므로" | **정상 동작만 본 판단.** 재조정 지연(최대 20초)과 ES 장애 구간을 빼먹음 | 정상 경로가 아니라 지연·장애 구간까지 볼 것 |
| "워터마크 증분은 리뷰 평점 변화를 놓친다" | **기우.** `findChangedFamilyRootIds`가 product·review 변경을 이미 합집합으로 처리 | 우려를 제기하기 전에 기존 코드를 먼저 읽을 것 |
| "3키 봉투로 고정해야 FE 계약이 안 깨진다" | **근거 오류.** JSON 키 추가는 비파괴적이고, 새 섹션 렌더링엔 어차피 FE 변경 필요 | "미리 만들어두면 나중에 편하다"는 대개 투기 |
| "기존 공유 Redis 재사용이니 비용 0" | 인스턴스는 기존이나 **product-service엔 새 의존성**. 게다가 `replicas: 1`이라 공유 이득 자체가 없음 | "이미 있는 것"과 "이 모듈에 이미 있는 것"은 다름 |
| 20초 주기를 근거로 사용 | **근거가 기록된 적 없음.** #377 계획서는 "짧은 값(15~30초, 예: 20000)" | 물려받은 값을 근거처럼 인용하지 말 것 |
| "하이브리드 검색은 pgvector와 ES를 비교하는 것" (사용자 이해) | 하이브리드의 두 갈래는 **저장소 2개가 아니라 검색 방법 2개**(BM25 + 벡터) | 용어를 먼저 맞출 것 |

### 발견된 기존 문제

| 발견 | 조치 |
|---|---|
| `reconcileAll`이 family당 4쿼리(N+1) + 변경 없이도 전체 재작성 | #585 |
| `findChangedFamilyRootIds`·`getAverageRatings(List)` 배치 API가 죽은 코드 | #585에서 사용 |
| `findFamilyRootIdsByReviewUpdatedSince`가 family 루트가 아닌 `r.product.id` 반환 | #585 선행 검증 |
| k8s ES가 stock 이미지라 nori 없음 → 배포 시 검색이 조용히 RDB 폴백 | #583 |
| ES 연결이 HTTP·무인증 | #582 |
| rules/CLAUDE.md의 URL이 `/api/v1/.../related`로 2번 낡음(#496·#273 미반영) | #380에서 정정 |
| #567 코멘트에서 약속한 후속 이슈 2건이 미생성 | #582·#583 생성, PR에 알림 |

### 공유 ES 노드 용량 — 세입자가 늘고 있다

heap 1GB 단일 노드에 순차 유입 예정: `products-v1`(현재) · `gateway-access-*`(#567) ·
`logs-prompthub.application-dev`/`infrastructure-dev`(#575, 전 서비스+인프라 로그) ·
결제 감사로그(#540) · `behavior-logs`(#381).

D12(벡터를 PG로)와 #585(20초마다 전체 재작성 제거)가 이 노드의 압박을 줄이는 방향으로
작용한다 — 각각 다른 이유로 결정됐으나 결과적으로 정합적이다.

### 미해결 — 별도 판단 필요

- **#575가 product-service에 요구하는 로그 표준화**: ECS JSON stdout(Boot 4.1 내장 기능이라
  yml 2줄), `X-Request-Id` → MDC 필터, HTTP 요청 로그, 예외 핸들러 구조화 필드.
  현재 `ProductExceptionHandler`는 request id를 메시지 문자열에 넣고 있어 Kibana에서
  `http.request.id`로 검색되지 않는다. 9개 서비스 공통 작업이라 분담 조율이 필요하다

## 브랜치 전략

```
#586(pgvector 이미지) ──┐
                        ├─→ #378(시맨틱) ──→ #380(kNN 추천)
#585(재조정 증분) ──────┘
#379(자동완성)  ← 독립
#381(행동 로그) ← 독립
#583(nori 이미지) ← 배포 전제, 독립
#582(ES 인증) ← #567 머지 후
```

**병렬 착수 가능**: #379, #381, #583, #585, #586
**선행 필요**: #378(#586 이후) → #380(#378 이후)

각 브랜치는 최신 develop에서 시작한다(`create-branch` 스킬). 선행 이슈가 머지된 뒤 새로
브랜치를 파므로 스택 리베이스가 없다.

## 테스트 전략

es-6 §4를 따른다. 순수 로직은 유닛(RRF 병합, 재정렬 점수, 해시 스킵 판단), ES 통합은
Testcontainers, OpenAI는 `EmbeddingPort` fake, 컨슈머는 멱등성 검증.

D11 관련 추가 검증: 각 이슈의 컨트롤러 테스트가 `ProductControllerTest`에 추가되는지
(별도 `SearchControllerTest`/`RecommendationControllerTest`가 생기지 않는지) 확인.

#585 관련 핵심 회귀: 변경이 없는 사이클에서 `bulkReconcile`이 호출되지 않는지.

## 원본 설계 문서와의 관계

| 문서 | 이 문서가 대체하는 부분 |
|---|---|
| es-1 인덱스 모델링 | 임베딩 저장 위치 (ES 전용 → PG 원본 + ES 복사본) |
| es-3 자동완성·오타교정 | #379 범위 (자모·초성·오타교정 제외). 원안은 확장 시 참조용으로 유효 |
| es-4 추천 | kNN 조회 위치(ES → pgvector), 컨트롤러 이관 폐기, 재정렬 방식 |
| es-6 패키지·Phase | §1 컨트롤러 배치, §2 의존 방향 |
| es-7 튜닝 노브 | 재정렬 가중치·오타 임계·자모 관련 노브 제외, 타입 보너스 추가 |

es-0(개요·결정 로그), es-2(하이브리드 쿼리·RRF), es-5(이벤트 계약)는 골자가 유효하다.

**#376·#377의 plan 문서는 수정하지 않는다** — 이미 구현·머지된 작업의 이력 기록이므로
소급 수정하면 "무엇을 어떻게 구현했는지"가 사라진다.

---

## 2026-07-29 코드 실측 대조

이 문서를 포함해 es-0~es-7의 마지막 개정은 2026-07-27 00:30~00:46이다. 그 **약 4시간 뒤부터**
코드가 계속 움직였다 — nori 제거(`ef6c3d68`, 04:36), #583·#594·#602 close(07:38~), `extractedText`
매핑 필드 제거(`a961d319`, 11:36), 이후 #378·#380 구현.

아래는 2026-07-29에 코드를 직접 읽어 대조한 결과다. **원문은 고치지 않는다** — 어느 서술을
지금 믿으면 안 되는지만 표시한다.

| # | 문서 | 문서가 말하는 것 | 코드 실제 | 조치 |
|---|---|---|---|---|
| 1 | es-0/1/2/6/7, 이 문서 | 한국어 형태소 분석기(nori)를 쓴다. #583으로 커스텀 이미지 퍼블리시 | 매핑에 `analyzer` 지정이 **하나도 없다**(전부 standard). `docker/elasticsearch/Dockerfile`도 삭제됨. **#583은 CLOSED/NOT_PLANNED** | 폐기. nori 관련 서술 전부 무효 |
| 2 | es-3 배너 | `name.jamo`·`name.chosung` 멀티필드가 **이미 있어 리인덱스 없이** 되살릴 수 있다 | `name`의 서브필드는 `keyword` 하나뿐. **되살리려면 리인덱스가 필요하다** | 배너의 복구 비용 서술이 틀림 |
| 3 | es-0 §스코프 제외, es-1 | 매핑에 `extractedText` 자리를 예약해 둠 | 제거됨(`a961d319`). #602 재개 시 매핑 추가 필요 | 정정 |
| 4 | es-6 §1 | 패키지가 `com.prompthub.product.search` (product 하위) | `com.prompthub.search`, `com.prompthub.recommendation` — **product의 형제** | 정정 |
| 5 | es-2/3/4/6 | `GET /api/v1/products`, `/api/v1/search/suggest`, `/api/v1/products/{id}/related` | `/api/v2/products`, `/api/v2/products/suggest`, `/api/v2/products/{productId}/recommends` | 정정. rules는 이미 고쳐졌으나 spec 본문은 미정정 |
| 6 | es-7 §노브 | prefix가 `search.ranking.*`, `search.suggest.*`, `search.pipeline.*`, `recommendation.*` | `prompthub.search.ranking.*` 하나만 존재. 나머지 셋은 **코드에 없음**(추천 타입 보너스는 상수 하드코딩, 의도적) | 정정 |
| 7 | es-1 §5, es-7 등급 A 표 | 카운트 동기화 배치 **10분** | `fixed-delay-ms: 20000`(20초) + 매일 04:00 전체 스윕 | es-7 배너는 정정됐으나 본문 표는 그대로 |
| 8 | es-2 §3, es-7 등급 A | 쿼리 임베딩 캐시는 Redis, TTL 7일, LRU 50MB | in-process `LinkedHashMap` LRU **1000개**(≈6MB), TTL 없음, 300ms 예산 | es-7 배너는 정정됐으나 es-2 본문·es-7 표는 그대로 |
| 9 | es-2, es-7 | kNN `k=50`, `num_candidates=200` | `k=size`, `num_candidates=size*2`, **`similarity=0.35`** | 정정. **유사도 하한 0.35는 어느 설계 문서에도 없다** — #645에서 "검색 0건이 나올 수 없는" 버그를 잡으며 추가된 노브 |
| 10 | 이 문서 §이슈별 최종 범위 | #583 "신규". #594·#602는 표에 없음 | #583 CLOSED/NOT_PLANNED · #594 CLOSED/NOT_PLANNED · #602 CLOSED/NOT_PLANNED · **#586 CLOSED/COMPLETED** · #381 OPEN/REOPENED | 인벤토리가 낡음 |

### 문서에 없는데 코드에 있는 것

설계 문서가 뒤처진 것과 별개로, **구현 과정에서 생겼는데 어느 문서에도 기록되지 않은 값**이
있다. 가장 중요한 게 9번의 `MIN_SEMANTIC_SIMILARITY = 0.35f`다.

이 값이 없으면 색인 문서 수가 `k`보다 적을 때 어떤 질의든 전체 문서가 후보로 들어와
**결과 0건이 나올 수 없게 된다**(#645에서 실제로 재현). 존재 이유는 실측이지만 값 자체는
코드 주석대로 "실측 없이 잡은 첫 컷"이다.

### 이 대조의 결론

**es-0~es-7은 이제 "원안 이력"으로만 읽는다.** 현재 구현을 알고 싶으면 코드이거나, 아래
포트폴리오 문서를 본다(2026-07-29 게시).

- `decisions/product-service/es-vector-search-end-to-end-flow` — 색인·검색·추천 전 과정
- `decisions/product-service/search-quality-tuning-values-rationale` — 값 20개와 근거 등급
- `decisions/product-service/search-roadmap-deferred-issues-and-reopen-conditions` — 중단 5건과 재개 조건
