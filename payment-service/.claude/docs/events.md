# Kafka 이벤트 계약

payment-service가 **발행(Publish)** 및 **구독(Consume)** 하는 Kafka 이벤트 목록.

모든 이벤트는 모노레포 공통 규칙(`../../../docs/architecture/common-event-message.md`)의 `EventMessage<T>` 봉투를 따른다.

각 이벤트의 payload 상세 스키마는 이 문서의 "Payload 스키마" 섹션을 참조.

모노레포 전체 이벤트 흐름: `../../../docs/architecture/event-flow.md`

---

## 발행 토픽

| 토픽 | 발행 시점 | 구독자 | 구독자 처리 내용 |
|---|---|---|---|
| `payment-events` | Toss confirm 성공 / PG 환불 성공 / PG confirm 실패 | Order | 봉투 `eventType` 필드로 분기 처리 |

구독자는 봉투 `eventType` 값(`PAYMENT_APPROVED`, `PAYMENT_REFUNDED`, `PAYMENT_FAILED` — `PaymentEventType` enum의 `code()`)으로 처리 로직을 분기한다.

- **봉투 필드**: `eventId`(UUID, 신규 채번), `eventType`(String), `occurredAt`(LocalDateTime, KST 기준 — 승인/실패/환불이 실제 발생한 시각), `aggregateType`(`"ORDER"` 고정), `aggregateId`(orderId). Kafka key도 `aggregateId`(orderId)와 동일.
- `aggregateType`을 도메인 매핑표상 `"PAYMENT"`가 아니라 `"ORDER"`로 고정하는 이유: order-service가 주문 흐름 전체(ORDER_PAID 등)와 동일한 `orderId` 기준으로 이벤트 순서·상관관계를 유지하기 위함(공통 규칙 §9·§14).

---

## 구독 토픽

| 토픽 | eventType | 컨슈머 그룹 | 처리 내용 | 에러 처리 |
|---|---|---|---|---|
| `order-events` | `ORDER_CREATED` | `payment-service-order-events` (전용) | 주문 스냅샷 upsert(source=EVENT) | 재시도 3회(1s) 후 `order-events.DLT` |
| `order-events` | `ORDER_REFUND_REQUESTED` | `payment-service-order-events` (전용) | OrderProduct 단위 부분환불 처리(PG 호출 포함 동기) | 재시도 3회(1s) 후 `order-events.DLT` |

- **`EventMessage<T>` 봉투**를 소비한다. 최상위 `eventType`으로 필터링하고, `ORDER_CREATED`가 아닌 타입(`ORDER_PAID`/`ORDER_REFUND` 등)은 무시한다. `ORDER_CREATED`인 경우 `payload` 노드만 추출해 `OrderCreatedMessage`로 매핑한다.
- order-service의 `OrderCreatedPayload`에는 `orderNumber`/`orderStatus` 필드도 있으나 payment-service는 사용하지 않아 매핑하지 않는다(알 수 없는 필드는 무시).
- `StringDeserializer` + `ObjectMapper` 수동 파싱(`ErrorHandlingDeserializer` 위임), `AckMode.MANUAL`.
- `createdAt`은 존 없는 `LocalDateTime`으로 도착하므로 소비 시 KST를 부여해 저장한다.

---

## 구현 위치

| 파일 | 역할 |
|---|---|
| `infrastructure/messaging/config/PaymentTopic.java` | 토픽 상수 정의 |
| `infrastructure/messaging/config/KafkaConfig.java` | NewTopic 빈, order-events 전용 ConsumerFactory/ContainerFactory, DefaultErrorHandler(DLT) |
| `infrastructure/messaging/PaymentEventType.java` | 공통 `EventType` 구현 enum (`PAYMENT_APPROVED`/`PAYMENT_REFUNDED`/`PAYMENT_FAILED`) |
| `infrastructure/messaging/KafkaPaymentEventPublisher.java` | Kafka 메시지 발행 구현체 (approved/refunded/failed, `EventMessage<T>` 봉투 구성) |
| `application/service/ProcessRefundService.java` | 부분환불 처리(PG 호출 → 저장) 및 `PaymentRefundedEvent`/`PaymentRefundFailedEvent` 발행 |
| `infrastructure/messaging/consumer/OrderEventConsumer.java` | `order-events` 구독 → 주문 스냅샷 upsert / 부분환불 개시 |

---

## 발행 경로

| 발생 시점 | 경로 |
|---|---|
| 일반 서비스 (결제 승인 등) | `ApplicationEventPublisher` → `@TransactionalEventListener(AFTER_COMMIT)` → `KafkaPaymentEventPublisher` |
| 스케줄러 (환불 retry) | `TransactionSynchronizationManager.registerSynchronization().afterCommit()` → `KafkaPaymentEventPublisher.publishRefunded()` 직접 호출 |

---

## Payload 스키마

모든 발행 이벤트는 공통 `EventMessage<T>` 봉투로 감싸 발행한다. 아래는 각 이벤트의 봉투 전체 예시와 `payload` 필드 상세다.

### PAYMENT_APPROVED

```json
{
  "eventId": "9c1f2a7e-4b8d-4e2a-9c11-2d3e4f5a0001",
  "eventType": "PAYMENT_APPROVED",
  "occurredAt": "2026-06-15T19:01:00",
  "aggregateType": "ORDER",
  "aggregateId": "660e8400-e29b-41d4-a716-446655440001",
  "payload": {
    "paymentId": "550e8400-e29b-41d4-a716-446655440000",
    "orderId":   "660e8400-e29b-41d4-a716-446655440001",
    "userId":    "770e8400-e29b-41d4-a716-446655440002",
    "amount":    9900,
    "approvedAt": "2026-06-15T19:01:00+09:00"
  }
}
```

| 필드 (`payload`) | 타입 | 필수 | 설명 |
|---|---|---|---|
| `paymentId` | UUID | ✅ | Payment ID |
| `orderId` | UUID | ✅ | 주문 ID |
| `userId` | UUID | ✅ | 결제 사용자 ID |
| `amount` | Int | ✅ | 승인된 결제 금액 |
| `approvedAt` | ISO 8601 (KST) | ✅ | PG 승인 완료 일시 |

`occurredAt`(봉투)은 `approvedAt`을 KST `LocalDateTime`으로 변환한 값과 동일 시각이다.

---

### PAYMENT_REFUNDED

```json
{
  "eventId": "9c1f2a7e-4b8d-4e2a-9c11-2d3e4f5a0002",
  "eventType": "PAYMENT_REFUNDED",
  "occurredAt": "2026-06-15T20:00:00",
  "aggregateType": "ORDER",
  "aggregateId": "660e8400-e29b-41d4-a716-446655440001",
  "payload": {
    "paymentId":  "550e8400-e29b-41d4-a716-446655440000",
    "orderId":    "660e8400-e29b-41d4-a716-446655440001",
    "userId":     "770e8400-e29b-41d4-a716-446655440002",
    "orderProductId": "880e8400-e29b-41d4-a716-446655440003",
    "amount":     4000,
    "paymentStatus": "PARTIAL_REFUNDED",
    "refundedAt": "2026-06-15T20:00:00+09:00"
  }
}
```

| 필드 (`payload`) | 타입 | 필수 | 설명 |
|---|---|---|---|
| `paymentId` | UUID | ✅ | Payment ID |
| `orderId` | UUID | ✅ | 주문 ID |
| `userId` | UUID | ✅ | 환불 요청 사용자 ID |
| `orderProductId` | UUID | ✅ | 환불된 OrderProduct ID |
| `amount` | Int | ✅ | 이번 환불 건(해당 상품)의 금액 |
| `paymentStatus` | String | ✅ | 환불 반영 후 Payment 상태(`PARTIAL_REFUNDED`/`ALL_REFUNDED`) |
| `refundedAt` | ISO 8601 (KST) | ✅ | PG 환불 완료 일시 |

---

### PAYMENT_REFUND_FAILED

PG 환불 실패 시 발행. `Refund.status=FAILED`로만 기록되고 Payment 상태는 그대로 유지된다.

```json
{
  "eventId": "9c1f2a7e-4b8d-4e2a-9c11-2d3e4f5a0004",
  "eventType": "PAYMENT_REFUND_FAILED",
  "occurredAt": "2026-06-15T20:05:00",
  "aggregateType": "ORDER",
  "aggregateId": "660e8400-e29b-41d4-a716-446655440001",
  "payload": {
    "paymentId": "550e8400-e29b-41d4-a716-446655440000",
    "orderId":   "660e8400-e29b-41d4-a716-446655440001",
    "userId":    "770e8400-e29b-41d4-a716-446655440002",
    "orderProductId": "880e8400-e29b-41d4-a716-446655440003",
    "refundAmount": 4000,
    "paymentStatus": "PAID",
    "failureReason": "잘못된 요청",
    "failedAt": "2026-06-15T20:05:00+09:00"
  }
}
```

| 필드 (`payload`) | 타입 | 필수 | 설명 |
|---|---|---|---|
| `paymentId` | UUID | ✅ | Payment ID |
| `orderId` | UUID | ✅ | 주문 ID |
| `userId` | UUID | ✅ | 결제 사용자 ID |
| `orderProductId` | UUID | ✅ | 환불 시도된 OrderProduct ID |
| `refundAmount` | Int | ✅ | 시도했던 환불 금액 |
| `paymentStatus` | String | ✅ | 실패 시점 Payment 상태(상태 불변, `PAID`/`PARTIAL_REFUNDED` 중 하나) |
| `failureReason` | String | — | PG 실패 사유(nullable) |
| `failedAt` | ISO 8601 (KST) | ✅ | 실패 처리 일시 |

구독자(order-service) 반응: 자기 쪽 반품 상태를 실패로 되돌리거나 재시도 여부 판단.

> **Kafka 유실 시 폴백**: order-service가 위 이벤트를 못 받았을 경우(재시도 소진 → DLT), `PaymentQueryService.GetRefund` gRPC(`grpc/payment/payment_query.proto`, 포트 9084)로 폴백 조회할 수 있다. 조회 키는 `paymentId`+`orderProductId`.

---

### PAYMENT_FAILED

PG 결제 승인 실패(confirm TX3) 시 발행. orderId 중심 최소 필드.

```json
{
  "eventId": "9c1f2a7e-4b8d-4e2a-9c11-2d3e4f5a0003",
  "eventType": "PAYMENT_FAILED",
  "occurredAt": "2026-06-15T19:02:00",
  "aggregateType": "ORDER",
  "aggregateId": "660e8400-e29b-41d4-a716-446655440001",
  "payload": {
    "paymentId": "550e8400-e29b-41d4-a716-446655440000",
    "orderId":   "660e8400-e29b-41d4-a716-446655440001",
    "userId":    "770e8400-e29b-41d4-a716-446655440002"
  }
}
```

| 필드 (`payload`) | 타입 | 필수 | 설명 |
|---|---|---|---|
| `paymentId` | UUID | ✅ | Payment ID |
| `orderId` | UUID | ✅ | 주문 ID |
| `userId` | UUID | ✅ | 결제 사용자 ID |

구독자(order-service) 반응: PENDING → FAILED (재결제 시 FAILED → PAID 복귀 허용 필요).

> **Kafka 유실 시 폴백**: order-service가 `PAYMENT_APPROVED`/`PAYMENT_FAILED`를 못 받았을 경우, `PaymentQueryService.GetPayment` gRPC(`grpc/payment/payment_query.proto`, 포트 9084)로 폴백 조회할 수 있다. 조회 키는 `orderId`(동일 orderId에 재결제로 여러 건이 있으면 최신 1건 반환).

---

## 구독 Payload 스키마

### ORDER_CREATED (`eventType: "ORDER_CREATED"`, 토픽 `order-events`)

`EventMessage<OrderCreatedPayload>` 봉투. payment-service는 `payload` 노드만 추출해 `OrderCreatedMessage`로 매핑한다.

```json
{
  "eventId": "f3bdb7f2-ec60-4c77-aab7-57d8b4d84e9a",
  "eventType": "ORDER_CREATED",
  "occurredAt": "2026-07-05T12:00:00",
  "aggregateType": "ORDER",
  "aggregateId": "660e8400-e29b-41d4-a716-446655440001",
  "payload": {
    "orderId": "660e8400-e29b-41d4-a716-446655440001",
    "buyerId": "770e8400-e29b-41d4-a716-446655440002",
    "orderNumber": "ORD-20260705-0001",
    "totalAmount": 50000,
    "orderStatus": "PENDING",
    "createdAt": "2026-07-05T12:00:00"
  }
}
```

| 필드 (`payload`) | 타입 | 필수 | 설명 |
|---|---|---|---|
| `orderId` | UUID | ✅ | 주문 ID (파티션 키) |
| `buyerId` | UUID | ✅ | 주문자 ID (결제 본인 검증 기준) |
| `totalAmount` | Int | ✅ | 결제할 총액 (금액의 진실 공급원) |
| `createdAt` | LocalDateTime | ✅ | 주문 생성 일시 (존 없음 → 소비 시 KST 부여) |
| `orderNumber`, `orderStatus` | - | - | order-service payload에 존재하나 payment-service는 사용하지 않음(무시) |

---

### ORDER_REFUND_REQUESTED (`eventType: "ORDER_REFUND_REQUESTED"`, 토픽 `order-events`)

`EventMessage<OrderRefundRequestedPayload>` 봉투. order-service가 OrderProduct 단위 환불을 확정하면 발행한다.

```json
{
  "eventId": "f3bdb7f2-ec60-4c77-aab7-57d8b4d84e9b",
  "eventType": "ORDER_REFUND_REQUESTED",
  "occurredAt": "2026-07-13T10:00:00",
  "aggregateType": "ORDER",
  "aggregateId": "660e8400-e29b-41d4-a716-446655440001",
  "payload": {
    "orderId": "660e8400-e29b-41d4-a716-446655440001",
    "orderProductId": "880e8400-e29b-41d4-a716-446655440003",
    "buyerId": "770e8400-e29b-41d4-a716-446655440002",
    "refundAmount": 4000,
    "requestedAt": "2026-07-13T10:00:00"
  }
}
```

| 필드 (`payload`) | 타입 | 필수 | 설명 |
|---|---|---|---|
| `orderId` | UUID | ✅ | 주문 ID |
| `orderProductId` | UUID | ✅ | 환불 대상 OrderProduct ID (항상 필수 — 주문 전체 한번에 환불하는 이벤트는 없음) |
| `buyerId` | UUID | ✅ | 환불 요청 사용자 ID (참고용 로그, 별도 거부 로직 없음) |
| `refundAmount` | Int | ✅ | 환불 금액 — payment-service는 이 값을 그대로 신뢰(누적 초과 여부만 검증) |
| `requestedAt` | LocalDateTime | ✅ | 존 없음 → 소비 시 KST 부여 |

---

