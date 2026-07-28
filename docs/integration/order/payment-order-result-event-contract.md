# 결제 결과 이벤트 소비 계약

Order Service는 `payment-events` 토픽의 주문 단위 결제 결과를 소비한다. 배포 순서와 DLT 재처리를 고려해 Payment Service의 축소형 payload와 식별자가 포함된 확장형 payload를 함께 수용한다.

## PAYMENT_APPROVED

필수 필드는 다음과 같다.

- `orderId`
- `approvedAmount` 또는 하위 호환 별칭 `amount`
- `approvedAt`

`paymentId`와 `userId`는 선택값이다. `userId`가 있으면 주문의 구매자와 비교하고, 없으면 잠근 주문의 구매자 정보를 기준으로 처리한다. 승인 금액은 항상 주문 총액과 비교한다.

## PAYMENT_FAILED

현재 축소형 생산 계약은 다음 필드를 발행한다.

- `orderId`
- `failedAmount`
- `failedAt`

과거 확장형 메시지의 `paymentId`, `userId`, `failureCode`, `failureReason`도 계속 수용한다. `userId`가 있으면 주문 구매자와 비교하고, `failedAmount`가 있으면 주문 총액과 비교한다. 과거 메시지에 `failedAt`이 없으면 envelope의 `occurredAt`을 사용한다.

## 운영 원칙

- 이벤트 envelope의 `eventId`, `eventType`, `occurredAt`은 필수다.
- `eventId + consumerGroup` 처리 이력으로 중복 소비를 방지한다.
- 계약 불일치로 DLT에 적재된 축소형 이벤트는 이 소비자 버전 배포 후 재처리할 수 있다.
