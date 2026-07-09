# Kafka 이벤트 계약

payment-service가 **발행(Publish)** 및 **구독(Consume)** 하는 Kafka 이벤트 목록.

각 이벤트의 payload 상세 스키마는 이 문서의 "Payload 스키마" 섹션을 참조.

모노레포 전체 이벤트 흐름: `../../../docs/architecture/event-flow.md`

---

## 발행 토픽

| 토픽 | 발행 시점 | 구독자 | 구독자 처리 내용 |
|---|---|---|---|
| `payment-events` | Toss confirm 성공 / PG 환불 성공 / PG confirm 실패 | Order | `eventType` 필드로 분기 처리 |

구독자는 `eventType` 값(`"payment.approved"`, `"payment.refunded"`, `"payment.failed"`)으로 처리 로직을 분기한다.

> **토픽 명칭 주의**: payment는 `payment-events`(dash)로 발행한다. order-service가 현재 `payment.events`(dot)를 구독 중이라면 dash로 통일되기 전까지 payment 발행 메시지가 소비되지 않는다(order 측 토픽 통일 선행 필요).

---

## 구독 토픽

| 토픽 | eventType | 컨슈머 그룹 | 처리 내용 | 에러 처리 |
|---|---|---|---|---|
| `order-events` | `ORDER_CREATED` | `payment-service-order-events` (전용) | 주문 스냅샷 upsert(source=EVENT) | 재시도 3회(1s) 후 `order-events.DLT` |

- **평면(flat) 메시지**를 소비한다(envelope 미사용). 최상위 `eventType`으로 필터링하고, `ORDER_CREATED`가 아닌 타입(`ORDER_PAID`/`ORDER_REFUND` 등)은 무시한다.
- `StringDeserializer` + `ObjectMapper` 수동 파싱(`ErrorHandlingDeserializer` 위임), `AckMode.MANUAL`.
- `createdAt`은 존 없는 `LocalDateTime`으로 도착하므로 소비 시 KST를 부여해 저장한다.

---

## 구현 위치

| 파일 | 역할 |
|---|---|
| `infrastructure/messaging/config/PaymentTopic.java` | 토픽 상수 정의 |
| `infrastructure/messaging/config/KafkaConfig.java` | NewTopic 빈, order-events 전용 ConsumerFactory/ContainerFactory, DefaultErrorHandler(DLT) |
| `infrastructure/messaging/KafkaPaymentEventPublisher.java` | Kafka 메시지 발행 구현체 (approved/refunded/failed) |
| `infrastructure/messaging/RefundEventHandler.java` | 환불 도메인 이벤트 처리 (`@TransactionalEventListener`) |
| `infrastructure/messaging/consumer/OrderEventConsumer.java` | `order-events` 구독 → 주문 스냅샷 upsert |

---

## 발행 경로

| 발생 시점 | 경로 |
|---|---|
| 일반 서비스 (결제 승인 등) | `ApplicationEventPublisher` → `@TransactionalEventListener(AFTER_COMMIT)` → `KafkaPaymentEventPublisher` |
| 스케줄러 (환불 retry) | `TransactionSynchronizationManager.registerSynchronization().afterCommit()` → `KafkaPaymentEventPublisher.publishRefunded()` 직접 호출 |

---

## Payload 공통 필드

모든 이벤트에 `eventType` 필드 포함. 토픽은 `payment-events` 단일 토픽이며, `eventType` 값으로 이벤트 종류를 구분한다.

```json
{ "eventType": "payment.approved", ... }
```

---

## Payload 스키마

### payment.approved (`eventType: "payment.approved"`)

```json
{
  "eventType": "payment.approved",
  "paymentId": "550e8400-e29b-41d4-a716-446655440000",
  "orderId":   "660e8400-e29b-41d4-a716-446655440001",
  "userId":    "770e8400-e29b-41d4-a716-446655440002",
  "amount":    9900,
  "approvedAt": "2026-06-15T10:01:00Z"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `eventType` | String | ✅ | `"payment.approved"` 고정 |
| `paymentId` | UUID | ✅ | Payment ID |
| `orderId` | UUID | ✅ | 주문 ID |
| `userId` | UUID | ✅ | 결제 사용자 ID |
| `amount` | Int | ✅ | 승인된 결제 금액 |
| `approvedAt` | ISO 8601 | ✅ | PG 승인 완료 일시 |

---

### payment.refunded (`eventType: "payment.refunded"`)

```json
{
  "eventType":  "payment.refunded",
  "paymentId":  "550e8400-e29b-41d4-a716-446655440000",
  "orderId":    "660e8400-e29b-41d4-a716-446655440001",
  "userId":     "770e8400-e29b-41d4-a716-446655440002",
  "amount":     9900,
  "refundedAt": "2026-06-15T11:00:00Z"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `eventType` | String | ✅ | `"payment.refunded"` 고정 |
| `paymentId` | UUID | ✅ | Payment ID |
| `orderId` | UUID | ✅ | 주문 ID |
| `userId` | UUID | ✅ | 환불 요청 사용자 ID |
| `amount` | Int | ✅ | 환불 금액 (전체 환불이므로 원래 결제 금액과 동일) |
| `refundedAt` | ISO 8601 | ✅ | PG 환불 완료 일시 |

---

### payment.failed (`eventType: "payment.failed"`)

PG 결제 승인 실패(confirm TX3) 시 발행. orderId 중심 최소 필드.

```json
{
  "eventType": "payment.failed",
  "paymentId": "550e8400-e29b-41d4-a716-446655440000",
  "orderId":   "660e8400-e29b-41d4-a716-446655440001",
  "userId":    "770e8400-e29b-41d4-a716-446655440002"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `eventType` | String | ✅ | `"payment.failed"` 고정 |
| `paymentId` | UUID | ✅ | Payment ID |
| `orderId` | UUID | ✅ | 주문 ID |
| `userId` | UUID | ✅ | 결제 사용자 ID |

구독자(order-service) 반응: PENDING → FAILED (재결제 시 FAILED → PAID 복귀 허용 필요).

---

## 구독 Payload 스키마

### ORDER_CREATED (`eventType: "ORDER_CREATED"`, 토픽 `order-events`)

**평면 메시지**(envelope 미사용). order-service가 발행할 계약(동결).

```json
{
  "eventType": "ORDER_CREATED",
  "orderId": "660e8400-e29b-41d4-a716-446655440001",
  "buyerId": "770e8400-e29b-41d4-a716-446655440002",
  "totalOrderAmount": 50000,
  "createdAt": "2026-07-05T12:00:00"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `eventType` | String | ✅ | `"ORDER_CREATED"` 고정 |
| `orderId` | UUID | ✅ | 주문 ID (파티션 키) |
| `buyerId` | UUID | ✅ | 주문자 ID (결제 본인 검증 기준) |
| `totalOrderAmount` | Int | ✅ | 결제할 총액 (금액의 진실 공급원) |
| `createdAt` | LocalDateTime | ✅ | 주문 생성 일시 (존 없음 → 소비 시 KST 부여) |

> order-service의 기존 `ORDER_PAID`/`ORDER_REFUND`는 `OrderEventEnvelope`로 감싸 발행되나, `ORDER_CREATED`는 위 평면 구조로 발행하기로 동결. 같은 토픽에 두 형태가 공존해도 payment 컨슈머는 최상위 `eventType`만으로 필터링하므로 무해하다.

---

