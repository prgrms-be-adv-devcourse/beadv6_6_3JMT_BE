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
| 판매자 이름(상점명) — 다건 | 정산 목록 조회 시(판매자 여러 명) | User: `POST /internal/sellers/batch-query` (§2-1) |
| 판매자 이름(상점명) — 단건 | 정산 단건·요약 조회 시 | User: `GET /internal/sellers/{sellerId}` (§2-1) |
| 판매자 등록 상품 수 | 정산 요약 조회 시 | Product: `GET /internal/products/count?sellerId={sellerId}` (§2-2) |

> **정산 목록은 다건(batch)이 기본 경로다.** 어드민 판매자별 정산 목록은 한 페이지에 판매자가
> 여러 명 나오므로, 행마다 단건 조회를 부르면 N+1 이 된다. 목록에 등장하는 `sellerId` 를 모아
> **한 번에** `POST /internal/sellers/batch-query` 로 조회해 `sellerId → sellerName` 으로 매핑한다.
> 단건(`GET /internal/sellers/{sellerId}`)은 정산 단건/요약처럼 판매자가 하나일 때만 쓴다.

- §2-1 User API 는 product-service 주도로 신설 예정인 **공용 internal API 를 재사용**한다(정산 전용 신설 아님).
  §2-2 Product API 는 정산용으로 **신설 요청**한다.
- 통신 방식: **REST**. Spring Cloud 서비스 디스커버리(`lb://USER-SERVICE` 등)로 게이트웨이를 거치지 않고
  서비스 간 직접 호출한다(OpenFeign/WebClient). gRPC 아님.
- 응답은 이 프로젝트 internal API 관례대로 `success`/`message` 래퍼 없이 **raw JSON** 으로 받는다.
- 정산 요약 응답의 `registeredPromptCount`(= 등록 상품 수, 현재 0 고정)는 §2-2 조회값으로 채운다.
- 기존 `ProductPromptEventListener` 골격은 이벤트를 쓰지 않으므로 **제거했다**(동기 조회로 대체).

### 2-1. User — 판매자 정보 조회 (product-service 공용 internal API 재사용)

product-service 가 상품 목록/상세에 판매자 정보를 표시하려고 user-service 에 요청해 만드는 internal API 다.
user-service 가 seller·user 테이블을 내부 JOIN 해 응답한다.
**정산도 정산 전용 API 를 따로 만들지 않고 이 공용 API 를 재사용**한다.

> **정산 계좌 정보는 이 응답에 없다.** 지급 실행을 붙이는 단계에서 계좌(은행 코드·계좌번호·예금주)를
> 어떻게 받을지 별도로 정한다. 지금은 셀러 식별·표시 정보만 가져온다.

```
GET /internal/sellers/{sellerId}
```

- 제공: User 서비스 (product-service 주도로 신설 예정)
- 호출: Settlement → User
- 호출 시점: 정산 단건·요약 조회 시(판매자명 표시)
- `{sellerId}` 는 정산이 보유한 판매자 ID 다. User 가 이 값으로 seller 를 식별해 응답한다.

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `sellerId` | UUID | 판매자 ID (정산이 보유한 값) |

**Response 200** (raw JSON)

```json
{
  "sellerId": "uuid",
  "sellerName": "프롬프트마스터",
  "profileImageUrl": "https://cdn.example.com/images/profile.jpg",
  "status": "ACTIVE"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `sellerId` | UUID | ✅ | 판매자 ID |
| `sellerName` | String | ✅ | 판매자명 |
| `profileImageUrl` | String \| null | ⬜ | 프로필 이미지 URL |
| `status` | String | ✅ | 판매자/계정 상태 (예: `ACTIVE`) |

> 정산은 이 중 `sellerName` 을 정산 목록·요약 표시에 사용한다(`status` 로 활성 여부도 확인 가능).

#### 다건(batch) 조회 — 정산 목록용

정산 목록은 한 번에 판매자 여러 명을 표시하므로, 단건을 행마다 호출하지 않고
**`sellerId` 목록을 한 번에 보내 판매자 정보 목록을 받는다**(N+1 회피).

```
POST /internal/sellers/batch-query
```

- 제공: User 서비스 (단건과 동일 데이터, 다건 입력만 추가)
- 호출: Settlement → User
- 호출 시점: 어드민 판매자별 정산 **목록** 조회 시
- 정산이 목록에 등장한 `sellerId` 들을 모아 한 번에 호출하고, 응답을 `sellerId → sellerName` 으로 매핑한다.

**Request Body** (raw JSON)

```json
{
  "sellerIds": [
    "770e8400-e29b-41d4-a716-446655440002",
    "881e8400-e29b-41d4-a716-446655440013"
  ]
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `sellerIds` | UUID[] | ✅ | 조회할 판매자(사용자) ID 목록. 중복은 호출 전 제거 권장 |

**Response 200** (raw JSON — 단건 응답 객체의 배열)

```json
[
  {
    "sellerId": "770e8400-e29b-41d4-a716-446655440002",
    "sellerName": "프롬프트마스터",
    "profileImageUrl": "https://cdn.example.com/images/profile.jpg",
    "status": "ACTIVE"
  },
  {
    "sellerId": "881e8400-e29b-41d4-a716-446655440013",
    "sellerName": "AI스튜디오",
    "profileImageUrl": null,
    "status": "ACTIVE"
  }
]
```

- 배열 원소 스키마는 단건 응답과 동일하다.
- **존재하지 않는 `sellerId` 는 응답 배열에서 빠진다**(요청 N개 ≠ 응답 N개일 수 있음).
  정산은 매핑에 없는 `sellerId` 의 판매자명을 빈 값/대체 표기로 처리한다.
- 응답 순서는 보장하지 않는다. 정산은 `sellerId` 키로 매핑해 쓴다.

> 정산 포트는 `SellerQueryPort.findSellers(List<UUID> sellerIds)` 형태로 다건을 받고,
> 어댑터(`infrastructure/client`)가 이 API 를 호출한다. 단건 조회가 필요한 곳은 같은 포트의
> 단건 메서드(또는 1개짜리 목록 호출)로 처리한다. (포트·어댑터 규칙은 `clean-architecture.md` §4)

### 2-2. Product — 판매자 등록 상품 수 조회

정산 요약 화면의 등록 상품 수(`registeredPromptCount`)를 채울 때 조회한다.

```
GET /internal/products/count?sellerId={sellerId}
```

- 호출: Settlement → Product
- 호출 시점: 판매자 정산 요약 조회 시

**Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `sellerId` | UUID | ✅ | 판매자(사용자) ID |

**Response 200** (raw JSON)

```json
{
  "sellerId": "770e8400-e29b-41d4-a716-446655440002",
  "productCount": 12
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `sellerId` | UUID | ✅ | 판매자(사용자) ID |
| `productCount` | Integer | ✅ | 판매자가 등록한(판매 중인) 상품 수 |

> 집계 기준(판매 중만 vs 전체 상태 포함)은 Product 팀과 확정한다. 정산은 받은 값을 그대로 노출한다.

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
| 판매자 이름(단건) | 동기 조회 — User 공용 API `GET /internal/sellers/{sellerId}` 재사용(product 주도 신설). 계좌는 추후 |
| 판매자 이름(다건) | 동기 조회 — User `POST /internal/sellers/batch-query` (정산 목록용, N+1 회피). 신설 요청 필요 |
| 등록 상품 수 | 동기 조회 (Product internal API 신설 필요), `ProductPromptEventListener` 제거 완료 |
| `settlement.payout.completed` 발행 | **추후(파이널)** — 현재 범위 아님 |
| `GET /internal/orders/paid` 폴링 | 폐기 예정 (이벤트로 대체) |
