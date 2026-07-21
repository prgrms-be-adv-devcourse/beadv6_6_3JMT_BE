# Payment Service 단건 환불 연동 계약

## 1. 문서 목적

Order Service의 주문 상품 단건 환불 구현에서 사용하는 payment-service의 현재 계약을 기록한다.

- Frontend는 한 번에 하나의 `orderProductId`만 환불 요청한다.
- payment-service에도 단건 Kafka 이벤트와 단건 gRPC 조회가 이미 구현돼 있다.
- 따라서 payment-service 개발자에게 신규 계약이나 코드 변경을 요청하지 않는다.
- 이 문서는 두 서비스의 연동 확인 기준이며 payment-service 작업 요청서가 아니다.

## 2. 전체 흐름

```text
Frontend
  -> POST /api/v2/orders/{orderId}/refund
     { paymentId, orderProductId }
  -> Order Service가 로컬 결제·주문 상품 검증
  -> ORDER_REFUND_REQUESTED 단건 이벤트 발행
  -> Payment Service가 orderId로 결제를 찾고 상품 한 건 환불
  -> PAYMENT_REFUNDED 또는 PAYMENT_REFUND_FAILED 발행
  -> Order Service가 paymentId + orderProductId로 결과 반영

Kafka 결과 미수신
  -> Order Service가 10분 후 GetRefund(paymentId, orderProductId) 호출
```

## 3. Order Service -> Payment Service

### 3.1 이벤트

| 항목 | 값 |
|---|---|
| topic | `order-events` |
| eventType | `ORDER_REFUND_REQUESTED` |
| Kafka key | `orderId` |
| envelope aggregate ID | `orderId` |

payload:

```json
{
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "orderProductId": "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa",
  "buyerId": "13f5ed29-251a-4f17-ae5a-56a1e2cc40f8",
  "refundAmount": 10000,
  "requestedAt": "2026-07-15T12:00:00"
}
```

현재 payment-service DTO 기준 필드:

```java
OrderRefundRequestedMessage(
    UUID orderId,
    UUID orderProductId,
    UUID buyerId,
    int refundAmount,
    LocalDateTime requestedAt
)
```

### 3.2 의도적으로 보내지 않는 값

- `paymentId`: payment-service가 `orderId`로 결제를 조회한다.
- `refundRequestId`: 현재 payment-service 계약에 없으며 Order Service 내부 식별자로만 사용한다.
- 상품 목록: 요청당 상품이 정확히 한 건이므로 `products[]`나 `orderProductIds`를 추가하지 않는다.

Order Service는 Outbox에 위 payload를 저장해 트랜잭션 커밋 후 발행한다. 최초 요청 이벤트가 발행된 뒤 Order Service에서 동일 비즈니스 요청을 재발행하지 않는다.

## 4. Payment Service -> Order Service 성공

### 4.1 이벤트

| 항목 | 값 |
|---|---|
| topic | `payment-events` |
| eventType | `PAYMENT_REFUNDED` |

payload:

```json
{
  "paymentId": "f8ab35e8-7585-42ea-8615-f11215d7cb07",
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "13f5ed29-251a-4f17-ae5a-56a1e2cc40f8",
  "orderProductId": "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa",
  "amount": 10000,
  "paymentStatus": "PARTIAL_REFUNDED",
  "refundedAt": "2026-07-15T12:00:05+09:00"
}
```

현재 payment-service DTO 기준 필드:

```java
PaymentRefundedMessage(
    UUID paymentId,
    UUID orderId,
    UUID userId,
    UUID orderProductId,
    int amount,
    String paymentStatus,
    String refundedAt
)
```

Order Service는 다음 값을 저장 요청과 비교한 뒤 성공을 반영한다.

- `paymentId`
- `orderId`
- `userId == buyerId`
- `orderProductId`
- `amount == refundAmount`

## 5. Payment Service -> Order Service 실패

### 5.1 이벤트

| 항목 | 값 |
|---|---|
| topic | `payment-events` |
| eventType | `PAYMENT_REFUND_FAILED` |

payload:

```json
{
  "paymentId": "f8ab35e8-7585-42ea-8615-f11215d7cb07",
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "13f5ed29-251a-4f17-ae5a-56a1e2cc40f8",
  "orderProductId": "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa",
  "refundAmount": 10000,
  "paymentStatus": "PAID",
  "failureReason": "PG 환불 실패",
  "failedAt": "2026-07-15T12:00:05+09:00"
}
```

현재 payment-service DTO 기준 필드:

```java
PaymentRefundFailedMessage(
    UUID paymentId,
    UUID orderId,
    UUID userId,
    UUID orderProductId,
    int refundAmount,
    String paymentStatus,
    String failureReason,
    String failedAt
)
```

Order Service는 성공과 같은 식별자를 비교하고 `refundAmount`까지 일치할 때만 실패를 반영한다.

현재 정책에서 이 이벤트는 최종 실패다. Order Service는 `order_refund.status=FAILED`를 기록하지만 주문과 상품을 이전 상태로 복원하지 않고 `REFUND_REQUESTED`로 유지한다.

## 6. gRPC 환불 상태 조회

Kafka 결과를 받지 못한 요청은 최초 요청 10분 후 payment-service의 기존 RPC를 조회한다.

```protobuf
service PaymentQueryService {
  rpc GetRefund(GetRefundRequest) returns (GetRefundResponse);
}

message GetRefundRequest {
  string payment_id = 1;
  string order_product_id = 2;
}

message GetRefundResponse {
  string payment_id = 1;
  string order_id = 2;
  string user_id = 3;
  string order_product_id = 4;
  int32 amount = 5;
  string payment_status = 6;
  string refund_status = 7;
  string refunded_at = 8;
}
```

### 6.1 상태 매핑

| `refund_status` | Order Service 처리 |
|---|---|
| `REQUESTED` | 결과 대기를 유지하고 다음 조회 예약 |
| `COMPLETED` | 성공 이벤트와 같은 로직으로 완료 반영 |
| `FAILED` | 실패로 반영하고 주문·상품은 `REFUND_REQUESTED` 유지 |

`GetRefundResponse`에는 실패 시각과 실패 사유가 없다. `FAILED` 확인 시 Order Service는 조회 시각을 `failedAt`으로 사용하고 `failureCode=PAYMENT_REFUND_FAILED_CONFIRMED_BY_GRPC`를 기록한다.

`NOT_FOUND`, `UNAVAILABLE`, `DEADLINE_EXCEEDED`는 환불 실패로 확정하지 않고 다음 조회를 예약한다.

## 7. 멱등성과 상관관계

현재 Kafka 요청에 `refundRequestId`가 없으므로 외부 결과의 상관관계는 `paymentId + orderProductId`를 사용한다.

Order Service 보장:

- 같은 `eventId + consumerGroup`은 한 번만 처리한다.
- 같은 `paymentId + orderProductId`의 결과가 다른 eventId로 다시 와도 상태와 후속 Outbox를 중복 반영하지 않는다.
- gRPC가 먼저 완료를 반영한 뒤 Kafka 성공이 늦게 도착해도 완료와 `ORDER_REFUND` Outbox는 한 번만 반영한다.
- 같은 주문 상품은 `order_refund_product.order_product_id` unique 제약으로 다시 요청할 수 없다.

Payment Service의 기존 결제 잔액·상품 중복 환불 검증은 그대로 사용한다.

## 8. 현재 계약의 알려진 제약

- 여러 상품을 하나의 PG 환불로 묶는 원자적 다건 환불은 지원하지 않는다.
- 여러 상품을 환불하려면 앞선 요청 완료 후 상품별로 순차 요청한다.
- 요청 이벤트에 `paymentId`나 `refundRequestId`가 없어 이 값으로 소비 단계 멱등성을 강화할 수 없다.
- payment-service는 현재 PG 환불 예외를 `PAYMENT_REFUND_FAILED`로 즉시 발행하며 별도 PG 재처리 Worker가 없다.
- 실패 정책상 Order Service의 주문과 대상 상품은 `REFUND_REQUESTED`에 남으므로 운영 복구 절차가 별도로 필요할 수 있다.

이 제약을 해소하려면 payment-service 또는 Frontend 계약 변경이 필요하므로 현재 Order Service 구현 범위에는 포함하지 않는다.

## 9. 통합 확인 체크리스트

- [ ] `ORDER_REFUND_REQUESTED` 필드명과 시간 직렬화 형식이 payment-service DTO와 일치한다.
- [ ] `PAYMENT_REFUNDED`의 `amount`와 `PAYMENT_REFUND_FAILED`의 `refundAmount` 차이를 반영한다.
- [ ] `PAYMENT_REFUND_FAILED`가 payment-service Router와 Producer에서 실제 발행된다.
- [ ] dev/prod에서 `PaymentQueryService.GetRefund` endpoint와 deadline 설정이 유효하다.
- [ ] `REQUESTED`, `COMPLETED`, `FAILED`, `NOT_FOUND` gRPC 응답을 통합 테스트한다.
- [ ] 같은 `paymentId + orderProductId`의 Kafka/gRPC 중복 결과를 한 번만 반영한다.
- [ ] payment-service 변경 없이 양 서비스의 현재 배포 버전 조합으로 계약 테스트가 통과한다.

## 10. 관련 문서

- 설계: `docs/superpowers/specs/2026-07-15-order-partial-refund-design.md`
- 구현 계획: `docs/superpowers/plans/2026-07-15-order-partial-refund.md`
- 의사결정: `docs/trade-off/2026-07-15-single-order-product-refund.md`
