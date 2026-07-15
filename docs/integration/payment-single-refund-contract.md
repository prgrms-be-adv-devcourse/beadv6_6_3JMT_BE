# Payment 주문 상품 단건 환불 계약 확인

## 결론

Order Service 부분 환불은 payment-service에 이미 구현된 단건 계약을 사용한다. payment-service의 Kafka 이벤트, payload 또는 gRPC를 변경하는 별도 작업은 요청하지 않는다.

## Order Service -> Payment Service

- topic: `order-events`
- eventType: `ORDER_REFUND_REQUESTED`
- Kafka key와 aggregate ID: `orderId`

```json
{
  "orderId": "UUID",
  "orderProductId": "UUID",
  "buyerId": "UUID",
  "refundAmount": 10000,
  "requestedAt": "2026-07-15T12:00:00"
}
```

요청에는 상품 목록, `paymentId`, Order Service 내부 `refundRequestId`를 추가하지 않는다.

## Payment Service -> Order Service

성공 이벤트는 `PAYMENT_REFUNDED`다.

```json
{
  "paymentId": "UUID",
  "orderId": "UUID",
  "userId": "UUID",
  "orderProductId": "UUID",
  "amount": 10000,
  "paymentStatus": "PARTIAL_REFUNDED",
  "refundedAt": "2026-07-15T12:00:05+09:00"
}
```

실패 이벤트는 `PAYMENT_REFUND_FAILED`다.

```json
{
  "paymentId": "UUID",
  "orderId": "UUID",
  "userId": "UUID",
  "orderProductId": "UUID",
  "refundAmount": 10000,
  "paymentStatus": "PAID",
  "failureReason": "PG 환불 실패",
  "failedAt": "2026-07-15T12:00:05+09:00"
}
```

Order Service는 `paymentId + orderProductId`로 저장된 요청을 찾고 주문, 구매자와 금액까지 비교한다.

## gRPC

Kafka 결과 유실 시 기존 RPC를 사용한다.

```protobuf
rpc GetRefund(GetRefundRequest) returns (GetRefundResponse);

message GetRefundRequest {
  string payment_id = 1;
  string order_product_id = 2;
}
```

Order Service는 `REQUESTED`, `COMPLETED`, `FAILED` 상태를 처리한다. 신규 `refundRequestId` 기반 RPC는 요구하지 않는다.

## 롤아웃 확인

- 현재 배포본이 `ORDER_REFUND_REQUESTED` 단건 payload를 소비한다.
- 현재 배포본이 `PAYMENT_REFUNDED`, `PAYMENT_REFUND_FAILED`를 발행한다.
- 현재 배포본이 `GetRefund(paymentId, orderProductId)`를 제공한다.
- 위 계약 확인 외에 payment-service 선행 개발은 없다.

상세한 Order Service 매핑과 제약은 `order-service/docs/integration/payment-service-single-refund-contract.md`를 따른다.
