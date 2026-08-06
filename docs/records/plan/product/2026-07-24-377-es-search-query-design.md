# #377 — 상품 목록·검색 API Elasticsearch 전환 설계

> 구현 계획은 `product-service/docs/plans/2026-07-24-377-es-search-query-plan.md` 참고.
> 선행 설계: `2026-07-16-es-2-search-query-design.md`(원안), `2026-07-23-es-376-search-sync-
> redesign-design.md`(#376 재설계 — 이 문서가 §8에서 "#377 시작 시 반드시 재검토"를 명시함).

## 1. 배경 — 왜 지금 이걸 다시 짚는가

#376(ES 색인 파이프라인)이 develop에 머지되어 `product-service`에 `search` 패키지(색인 컨슈머,
7일 주기 재조정 배치, ES 클라이언트)가 이미 존재한다. 하지만 공개 상품 목록/검색 API
(`GET /api/v2/products`)는 아직 RDB만 조회한다 — ES는 색인만 될 뿐 아무도 읽지 않는 사본이다.
#377은 이 API의 조회 엔진을 ES로 전환해 nori 형태소 검색과 인기 가산 랭킹이 실제로 동작하게
한다.

브레인스토밍 중 두 가지가 새로 드러났다:

1. **재설계 문서(2026-07-23) §8이 명시한 경고** — "판매중단/삭제/admin 승인취소가 최대 7일
   지연되어도 무해하다"는 전제는 "공개 API가 아직 RDB만 본다"에 의존한다. #377이 ES로 전환하는
   순간 이 지연이 실제 사용자 노출 문제(검색엔 뜨는데 클릭하면 404인 "깨진 카드")가 된다.
2. **기존 버그** — `ProductSellerService.createProduct()`가 생성 직후(DRAFT, 검수 전) 상태에서도
   `publishProductChanged`를 발행하는데, `ProductSearchEventHandler.reconcileFamily()`가
   `family.currentOnSale()`이 없을 때 `sellerHistory().get(0)`(상태 무관 최신 버전)로 폴백해
   무조건 upsert한다. 지금 코드 그대로 #377을 얹으면 **판매자가 상품을 등록만 해도(승인 전)
   검색에 노출될 수 있다.**

두 문제 모두 "ES 문서 존재 = ON_SALE 노출"이라는 인덱스 설계 원칙(2026-07-16-es-1 §1)이 코드로는
지켜지지 않고 있던 경우다. #377은 조회 전환과 함께 이 원칙을 실제로 강제하는 로직까지 고친다 —
별도 이슈로 미루지 않는다. 조회 전환이 이 버그를 실사용자에게 노출시키는 당사자이기 때문이다.

**주문 확정 시점의 안전성은 별개로 이미 보장되어 있다**(코드 확인 완료):
`ProductGrpcService.getOrderSnapshots/getCartSnapshots`(product-service)는 검색 결과가 아니라
그 순간 RDB를 `ProductFamily::currentOnSale`로 재조회해서 필터링한다. 따라서 검색에 stale 상품이
잠깐 보이더라도 실제 구매가 체결되지는 않는다 — 이번 작업의 목표는 "깨진 카드"를 줄이는 것이지
트랜잭션 정합성 문제를 막는 것이 아니다(그 정합성은 이미 다른 계층이 책임진다).

## 2. 범위 확정 — 계약 불변

- 엔드포인트: `GET /api/v2/products` 그대로(설계 원문서들이 "v1"이라 쓴 건 #273 이전의 stale
  참조 — 실제 서빙 경로는 `/api/v2`). URL·쿼리 파라미터(`q`, `productType`, `sort`, `page`,
  `size`)·응답 필드(`docs/api-spec/product.md`) 전부 동일. 문서 수정 불필요.
- 정렬 값은 `popular|rating|price-asc` **3종으로 확정** — 원 설계 문서(es-2)가 가정한
  "인기/판매/최신"(popular/sales/latest)은 실제 계약과 달라 채택하지 않는다. 기존 계약엔
  `price-desc`도 있었지만 FE(`app/browse/page.tsx`, `app/page.tsx`)가 실제로 쓰지 않아 이번에
  제거한다 — `docs/api-spec/product.md`의 sort enum과 `ProductQueryService.normalizeSort`(RDB/ES
  공유)에서 `price-desc` 특수 처리를 없앤다.
- 컨트롤러 소유는 `product.presentation.controller.ProductController`에 그대로 둔다(search
  패키지로 이관하지 않음) — 원 설계(es-6)는 "컨트롤러 소유를 search로 이관"을 제안했지만, #376이
  실제로 만든 `ReindexController`가 이미 `product` 패키지에 남아 `search` 패키지의
  `ProductReindexService`를 직접 호출하는 선례를 만들어뒀다. 그 선례를 따른다 — search 패키지는
  "ES에서 데이터를 가져오는" 조회 전용 컴포넌트만 제공하고, product 패키지가 계속 API 표면을
  소유한다.

## 3. 정렬·랭킹 설계

- `popular`(기본) — `function_score`: 관련도(q 있으면 nori multi_match) 위에 salesCount(family
  합산)/viewCount(family 합산)/ratingAvg/firstPublishedAt 신선도를 약하게 가산. 가중치는 원
  설계(es-7 튜닝 노브 문서)의 초기값(0.3/0.1/0.1/0.2, gauss 30d/0.7)을 그대로 가져오되
  `prompthub.search.ranking.*` config로 빼서 등급 A 노브로 유지한다.
- `rating`/`price-asc` — 단순 ES 필드 정렬. 오늘 RDB가 하는 것과 동일한 의미를
  ES로 자리만 옮긴다(오름차순/내림차순 의미 변경 없음 — "가격순 UX가 오름차순이 맞는지"는 FE
  라벨링 문제로 이번 범위 밖).
- q 유무와 무관하게 정렬 로직은 동일 — q가 있어도 `sort≠popular`면 관련도 대신 지정 정렬을
  그대로 적용한다(오늘 RDB 동작과 동치).

## 4. 페이지네이션 — search_after 대신 from/size

원 설계(es-2)는 "목록은 search_after"를 가정했지만, 실제 계약은 정수 `page` 파라미터(오프셋
방식)이고 opaque cursor가 없다. search_after를 쓰려면 계약 자체를 바꿔야 해서 "URL·응답 계약
유지" 원칙과 충돌한다. 대신 ES `from=(page-1)*size`/`size`로 통일한다 — 카탈로그 규모상 deep
pagination 비용 문제도 실질적으로 없다. `track_total_hits: true`로 정확한 total을 받아
`meta.total`/`meta.hasNext`를 오늘과 동일하게 계산한다.

FE는 현재 `page`/`size`를 아예 보내지 않고(기본값 1/20에만 의존) 페이지네이션 UI 자체가 없다 —
이건 이 BE 계약과 무관한 FE 기능 공백이라, 별도로 `beadv6_6_3JMT_FE`에 이슈+구현을 진행한다
(§7).

## 5. RDB 폴백

`ProductQueryService.getProducts(...)`가 ES 조회를 먼저 시도하고, 예외 시 **기존 RDB 코드
경로를 한 글자도 바꾸지 않고** 그대로 호출한다. "ES는 사본" 원칙의 조회판 — 오늘 동작하는 그대로가
안전망이 된다.

## 6. 삭제 정합성 — 새 아키텍처가 아니라 기존 로직의 빠진 분기

핵심 발견: 지금 버그(DRAFT 노출)와 지연 문제(판매중단 노출 지속)는 사실 **하나의 로직 조각**이
빠져서 생긴다 — "ON_SALE이 있으면 upsert, 없으면 삭제"에서 "없으면 삭제" 분기가 아예 없었다.

- **product-service 자체 행동(생성/패치/판매중단)**: `reconcileFamily()`가 `currentOnSale()`
  유무로 분기하도록 고치고(있으면 upsert, 없으면 `bulkReconcile(빈 리스트, [familyRootId])`로
  삭제 — 기존 벌크 메서드 재사용, 신규 인덱서 API 불필요), 컨슈머가 `PRODUCT_STOPPED`도
  구독하도록 확장한다. product-service는 이미 Kafka를 갖고 있으므로 이 경로는 **완전 실시간**이
  된다. 부수 효과로 "생성 직후 DRAFT가 색인되는" 기존 버그도 함께 사라진다(생성 시
  `currentOnSale()`이 없으니 애초에 upsert를 안 함).
- **admin-service 발 변화(승인취소)**: admin-service는 Kafka를 전혀 모른다(#376 재설계에서
  의도적으로 유지한 경계 — 되돌리면 그때 걷어낸 outbox 복잡도가 재점화될 수 있어 이번엔 건드리지
  않는다). 이 경로만 트리거할 이벤트가 없으므로, 기존 `ProductReconcileScheduler`의 전체 비교
  주기를 7일 → 15~30초로 단축해 안전망으로 쓴다. 카탈로그 규모(수십~수천 개)에서는 이 주기로
  돌려도 메모리·부하 문제가 없다(매 실행 임시 데이터, GC로 즉시 회수) — 카탈로그가 수만 개 이상
  으로 커지면 그때 "바뀐 것만" 증분 조회 방식으로 바꾼다(지금은 과설계).

이 하이브리드가 "왜 두 갈래냐"의 답: 실시간/배치 두 메커니즘을 새로 설계한 게 아니라, **같은
상태 확인 로직 하나**를 (1) product-service 자신의 Kafka 이벤트가 트리거하는 경우와 (2) admin의
변화처럼 트리거할 이벤트가 아예 없어서 주기적으로 대신 확인해야 하는 경우로 나눠 재사용할 뿐이다.

## 6.5. #553 — 부트스트랩·재조정 레이스 컨디션 (같은 브랜치/PR에서 함께 수정)

브레인스토밍 도중 2026-07-24 배포 로그에서 실제로 재현된 별도 버그를 발견했다: ES 인덱스가 아직
없는 상태에서 기동하면 `ProductIndexBootstrap.createIndexIfMissing()`(ApplicationReadyEvent)과
`ProductReconcileScheduler.reconcile()`(첫 tick)이 거의 동시에 실행되어, 스케줄러가 먼저 돌면
`index_not_found_exception`이 난다(Spring 기본 에러 핸들러가 로그만 남기고 삼켜 앱 기동 자체는
정상). 첫 실행이 실패하면 다음 정상 실행까지 재조정 안전망이 비어있는데, 이번 이슈에서 그 주기를
15~30초로 줄이면서 영향(공백 기간)은 크게 줄어들지만 근본 원인(기동 순서 미보장)은 남는다.

이 이슈(#376이 만든 두 컴포넌트 간 문제)는 별도 GitHub 이슈(#553)로 등록했지만, #377이 바로 이
재조정 배치를 훨씬 자주 돌리게 만드는 당사자라 같은 브랜치/PR에서 함께 고친다(`Closes #377,
Closes #553`). 고치는 방법은 `ProductIndexBootstrap`이 이미 쓰는 `existsAlias` 체크 패턴을
`ProductReindexService.reconcileAll()` 진입부에 가드로 재사용 — 인덱스가 없으면 이번 사이클을
조용히 건너뛴다. 새 아키텍처나 Spring 라이프사이클 순서 조정(fragile)에 기대지 않는, 방어적이고
결정적인 수정이다.

## 7. 범위 외 / 후속

- `searchId`/`SEARCH_EXECUTED` 행동로그 — #381(behavior-events 토픽·컨슈머·저장소 자체가 아직
  없음, #381의 몫).
- 시맨틱/kNN 하이브리드(RRF) — #378. 자동완성/오타교정 — #379.
- admin-service Kafka 발행 경로 추가 — 이번엔 안 함.
- **FE 후속(별도 저장소, 실제 구현까지 진행)**: `beadv6_6_3JMT_FE`의 `browse` 페이지에 `page`/
  `size` 전달 + 페이지네이션 UI, 판매중지/삭제 상품 구매 시도 시 alert+탐색목록 redirect.
