# #377 — 상품 목록·검색 API Elasticsearch 전환 구현 계획

> 설계 배경/근거는 `docs/superpowers/specs/2026-07-24-377-es-search-query-design.md`(로컬 전용)
> 참고. 이 문서는 실제 구현 순서·대상 파일을 다룬다.

## 범위 확정

- `GET /api/v2/products` URL·쿼리 파라미터·응답 필드 동일, 신규 값 추가 없음.
- **정렬은 3종(`popular|rating|price-asc`)으로 축소** — 기존 계약엔 `price-desc`도 있었지만 FE가
  실제로 쓰지 않아(  `app/browse/page.tsx`, `app/page.tsx` 확인 완료) 이번에 계약에서 제거한다.
  `docs/api-spec/product.md`의 sort enum 설명과 `ProductQueryService.normalizeSort`(RDB 경로,
  ES 경로가 공유)에서 `price-desc` 특수 처리를 없앤다 — 인식 못 하는 값은 기존과 동일하게
  `popular`로 떨어진다(에러 아님, 단순히 특별 취급을 그만두는 것).
- 컨트롤러는 `product.presentation.controller.ProductController`에 그대로(이관 없음).

## 작업 순서

### 1. viewCount family 합산 (선행 — 이후 단계가 이 값을 씀)

- `ProductRepository`/`ProductJpaRepository`에 `sumViewCountByFamilyRootId(UUID)` 추가
  (`sumSalesCountByFamilyRootId`와 동일 패턴).
- `search.application.FamilyUpsertInput`에 `familyViewCount` 필드 추가.
- `ProductSearchEventHandler.reconcileFamily()`, `ProductReindexService.reconcileAll()`에서
  `onSale.getViewCount()` 대신 새 합산값 사용.
- `ElasticsearchProductSearchIndexer.buildDocument(...)`/`upsert(...)` 시그니처를
  `familySalesCount`와 나란히 `familyViewCount` 받도록 조정.
- persistence 테스트: 버전 교체 후에도 과거 조회수 합산이 유지되는지 검증.

### 2. 삭제 정합성 — `reconcileFamily` 분기 수정 + 실시간 STOPPED 반영

- `search.application.ProductSearchEventHandler.reconcileFamily(familyRootId)`:
  - `family.currentOnSale()` 있음 → 기존처럼 upsert.
  - 없음 → `sellerHistory().get(0)` 폴백 **삭제**, 대신
    `productSearchIndexer.bulkReconcile(List.of(), List.of(familyRootId))` 호출.
- `search.infra.messaging.ProductSearchEventConsumer.handle(...)`에 `PRODUCT_STOPPED`(방어적으로
  `PRODUCT_DELETED`도, 실제로는 DRAFT 삭제라 no-op) 분기 추가 — payload의 `productId`로
  `productRepository.findById(productId).map(Product::familyRootId)` 조회 후 동일
  `reconcileFamily(familyRootId)` 호출.
- 단위 테스트(`ProductSearchEventHandlerTest`): `PRODUCT_CHANGED` on 생성(DRAFT) → upsert 호출
  안 됨(버그 수정 검증) / on 패치(ON_SALE) → upsert 호출됨 / `PRODUCT_STOPPED` → `bulkReconcile`
  delete 목록에 familyRootId 포함되어 호출됨.

### 3. 재조정 배치 주기 단축

- `config/src/main/resources/configs/product-service.yml`와
  `product-service/src/main/resources/application-local.yml`의
  `prompthub.search.reconcile.fixed-delay-ms`를 `604800000`(7일) → 짧은 값(15~30초, 예:
  `20000`)으로 변경.

### 3.5. #553 — 부트스트랩·재조정 레이스 컨디션 수정 (같은 PR, `Closes #553`)

ES 인덱스가 아직 없는 상태(최초 배포 등)에서 기동하면 `ProductIndexBootstrap`(ApplicationReadyEvent)과
`ProductReconcileScheduler`의 첫 tick이 거의 동시에 실행돼 `index_not_found_exception`이 나는
레이스 컨디션(2026-07-24 배포 로그에서 재현, 이슈 #553). `ProductIndexBootstrap`이 이미 쓰는
`client.indices().existsAlias(e -> e.name(ALIAS)).value()` 패턴을 재조정 진입부에서 재사용해서
고친다.

- `search.application.ProductSearchIndexer` 포트에 `boolean indexExists()` 추가.
- `ElasticsearchProductSearchIndexer`에서 `client.indices().existsAlias(e ->
  e.name(ProductIndexBootstrap.ALIAS)).value()`로 구현.
- `ProductReindexService.reconcileAll()` 맨 앞에 가드 추가: `indexExists()`가 false면
  `log.info(...)` 후 즉시 return. 스케줄러와 온디맨드 컨트롤러(`/internal/search/reindex`)
  양쪽에 같은 서비스 메서드를 공유하므로 자동으로 적용됨.
- 단위 테스트: `indexExists()`가 false면 `reconcileAll()`이 RDB 조회/`bulkReconcile` 호출 없이
  즉시 반환하는지 검증.
- PR 본문에 `Closes #377, Closes #553` 명시.

### 4. ES 조회 컴포넌트 신규 작성 (search 패키지)

- `search.application.ProductSearchQueryService`: q/productType/sort/page/size를 받아 ES 쿼리를
  구성·실행, 결과(히트 목록 + total)를 반환.
- `search.infra.es.ElasticsearchProductSearchQuerier`(또는 동등한 이름): 읽기 전용 어댑터.
  기존 `ElasticsearchClientConfig`의 클라이언트 빈, `ProductIndexBootstrap.ALIAS` 재사용.
- 쿼리 구성:
  - `productType` != `all` → `term` 필터.
  - `popular` → `function_score`(q 있으면 `multi_match` name^3/tags.text^2/description^1.5/
    content, 없으면 `match_all`) + functions(salesCount/viewCount/ratingAvg weight, firstPublishedAt
    gauss) — 가중치는 신규 `prompthub.search.ranking.*` `@ConfigurationProperties`로 뺀다(초기값
    0.3/0.1/0.1/0.2, scale 30d, decay 0.7).
  - `rating`/`price-asc` → 단순 `sort` 절(`ratingAvg`/`amount` + `_id` tiebreaker).
  - 페이징: `from=(page-1)*size`, `size=size`, `track_total_hits: true`.
- 쿼리 빌더 유닛 테스트(ES 없이, 순수 함수로 쿼리 구성 검증) + Testcontainers 통합 테스트
  (`ElasticsearchIntegrationTestSupport` 재사용): 3개 정렬 순서, nori 검색 매칭, productType 필터,
  **페이지네이션 정확성**(size=2로 여러 페이지 요청 시 중복/누락 없음, `hasNext` 정확성).

### 5. product 패키지 연결 — ES 우선, RDB 폴백

- `ProductQueryService.getProducts(...)` 수정: `ProductSearchQueryService` 호출 시도 → 예외
  (연결 실패/쿼리 오류) 시 catch → **기존 RDB 코드 경로(`productRepository.findPublicProducts`/
  `countPublicProducts`, `normalizeSort` 포함) 그대로 호출**, 코드 변경 없음.
- ES 히트 → `ProductListItemResponse` 매핑(product 패키지, 기존 `toListItemResponse`와 같은
  위치): name→title, description→desc, thumbnailUrl→`storageClient.
  generatePresignedDownloadUrl(...)`, ratingAvg→rating, salesCount, productId→id, sellerId,
  productType, model, amount, tags, firstPublishedAt→createdAt, currentVersionAt→updatedAt.
  `originalAmount`/`badge`는 오늘처럼 `null`.
- `ProductQueryServiceTest`: ES 성공 시 매핑 검증 / ES 예외 시 RDB 경로 호출 검증(fallback) —
  기존 RDB 테스트는 코드 변경 없으므로 그대로 통과해야 함.
- `ProductControllerTest`: 계약 불변 회귀 확인(기존 테스트 그대로 통과).

## Build & 수동 검증

```powershell
cd C:\programmers_prj\beadv6_6_3JMT_BE\product-service
.\gradlew.bat clean build --no-daemon
```

- 로컬 docker-compose ES 기동 → `/internal/search/reindex` 트리거 → `GET /api/v2/products?
  sort=popular|rating|price-asc&page=1..N` 확인.
- ES 컨테이너 중지 → 같은 요청이 RDB 폴백으로 계속 성공하는지 확인.
- 판매중지 시나리오: 상품 판매중단 → 즉시 검색에서 사라지는지 수동 재현(실시간 경로 검증).
- **#553 재현 확인**: ES 인덱스/alias를 삭제한 뒤 재기동해 재조정 스케줄러 첫 tick이
  `index_not_found_exception` 없이 스킵 로그만 남기고 넘어가는지 확인.

## 범위 외 (다른 이슈)

- `searchId`/`SEARCH_EXECUTED` — #381. 시맨틱/kNN 하이브리드 — #378. 자동완성/오타교정 — #379.
- admin-service Kafka 발행 경로 추가 — 이번엔 안 함.

## FE 후속 작업 (별도 저장소 `beadv6_6_3JMT_FE` — 이슈 등록 + 실제 구현까지 진행)

- `browse` 페이지에 `page`/`size` 파라미터 전달 + 페이지네이션 UI(현재 미구현).
- 판매중지/삭제 상품 구매·상세 진입 시 alert + 탐색목록 redirect.
