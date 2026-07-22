# 정산 서비스 연동 카탈로그 (동기 조회 · 발행)

Settlement 서비스가 외부와 주고받는 데이터를 정리한다. 네 갈래로 나눈다.

- **원천 데이터 조회(gRPC pull)** — 정산의 원천(결제/환불). 배치 시점에 order 에서 당겨와 정산
  DB(`settlement_source_line`)에 **소유**한다. → §1
- **화면 조합 경계** — 판매자 정보와 상품 통계는 정산 백엔드가 gRPC로 조합하지 않는다.
  프론트가 각 소유 서비스의 REST API를 호출한다. → §2
- **발행** — 셀러 정산 seed 용 `SETTLEMENT_CREATED`는 Transactional Outbox로 구현됨, 지급 완료
  알림은 추후. → §3
- **어드민 연동** — 추후(파이널) 어드민 모듈 분리. gRPC 가 아니라 어드민이 정산 DB 를
  **직접 접근**하므로 rpc 계약이 없다 — 대신 스키마·예약 테이블이 계약이다. → §4

> 원천 수급을 왜 이벤트(Kafka push)가 아니라 gRPC pull 로 하는지는 `trade-offs/order-data-sourcing.md`
> 를 본다. 이 문서는 그 결정에 따른 **연동 계약(무엇을 주고받는지)** 을 정리한다.
> proto 계약은 루트 `grpc/order/order_query.proto`의 공유 계약이며 settlement/order 양쪽에 구현돼 있다.
> 구현(계층·패키지)은 `kafka-messaging-design.md` 를 본다.

> **현재 실제 통신 상태(어느 모듈이 무엇을 구현/비활성/대기 중인지)는
> `settlement-internal-comm-topology.md`의 현황 매트릭스를 본다.** §1은 정산 원천 gRPC 계약,
> §2는 프론트 화면 조합 경계, §3은 정산 이벤트 발행 계약을 설명한다.

---

## 1. 원천 데이터 조회 — order 정산 라인 (gRPC pull)

정산의 **원천 데이터**(결제·환불)는 정산 DB 에 적재해야 한다. 배치가 도는 시점에 order 에 gRPC 로
그 기간의 정산 라인을 요청해 `settlement_source_line` 에 소유한다. **이벤트 구독이 아니라 배치 시점
조회다** — 상시 컨슈머 없이 CronJob 배치가 필요할 때 당겨온다. (결정 배경은 `trade-offs/order-data-sourcing.md`.)

| 조회 | 발행처(서버) | 용도 | 멱등 단위 | 구현 현황 |
|------|-------------|------|----------|----------|
| `GetSettleableLines(period_start, period_end)` · `PAID` 라인 | Order | 결제 라인을 `settlement_source_line`(PAID) 적재 | `orderProductId` + PAID | 클라이언트·서버 구현 완료 |
| `GetSettleableLines(period_start, period_end)` · `REFUND` 라인 | Order | 환불 라인을 `settlement_source_line`(REFUND) 적재 | `orderProductId` + REFUND | 클라이언트·서버 구현 완료 |

### 1-1. 왜 order 인가 / 무엇을 받는가

정산 소스는 결제(payment) 단위가 아니라 **주문상품(`orderProductId`) 단위**다. `OrderProduct` 가
정산에 필요한 값(`sellerId`·금액)을 단위로 보유하므로, order 에서 라인을 받으면 소스 라인으로
**1:1 매핑**된다. (Payment 의 결제 단위는 상품·판매자 분해가 불가능해 조회 대상이 아니다.)

**상태(status)가 아니라 시각(`paidAt`·`refundedAt`)으로 조회한다.** `order_product` 는 결제/환불을
상태 enum 을 in-place 로 바꿔 표현하므로(`PAID` → `REFUNDED`), "지금 status"로 고르면 시점 귀속이
깨진다. 결제는 `paidAt`, 환불은 `refundedAt` 이 정산 기간에 든 라인을 각각 받는다. timestamp 는
불변이라 상태가 바뀌어도 각 사건이 자기 기간에 귀속된다. (원리·케이스 표·음수 정산은
`order-data-sourcing.md`.)

```
결제 라인:  paidAt     ∈ 기간  → PAID   SourceLine (line_amount = 결제액)
환불 라인:  refundedAt ∈ 기간  → REFUND SourceLine (line_amount = 환불액, 순액에서 차감)
```

### 1-2. gRPC 계약 — `GetSettleableLines` (Settlement 호출, Order 제공)

포함 날짜 `period_start`·`period_end`로 결제·환불 라인을 요청하고, `line_type`으로 구분된 한 응답
(`repeated SettleableLine`)으로 받는다. 현행 요청은 월요일 시작·그 주 일요일 종료인 7일 범위만
허용한다. 서버는 내부적으로 `paidAt`과 `refundedAt`을
`[periodStart 00:00, periodEnd + 1일 00:00)` 반개구간으로 각각 조회해 두 결과를 합친다.
**status 로 필터하지 않는다** — 환불된 라인도 `paidAt` 이 기간 안이면 PAID 라인으로, `refundedAt` 이
기간 안이면 REFUND 라인으로 각각 내려온다.

> **전송 방식은 unary(단일 요청/응답 + `repeated`)로 확정.** 초안은 대량 대응 server-streaming 이었으나,
> 주간 배치 1회 호출·구현 단순성을 우선해 unary + `repeated` 로 구현했다(#260). 볼륨이 실제로 문제가
> 되면 그때 streaming 으로 전환한다.

- 제공(서버): Order 서비스 (`OrderQueryGrpcServer`, 구현 완료)
- 호출: Settlement(배치 첫 스텝 `loadSettlementSourceStep`) → Order
- 호출 시점: `CronJob/settlement-weekly` → `SettlementCronJobRunner` → `settlementJob` 실행 시
  (매주 월요일 00:00 `Asia/Seoul`, 수동 실행도 같은 잡 경로)
- proto: `grpc/order/order_query.proto` (루트 공유)

```protobuf
syntax = "proto3";

package prompthub.order;

option java_multiple_files = true;
option java_package = "com.prompthub.order.grpc";
option java_outer_classname = "OrderQueryProto";

service OrderQueryService {
  rpc GetSettleableLines(GetSettleableLinesRequest) returns (GetSettleableLinesResponse);
}

message GetSettleableLinesRequest {
  string period = 1 [deprecated = true]; // 이전 배포 호환용 "yyyy-MM" fallback
  string period_start = 2;               // 포함 시작일 "yyyy-MM-dd", 월요일
  string period_end = 3;                 // 포함 종료일 "yyyy-MM-dd", 일요일
}

message GetSettleableLinesResponse {
  repeated SettleableLine lines = 1;
}

message SettleableLine {
  string line_type        = 1;   // PAID | REFUND
  string order_id         = 2;
  string order_product_id = 3;
  string seller_id        = 4;
  int64  line_amount      = 5;   // PAID 는 결제액, REFUND 는 환불액(원화, 양수)
  string occurred_at      = 6;   // PAID 면 paidAt, REFUND 면 refundedAt
}
```

**요청 — `GetSettleableLinesRequest`**

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `period_start` | string(`YYYY-MM-DD`) | ✅(현행) | 포함 시작일. 월요일이어야 한다 |
| `period_end` | string(`YYYY-MM-DD`) | ✅(현행) | 포함 종료일. `period_start + 6일`인 일요일이어야 한다 |
| `period` | string(`YYYY-MM`) | 레거시 | 두 신규 필드가 모두 없을 때만 order-service가 월 범위로 해석하는 배포 호환 fallback. 신규 호출 금지 |

`period_start`와 `period_end`는 둘 다 보내거나 둘 다 생략해야 한다. 하나만 있거나 날짜·주간 규칙이
잘못되면 order-service는 gRPC `INVALID_ARGUMENT`를 반환한다. settlement-service 클라이언트는 항상
두 신규 필드만 보낸다.

**응답 — `GetSettleableLinesResponse` (`lines`: repeated `SettleableLine`)**

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `line_type` | string | ✅ | `PAID` \| `REFUND` |
| `order_id` | string(UUID) | ✅ | 주문 ID |
| `order_product_id` | string(UUID) | ✅ | 주문상품 ID(정산 소스 라인 단위) |
| `seller_id` | string(UUID) | ✅ | 판매자 ID |
| `line_amount` | int64 | ✅ | PAID=결제액, REFUND=환불액(원화, 양수) |
| `occurred_at` | string(DateTime) | ✅ | PAID=paidAt, REFUND=refundedAt (라인 `occurred_at`) |

> **정산이 라인 적재에 쓰는 값:** `line_type`·`order_id`·`order_product_id`·`seller_id`·`line_amount`·
> `occurred_at`. 대상 도메인은 `SettlementSourceLine.paid(...)` / `.refunded(...)` 로, `line_type` 에
> 따라 갈린다. 멱등키 `event_id` 는 order 가 주지 않고 정산이 `(order_product_id + line_type)` 로
> 로컬 파생한다(§1-3).

### 1-3. 멱등 / 라인 적재

배치 Step 0 가 받은 라인을 `settlement_source_line` 에 멱등 적재한다.

- 멱등키 seed 는 **`orderProductId` + 상태**다.
  `PAID` = `nameUUIDFromBytes(orderProductId | "PAID")`,
  `REFUND` = `nameUUIDFromBytes(orderProductId | "REFUND")`.
- 같은 `orderProductId` 라도 PAID/REFUND 는 seed 가 달라 **별도 라인**으로 적재된다. 환불이 결제
  라인을 덮어쓰지 않고 음수 보정 라인으로 더해져, 배치가 PAID 합 − REFUND 합으로 순액을 낸다.
- 배치 재실행 시 같은 기간 재조회해도 같은 멱등키라 중복 적재가 걸러진다. bulk 경로라 라인별
  `existsByEventId` 대신 조회한 멱등키를 모아 `findExistingEventIds` 로 한 번에 걸러내고 `saveAll` 한다.
- `settlement_source_line.event_id`(단일 unique) 스키마·도메인 제약은 그대로 둔다. 부분·다중 환불이
  생기면 order 가 `refundId` 를 주고 seed 에 더한다(현재 `OrderProduct.refund()` 는 전액 1회).

정산 배치는 `settlement_source_line` 의 미정산 PAID 라인을 판매자·기간 단위로 모으고 REFUND 를
차감해 `Settlement` 를 계산한다. (이미 정산된 라인 제외 dedup 은 `SettlementDetail` 기준 — 배치가 건다.)

---

## 2. 참고 데이터와 화면 조합 경계

정산 금액과 대상을 결정하는 order 원천 라인은 settlement-service가 내부 gRPC로 조회한다(§1).
판매자 화면의 부가 정보는 정산 백엔드가 합치지 않고 프론트가 소유 서비스의 REST API를 별도로 호출한다.

- 판매자 단건·다건 정보는 user-service의 REST Seller 조회 API가 제공한다(§2-1).
- 등록 프롬프트 수와 누적 판매건수는 Product 공개 API가 제공한다(§2-2).
- 판매자 정산 계좌 정보는 아직 범위가 아니다. 지급 실행을 붙일 때 별도로 정한다.

### 2-1. User — 판매자 정보 조회 REST API

Product와 구매상품 등 화면별 판매자 정보 조합은 user-service가 소유한 REST API를 사용한다.
user-service의 Product용 `ProductSellerQueryGrpcService`와 정산용
`SettlementSellerQueryGrpcService`는 호출자가 없어 제거됐으며 settlement-service에는 Seller 조회
클라이언트가 없다.

- 상품 화면: `GET /api/v2/sellers/product`, `POST /api/v2/sellers/products`
- 구매상품 화면: `POST /api/v2/users/order-products`
- Wishlist 화면: `POST /api/v2/sellers/wishlists`

### 2-2. Product — 판매자 상품 통계 조합 경계

#452 이후 Seller Settlement 백엔드는 등록 상품 수와 판매건수를 조회하지 않는다. user-service
`sellersettlement`의 Product 통계 gRPC 클라이언트와 `GetSellerStats` 소비 계약은 제거된다.

Product는 등록 프롬프트 수와 누적 판매건수를 제공하는 공개 API를 별도 작업에서 정의한다. 프론트
`/shop`은 Product 통계 API와 Seller Settlement summary를 독립적으로 호출하며, Seller Settlement
summary는 `totalRevenueAmount`, `totalSettlementAmount`만 반환한다.

---

## 3. 발행 이벤트

발행은 두 갈래다. 셀러 정산 seed 용 `SETTLEMENT_CREATED` 는 **구현됐고**, 지급 완료 알림은 추후(파이널)다.

| 토픽 | eventType | 발행 시점 | 구독자 | 상태 |
|------|-----------|----------|--------|------|
| `settlement-events` | `SETTLEMENT_CREATED` | Settlement별 Outbox 적재 후 배치 시작/마지막 flush | user `sellersettlement`(seed) | ✅ Outbox 구현(#301) |
| `settlement.payout.completed` | (미정) | 지급 완료(`payout_status = PAID` 전이) 후 | User | 추후·파이널 |

### 3-1. `SETTLEMENT_CREATED` (✅ Transactional Outbox 구현, #301)

배치가 정산을 계산·생성할 때, 셀러 정산(user-service `sellersettlement`) 운영행을 seed 하도록 발행한다.
공통 래퍼 `EventMessage<T>` 로 감싸며(규칙은 `common-kafka-event-message.md`), 개별 envelope 를 새로 두지 않는다.

정산 1건마다 `EventMessage<SettlementCreatedEvent>` 한 건을 만들고, `Settlement` 저장 및 해당
`SettlementSourceLine.settlementId` 연결과 같은 트랜잭션에 완성된 JSON을 저장한다. Outbox PK와 envelope의
`eventId`는 동일하며, Kafka key는 `settlementId`(=`aggregateId`)다. 재시도와 redrive도 저장 JSON을 그대로
사용하므로 이벤트 식별자가 바뀌지 않는다.

- 주간 정산 Job 시작: `requestedAt` 이전 `PENDING`을 한 번 재시도한다.
- 주간 정산 Job 마지막: 현재 `settlementBatchId`의 `PENDING`을 한 번 발행한다.
- 실패 1~2회: `PENDING`, 3회: `FAILED`. `FAILED`는 자동 재시도에서 제외한다.
- 운영자 재처리: 별도 `outboxRedriveJob`에 `eventId`를 주입해 지정 이벤트만 `FAILED → PENDING`으로
  초기화하고 즉시 발행한다. 정산 계산이나 `SettlementBatch` 생성은 다시 실행하지 않는다.
- user `sellersettlement` 컨슈머는 `settlementId` 유니크로 at-least-once 중복을 흡수한다.

코드 배치, 트랜잭션 경계, 운영 DDL과 Kubernetes 전제는 `kafka-messaging-design.md`를 본다.

### 3-2. `settlement.payout.completed` (추후 — 파이널 단계)

> **현재 범위 아님.** 지급 완료 발행 이벤트는 정산 흐름이 완성되는 파이널 단계에서 도입한다.
> 아래는 그때를 위한 설계 메모다.

- 트리거(예정): `Settlement.payout(paidAt)` 호출로 `PAID` 전이된 직후.
- 용도(예정): 판매자에게 정산 입금 완료 알림.
- 페이로드(안): `eventId`·`settlementId`·`sellerId`·`periodStart`·`periodEnd`·`settlementTotalAmount`·`paidAt`.

> 발행은 정산이 밖으로 **알림**을 내보내는 것이라 Kafka 를 쓴다(수신을 gRPC pull 로 바꾼 것과 무관).
> 원천 **수신**만 pull 로 전환했고, 발행은 여전히 이벤트다.

---

## 4. 어드민 연동 — admin-service (DB 직접 접근, 추후 — 파이널 단계)

> **현재 범위 아님.** 파이널에서 어드민 API 가 admin-service 로 이관되지만
> (`admin-module-separation.md`), 어드민은 gRPC 로 호출하지 않고 **DB 를 직접 바라본다**
> (결정 배경: `../trade-offs/admin-data-access.md`). 운영 조회·상태변경은 운영 단일 진실인
> `seller_settlement`(유저 DB), 배치 예약·잡 상태는 정산 DB 를 본다(셀러 분리 배경:
> `../trade-offs/seller-settlement-separation.md`). 따라서 정산이 어드민에 제공하는 gRPC
> 서버·proto 는 없다. 이 절은 rpc 계약이 없다는 사실과, 그 대신 계약 역할을 하는 것을 기록한다.

| 어드민 동작 | 경로 | 계약 |
|------|------|------|
| 정산 목록·요약·상세 조회 | `seller_settlement` 테이블 직접 SELECT (유저 DB) | seller_settlement 스키마 |
| 정산 상태 변경 | `seller_settlement` 테이블 직접 UPDATE (유저 DB) | seller_settlement 스키마 + 상태 전이 표(단일 출처 문서) |
| 정산 배치 예약 | 배치 예약 테이블 INSERT → 정산 폴링 실행 (정산 DB) | 예약 테이블 스키마(설계 시 확정) |
| 예약·잡 상태 조회 | 예약 테이블·Spring Batch 메타테이블 SELECT (정산 DB) | 〃 |

- **`seller_settlement` 스키마가 운영 접근의 사실상 계약이다.** 그 컬럼 변경·리네이밍 시 어드민
  쿼리 영향 확인을 필수 절차로 둔다. (배치 예약·잡 상태는 정산 DB 스키마가 계약이다.)
- 배치 실행은 어드민이 정산 프로세스를 호출하지 않고 예약 테이블을 사이에 둔다. 구조·상태
  전이는 `admin-module-separation.md` 의 "배치 예약 실행" 절을 본다.

---

## 5. 정산 대상 인입 방식 전환 메모

- **1차(폐기)**: 정산 배치가 Order 내부 API `GET /internal/orders/paid` 를 폴링해 PAID 주문을 조회.
- **2차(폐기)**: 위 폴링을 `order-events`(`ORDER_PAID`/`ORDER_REFUNDED`) **이벤트 구독**으로 대체하고
  `OrderEventConsumer` 로 로컬 사본을 쌓았다.
- **현재(확정)**: 이벤트 구독을 폐기하고 **배치 시점 gRPC pull(`GetSettleableLines`)** 로 전환한다.
  상시 컨슈머 없이 CronJob 배치가 `paidAt`/`refundedAt` 시각 기준으로 그 기간의 결제·환불 라인을
  당겨 `settlement_source_line` 에 적재하고, 배치는 적재된 소스 라인만 읽어 `Settlement` 를 계산한다.
  전환 배경·설계는 `trade-offs/order-data-sourcing.md`.

---

## 6. 구현 현황 요약

| 항목 | 현황 |
|------|------|
| order 원천 수급 | **gRPC pull 구현 완료.** settlement의 `OrderSettlementQueryClient`와 order의 `OrderQueryGrpcServer`가 `period_start`·`period_end` 주간 계약으로 연결된다. `period(yyyy-MM)`는 order-service의 이전 배포 호환 fallback만 유지한다 |
| 기존 `OrderEventConsumer`(`order-events`) | **#317에서 제거 완료.** 컨슈머·수신 DTO·usecase·consumer 설정 없이 주간 배치가 order gRPC에서 원천을 조회한다 |
| 판매자 이름(다건/단건) | **정산 백엔드 통신 없음.** user-service REST Seller 조회 API를 프론트가 화면별로 호출하며 Product·Settlement용 Seller gRPC 서버는 제거됨 |
| 등록 상품 수 / 판매건수 | **Seller Settlement 내부 조회 제거(#452).** Product 공개 API를 프론트가 직접 호출하는 구조로 전환. 정확한 Product API 계약은 Product 후속 작업 |
| gRPC 의존성 | 추가됨 (`grpc-stub`·`grpc-protobuf`·`protobuf` + `protobuf-gradle-plugin`), proto 는 루트 `grpc/<소유서버>/` 공유 |
| `SETTLEMENT_CREATED` 발행 | **✅ Transactional Outbox 구현(#301)** — Settlement별 같은 트랜잭션 적재, 시작/마지막 배치 flush, 3회 실패 `FAILED`, `outboxRedriveJob(eventId)`. `settlement-events` 토픽, 셀러 정산 seed 용(§3-1) |
| `settlement.payout.completed` 발행 | **추후(파이널)** — 현재 범위 아님. 발행은 Kafka 유지 |
| 어드민 연동 (§4) | **추후(파이널)** — gRPC 서버 없음. 운영 조회·상태변경은 `seller_settlement`(유저 DB) 직접 접근(#245 재작업). 완료된 과거 주차 보정과 운영자 일회성 Job 제어면은 이번 CronJob 범위 밖 |
