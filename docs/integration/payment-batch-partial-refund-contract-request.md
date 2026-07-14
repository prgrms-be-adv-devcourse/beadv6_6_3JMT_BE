# Payment 다건 부분 환불 Kafka 계약 요청

## 요청 범위

Order Service는 `order-events` 토픽으로 아래 `REFUND_REQUESTED` 이벤트를 발행한다. Payment Service는 이를 소비해 PG 다건 부분 환불을 처리하고, 결과를 `payment-events` 토픽으로 발행한다.

`refundRequestId`는 PG 요청의 멱등성 키다. 같은 `refundRequestId`에 대한 재전송은 동일한 PG 환불 요청으로 처리되어야 하며, 중복 환불을 만들면 안 된다.

## Order Service → Payment Service

```json
{
  "eventType": "REFUND_REQUESTED",
  "aggregateType": "ORDER_REFUND",
  "aggregateId": "refundRequestId",
  "payload": {
    "refundRequestId": "UUID",
    "paymentId": "UUID",
    "orderId": "UUID",
    "buyerId": "UUID",
    "totalRefundAmount": 19000,
    "products": [
      {"orderProductId": "UUID", "refundAmount": 9000},
      {"orderProductId": "UUID", "refundAmount": 10000}
    ],
    "requestedAt": "2026-07-14T10:00:00"
  }
}
```

## Payment Service → Order Service

Payment Service는 성공 시 `PAYMENT_PARTIAL_REFUNDED`, 실패 시 `PAYMENT_PARTIAL_REFUND_FAILED`를 발행한다.

| 이벤트 | 공통 payload 필드 | 추가 payload 필드 |
|---|---|---|
| `PAYMENT_PARTIAL_REFUNDED` | `refundRequestId`, `paymentId`, `orderId`, `totalRefundAmount` | `refundedAt` |
| `PAYMENT_PARTIAL_REFUND_FAILED` | `refundRequestId`, `paymentId`, `orderId`, `totalRefundAmount` | `failureCode`, `failureReason`, `retryable`, `failedAt` |

## 롤아웃 조건

- Payment Service가 위 이벤트 이름과 payload를 `payment-events`에서 제공하기 전에는 Order Service의 다건 부분 환불 소비자를 활성화하지 않는다.
- PG 멱등성 키는 반드시 `refundRequestId`를 사용한다.
- 기존 `PAYMENT_REFUNDED` 전체 환불 이벤트 처리는 별도 정리 이슈 전까지 유지한다.
