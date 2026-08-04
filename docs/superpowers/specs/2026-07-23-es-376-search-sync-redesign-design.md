# ES 검색 동기화 재설계 (#376 재설계)

## 1. 배경 — 왜 다시 설계하는가

기존 #376 구현(`product-service/docs/plans/2026-07-23-es-376-indexing-pipeline-plan.md`)은 다음을 전제로 outbox 패턴까지 도입했다.

- admin-service가 상품 승인/승인취소(ON_SALE 전환)를 담당하는데, admin-service는 Kafka 클라이언트가 없어
  직접 이벤트를 발행할 수 없다.
- 이 문제를 풀기 위해 `product_outbox_event` 테이블 + `OutboxRelay`(5초 폴링) + admin-service 전용
  outbox 엔티티/팩토리를 만들었다.

구현하고 나서 다시 검토해보니, 이 설계는 다음 문제들을 계속 파생시켰다.

- "AI가 상품 100개를 한꺼번에 배치 승인하면 outbox 100줄 → Kafka 100개 → 컨슈머 100번"이라는
  비효율이 구조적으로 생김.
- 이를 풀려고 "이벤트 그룹화 후 최신 것만 처리", "outbox에 중복 스킵/삭제 로직 추가" 등 논의했으나,
  전부 `OutboxRelay`(범용 인프라 컴포넌트)에 도메인 지식을 넣어야 하는 문제로 이어짐.
- product-service 자신의 발행(패치버전/가격/삭제/중단)까지 outbox로 통일할지도 논쟁이 됐는데,
  이는 정합성엔 이득이지만 설계를 한층 더 복잡하게 만듦.

**핵심 발견**: 현재 공개 상품 목록/검색 API(`GET /api/v1/products`, `ProductController`/
`ProductQueryService`)는 **아직 RDB만 조회한다.** ES로 실제 검색 트래픽을 전환하는 작업은 별도
이슈(#377, "상품 목록·검색 API Elasticsearch 전환")이고, 이번 재설계 시점엔 구현되지 않았다.
즉 **admin-service가 일으키는 변화가 ES에 최대 며칠 지연돼 반영돼도 실사용자에게 영향이 없다.**
이 전제 덕분에, admin-service만을 위해 만들었던 outbox 패턴 전체를 걷어내고 "admin-service 발
변화는 주기 배치가 잡아낸다"는 훨씬 단순한 구조로 대체할 수 있다.

반면 **product-service 자신이 일으키는 변화(생성/패치버전/삭제/중단)는 지금처럼 실시간 이벤트로
처리**한다 — 이 경로는 admin-service의 Kafka 제약과 무관하고, outbox 없이도 지금 코드가 이미
문제없이 하고 있는 부분이라 굳이 배치로 늦출 이유가 없다.

이 문서는 "admin-service만을 위한 outbox 복잡도"를 걷어내고, **"이 다음에 올 이슈들(#377~)이
쉽게 얹힐 수 있는 가장 단순한 기반"**을 다시 설계한다.

## 2. 설계 원칙

- ES에 새로 추가/갱신되는 쪽(생성, 패치버전 업데이트)만 실시간 이벤트로 처리한다.
- ES에서 빠져야 하는 쪽(삭제, 판매중단, admin-service의 승인취소)은 실시간을 포기하고,
  주기 배치가 최종적으로 맞춰준다(최대 7일 지연 허용 — #377 전까지는 무해함). admin-service
  발 변화(승인 포함)도 전부 이 배치가 잡아낸다.
- admin-service는 Kafka/이벤트를 전혀 몰라도 된다 — 공유 DB에 직접 쓰는 지금 구조를 그대로 둔다.
  이걸 위해 outbox 같은 별도 인프라를 만들지 않는다.
- order-service가 이미 소비하고 있는 기존 이벤트(`PRODUCT_STOPPED`/`PRODUCT_DELETED`/
  `PRODUCT_PRICE_CHANGED`, 구체적 payload)는 다른 서비스의 계약이므로 그대로 둔다.
- search는 이 기존 이벤트를 더 이상 구독하지 않는다. 대신 search 전용의, "뭔가 바뀌었으니 다시
  확인하라"는 뜻만 담은 얇은 이벤트 하나를 새로 받는다.

## 3. 최종 아키텍처

```
product-service 자체 변화 — "ES에 추가/갱신"이 필요한 쪽만 실시간 이벤트
  - 첫 생성(createProduct)
  - 패치버전 갱신(검수 없이 즉시 ON_SALE, 가격 변경도 이 안에서 함께 일어남)
      ↓
  ProductEventProducer가 신규 search 전용 이벤트 발행 (예: PRODUCT_CHANGED, payload={familyRootId})
      → search가 즉시 소비 → familyRootId로 재조회 → ON_SALE 있으면 upsert
  (해당 시) 기존 order-service용 이벤트(PRODUCT_PRICE_CHANGED)도
      → 지금처럼 그대로 나란히 발행, order-service만 계속 소비 (변경 없음)

product-service 자체 변화 — "ES에서 제거"가 필요한 쪽은 실시간 이벤트 없음
  - 삭제(DRAFT) / 판매중단
      ↓
  기존 order-service용 이벤트(PRODUCT_STOPPED/PRODUCT_DELETED)는 지금처럼 그대로 발행(변경 없음)
  → 다만 search 전용 이벤트는 발행하지 않음 — 7일 배치가 잡아낼 때까지 기다림

admin-service 변화(승인/승인취소)
  → 이벤트 없음 (Kafka 모름, 공유 product 테이블에 직접 write, 변경 없음)
  → 승인/승인취소 둘 다 7일 배치가 잡아낼 때까지 기다림

search: 7일 주기 배치
  → "ES에서 제거돼야 하는 모든 경우"(판매중단/삭제/admin 승인취소)와, admin의 신규 승인을
    한꺼번에 잡아내는 백업 — RDB의 현재 ON_SALE 상품 전체 vs ES에 색인된 전체를 비교
  → 다른 부분(신규로 넣어야 할 것 / 더 이상 ON_SALE 아니라 지워야 할 것)만 ES `_bulk`로 반영
  → 온디맨드 수동 트리거(`/internal/search/reindex`)와 같은 로직을 공유
```

## 4. product-service 변경 사항

- `ProductEventProducer`에 신규 메서드 추가(예: `publishProductChanged(UUID familyRootId)`) —
  기존 4개 메서드(`publishOnSaleChanged`/`publishPriceChanged`/`publishStopped`/`publishDeleted`)는
  **그대로 유지**(order-service용).
- `createProduct()`, `updateProduct()`(패치버전 분기)에서 신규 메서드 호출 추가.
  `deleteProduct()`(양쪽 분기)는 신규 메서드를 호출하지 않는다 — 삭제/판매중단은 7일 배치가
  잡아낸다(기존 `PRODUCT_STOPPED`/`PRODUCT_DELETED` 발행은 order-service를 위해 그대로 유지).
- `search` 패키지의 컨슈머(`ProductSearchEventConsumer`/`ProductSearchEventHandler`)는 **삭제가
  아니라 단순화** — 기존처럼 `PRODUCT_ON_SALE_CHANGED`/`PRODUCT_STOPPED`/`PRODUCT_DELETED`/
  `PRODUCT_PRICE_CHANGED` 4종류를 각각 분기해서 처리하던 것을, **신규 이벤트 타입 하나만 구독**하는
  걸로 통일한다. 이 컨슈머는 이제 order-service가 쓰는 기존 이벤트 타입들을 더 이상 구독하지
  않는다(별도 consumer group이므로 구독 대상만 바꾸면 됨).
- **핸들러 로직도 더 단순해진다** — 지금의 `reconcileFamily()`는 "재조회 후 ON_SALE이 있으면
  upsert, 없으면 delete" 분기가 있는데, 이 분기를 없앤다. 이 신규 이벤트는 create(결과가 항상
  DRAFT)와 패치버전 업데이트(결과가 항상 ON_SALE)에서만 발행되고, 두 경우 다 "삭제해야 하는
  상황"이 아니므로 **재조회 후 무조건 upsert**만 하면 된다(DRAFT 상태로 색인돼도 무방 — 7일
  배치가 ON_SALE 아닌 건 어차피 다 걸러내므로). `ProductSearchIndexer.delete(UUID)`는 이제
  실시간 경로에서 전혀 호출되지 않고, 7일 배치의 벌크 삭제 전용이 된다.

## 5. 삭제 대상 — outbox 인프라 전체

admin-service의 Kafka 제약을 우회하려고 만들었던 아래 코드는 더 이상 필요 없다(develop에 머지된
적 없는 브랜치라 안전하게 삭제 가능).

**product-service**
- `infra/messaging/producer/OutboxRelay.java`
- `infra/messaging/producer/OutboxRelayProperties.java`
- `infra/messaging/config/OutboxRelayConfig.java`
- `domain/model/entity/OutboxEvent.java`, `domain/model/enums/OutboxEventStatus.java`
- `domain/repository/OutboxEventRepository.java`
- `infra/persistence/outbox/OutboxEventAdapter.java`, `OutboxEventPersistence.java`
- `application/service/outbox/OutboxEventAppender.java`
- `src/main/resources/db/migration/V3__product_outbox_event.sql`
- `search/infra/batch/ProductCountSyncScheduler.java` (7일 배치로 대체)
- `ProductSearchIndexer`의 `updatePrice()`/`updateCounts()`(부분갱신 전용 메서드 — 전체 재조회 후
  upsert/delete로 통일되므로 불필요)
- `product-service/docs/plans/2026-07-23-es-376-indexing-pipeline-plan.md` (이 문서로 대체)

**admin-service**
- `application/service/ProductOnSaleChangedEventFactory.java`
- `domain/model/entity/OutboxEvent.java`, `domain/repository/OutboxEventRepository.java`,
  `infrastructure/persistence/OutboxEventJpaRepository.java`, `OutboxEventRepositoryAdapter.java`
- `ProductService.approveProduct()`/`revertProductToPendingReview()`의 outbox insert 호출부

## 6. 신규로 만드는 것

- `ProductEventProducer.publishProductChanged(UUID familyRootId)` — search 전용 이벤트 발행.
- search 패키지 안에 7일 주기 스케줄러 1개(`@Scheduled(fixedDelay = 7일)`) — 핵심 로직은
  "RDB ON_SALE 전체 스냅샷 vs ES 색인 전체 스냅샷 비교 후 diff만 `_bulk` 반영"이며, 이 로직을
  온디맨드 리인덱스 컨트롤러(`/internal/search/reindex`)와 공유한다(하나는 수동 즉시 트리거, 하나는
  7일 스케줄 트리거).
- ES 클라이언트를 통한 `_bulk` 호출 어댑터(`ElasticsearchProductSearchIndexer`에 벌크 메서드 추가).

## 7. 그대로 유지되는 것

- `ProductEventProducer`의 기존 4개 발행(order-service용) — 코드 변경 없음.
- order-service의 `product-events` 소비 — 완전히 무관, 그대로.
- ES 매핑(`products-v1-mapping.json`), alias 운영, Docker/k8s 배포(nori 등) — 기존 #376 작업에서
  이미 검증됐고 이번 재설계와 무관하므로 그대로 둔다.

## 8. 명시적 한계 (다음 이슈에서 반드시 재검토)

"ES에서 제거돼야 하는 변화"(판매중단/삭제/admin 승인취소)가 최대 7일 지연이 허용된다는 전제가,
**"공개 검색/목록 API가 아직 RDB만 본다"는 사실**에 의존한다. #377에서 실제로 검색 API를 ES로
전환하면:

- "판매중단되거나 삭제되거나 admin이 승인취소한 상품이 최대 7일간 검색에 계속 뜨는" 문제는 이
  시점부터 실제 버그가 된다.
- 그때 가서 최소한 삭제/판매중단만이라도 다시 실시간 이벤트로 전환하거나(product-service는
  이미 관측 가능하므로 어렵지 않음), admin-service 발 변화까지 실시간이 필요하면 admin-service에
  최소한의 발행 경로를 추가하는 것, 혹은 7일 주기를 훨씬 짧게 당기는 것 등을 검토해야 한다.

이 문서와 후속 plan 문서에 이 한계를 명시해, #377 작업 시작 시 반드시 다시 확인하게 한다.

## 9. 이번 재설계가 다루지 않는 것

- #377(검색 API 전환), #378(임베딩) 이후 로드맵 — 이번 문서의 스코프 밖.
