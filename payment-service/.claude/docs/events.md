# Kafka 이벤트 계약

payment-service가 **발행(Publish)** 하는 Kafka 이벤트 목록. 구독(Consume) 이벤트는 현재 없음.

각 이벤트의 payload 상세 스키마는 이 문서의 "Payload 스키마" 섹션을 참조.

모노레포 전체 이벤트 흐름: `../../../docs/architecture/event-flow.md`

---

## 발행 토픽

| 토픽 | 발행 시점 | 구독자 | 구독자 처리 내용 |
|---|---|---|---|
| `payment.approved` | Toss confirm 성공 | Order | Order PAID 전환 + `is_download = true` |
| `payment.canceled` | PG 취소 성공 | Order | Order CANCELED 전환 + `is_download = false` |
| `payment.cancel_failed` | PG 취소 실패 | Order | PAID 상태 복구 |
| `payment.refunded` | PG 환불 성공 | Order | Order REFUNDED 전환 + `is_download = false` |

---

## 구현 위치

| 파일 | 역할 |
|---|---|
| `infrastructure/messaging/config/PaymentTopic.java` | 토픽 상수 정의 (현재 플레이스홀더) |
| `infrastructure/messaging/config/KafkaConfig.java` | NewTopic 빈 설정 (현재 플레이스홀더) |
| `application/gateway/messaging/` | EventPublisher 인터페이스 (출력 경계) |
| `infrastructure/messaging/` | EventPublisher 구현체 |

---

## Payload 공통 필드

모든 이벤트에 `eventType` 필드 포함 (토픽명과 동일한 값).

```json
{ "eventType": "payment.approved", ... }
```

---

## Payload 스키마

### payment.approved

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

### payment.canceled

```json
{
  "eventType": "payment.canceled",
  "paymentId": "550e8400-e29b-41d4-a716-446655440000",
  "orderId":   "660e8400-e29b-41d4-a716-446655440001",
  "canceledAt": "2026-06-15T10:05:00Z"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `eventType` | String | ✅ | `"payment.canceled"` 고정 |
| `paymentId` | UUID | ✅ | Payment ID |
| `orderId` | UUID | ✅ | 주문 ID |
| `canceledAt` | ISO 8601 | ✅ | PG 취소 완료 일시 |

---

### payment.cancel_failed

```json
{
  "eventType": "payment.cancel_failed",
  "paymentId": "550e8400-e29b-41d4-a716-446655440000",
  "orderId":   "660e8400-e29b-41d4-a716-446655440001",
  "reason":    "PG 취소 요청 타임아웃",
  "failedAt":  "2026-06-15T10:05:00Z"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `eventType` | String | ✅ | `"payment.cancel_failed"` 고정 |
| `paymentId` | UUID | ✅ | Payment ID |
| `orderId` | UUID | ✅ | 주문 ID |
| `reason` | String | ✅ | PG 취소 실패 사유 |
| `failedAt` | ISO 8601 | ✅ | 실패 일시 |

---

### payment.refunded

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

