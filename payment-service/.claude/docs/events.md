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
| `order-events` | `ORDER_REFUND_REQUESTED` | `payment-service-order-events` (전용) | OrderProduct 단위 부분환불 처리(PG 호출 포함 동기) | 재시도 3회(1s) 후 `order-events.DLT` |

- **`EventMessage<T>` 봉투**를 소비한다. 최상위 `eventType`으로 필터링하고, `ORDER_REFUND_REQUESTED`가 아닌 타입(`ORDER_CREATED`/`ORDER_PAID` 등)은 무시한다.
- `StringDeserializer` + `ObjectMapper` 수동 파싱(`ErrorHandlingDeserializer` 위임), `AckMode.MANUAL`.
- `requestedAt`은 존 없는 `LocalDateTime`으로 도착하므로 소비 시 KST를 부여해 저장한다.

---

## 구현 위치

| 파일 | 역할 |
|---|---|
| `infrastructure/messaging/config/PaymentTopic.java` | 토픽 상수 정의 |
| `infrastructure/messaging/config/KafkaConfig.java` | NewTopic 빈, order-events 전용 ConsumerFactory/ContainerFactory, DefaultErrorHandler(DLT) |
| `infrastructure/messaging/PaymentEventType.java` | 공통 `EventType` 구현 enum (`PAYMENT_APPROVED`/`PAYMENT_REFUNDED`/`PAYMENT_FAILED`) |
| `infrastructure/messaging/KafkaPaymentEventPublisher.java` | Kafka 메시지 발행 구현체 (approved/refunded/failed, `EventMessage<T>` 봉투 구성) |
| `application/service/ProcessRefundService.java` | 부분환불 처리(PG 호출 → 저장) 및 `PaymentRefundedEvent`/`PaymentRefundFailedEvent` 발행 |
| `infrastructure/messaging/consumer/OrderEventConsumer.java` | `order-events` 구독(`ORDER_REFUND_REQUESTED`만) → 부분환불 개시 |

---

## 발행 경로

| 발생 시점 | 경로 |
|---|---|
| 일반 서비스 (결제 승인 등) | `ApplicationEventPublisher` → `@TransactionalEventListener(AFTER_COMMIT)` → `KafkaPaymentEventPublisher` |

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
    "orderId":   "660e8400-e29b-41d4-a716-446655440001",
    "approvedAmount": 9900,
    "approvedAt": "2026-06-15T19:01:00+09:00"
  }
}
```

| 필드 (`payload`) | 타입 | 필수 | 설명 |
|---|---|---|---|
| `orderId` | UUID | ✅ | 주문 ID |
| `approvedAmount` | Int | ✅ | 승인된 결제 금액 |
| `approvedAt` | ISO 8601 (KST) | ✅ | PG 승인 완료 일시 |

`occurredAt`(봉투)은 `approvedAt`을 KST `LocalDateTime`으로 변환한 값과 동일 시각이다. `paymentId`/`userId`는 order-service가 자기 Order 엔티티로 이미 갖고 있어 payload에서 제거했다(#396).

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
    "orderId":       "660e8400-e29b-41d4-a716-446655440001",
    "refundAmount":  4000,
    "refundedAt":    "2026-06-15T20:00:00+09:00"
  }
}
```

| 필드 (`payload`) | 타입 | 필수 | 설명 |
|---|---|---|---|
| `orderId` | UUID | ✅ | 주문 ID |
| `refundAmount` | Int | ✅ | 이번 환불 건의 금액 |
| `refundedAt` | ISO 8601 (KST) | ✅ | PG 환불 완료 일시 |

`paymentId`/`userId`/`orderProductId`/`paymentStatus`는 order-service가 `orderId` 기준으로 이미 상관관계를 추적할 수 있어 payload에서 제거했다(#398).

---

### PAYMENT_REFUND_FAILED

PG 환불 실패 또는 과환불 검증 실패 시 발행. `Refund.status=FAILED`로만 기록되고 Payment 상태는 그대로 유지된다. 과환불처럼 확정적 비즈니스 규칙 위반도 예외/DLT가 아니라 이 이벤트로 정상 종료한다(#398).

```json
{
  "eventId": "9c1f2a7e-4b8d-4e2a-9c11-2d3e4f5a0004",
  "eventType": "PAYMENT_REFUND_FAILED",
  "occurredAt": "2026-06-15T20:05:00",
  "aggregateType": "ORDER",
  "aggregateId": "660e8400-e29b-41d4-a716-446655440001",
  "payload": {
    "orderId":       "660e8400-e29b-41d4-a716-446655440001",
    "refundAmount":  4000,
    "failedAt":      "2026-06-15T20:05:00+09:00"
  }
}
```

| 필드 (`payload`) | 타입 | 필수 | 설명 |
|---|---|---|---|
| `orderId` | UUID | ✅ | 주문 ID |
| `refundAmount` | Int | ✅ | 시도했던 환불 금액 |
| `failedAt` | ISO 8601 (KST) | ✅ | 실패 처리 일시 |

`paymentId`/`userId`/`orderProductId`/`paymentStatus`/`failureReason`은 payload에서 제거했다(#398). 실패 사유는 payment-service 내부 `Refund.reason` 컬럼에는 남는다(로그로도 확인 가능하나 외부에는 발행하지 않는다).

구독자(order-service) 반응: 자기 쪽 반품 상태를 실패로 되돌리거나 재시도 여부 판단.

> **Kafka 유실 시 폴백**: 없음. `GetRefund` gRPC 폴백 조회는 제거되었다(#398) — order-service가 Kafka 자체 재조회로 대응한다.

---

### PAYMENT_FAILED

PG 결제 승인 실패(Toss confirm 호출 자체가 실패한 경우) 시 발행. orderId 하나만 담는 최소 payload — 봉투 `aggregateId`와 동일한 값이지만, 다른 이벤트와 동일한 소비 패턴(payload에서 바로 필드 추출)을 유지하기 위해 중복 포함한다.

**금액 불일치(`PAY012`)는 여기 해당하지 않는다.** Toss를 호출하기 전 단계의 순수 입력 검증 실패라 Payment 레코드 자체를 만들지 않고, 이 이벤트도 발행하지 않는다 — order-service에 알릴 상태 변화가 없고, 같은 orderId로 올바른 금액으로 즉시 재시도할 수 있다.

```json
{
  "eventId": "9c1f2a7e-4b8d-4e2a-9c11-2d3e4f5a0003",
  "eventType": "PAYMENT_FAILED",
  "occurredAt": "2026-06-15T19:02:00",
  "aggregateType": "ORDER",
  "aggregateId": "660e8400-e29b-41d4-a716-446655440001",
  "payload": {
    "orderId": "660e8400-e29b-41d4-a716-446655440001"
  }
}
```

| 필드 (`payload`) | 타입 | 필수 | 설명 |
|---|---|---|---|
| `orderId` | UUID | ✅ | 주문 ID |

구독자(order-service) 반응: PENDING → FAILED. 이 주문은 이후 재결제로 PAID에 복귀하지 않는 영구 상태다(#396) — payment-service가 같은 orderId의 재결제 자체를 차단한다.

> **Kafka 유실 시 폴백**: order-service가 `PAYMENT_APPROVED`/`PAYMENT_FAILED`를 못 받았을 경우, `PaymentQueryService.GetPayment` gRPC(`grpc/payment/payment_query.proto`, 포트 9084)로 폴백 조회할 수 있다. 조회 키는 `orderId`(동일 orderId에 재결제로 여러 건이 있으면 최신 1건 반환).

---

## 구독 Payload 스키마

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
    "refundRequestId": "990e8400-e29b-41d4-a716-446655440099",
    "refundAmount": 4000,
    "requestedAt": "2026-07-13T10:00:00"
  }
}
```

| 필드 (`payload`) | 타입 | 필수 | 설명 |
|---|---|---|---|
| `orderId` | UUID | ✅ | 주문 ID |
| `orderProductId` | UUID | ✅ | 환불 대상 OrderProduct ID. order-service가 계속 보내지만 payment-service는 파싱만 하고 저장하지 않는다(#398) |
| `buyerId` | UUID | ✅ | 환불 요청 사용자 ID. payment-service는 이 필드 자체를 파싱하지 않는다(#398) — 들어와도 무시된다 |
| `refundRequestId` | UUID | ✅ | order-service가 발급하는 환불 요청 식별자. payment-service의 dedup 키(#398) — 동일 값 재전송 시 1회만 처리 |
| `refundAmount` | Int | ✅ | 환불 금액 — payment-service는 이 값을 그대로 신뢰(누적 초과 여부만 검증) |
| `requestedAt` | LocalDateTime | ✅ | 존 없음 → 소비 시 KST 부여 |

---

