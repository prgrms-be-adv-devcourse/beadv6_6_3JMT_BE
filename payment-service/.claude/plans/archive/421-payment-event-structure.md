# PAYMENT_FAILED payload 필드 확장 구현 계획

PAYMENT_FAILED Kafka 이벤트 payload에 `failedAmount`, `failedAt` 필드를 추가해 다른 결제 이벤트(PAYMENT_APPROVED/PAYMENT_REFUNDED/PAYMENT_REFUND_FAILED)와 동일한 "금액 + 시각" 패턴으로 통일한다.

---

## 배경 및 목표

현재 PAYMENT_FAILED payload는 `orderId` 하나뿐이다.

```json
{ "orderId": "660e8400-e29b-41d4-a716-446655440001" }
```

order-service가 실패 금액/시각까지 받아야 할 필요가 생겨 아래 구조로 변경 요청받았다.

```json
{
  "orderId": "UUID",
  "failedAmount": 9900,
  "failedAt": "2026-06-15T19:01:00+09:00"
}
```

요청 원문 필드명은 `faliedAmount`(오타)였으나, order-service와 확정된 계약이 아니라 사용자 확인 결과 `failedAmount`로 정정하기로 했다.

## 값 소스 확인

- `failedAmount` = `Payment.getTotalAmount()`(시도 결제 금액). PAYMENT_FAILED는 Toss confirm 자체 실패 케이스로, TX1에서 이미 생성된 Payment 레코드(READY/REQUESTED, `total_amount nullable=false` int)에 대해 `fail()`을 호출하는 흐름이다 — 발행 지점(`ConfirmPaymentService.java:129`, 유일한 발행 지점)에서 `totalAmount`는 항상 존재하며 null이 될 수 없다. `approvedAmount`는 실패 케이스에서 항상 null이라 후보가 아니다.
- `failedAt` = `payment.getFailedAt()`을 KST 문자열로 변환. `KafkaPaymentEventPublisher`에 이미 있는 `toKstString(OffsetDateTime)` 헬퍼를 재사용(다른 `on*` 메서드와 동일 패턴), 신규 헬퍼 불필요.
- 금액 불일치(`PAY012`)로 Payment 레코드 자체가 안 만들어지는 케이스는 이 이벤트를 발행하지 않으므로 영향 없음(기존 문서화된 동작 그대로 유지).

## 변경 파일

1. **`src/main/java/com/prompthub/payment/infrastructure/messaging/dto/PaymentFailedMessage.java`**
   `record PaymentFailedMessage(UUID orderId)` → `record PaymentFailedMessage(UUID orderId, int failedAmount, String failedAt)`

2. **`src/main/java/com/prompthub/payment/infrastructure/messaging/KafkaPaymentEventPublisher.java`** (`onPaymentFailed`, 69~93행)
   ```java
   PaymentFailedMessage payload = new PaymentFailedMessage(
       payment.getOrderId(),
       payment.getTotalAmount(),
       toKstString(payment.getFailedAt())
   );
   ```

3. **`.claude/docs/events.md`** PAYMENT_FAILED 섹션(150~176행)
   - JSON 예시에 `"failedAmount": 9900`, `"failedAt": "2026-06-15T19:01:00+09:00"` 추가
   - 필드 표에 두 행 추가
   - "orderId 하나만 담는 최소 payload" 설명 문구를 현재 구조에 맞게 수정

4. **`src/test/java/com/prompthub/payment/infrastructure/messaging/KafkaPaymentEventPublisherTest.java`**
   기존 테스트 `결제_실패_시_EventMessage_봉투로_발행한다`(72~95행)에 `failedAmount`/`failedAt` assertion 추가.

`ConfirmPaymentIntegrationTest.java`(`PG_결제_실패_시_payment_failed_수신_및_FAILED_저장`)는 Kafka record value 문자열에 `"PAYMENT_FAILED"` 포함 여부만 보는 느슨한 검증이라 수정 불필요.

## 테스트

- `../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.messaging.KafkaPaymentEventPublisherTest"`
- `../gradlew :payment-service:test --tests "com.prompthub.payment.ConfirmPaymentIntegrationTest"`
- 전체: `../gradlew :payment-service:test`
