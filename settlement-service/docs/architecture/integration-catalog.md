# 정산 서비스 연동 카탈로그 (동기 조회 · 발행)

Settlement 서비스가 외부와 주고받는 데이터를 정리한다. 네 갈래로 나눈다.

- **원천 데이터 조회(gRPC pull)** — 정산의 원천(결제/환불). 배치 시점에 order 에서 당겨와 정산
  DB(`settlement_source_line`)에 **소유**한다. → §1
- **참고 데이터 조회(gRPC)** — 남의 서비스가 소유한 참고 데이터(판매자 정보·상품 수). 복제하지
  않고 필요 시점에 조회한다. → §2
- **발행** — 셀러 정산 seed 용 `SETTLEMENT_CREATED` 는 구현됨, 지급 완료 알림은 추후. → §3
- **어드민 연동** — 추후(파이널) 어드민 모듈 분리. gRPC 가 아니라 어드민이 정산 DB 를
  **직접 접근**하므로 rpc 계약이 없다 — 대신 스키마·예약 테이블이 계약이다. → §4

> 원천 수급을 왜 이벤트(Kafka push)가 아니라 gRPC pull 로 하는지는 `trade-offs/order-data-sourcing.md`
> 를 본다. 이 문서는 그 결정에 따른 **연동 계약(무엇을 주고받는지)** 을 정리한다.
> proto 계약은 **정산 서비스 기준 제안**이며, 상대 팀과 합의되면 각 서비스 api-spec 에도 반영한다.
> 구현(계층·패키지)은 `kafka-messaging-design.md` 를 본다.

> **현재 실제 통신 상태(어느 모듈이 무엇을 구현/비활성/대기 중인지)는 `settlement-internal-comm-topology.md`
> 의 현황 매트릭스를 본다.** 이 문서(§2·§3)는 정산 도메인이 세 모듈로 분산되기 전 "정산 본체가 직접
> 조회·발행" 기준으로 쓰인 **계약 상세**다. 이관 후 §2(판매자명·상품수 조회)는 셀러 정산(user-service
> `sellersettlement`)에서 수행된다 — 각 절 상단 노트를 참고한다.

---

## 1. 원천 데이터 조회 — order 정산 라인 (gRPC pull)

정산의 **원천 데이터**(결제·환불)는 정산 DB 에 적재해야 한다. 배치가 도는 시점에 order 에 gRPC 로
그 기간의 정산 라인을 요청해 `settlement_source_line` 에 소유한다. **이벤트 구독이 아니라 배치 시점
조회다** — 상시 컨슈머 없이 CronJob 배치가 필요할 때 당겨온다. (결정 배경은 `trade-offs/order-data-sourcing.md`.)

| 조회 | 발행처(서버) | 용도 | 멱등 단위 | 구현 현황 |
|------|-------------|------|----------|----------|
| `GetSettleableLines(period)` · `PAID` 라인 | Order | 결제 라인을 `settlement_source_line`(PAID) 적재 | `orderProductId` + PAID | 클라이언트 구현 완료(#260) — **Order gRPC 서버 신설 요청** |
| `GetSettleableLines(period)` · `REFUND` 라인 | Order | 환불 라인을 `settlement_source_line`(REFUND) 적재 | `orderProductId` + REFUND | 클라이언트 구현 완료(#260) — **Order gRPC 서버 신설 요청** |

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

### 1-2. gRPC 계약 — `GetSettleableLines` (정산이 제안, Order 팀이 서버 구현)

period 하나로 결제·환불 라인을 `line_type` 으로 구분해 한 응답(`repeated SettleableLine`)으로 받는다.
서버는 내부적으로 `paidAt ∈ period`(결제)와 `refundedAt ∈ period`(환불) 두 쿼리를 합쳐 내려준다.
**status 로 필터하지 않는다** — 환불된 라인도 `paidAt` 이 기간 안이면 PAID 라인으로, `refundedAt` 이
기간 안이면 REFUND 라인으로 각각 내려온다.

> **전송 방식은 unary(단일 요청/응답 + `repeated`)로 확정.** 초안은 대량 대응 server-streaming 이었으나,
> 월 배치 1회 호출·구현 단순성을 우선해 unary + `repeated` 로 구현했다(#260). 볼륨이 실제로 문제가
> 되면 그때 streaming 으로 전환한다.

- 제공(요청 대상): Order 서비스 (gRPC 서버 신설 요청)
- 호출: Settlement(배치 첫 스텝 `loadSettlementSourceStep`) → Order
- 호출 시점: 정산 배치 실행 시(스케줄 CronJob·수동 모두 동일 경로)
- proto: `grpc/order/order_query.proto` (루트 공유, 정산이 정의해 제안·커밋)

```protobuf
syntax = "proto3";

package settlement.order;

option java_multiple_files = true;
option java_package = "com.prompthub.order.grpc";
option java_outer_classname = "OrderQueryProto";

service OrderQueryService {
  rpc GetSettleableLines(GetSettleableLinesRequest) returns (GetSettleableLinesResponse);
}

message GetSettleableLinesRequest {
  string period = 1;   // "2026-06" — paidAt/refundedAt '발생 시각' 기준 기간
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
| `period` | string(`YYYY-MM`) | ✅ | 정산 대상 기간. 서버는 이 기간을 `paidAt`/`refundedAt` 필터에 쓴다 |

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

## 2. 참고 데이터 조회 (gRPC 동기 조회)

> **이관 노트(현재 상태):** 아래 판매자명·상품수 참고 조회는 셀러 정산이 user-service 로 이관되며
> **`user-service` 의 `sellersettlement` 패키지에서 수행**된다. settlement 본체에는 이 클라이언트가
> 남아 있지 않다. 상품 수 조회는 `ProductStatsGrpcClient`(→ product `GetSellerStats`)로 구현되어 있으나,
> **product 서버는 아직 `CountBySeller` 라 서버 리네임 전까지 wire 불일치**(product 팀 리네임 대기, 조율됨). `sales_count` 필드도 서버 확장 대기. 판매자명 조회 서버
> (`GetSellers`)는 user-service `seller` 패키지에 live 이나 이를 호출하는 정산측 클라이언트는 아직 없다.
> 실제 구현/대기 상태는 `settlement-internal-comm-topology.md` §3-2·§4 를 본다. 아래 proto 는 계약 상세다.

판매자명과 상품 수는 **다른 서비스가 소유한 참고 데이터**다. 정산은 이를 복제(읽기모델)하지 않고,
**필요한 시점에 동기 조회**한다. (§1 의 원천 데이터와 달리 정산 DB 에 소유하지 않는다.)
(판매자 정산 계좌 정보는 아직 범위가 아니다 — 추후 지급 실행을 붙일 때 함께 정한다.)

**왜 복제하지 않는가**

- 판매자명·상품 수는 **표시용**이라 실시간 정확도가 중요하지 않다. 정산 조회 시 한 번 물으면 충분하다.
- 복제(읽기모델)는 저장소·정합성 재동기화 비용을 늘린다. 성능/장애 격리가 실제로 필요해질 때
  도입하면 되고, 시작 단계에는 동기 조회가 단순하고 안전하다.

| 데이터 | 조회 시점 | 호출 대상 |
|--------|----------|----------|
| 판매자 이름(상점명) — 다건 | 정산 목록 조회 시(판매자 여러 명) | User gRPC: `SellerQueryService.GetSellers` (§2-1) |
| 판매자 등록 상품 수 | 정산 요약 조회 시 | Product gRPC: `ProductQueryService.GetSellerStats` (§2-2) |

> **정산 목록은 다건(batch)이 기본 경로다.** 어드민 판매자별 정산 목록은 한 페이지에 판매자가
> 여러 명 나오므로, 행마다 단건 조회를 부르면 N+1 이 된다. 목록에 등장하는 `sellerId` 를 모아
> **한 번에** `GetSellers` 로 조회해 `sellerId → sellerName` 으로 매핑한다.
> 단건(정산 단건/요약)도 같은 rpc 에 `sellerIds` 1건을 담아 호출한다(단건 전용 rpc 를 따로 두지 않는다).

- **통신 방식: gRPC.** (REST/OpenFeign 아님 — 전환됨.) **단, 이 참고 조회의 proto·클라이언트는
  정산 본체(settlement-service)에 더 이상 없다** — 셀러 정산이 user-service 로 이관되며 그쪽에서
  계약·구현을 소유한다(§2-1·§2-2). 아래 서술은 이관 전 "정산 본체 직접 조회" 기준 설계 배경이다.
- 채널은 게이트웨이를 거치지 않고 서비스 간 직접 호출한다. 채널 주소는 `grpc.client.{user,product}-service.address`
  로 주입한다(서비스 디스커버리 연동은 상대 서버 연동 시 확정). 평문(`usePlaintext`) 기본, 보안(TLS)은 추후.
- **(현재)** 상품 수 조회 클라이언트는 셀러 정산(user-service `sellersettlement`)의 `ProductStatsGrpcClient`
  로 구현되어 있으나 product 서버는 아직 `CountBySeller` 라 서버 리네임 전까지 wire 불일치(조율됨). 판매자명 조회(`GetSellers`) 서버는
  user-service `seller` 패키지에 live 이나 이를 부르는 정산측 클라이언트는 아직 없다(요청 대기).
- 정산 요약 응답의 `registeredPromptCount`(= 등록 상품 수)는 §2-2 조회값으로 채운다.

> **§1(원천)과 §2(참고)의 실패 정책은 다르다.** §2 참고 조회(판매자명·상품수)는 표시용이라 실패
> 시 빈값으로 폴백해 정산 조회를 살린다. 그러나 §1 원천 조회(order 정산 라인)는 **정산 금액·대상에
> 직접 영향**을 주므로 실패를 삼키면 안 된다 — 배치가 멈추고 재시도해야 한다. (장식은 비우고, 돈은
> 멈춘다 — `internal-sync-transport.md`.)

### 2-1. User — 판매자 정보 조회 (`GetSellers`)

판매자명(상점명) 조회다. 정산 목록은 판매자가 여러 명이라 다건(batch) rpc 하나로 N+1 을 피하는
모양(`seller_ids` → `sellerId→sellerName` 매핑, 실패 시 빈 맵 폴백)이 된다.

> **현황:** `GetSellers` **서버는 user-service `seller` 패키지에 live** 이나, 이를 호출하는 **정산측
> 클라이언트는 아직 없다**(요청 대기 — `settlement-internal-comm-topology.md` §4-3). 정산 본체에
> `seller_query.proto`·클라이언트는 **존재하지 않는다.** 계약 전문(proto·필드)은 서버를 소유한
> user-service `seller` 쪽에서 관리한다. (정산 계좌 정보는 여기 없다 — 지급 실행 붙일 때 별도로 정한다.)

### 2-2. Product — 판매자 등록 상품 수·판매건수 조회 (`GetSellerStats`)

셀러 정산 요약의 등록 상품 수(`registeredPromptCount`)·판매건수를 채운다.

> **현황:** 이 조회는 셀러 정산이 user-service 로 이관되며 **`sellersettlement` 의
> `ProductStatsGrpcClient`(→ product `GetSellerStats`)로 구현된다.** product 서버는 live 이나 아직
> `CountBySeller` 라, 서버를 `GetSellerStats` 로 리네임하기 전까지 wire 불일치다(리네임 조율됨).
> `sales_count`(#262 확장) 필드도 서버 채움 대기다(실패 시 `SellerProductStats.empty()`(0) 폴백).
> 정산 본체엔 `product_query.proto` 클라이언트가 **없다.** 계약(proto)은 루트 `grpc/product/product_query.proto`
> 에 두고 소비자는 user-service `sellersettlement` 다(서버 원본은 product-service 잔존). 집계 기준(판매 중만 vs 전체)은 Product 팀과 확정한다.

---

## 3. 발행 이벤트

발행은 두 갈래다. 셀러 정산 seed 용 `SETTLEMENT_CREATED` 는 **구현됐고**, 지급 완료 알림은 추후(파이널)다.

| 토픽 | eventType | 발행 시점 | 구독자 | 상태 |
|------|-----------|----------|--------|------|
| `settlement-events` | `SETTLEMENT_CREATED` | 정산 생성(계산) 트랜잭션 커밋 후(AFTER_COMMIT) | user `sellersettlement`(seed) | ✅ 구현(#258) |
| `settlement.payout.completed` | (미정) | 지급 완료(`payout_status = PAID` 전이) 후 | User | 추후·파이널 |

### 3-1. `SETTLEMENT_CREATED` (✅ 구현됨)

배치가 정산을 계산·생성할 때, 셀러 정산(user-service `sellersettlement`) 운영행을 seed 하도록 발행한다.
공통 래퍼 `EventMessage<T>` 로 감싸며(규칙은 `common-kafka-event-message.md`), 개별 envelope 를 새로 두지 않는다.

> **발행 스펙·현황의 상세(포트/어댑터·페이로드 10필드·발행 시점·실패 정책·구독측)는
> `settlement-internal-comm-topology.md` §3-1 이 SSOT 다.** 삼중 서술을 피하려 여기서는 요약만 둔다 —
> `EventMessage<SettlementCreatedPayload>` 를 `settlement-events` 토픽에 `settlementId`(=`aggregateId`) 키로
> AFTER_COMMIT 직접 발행하고, user `sellersettlement` 컨슈머가 seed 한다(멱등: `settlementId` 유니크).
> 코드가 어느 계층·패키지에 놓이는지는 `kafka-messaging-design.md` 를 본다.

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
| order 원천 수급 | **gRPC pull 로 전환 — 정산측 구현 완료(#260).** `OrderSettlementQueryClient`·`OrderSettlementQueryPort`·`LoadSettlementSourceTasklet`(배치 첫 스텝) 커밋됨. Order 서버(`GetSettleableLines`)는 미구현 — Order 에 `paidAt` 추가 + 서버 신설 요청 필요(계약은 #260 이슈 코멘트) |
| 기존 `OrderEventConsumer`(`order-events`) | **제거 예정, 현재는 `settlement.kafka.listener.order.enabled: false` 로 비활성 유지.** order gRPC 서버 가동 전까지 유일하게 계약이 있는 폴백이라 코드·DTO 를 남겨두고, 서버 가동 후 정리 |
| 판매자 이름(다건/단건) | 동기 조회 — **gRPC** `SellerQueryService.GetSellers`. **user-service `seller` 패키지에 서버 live**, 그러나 이를 호출하는 정산측 클라이언트는 아직 없음(요청 대기). 계좌는 추후 |
| 등록 상품 수 / 판매건수 | 동기 조회 — **gRPC** `ProductQueryService.GetSellerStats`. **셀러 정산(user-service `sellersettlement`)의 `ProductStatsGrpcClient` 구현.** product 서버는 아직 `CountBySeller` — 서버 리네임(조율됨)·`sales_count`(#262) 확장 대기 |
| gRPC 의존성 | 추가됨 (`grpc-stub`·`grpc-protobuf`·`protobuf` + `protobuf-gradle-plugin`), proto 는 루트 `grpc/<소유서버>/` 공유 |
| `SETTLEMENT_CREATED` 발행 | **✅ 구현(#258)** — `settlement-events` 토픽, `EventMessage<SettlementCreatedPayload>`, AFTER_COMMIT 직접 발행. 셀러 정산 seed 용(§3-1) |
| `settlement.payout.completed` 발행 | **추후(파이널)** — 현재 범위 아님. 발행은 Kafka 유지 |
| 어드민 연동 (§4) | **추후(파이널)** — gRPC 서버 없음. 운영 조회·상태변경은 `seller_settlement`(유저 DB) 직접 접근(#245 재작업), 배치는 정산 DB 예약 테이블 + 폴링. 예약 테이블 스키마 미정 |
</content>
