# 정산 서비스 연동 카탈로그 (동기 조회 · 발행)

Settlement 서비스가 외부와 주고받는 데이터를 정리한다. 네 갈래로 나눈다.

- **원천 데이터 조회(gRPC pull)** — 정산의 원천(결제/환불). 배치 시점에 order 에서 당겨와 정산
  DB(`settlement_source_line`)에 **소유**한다. → §1
- **참고 데이터 조회(gRPC)** — 남의 서비스가 소유한 참고 데이터(판매자 정보·상품 수). 복제하지
  않고 필요 시점에 조회한다. → §2
- **발행** — 추후(파이널) 도입. → §3
- **어드민 제공(gRPC 서버)** — 추후(파이널) 어드민 모듈 분리와 함께 도입. §1·§2 와 달리
  정산이 **호출받는 쪽**이다. → §4

> 원천 수급을 왜 이벤트(Kafka push)가 아니라 gRPC pull 로 하는지는 `trade-offs/order-data-sourcing.md`
> 를 본다. 이 문서는 그 결정에 따른 **연동 계약(무엇을 주고받는지)** 을 정리한다.
> proto 계약은 **정산 서비스 기준 제안**이며, 상대 팀과 합의되면 각 서비스 api-spec 에도 반영한다.
> 구현(계층·패키지)은 `kafka-messaging-design.md` 를 본다.

---

## 1. 원천 데이터 조회 — order 정산 라인 (gRPC pull)

정산의 **원천 데이터**(결제·환불)는 정산 DB 에 적재해야 한다. 배치가 도는 시점에 order 에 gRPC 로
그 기간의 정산 라인을 요청해 `settlement_source_line` 에 소유한다. **이벤트 구독이 아니라 배치 시점
조회다** — 상시 컨슈머 없이 CronJob 배치가 필요할 때 당겨온다. (결정 배경은 `trade-offs/order-data-sourcing.md`.)

| 조회 | 발행처(서버) | 용도 | 멱등 단위 | 구현 현황 |
|------|-------------|------|----------|----------|
| `GetSettleableLines(period)` · `PAID` 라인 | Order | 결제 라인을 `settlement_source_line`(PAID) 적재 | `orderProductId` + PAID | 클라이언트 미구현 — **Order gRPC 서버 신설 요청** |
| `GetSettleableLines(period)` · `REFUND` 라인 | Order | 환불 라인을 `settlement_source_line`(REFUND) 적재 | `orderProductId` + REFUND | 클라이언트 미구현 — **Order gRPC 서버 신설 요청** |

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

period 하나로 결제·환불 라인을 `line_type` 으로 구분해 스트리밍한다(대량 대응). 서버는 내부적으로
`paidAt ∈ period`(결제)와 `refundedAt ∈ period`(환불) 두 쿼리를 합쳐 내려준다. **status 로 필터하지
않는다** — 환불된 라인도 `paidAt` 이 기간 안이면 PAID 라인으로, `refundedAt` 이 기간 안이면 REFUND
라인으로 각각 내려온다.

- 제공(요청 대상): Order 서비스 (gRPC 서버 신설 요청)
- 호출: Settlement(배치 Step 0) → Order
- 호출 시점: 정산 배치 실행 시(스케줄 CronJob·수동 모두 동일 경로)
- proto: `settlement-service/src/main/proto/order_settlement_query.proto` (정산이 정의해 제안)

```protobuf
syntax = "proto3";

package settlement.order;

option java_multiple_files = true;
option java_package = "com.prompthub.settlement.grpc.order";
option java_outer_classname = "OrderSettlementQueryProto";

service OrderSettlementQueryService {
  rpc GetSettleableLines(SettleablePeriodRequest) returns (stream SettleableLine);
}

message SettleablePeriodRequest {
  string period = 1;   // "2026-06" — paidAt/refundedAt '발생 시각' 기준 기간
}

message SettleableLine {
  string order_product_id = 1;
  string order_id         = 2;
  string seller_id        = 3;
  int64  amount           = 4;   // PAID 는 결제액, REFUND 는 환불액
  string line_type        = 5;   // PAID | REFUND
  string occurred_at      = 6;   // PAID 면 paidAt, REFUND 면 refundedAt
}
```

**요청 — `SettleablePeriodRequest`**

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `period` | string(`YYYY-MM`) | ✅ | 정산 대상 기간. 서버는 이 기간을 `paidAt`/`refundedAt` 필터에 쓴다 |

**응답 — `SettleableLine` (stream)**

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `order_product_id` | string(UUID) | ✅ | 주문상품 ID(정산 소스 라인 단위) |
| `order_id` | string(UUID) | ✅ | 주문 ID |
| `seller_id` | string(UUID) | ✅ | 판매자 ID |
| `amount` | int64 | ✅ | PAID=결제액, REFUND=환불액(원화) |
| `line_type` | string | ✅ | `PAID` \| `REFUND` |
| `occurred_at` | string(DateTime) | ✅ | PAID=paidAt, REFUND=refundedAt (라인 `occurred_at`) |

> **정산이 라인 적재에 쓰는 값:** `order_product_id`·`order_id`·`seller_id`·`amount`·`line_type`·
> `occurred_at`. 대상 도메인은 `SettlementSourceLine.paid(...)` / `.refunded(...)` 로, `line_type` 에
> 따라 갈린다.

### 1-3. 멱등 / 라인 적재

배치 Step 0 가 받은 라인을 `settlement_source_line` 에 멱등 적재한다.

- 멱등키 seed 는 **`orderProductId` + 상태**다.
  `PAID` = `nameUUIDFromBytes(orderProductId | "PAID")`,
  `REFUND` = `nameUUIDFromBytes(orderProductId | "REFUND")`.
- 같은 `orderProductId` 라도 PAID/REFUND 는 seed 가 달라 **별도 라인**으로 적재된다. 환불이 결제
  라인을 덮어쓰지 않고 음수 보정 라인으로 더해져, 배치가 PAID 합 − REFUND 합으로 순액을 낸다.
- 배치 재실행 시 같은 기간 재조회해도 같은 멱등키라 중복 적재가 걸러진다(`existsByEventId`).
- `settlement_source_line.event_id`(단일 unique) 스키마·도메인 제약은 그대로 둔다. 부분·다중 환불이
  생기면 order 가 `refundId` 를 주고 seed 에 더한다(현재 `OrderProduct.refund()` 는 전액 1회).

정산 배치는 `settlement_source_line` 의 미정산 PAID 라인을 판매자·기간 단위로 모으고 REFUND 를
차감해 `Settlement` 를 계산한다. (이미 정산된 라인 제외 dedup 은 `SettlementDetail` 기준 — 배치가 건다.)

---

## 2. 참고 데이터 조회 (gRPC 동기 조회)

판매자명과 상품 수는 **다른 서비스가 소유한 참고 데이터**다. 정산은 이를 복제(읽기모델)하지 않고,
**필요한 시점에 동기 조회**한다. (§1 의 원천 데이터와 달리 정산 DB 에 소유하지 않는다.)
(판매자 정산 계좌 정보는 아직 범위가 아니다 — 추후 지급 실행을 붙일 때 함께 정한다.)

**왜 복제하지 않는가**

- 판매자명·상품 수는 **표시용**이라 실시간 정확도가 중요하지 않다. 정산 조회 시 한 번 물으면 충분하다.
- 복제(읽기모델)는 저장소·정합성 재동기화 비용을 늘린다. 성능/장애 격리가 실제로 필요해질 때
  도입하면 되고, 시작 단계에는 동기 조회가 단순하고 안전하다.

| 데이터 | 조회 시점 | 호출 대상 |
|--------|----------|----------|
| 판매자 이름(상점명) — 다건 | 정산 목록 조회 시(판매자 여러 명) | User gRPC: `SellerQueryService.FindSellers` (§2-1) |
| 판매자 등록 상품 수 | 정산 요약 조회 시 | Product gRPC: `ProductQueryService.CountBySeller` (§2-2) |

> **정산 목록은 다건(batch)이 기본 경로다.** 어드민 판매자별 정산 목록은 한 페이지에 판매자가
> 여러 명 나오므로, 행마다 단건 조회를 부르면 N+1 이 된다. 목록에 등장하는 `sellerId` 를 모아
> **한 번에** `FindSellers` 로 조회해 `sellerId → sellerName` 으로 매핑한다.
> 단건(정산 단건/요약)도 같은 rpc 에 `sellerIds` 1건을 담아 호출한다(단건 전용 rpc 를 따로 두지 않는다).

- **통신 방식: gRPC.** proto 계약(`src/main/proto/seller_query.proto`·`product_query.proto`)을 **정산이
  정의해 제안**하고, User·Product 팀에 해당 gRPC 서버 신설을 요청한다. (REST/OpenFeign 아님 — 전환됨)
- 채널은 게이트웨이를 거치지 않고 서비스 간 직접 호출한다. 채널 주소는 `grpc.client.{user,product}-service.address`
  로 주입한다(서비스 디스커버리 연동은 상대 서버 연동 시 확정). 평문(`usePlaintext`) 기본, 보안(TLS)은 추후.
- 정산 클라이언트는 구현 완료다(`infrastructure/client` 의 `SellerQueryClient`·`ProductQueryClient`,
  블로킹 스텁). **상대 gRPC 서버가 아직 없으므로** 호출 실패 시 빈값(빈 맵/0)으로 폴백해 정산 조회를 막지 않는다.
- 정산 요약 응답의 `registeredPromptCount`(= 등록 상품 수)는 §2-2 조회값으로 채운다.

> **§1(원천)과 §2(참고)의 실패 정책은 다르다.** §2 참고 조회(판매자명·상품수)는 표시용이라 실패
> 시 빈값으로 폴백해 정산 조회를 살린다. 그러나 §1 원천 조회(order 정산 라인)는 **정산 금액·대상에
> 직접 영향**을 주므로 실패를 삼키면 안 된다 — 배치가 멈추고 재시도해야 한다. (장식은 비우고, 돈은
> 멈춘다 — `internal-sync-transport.md`.)

### 2-1. User — 판매자 정보 조회 (gRPC `SellerQueryService`)

정산이 판매자명을 가져오려고 User 서비스에 신설 요청하는 gRPC 서비스다.
정산 목록은 판매자가 여러 명이라 **다건(batch) rpc 하나**로 N+1 을 피한다. 단건(정산 단건/요약)도
같은 rpc 에 `seller_ids` 1건을 담아 호출한다.

> **정산 계좌 정보는 이 응답에 없다.** 지급 실행을 붙이는 단계에서 계좌(은행 코드·계좌번호·예금주)를
> 어떻게 받을지 별도로 정한다. 지금은 셀러 식별·표시 정보만 가져온다.

- 제공(요청 대상): User 서비스 (gRPC 서버 신설 요청)
- 호출: Settlement → User
- 호출 시점: 정산 목록·단건·요약 조회 시(판매자명 표시)
- proto: `settlement-service/src/main/proto/seller_query.proto` (정산이 정의해 제안)

**proto 계약 (전문 — User 팀이 그대로 받아 서버를 구현한다)**

```protobuf
syntax = "proto3";

package settlement.seller;

option java_multiple_files = true;
option java_package = "com.prompthub.settlement.grpc.seller";
option java_outer_classname = "SellerQueryProto";

service SellerQueryService {
  rpc FindSellers(SellerBatchQueryRequest) returns (SellerBatchQueryResponse);
}

message SellerBatchQueryRequest {
  repeated string seller_ids = 1;
}

message SellerBatchQueryResponse {
  repeated SellerInfo sellers = 1;
}

message SellerInfo {
  string seller_id = 1;
  string seller_name = 2;
  string profile_image_url = 3;
  string status = 4;
}
```

**요청 — `SellerBatchQueryRequest`**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `seller_ids` | repeated string(UUID) | ✅ | 조회할 판매자 ID 목록. 중복은 호출 전 제거 권장 |

**응답 — `SellerBatchQueryResponse` (`sellers`: repeated `SellerInfo`)**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `seller_id` | string(UUID) | ✅ | 판매자 ID |
| `seller_name` | string | ✅ | 판매자명 |
| `profile_image_url` | string | ⬜ | 프로필 이미지 URL(없으면 빈 문자열) |
| `status` | string | ⬜ | 판매자/계정 상태 (예: `ACTIVE`) |

**메시지 예시** (gRPC 는 바이너리라, 값은 textproto 표기로 보인다)

```textproto
# 요청 SellerBatchQueryRequest
seller_ids: "770e8400-e29b-41d4-a716-446655440002"
seller_ids: "881e8400-e29b-41d4-a716-446655440013"
```

```textproto
# 응답 SellerBatchQueryResponse
sellers { seller_id: "770e8400-e29b-41d4-a716-446655440002" seller_name: "프롬프트마스터" profile_image_url: "https://cdn.example.com/p.jpg" status: "ACTIVE" }
sellers { seller_id: "881e8400-e29b-41d4-a716-446655440013" seller_name: "AI스튜디오" status: "ACTIVE" }
```

- **정산이 실제 쓰는 필드는 `seller_id`·`seller_name` 뿐이다.** `profile_image_url`·`status` 는 받되 사용하지 않는다.
- **존재하지 않는 `seller_id` 는 응답에서 빠진다**(요청 N개 != 응답 N개). 정산은 매핑에 없는 판매자명을 빈 값/대체 표기로 처리한다.
- 응답 순서는 보장하지 않는다. 정산은 `seller_id` 키로 매핑해 쓴다.
- 조회 실패는 gRPC status(예: `UNAVAILABLE`)로 내려오면 되고, 정산은 이를 빈 결과로 폴백한다.

> 정산 포트는 `SellerQueryPort.findSellerNames(List<UUID> sellerIds)` 로 다건을 받고,
> 어댑터(`infrastructure/client/SellerQueryClient`)가 블로킹 스텁으로 `FindSellers` 를 호출한다.
> 호출 실패(`StatusRuntimeException`)는 빈 맵으로 폴백한다. (포트·어댑터 규칙은 `clean-architecture.md` §4)

### 2-2. Product — 판매자 등록 상품 수 조회 (gRPC `ProductQueryService`)

정산 요약 화면의 등록 상품 수(`registeredPromptCount`)를 채울 때 조회한다.

- 호출: Settlement → Product
- 호출 시점: 판매자 정산 요약 조회 시
- proto: `settlement-service/src/main/proto/product_query.proto` (정산이 정의해 제안)

**proto 계약 (전문 — Product 팀이 그대로 받아 서버를 구현한다)**

```protobuf
syntax = "proto3";

package settlement.product;

option java_multiple_files = true;
option java_package = "com.prompthub.settlement.grpc.product";
option java_outer_classname = "ProductQueryProto";

service ProductQueryService {
  rpc CountBySeller(ProductCountRequest) returns (ProductCountResponse);
}

message ProductCountRequest {
  string seller_id = 1;
}

message ProductCountResponse {
  string seller_id = 1;
  int32 product_count = 2;
}
```

**요청 — `ProductCountRequest`**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `seller_id` | string(UUID) | ✅ | 판매자(사용자) ID |

**응답 — `ProductCountResponse`**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `seller_id` | string(UUID) | ✅ | 판매자(사용자) ID |
| `product_count` | int32 | ✅ | 판매자가 등록한(판매 중인) 상품 수 |

**메시지 예시**

```textproto
# 요청 ProductCountRequest
seller_id: "770e8400-e29b-41d4-a716-446655440002"
```

```textproto
# 응답 ProductCountResponse
seller_id: "770e8400-e29b-41d4-a716-446655440002"
product_count: 12
```

- 정산이 실제 쓰는 값은 `product_count` 다. 받은 값을 정산 요약에 그대로 노출한다.
- 집계 기준(판매 중만 vs 전체 상태 포함)은 Product 팀과 확정한다.
- 어댑터(`infrastructure/client/ProductQueryClient`)가 `CountBySeller` 를 호출하고, 실패(gRPC status) 시 0 으로 폴백한다.

---

## 3. 발행 이벤트 (추후 — 파이널 단계)

> **현재 범위 아님.** 지급 완료 발행 이벤트는 지금 구현하지 않고, 정산 흐름이 완성되는 파이널 단계에서
> 도입한다. 아래는 그때를 위한 설계 메모로만 남긴다.

| 토픽 | 발행 시점 | 구독자 | 도입 시점 |
|------|----------|--------|----------|
| `settlement.payout.completed` | 지급 완료(`payout_status = PAID` 전이) 후 | User | 파이널 |

- 트리거(예정): `Settlement.payout(paidAt)` 호출로 `PAID` 전이된 직후.
- 용도(예정): 판매자에게 정산 입금 완료 알림.
- 페이로드(안): `eventId`·`settlementId`·`sellerId`·`periodStart`·`periodEnd`·`settlementTotalAmount`·`paidAt`.

> 발행은 정산이 밖으로 **알림**을 내보내는 것이라 Kafka 를 쓴다(수신을 gRPC pull 로 바꾼 것과 무관).
> 원천 **수신**만 pull 로 전환했고, 지급 완료 **발행**은 여전히 이벤트다.

---

## 4. 어드민 제공 — admin-service 조회·명령 (gRPC 서버, 추후 — 파이널 단계)

> **현재 범위 아님.** 파이널에서 어드민 API 가 admin-service 로 이관되면
> (`admin-module-separation.md`), 정산이 어드민용 gRPC 서버를 제공한다. §1·§2 는 정산이
> 호출하는 쪽이지만 여기서는 **정산이 서버**다. 아래는 rpc 후보 목록이며, proto 는 이관
> 설계 단계에서 확정해 이 절에 채운다.

| rpc (안) | 대응하는 기존 REST | 성격 | 호출 |
|------|------|------|------|
| 정산 목록·요약·상세 조회 | `SettlementController` GET 계열 | 조회 | admin → Settlement |
| 정산 상태 변경 | `SettlementController` PATCH | 명령(도메인 상태 전이) | admin → Settlement |
| 정산 배치 실행 | `SettlementBatchController` POST | 명령(`RunSettlementBatchUseCase`) | admin → Settlement |
| 배치 잡 상태 조회 | `SettlementBatchController` GET | 조회 | admin → Settlement |

- 조회·명령 모두 이 gRPC 한 경로다. 어드민은 정산 DB 에 직접 커넥션을 맺지 않는다
  (결정 배경: `../trade-offs/admin-data-access.md`).
- 상태 전이 규칙·배치 실행은 계속 정산이 보장한다. 기존 유스케이스를 gRPC 인바운드
  어댑터가 재사용하는 구조다(`admin-module-separation.md`).

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
| order 원천 수급 | **gRPC pull 로 전환 확정.** `OrderSettlementQueryClient`(신규)·Order 서버(`GetSettleableLines`) 미구현 — Order 에 `paidAt` 추가 + 서버 신설 요청 필요 |
| 기존 `OrderEventConsumer`(`order-events`) | **제거 대상**(이벤트 구독 폐기). Kafka consumer 설정·order 이벤트 DTO 함께 정리 |
| 판매자 이름(다건/단건) | 동기 조회 — **gRPC** `SellerQueryService.FindSellers`. 정산 클라이언트 구현 완료(`SellerQueryClient`), User gRPC 서버 신설 요청 필요. 계좌는 추후 |
| 등록 상품 수 | 동기 조회 — **gRPC** `ProductQueryService.CountBySeller`. 정산 클라이언트 구현 완료(`ProductQueryClient`), Product gRPC 서버 신설 요청 필요 |
| gRPC 의존성 | 추가됨 (`grpc-stub`·`grpc-protobuf`·`protobuf` + `protobuf-gradle-plugin`), proto `src/main/proto/*.proto` |
| `settlement.payout.completed` 발행 | **추후(파이널)** — 현재 범위 아님. 발행은 Kafka 유지 |
| 어드민용 gRPC 서버 (§4) | **추후(파이널)** — 어드민 모듈 분리와 함께 도입. proto 미정, rpc 후보만 확정 |
</content>
