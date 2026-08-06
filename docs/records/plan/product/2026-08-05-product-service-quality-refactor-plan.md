# product-service 품질 고도화 — 리팩토링 로드맵

작성일: 2026-08-05 · 기준 커밋: `develop 64de86be`

한 줄 목적: **기능은 다 되는데 구조가 밀린 지점 9곳을 이슈 단위로 끊어서 순서대로 갚는다.**

이 문서는 "무엇을 왜 고칠지"의 공유 기록이다. 각 항목은 별도 이슈 + 브랜치 + PR로 진행하며,
이슈가 생성되면 아래 표의 `이슈` 칸을 채운다. `#411`은 이미 열려 있어 재사용한다.

---

## 왜 지금인가

product-service는 PR 58건이 전부 머지돼 기능은 완성 단계다. 그런데 고정 루브릭으로 코드
품질을 재측정해보니 **2026-07-29 대비 나빠져 있었다.**

| 정량 지표 | 2026-07-29 | 현재 |
| --- | --- | --- |
| 비대 클래스 | 4 | **6** |
| 긴 메서드 | 0 | 0 |
| 중복률 | 1.85% | 1.62% |
| LOC / 파일 수 | 4,726 / 110 | 5,014 / 112 |

새로 비대 판정을 받은 둘은 `ProductSellerService`(242 LOC·**17메서드**)와
`ProductJpaRepository`(**307 LOC**)다. 여기에 이전 점검에서 못 잡았던 위반 4건을 이번에
코드에서 직접 확인했다(I-2·I-7에 편입).

**PR·이슈 이력을 보면 결함이 세 갈래로 반복된다.** 이 로드맵은 그 뿌리를 겨냥한다.

| 반복 패턴 | 과거 이슈 | 뿌리 | 대응 |
| --- | --- | --- | --- |
| 검색 파이프라인 사고 | #548 #553 #645 #689 | 색인·재대사 실패가 조용히 지나감 | **I-2** |
| 응답 조립 누락 | #441 #482 #690 | 응답 조립 코드가 여러 계층에 흩어짐 | **I-3 · I-8** |
| 계약 정합 오류 | #496 #323 #509 | — | 이번 범위 아님 |

---

## 측정 기준

고정 루브릭 14개 카테고리(컨트롤러 위임, 엔티티 캡슐화, HTTP 의미, 로그, 예외, 레이어 분리,
미사용 코드, 의미론적 중복, 진단성, 서비스 간 통신 견고성, 서비스 경계, 값객체 설계, 다형성
기회, 응집도·결합도)로 위반을 세고, 아래 산식으로 점수를 낸다.

```
score = 100 − 5×High − 2×Medium − 0.5×Low
            − 비대클래스 − 긴메서드 − max(0, ceil(중복률 − 3))
```

비대 기준은 **300 LOC 초과 또는 메서드 15개 초과**, 긴 메서드는 50 LOC 초과다. 각 항목의
`점수` 칸은 이 산식 기준 예상 회복분이며, **우선순위를 정하는 신호로만 쓴다.** 점수를 올리려고
구조를 억지로 비트는 일은 하지 않는다(→ "고치지 않기로 한 것" 참고).

---

## 진행 순서

빠르고 위험이 낮은 것부터, 계약을 건드리는 큰 것을 마지막에 둔다.

| 차수 | 항목 | 회복 | 성격 |
| --- | --- | --- | --- |
| **1차** | I-1 · I-2 | +16 | 실패가 조용히 묻히는 곳 — 운영 리스크 |
| **2차** | I-3 · I-4 · I-5 · I-8 · I-9 | +19.5 | 정리 — 파일이 안 겹쳐 병렬 가능 |
| **3차** | I-7 · I-6 | +9 | 구조 변경 — 영향 범위 가장 큼 |

---

## 항목

| ID | 제목 | 타입 | 점수 | 이슈 |
| --- | --- | --- | --- | --- |
| I-1 | Kafka 발행 실패가 조용히 사라진다 | `fix` | +7 | |
| I-2 | ES 색인이 실패를 숨긴다 | `fix` | +9 | |
| I-3 | 응답에 값이 절대 안 들어가는 필드 3개 | `refactor` | +6 | |
| I-4 | 업로드 정책이 컨트롤러에 있고 S3 키 파싱이 중복 | `refactor` | +6 | |
| I-5 | ProductType별 본문 해석이 3곳에 흩어짐 | `refactor` | +2 | |
| I-6 | Kafka 컨슈머 파싱 3중복 + Jackson 버전 혼재 | `refactor` | +2 | |
| I-7 | ProductQueryService 3분할 + 포트 동반 분할 | `refactor` | +7 | **#411** |
| I-8 | ProductSellerService 슬림화 + 응답 DTO의 S3 직접 호출 제거 | `refactor` | +3 | |
| I-9 | 잔여 소소한 정리 | `chore` | +2.5 | |

---

### I-1 · Kafka 발행 실패가 조용히 사라진다 — `fix` — +7

| 위반 | 심각도 | 위치 |
| --- | --- | --- |
| `kafkaTemplate.send()` 반환 `CompletableFuture` 미확인 | **High** | `ProductEventProducer.java:86,90` |
| 발행 어댑터를 포트 없이 구체 클래스로 주입 | Medium | `ProductSellerService.java:14,40` |

`send()`가 돌려주는 future를 아무도 안 본다. AFTER_COMMIT 콜백 경로와 즉시 발행 경로 둘 다
같다. 전송이 비동기로 실패해도 **로그도 재시도도 없이** 이벤트가 사라진다. 가격 변경·판매중단
이벤트가 유실되면 ES 색인이 조용히 stale해지고, 그게 검색 결과로 나갈 때까지 아무도 모른다.

- `.whenComplete((r, ex) -> { if (ex != null) log.error(...) })`를 붙여 최소한
  `topic`·`eventType`·`aggregateId`를 남긴다.
- 리포지토리 3종(Product/Review/ProcessedEvent)은 전부 `domain/repository` 포트 +
  `infra/persistence` 어댑터 구조인데 **이벤트 발행만 이 패턴에서 빠져 있다.**
  `ProductEventPublisher` 포트를 `application/usecase`에 두고
  (`.claude/rules/clean-architecture.md` §4 — 내부 기술 인프라 아웃바운드 포트)
  `ProductEventProducer`가 구현하게 한다.

**검증**: 발행 실패를 주입해 `log.error`가 찍히는지. 포트로 바꾼 뒤 기존 발행 5종이 그대로 도는지.

---

### I-2 · ES 색인이 실패를 숨긴다 — `fix` — +9

| 위반 | 심각도 | 위치 |
| --- | --- | --- |
| `client.bulk()` 응답의 **item별 실패 미검사** | **High** | `ElasticsearchProductSearchIndexer.java:70-84` |
| alias 자리에 rogue index가 있으면 무방비 | Medium | `ProductIndexBootstrap.java:26-34` |
| `size(10000)` 고정 | Medium | `ElasticsearchProductSearchIndexer.java:53` |

**bulk 부분 실패** — bulk는 HTTP 200을 주면서 개별 item만 실패할 수 있다. 지금은 `IOException`
계열만 잡고 응답 본문은 안 본다. 즉 **문서 절반이 색인에 실패해도 "전체 재조정 완료" 로그가
찍힌다.** 응답 `errors()`와 item별 `error()`를 검사해 실패 ID·원인을 구조화 로그로 남기고,
재시도 가능한 오류만 제한적으로 재시도한다. 최종 실패는 다음 재대사 주기에서 복구되게 둔다
(무제한 재시도 금지). bulk 요청은 고정 chunk로 나눈다.

**alias 무결성** — `existsAlias(ALIAS)` 하나만 본다. `products`라는 이름의 *인덱스*가 alias
자리를 차지하면 `existsAlias`는 `false`를 주고, 이어지는 `putAlias`가 "an index exists with
the same name as the alias"로 터진다. **실제로 겪은 장애다.** alias 존재 + 대상 인덱스 이름 +
필수 mapping을 함께 검사하고, 불일치면 조용히 넘어가지 말고 명시적으로 실패시켜 복구 절차를
로그에 남긴다.

**10k 상한** — `findAllIndexedFamilyRootIds`가 `size(10000)` 고정이라 색인 문서가 1만 건을
넘으면 고아 문서 탐지가 불완전해진다. `search_after`로 전환한다.

**검증**: 일부 item만 실패하는 bulk 응답에서 실패 ID가 잡히는지 / alias 없음·rogue index·mapping
불일치 각각 / 1만 건 초과 고아 탐지.

---

### I-3 · 응답에 값이 절대 안 들어가는 필드 3개 — `refactor` — +6

| 필드 | 실제 값 | 선언 | 채우는 코드 |
| --- | --- | --- | --- |
| `originalAmount` | 항상 `null` | `ProductListItemResponse.java:13` | `ProductQueryService.java:252,272` |
| `features` | 항상 `List.of()` | `ProductDetailResponse.java:24` | `ProductQueryService.java:157` |
| `badge` | 항상 `null` | 두 Response | 채우는 경로 없음 |

`Product.getBadge()`는 ES 색인에는 들어가지만(`ElasticsearchProductSearchIndexer.java:105`)
`ProductSearchHit`에 그 필드가 없어 **조회 응답으로 돌아올 길이 없다.** 값을 만드는 정책도
어디에도 없다.

**FE 영향 없음 — 확인 완료.** 세 필드 모두 optional 선언 + fallback이라 응답에서 빠져도
화면이 깨지지 않는다.

- `components/ui/PromptCard.tsx:27,32,74` — `if (p.originalAmount && p.originalAmount > p.amount)`
- `app/detail/[id]/page.tsx:38,45,50,316` — `const features = p.features ?? ['결제 즉시 다운로드', ...]`
- `app/mypage`·`app/reader`·`lib/orderAdapters.ts`의 `badge`는 **주문 상태**를 담는 별개 필드다 — 무관

`badge`는 필드를 지울지 `ProductSearchHit`에 추가해 살릴지 결정이 필요하다. **기본 방향은
제거** — 값을 채우는 정책이 정해진 적이 없다. 뱃지 기능이 실제로 필요하면 별도 이슈로 다룬다.

**검증**: 응답 JSON에서 세 필드가 빠진 뒤 `/browse`·`/detail/[id]`·`/` 렌더 확인.

---

### I-4 · 업로드 정책이 컨트롤러에 있고 S3 키 파싱이 중복 — `refactor` — +6

| 위반 | 심각도 | 위치 |
| --- | --- | --- |
| 허용 확장자·productType 분기가 컨트롤러에 | Medium | `FileUploadController.java:59-82` |
| `purpose`가 `"file"`/`"thumbnail"`/`"image"` 문자열 리터럴 | Medium | 같은 메서드 |
| `extractKey` 로직 완전 중복 | Medium | `FileUploadController.java:110-115` ↔ `ProductSellerService.java:258-263` |

`resolveContentType`이 "PPT면 pptx/ppt만, EXCEL이면 xlsx/xls만" 같은 **업무 규칙**을 컨트롤러
안에서 판단한다. 컨트롤러는 HTTP 관심사만 다뤄야 한다(`.claude/rules/controller-exception.md` §1).

`purpose`가 문자열이라 오타·누락이 컴파일 타임에 안 잡힌다. `UploadPurpose` enum으로 바꾼다.

`extractKey`(presigned URL → S3 key)는 두 파일에 **글자 그대로 같은 코드**가 있다. 한 곳으로
모은다 — 값객체(`S3ObjectKey`)나 `StorageClient` 쪽 책임 중 하나로.

**검증**: 확장자·purpose 조합별 응답 코드. 키 파싱은 presigned URL·평문 키·`null` 각각.

---

### I-5 · ProductType별 본문 해석이 3곳에 흩어짐 — `refactor` — +2

"이 상품 유형에서 본문은 어느 필드인가"를 세 곳이 각자 판단한다.

| 위치 | 하는 일 |
| --- | --- |
| `ProductContent.java:43-47` | 유형별 필수 필드 조합 검증 |
| `ProductGrpcService.java:89-93` | 구매자 산출물 결정 |
| `PurchasedProductQueryService.java:53-57` | 구매 상품 응답 조립 |

셋 다 `PROMPT → content` / `PPT,EXCEL → fileUrl` / `NOTION → externalUrl`로 같은 규칙이다.
새 유형이 생기면 **세 곳을 다 고쳐야 하고, 하나를 빠뜨려도 컴파일은 통과한다.** `ProductType`에
메서드를 두거나 작은 전략으로 묶어 한 곳만 고치게 한다.

**검증**: 4개 유형 전부에 대해 세 경로 결과가 이전과 동일한지.

---

### I-6 · Kafka 컨슈머 파싱 3중복 + Jackson 버전 혼재 — `refactor` — +2

`EventMessage<JsonNode>` 역직렬화 try/catch가 세 컨슈머에 거의 그대로 있고, **서로 다른
Jackson 메이저 버전**을 쓴다.

| 파일 | Jackson |
| --- | --- |
| `OrderEventConsumer.java:14-16` | `tools.jackson.*` (3.x) |
| `ProductInspectionResultConsumer.java:13-15` | `tools.jackson.*` (3.x) |
| `ProductSearchEventConsumer.java:3-6` | `com.fasterxml.jackson.*` (2.x) |

버전 혼재는 의도가 아니라 놓친 것이다. 파싱을 공통 헬퍼로 모으고 컨슈머 3곳의 버전을 맞춘다.

**`ElasticsearchClientConfig.java:8-10`은 그대로 둔다** — `JacksonJsonpMapper`가 Jackson 2를
요구하는 ES 클라이언트 제약이지 드리프트가 아니다.

셋 중 회귀 위험이 가장 큰 항목이라 **3차 마지막**에 둔다.

**검증**: 3개 컨슈머의 정상·미지원 eventType·payload 매핑 실패 경로
(`.claude/rules/kafka-event.md` §17 기준).

---

### I-7 · ProductQueryService 3분할 + 포트 동반 분할 — `refactor` — +7 — **#411**

가장 크고, 회복 효율도 가장 높다. 포트를 같은 축으로 쪼개면 **비대 클래스 3건이 한 번에 빠진다.**

| 해소 | 회복 |
| --- | --- |
| 검색 오케스트레이션·DTO 매핑 3벌·presign·버전이력이 한 클래스 (응집도) | +2 |
| `getProducts()` L66의 `catch (RuntimeException)` — 프로그래밍 오류까지 RDB 폴백에 숨김 | +2 |
| 비대 `ProductQueryService` (311 LOC·19메서드) | +1 |
| 비대 `ProductRepositoryAdapter` (146 LOC·**25메서드**) | +1 |
| 비대 `ProductJpaRepository` (**307 LOC**) | +1 |

분리 축 (선례: #550의 `PurchasedProductQueryService`):

- `ProductListQueryService` — `getProducts` + `suggest` (ES 결합을 여기로 격리)
- `ProductDetailQueryService` — `getProduct` + `getRecommendedProducts` + `getProductReviews`
- `ProductBatchQueryService` — `getProductsByIds`

**같이 처리할 것 3가지.**

1. **CQS 위반** — `getProduct()`가 조회 도중 `incrementViewCount()` + `save()`를 한다
   (`ProductQueryService.java:134-136`). 클래스는 `@Transactional(readOnly = true)`인데 이
   메서드만 쓰기로 오버라이드한다. 이름은 조회인데 실제로는 쓰기라, 나중에 캐싱이나 read
   replica를 붙이면 조회수가 조용히 사라진다. 명시적 커맨드로 분리한다.
2. **`getProductsByIds`의 2N 집계** — 상품마다 `sumSalesCountByFamilyRootId`와
   `getAverageRating`을 각각 호출한다(`ProductQueryService.java:219-220`). 상품 100건이면
   집계 쿼리만 200번이다. 일괄 조회 + Map 조립으로 바꿔 **입력 크기와 무관하게 쿼리 수를
   고정**한다. `getAverageRatings(List)`는 이미 있으니 그대로 쓰고, 판매량 쪽 일괄 조회만
   추가한다.
3. **폴백 catch 좁히기** — ES 연결·timeout 등 예상 예외로 한정한다. 지금은 NPE도 폴백에 묻힌다.

`ProductRepository` 포트와 `ProductRepositoryAdapter`·`ProductJpaRepository`도 같은 3축으로
나눈다.

**공개 계약은 바꾸지 않는다.** `ProductQueryUseCase`와 컨트롤러 응답 JSON이 리팩토링 전후로
동일해야 한다.

**검증**: 컨트롤러 응답 JSON 전후 동일. `getProductsByIds`는 입력 1/10/100건에서 집계 쿼리
수가 고정되는지 쿼리 로그로 확인.

---

### I-8 · ProductSellerService 슬림화 + 응답 DTO의 S3 직접 호출 제거 — `refactor` — +3

| 해소 | 회복 |
| --- | --- |
| presentation DTO의 정적 팩토리가 `StorageClient`로 S3 원격 호출 (레이어 분리) | +2 |
| 비대 `ProductSellerService` (242 LOC·**17메서드**) | +1 |

`SellerProductDetailResponse.java:32,55,60`과 `SellerProductListItemResponse.java:26,44`가
`StorageClient`를 **인자로 받아 DTO 안에서 presign 원격 호출을 한다.** 같은 서비스의
`ProductDetailResponse`·`ProductListItemResponse`는 완성된 URL 문자열만 받는데 셀러 쪽만
다르다. 응답 DTO가 원격 호출을 하면 직렬화 시점에 네트워크가 끼어드는 셈이라, 실패 처리도
타임아웃도 걸 자리가 없다. 서비스가 URL을 조립해 넘기도록 통일한다.

S3 키 이동·추출 헬퍼(`moveToProductPath`·`moveToProductPaths`·`extractKey`·`extractKeys`·
`presignOrNull`·`presignAll`, L201-268)를 협력자로 추출해 메서드 수를 15 이하로 내린다.
I-4의 공용 키 파싱과 같은 자리에 모은다.

**검증**: 판매자 목록·상세 응답의 presigned URL이 이전과 동일한지.

---

### I-9 · 잔여 소소한 정리 — `chore` — +2.5

| 항목 | 위치 | 회복 |
| --- | --- | --- |
| `ProductFamily`의 죽은 메서드 2개 제거 → 16메서드에서 14로 | `ProductFamily.java:43,63` | +1 |
| `handleNoResourceFound`/`handleException`의 미사용 `HttpServletRequest` 파라미터 | `ProductExceptionHandler.java:66-69,80` | +0.5 |
| `copyObject` 실패에 `S3_PRESIGN_FAILED`("파일 업로드 URL 생성에 실패했습니다") 재사용 | `S3StorageAdapter.java:83` | +0.5 |
| `new ProductContent(...)` 12인자 조립이 두 곳에 반복 | `ProductSellerService.java:53-57,86-90` | +0.5 |

`ProductFamily`의 죽은 메서드는 확인 완료다. `members()`는 **프로덕션·테스트 어디서도 안
쓰이고**, `mostRecentSuperseded()`는 **자기 테스트에서만** 호출된다. 둘을 제거하면 메서드가
14개가 되어 비대 판정에서 빠진다. 나머지 14개는 작고 응집도가 높으니 **더 쪼개지 않는다.**

`copyObject` 실패에 "URL 생성 실패" 메시지가 나가는 건 로그와 응답이 실제 원인과 어긋나는
문제다. 전용 에러코드를 추가한다 — `docs/error-codes.md` 동기화 필요.

---

## 고치지 않기로 한 것

점수는 잃지만 손대지 않는다. 이유를 남겨 다음 점검에서 다시 논쟁하지 않게 한다.

| 항목 | 손실 | 이유 |
| --- | --- | --- |
| `ProductQueryGrpcService.java:44-53,71-81` 스냅샷 매핑 중복 | −2 | `GetOrderSnapshots`/`GetCartSnapshots`는 order-service 소비자 전환이 끝날 때까지 유지하는 **의도된 과도기**다 (`.claude/rules/product-api.md`에 명시) |
| `ProductJpaRepository`의 `findPublicProducts` ↔ `findProjectionsByIds` JPQL 중복 | −0.5 | JPQL에 조각을 공유할 수단이 마땅치 않다 — 구조적 제약 |
| `Product` 엔티티 비대 (248 LOC·17메서드) | −1 | 상태 전이 가드마다 작고 단순한 메서드가 많아 **응집도는 오히려 높다.** 쪼개면 도메인 규칙이 흩어진다 |

→ 전부 반영해도 상한은 **약 91~93점**이다.

---

## 이 로드맵 이후

지금 착수하지 않는다. 순서와 이유만 적어둔다.

| 이슈 | 판단 | 이유 |
| --- | --- | --- |
| #518 build.gradle gRPC 의존성 제거 | **작업 아님 — close 후보** | 재검증 결과 gRPC는 order-service와 ai-service(#698)가 살아있게 쓰는 중이다. 지우면 컴파일이 깨진다 |
| #684 반려 상품 삭제 | **정책 결정 먼저** | REJECTED를 실제 소프트 삭제할지, 현 설계(STOPPED 전이 후 목록 유지)가 맞고 FE 버튼만 고칠지 |
| #560 판매자 목록 페이징 | I-7 이후 | `PageResponse` 계약 변경 → FE 동반 수정 필요 |
| #508 주간 트렌딩 랭킹 | I-7 이후 | 신규 테이블 + JPQL 분기를 얹는 작업이라, 3분할 전에 하면 비대 클래스를 더 키우고 나중에 두 번 고쳐야 한다 |
| #381 행동 로그 파이프라인 | 마지막 | 단독으로 크고, 보류 4건(#382·#650·#594·일부 #383)이 여기 매달려 있다 |
| #582 ES 보안 전환 | 코드 작업 없음 | PR #659로 1·2단계(인증 연결 지원 + 가이드) 완료. 3단계(`xpack.security.enabled=true`)는 ELK 담당자와 시점 조율 대기 |

---

## 공통 규칙

- 항목당 **1 이슈 = 1 브랜치 = 1 PR**. 하나의 브랜치에 관련 없는 항목을 섞지 않는다.
- 동작 변경과 구조 변경을 같은 PR에 섞지 않는다.
- PR 전 `.\gradlew.bat :product-service:build --no-daemon`으로 컴파일·checkstyle·테스트를
  함께 확인한다. `test` task만 단독 실행하지 않는다.
- 계약(응답 JSON·gRPC·이벤트 payload)이 바뀌는 항목은 **I-3 하나뿐**이고, 그건 FE가 이미
  optional로 받고 있어 안전함을 확인했다. 나머지는 전부 내부 구조 변경이다.
- `docs/api-spec/product.md`·`docs/error-codes.md` 영향이 있으면 같은 PR에서 동기화한다.
