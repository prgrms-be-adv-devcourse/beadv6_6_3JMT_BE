# 정산 서비스 연동 카탈로그 (이벤트 · 동기 조회)

Settlement 서비스가 외부와 주고받는 데이터를 정리한다. 두 갈래로 나눈다.

- **이벤트로 받는 것** — 정산의 원천 데이터(매출/환불). 정산 DB 에 쌓아야 하는 데이터다. → §1
- **동기 조회로 가져오는 것** — 남의 서비스가 소유한 참고 데이터(판매자 정보·상품 수). 복제하지
  않고 필요 시점에 조회한다. → §2
- **발행** — 추후(파이널) 도입. → §3

> 이벤트 토픽·페이로드는 **정산 서비스 기준 초안**이다. 발행처와 합의되면 각 서비스의
> api-spec(`docs/api-spec/*.md`) 에도 반영한다. (루트 api-spec 반영 여부는 별도 결정)
> 구현(계층·패키지·발행 전략)은 `kafka-messaging-design.md` 를 본다.
> 현재 메시징 인프라(Kafka 등) 의존성은 `settlement-service/build.gradle` 에 아직 없다.

---

## 1. 수신(구독) 이벤트

정산의 **원천 데이터**라 정산 DB 에 적재해야 하는 것만 이벤트로 받는다.

Order 는 단일 토픽 `order-events` 에 `OrderEventEnvelope` 로 감싸 발행하고, 종류는 envelope 의 `eventType` 으로 구분한다. (order-service 실제 구현 기준)

| 토픽 | eventType | 발행처 | 용도 | 멱등 단위 | 구현 현황 |
|------|-----------|--------|------|----------|----------|
| `order-events` | `ORDER_PAID` | Order | 주문상품 목록을 펼쳐 `settlement_source_line`(PAID) 적재 | `orderProductId` + PAID | 수신 구현(`OrderEventConsumer`, `autoStartup=false`) |
| `order-events` | `ORDER_REFUNDED` | Order | 주문상품 환불 시 `settlement_source_line`(REFUND) 적재 | `orderProductId` + REFUND | 수신 구현(명세 확정) — **Order 발행 요청 필요** |

> 환불(`ORDER_REFUNDED`) 수신은 Settlement 가 **필요 명세를 확정해 미리 구현해 뒀다**(아래 §1-2 ·
> `OrderEventConsumer` 의 `ORDER_REFUNDED` 분기). Order 는 아직 발행하지 않으므로, settlement 가 정한
> 이 형태로 발행해 달라고 Order 팀에 요청한다. Order 가 발행을 시작하면
> `settlement.kafka.listener.order.enabled=true` 로 켜면 PAID·REFUND 가 한 컨슈머에서 처리된다.

### 1-1. 주문상품 확정 → 정산 소스 라인 (`ORDER_PAID`)

**왜 Payment 가 아니라 Order 의 이벤트를 받는가**

정산 소스는 결제(payment) 단위가 아니라 **주문상품(`orderProductId`) 단위**다. `OrderProduct` 는
정산에 필요한 값(`sellerId`·`productId`·금액)을 단위로 모두 보유하므로, 주문상품이 `PAID` 로
확정되는 시점에 Order 가 발행하는 `ORDER_PAID` 를 받으면 소스 라인으로 **1:1 매핑**된다.
(Payment 의 `PAYMENT_APPROVED` 는 주문/결제 단위라 상품·판매자 분해가 불가능해 정산이 직접 구독하지 않는다.)

**발행/수신 형태 — envelope + 주문 단위 묶음**

- Order 가 결제 승인을 반영해 주문을 `PAID` 로 전이시킨 직후, 그 주문의 정산 대상 주문상품을
  `products` 배열로 묶어 `OrderEventEnvelope<OrderPaidEvent>` 로 **주문당 1건**(`order-events`) 발행한다.
- 메시지 key 는 `orderId`, value 는 envelope JSON(`eventType="ORDER_PAID"`)이다.
- 정산은 `products` 를 펼쳐 항목마다 `SettlementSourceLine` 을 만들고, 각 라인을
  **`orderProductId` + 상태(PAID/REFUND)** 단위로 멱등 처리한다.
  (주문 단위 `eventId` 만으로는 한 주문의 여러 라인을 구분할 수 없다.)

**페이로드 — `order-events` / `ORDER_PAID`** (order-service `OrderEventEnvelope<OrderPaidEvent>` 기준)

```json
{
  "eventId": "dd0e8400-e29b-41d4-a716-446655440040",
  "eventType": "ORDER_PAID",
  "version": 1,
  "occurredAt": "2026-06-15T10:01:00",
  "aggregateId": "660e8400-e29b-41d4-a716-446655440001",
  "payload": {
    "orderId": "660e8400-e29b-41d4-a716-446655440001",
    "buyerId": "990e8400-e29b-41d4-a716-446655440009",
    "totalOrderAmount": 9900,
    "totalProductCount": 1,
    "paidAt": "2026-06-15T10:01:00",
    "products": [
      {
        "orderProductId": "ee0e8400-e29b-41d4-a716-446655440050",
        "productId": "880e8400-e29b-41d4-a716-446655440003",
        "sellerId": "770e8400-e29b-41d4-a716-446655440002",
        "productTitle": "프롬프트 제목",
        "productType": "PROMPT",
        "productAmount": 9900
      }
    ]
  }
}
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `eventId` | UUID | ✅ | 이벤트 식별자(주문 단위, envelope) |
| `eventType` | String | ✅ | `ORDER_PAID` |
| `version` | Integer | ✅ | 이벤트 스키마 버전 |
| `occurredAt` | DateTime | ✅ | 이벤트 발생 시각(라인 `occurred_at` 으로 사용) |
| `aggregateId` | UUID | ✅ | 애그리거트 ID(=orderId) |
| `payload.orderId` | UUID | ✅ | 주문 ID |
| `payload.buyerId` | UUID | ✅ | 구매자 ID(정산 미사용) |
| `payload.totalOrderAmount` | Integer | ✅ | 주문 총액(정산 미사용) |
| `payload.totalProductCount` | Integer | ✅ | 주문상품 수(정산 미사용) |
| `payload.paidAt` | DateTime | ✅ | 결제 확정 시각(정산 미사용) |
| `payload.products[].orderProductId` | UUID | ✅ | 주문상품 ID(정산 소스 라인 단위) |
| `payload.products[].productId` | UUID | ✅ | 상품 ID(정산 미사용) |
| `payload.products[].sellerId` | UUID | ✅ | 판매자 ID |
| `payload.products[].productTitle` | String | ✅ | 상품명(정산 미사용) |
| `payload.products[].productType` | String | ✅ | 상품 유형(정산 미사용) |
| `payload.products[].productAmount` | Integer | ✅ | 구매 시점 상품 금액(원화, 라인 `line_amount`) |

> 정산이 라인 적재에 쓰는 값은 `eventId`·`occurredAt`·`payload.orderId`·`products[].orderProductId`·
> `products[].sellerId`·`products[].productAmount` 다. 나머지는 수신하되 사용하지 않는다.

**대상 도메인**: `SettlementSourceLine.paid(...)` / `.refunded(...)`.
정산 배치는 `settlement_source_line` 의 미정산 PAID 라인을 판매자·기간 단위로 모아 `Settlement` 를 계산한다.

> **멱등키:** `SettlementSourceLine.eventId` 는 단일 컬럼 unique 다. 묶음 이벤트라 주문 `eventId` 1개에
> 라인 N개가 나오므로, 라인 멱등키를 **`nameUUIDFromBytes(주문eventId | orderProductId | 상태)`** 합성 UUID 로
> 만들어 라인마다 다른 키를 보장한다. 스키마·도메인 제약은 그대로 둔다.

### 1-2. 주문상품 환불 → 정산 소스 라인 (`ORDER_REFUNDED`) — settlement 확정 명세

환불도 `ORDER_PAID` 와 같은 envelope 구조로 받되 **payload 만 환불용**(`OrderRefundedEvent`)이다.
종류는 envelope 의 `eventType` 으로 구분하므로 payload 에 별도 type 필드를 두지 않는다(단일 출처).
**Order 는 아직 미발행이라, 아래는 settlement 가 필요 필드로 확정한 요청 명세다.** Order 가 이 형태로
발행하면 컨슈머(`ORDER_REFUNDED` 분기)가 그대로 처리한다.

**페이로드(안) — `order-events` / `ORDER_REFUNDED`**

```json
{
  "eventId": "dd0e8400-e29b-41d4-a716-446655440041",
  "eventType": "ORDER_REFUNDED",
  "version": 1,
  "occurredAt": "2026-06-20T09:00:00",
  "aggregateId": "660e8400-e29b-41d4-a716-446655440001",
  "payload": {
    "orderId": "660e8400-e29b-41d4-a716-446655440001",
    "buyerId": "990e8400-e29b-41d4-a716-446655440009",
    "totalRefundAmount": 9900,
    "refundedAt": "2026-06-20T09:00:00",
    "products": [
      {
        "orderProductId": "ee0e8400-e29b-41d4-a716-446655440050",
        "productId": "880e8400-e29b-41d4-a716-446655440003",
        "sellerId": "770e8400-e29b-41d4-a716-446655440002",
        "refundAmount": 9900
      }
    ]
  }
}
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `eventId` | UUID | ✅ | 이벤트 식별자(주문 단위, envelope) |
| `eventType` | String | ✅ | `ORDER_REFUNDED` |
| `occurredAt` | DateTime | ✅ | 이벤트 발생 시각(라인 `occurred_at` 으로 사용) |
| `aggregateId` | UUID | ✅ | 애그리거트 ID(=orderId) |
| `payload.orderId` | UUID | ✅ | 주문 ID |
| `payload.buyerId` | UUID | ⬜ | 구매자 ID(정산 미사용) |
| `payload.totalRefundAmount` | Integer | ⬜ | 환불 총액(정산 미사용) |
| `payload.refundedAt` | DateTime | ⬜ | 환불 확정 시각(정산 미사용 — 라인 시각은 `envelope.occurredAt`) |
| `payload.products[].orderProductId` | UUID | ✅ | 주문상품 ID(정산 소스 라인 단위) |
| `payload.products[].productId` | UUID | ⬜ | 상품 ID(정산 미사용) |
| `payload.products[].sellerId` | UUID | ✅ | 판매자 ID |
| `payload.products[].refundAmount` | Integer | ✅ | 환불 금액(원화, 라인 `line_amount`) |

> **정산이 실제 쓰는 필드는 `eventId`·`occurredAt`·`payload.orderId`·`products[].orderProductId`·
> `products[].sellerId`·`products[].refundAmount` 다.** 부분 환불은 `products` 에 환불 대상 주문상품만 담아
> 보내면 된다. 같은 주문상품의 PAID/REFUND 는 멱등키 seed 의 상태가 달라(`...|PAID` vs `...|REFUND`) 별도
> 라인으로 적재된다.

---

## 2. 동기 조회로 가져오는 것 (이벤트 미사용)

판매자명과 상품 수는 **다른 서비스가 소유한 참고 데이터**다. 정산은 이를 이벤트로
복제(읽기모델)하지 않고, **필요한 시점에 동기 조회**한다.
(판매자 정산 계좌 정보는 아직 범위가 아니다 — 추후 지급 실행을 붙일 때 함께 정한다.)

**왜 복제하지 않는가**

- 판매자명·상품 수는 **표시용**이라 실시간 정확도가 중요하지 않다. 정산 조회 시 한 번 물으면 충분하다.
- 복제(읽기모델)는 저장소·이벤트·정합성 재동기화 비용을 늘린다. 성능/장애 격리가 실제로 필요해질 때
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
- 기존 `ProductPromptEventListener` 골격은 이벤트를 쓰지 않으므로 **제거했다**(동기 조회로 대체).

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

> **전환 메모:** 추후 외부 호출이 병목이 되거나 장애 격리가 필요해지면, 그때 이벤트 기반 복제(읽기모델)로
> 전환을 검토한다. (그 경우 `seller.*`·`product.*` 수신 토픽을 §1 에 추가)

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

---

## 4. 정산 대상 인입 방식 전환 메모

- **기존**: 정산 배치가 Order 내부 API `GET /internal/orders/paid` 를 폴링해 PAID 주문을 조회.
- **전환**: 위 폴링을 폐기하고 `order-events`(`ORDER_PAID`) **이벤트 구독으로 대체**한다.
  Order 가 주문상품 확정 시 이벤트를 발행하면 정산이 `settlement_source_line` 에 적재하고,
  배치는 적재된 소스 라인만 읽어 `Settlement` 를 계산한다.
- Order 는 이미 `ORDER_PAID` 를 발행한다(Outbox→`order-events`). 환불(`ORDER_REFUNDED`)은 아직
  발행하지 않아 **Order 팀에 발행을 요청할 예정**이다.

---

## 5. 구현 현황 요약

| 항목 | 현황 |
|------|------|
| Kafka 등 메시징 의존성 | 추가됨 (`spring-boot-starter-kafka`) |
| `ORDER_PAID` 수신(`order-events`) | 구현(`OrderEventConsumer`, `autoStartup=false`) |
| `ORDER_REFUNDED` 수신 | 명세 확정·수신 구현 완료(§1-2) — Order 발행 요청 필요 |
| 판매자 이름(다건/단건) | 동기 조회 — **gRPC** `SellerQueryService.FindSellers`. 정산 클라이언트 구현 완료(`SellerQueryClient`), User gRPC 서버 신설 요청 필요. 계좌는 추후 |
| 등록 상품 수 | 동기 조회 — **gRPC** `ProductQueryService.CountBySeller`. 정산 클라이언트 구현 완료(`ProductQueryClient`), Product gRPC 서버 신설 요청 필요. `ProductPromptEventListener` 제거 완료 |
| gRPC 의존성 | 추가됨 (`grpc-stub`·`grpc-protobuf`·`protobuf` + `protobuf-gradle-plugin`), proto `src/main/proto/*.proto` |
| `settlement.payout.completed` 발행 | **추후(파이널)** — 현재 범위 아님 |
| `GET /internal/orders/paid` 폴링 | 폐기 예정 (이벤트로 대체) |
