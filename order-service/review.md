# EventMessage 적용 검증 문서

## 1. 목적

이 문서는 `common` 모듈의 `EventMessage<T>` 도입 작업이 `order-service`에 올바르게 적용되었는지 검증하기 위한 기준을 정의한다.

검증 대상은 다음과 같다.

```text
1. common 모듈에 EventMessage<T>가 올바르게 추가되었는가?
2. order-service의 Kafka Consumer가 EventMessage<JsonNode>로 메시지를 수신하는가?
3. eventType은 String으로 유지하고, 도메인 enum 변환은 Router에서 수행하는가?
4. payload는 Handler에서 구체 타입으로 변환하는가?
5. payment-events, order-events 토픽명이 일관되게 적용되었는가?
6. ORDER_PAID, ORDER_REFUND 발행 시 EventMessage 구조로 OutboxEvent가 저장되는가?
7. 미지원 eventType은 DLT로 보내지 않고 정상 소비되는가?
8. payload 매핑 실패, 필수 필드 누락, DB 처리 실패는 DLT 대상이 되는가?
9. 중복 eventId 수신 시 멱등성이 보장되는가?
```

---

## 2. 검증 범위

### 포함 범위

| 구분                      | 검증 대상                                                                                        |
| ----------------------- | -------------------------------------------------------------------------------------------- |
| common                  | `EventMessage<T>`, `EventType` 인터페이스                                                         |
| order-service Consumer  | `PaymentEventConsumer`, `PaymentEventRouter`                                                 |
| order-service Handler   | `PaymentApprovedEventHandler`, `PaymentRefundedEventHandler`                                 |
| order-service EventType | `PaymentEventType`, `OrderEventType`                                                         |
| order-service Payload   | `PaymentApprovedPayload`, `PaymentRefundedPayload`, `OrderPaidPayload`, `OrderRefundPayload` |
| order-service Outbox    | `OutboxEventAppender`, `OutboxRelay`                                                         |
| Kafka Topic             | `payment-events`, `order-events`, `.DLT`                                                     |
| 멱등성                     | `processed_event`, `eventId + consumerGroup`                                                 |

### 제외 범위

| 제외 항목                       | 사유                                           |
| --------------------------- | -------------------------------------------- |
| payment-service 실제 결제 승인 연동 | 이번 검증은 order-service의 EventMessage 적용 여부가 목적 |
| settlement-service 실제 정산 처리 | order-events 발행까지만 order-service 범위          |
| product-service 판매 수 증가 처리  | order-service 외부 처리 영역                       |
| 운영 Kafka 클러스터 장애 복구         | 로컬/테스트 환경 기준 검증 문서                           |

---

## 3. 최종 적용 기준

### 3.1 EventMessage 구조

`common` 모듈에는 아래 구조가 존재해야 한다.

```java
public record EventMessage<T>(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        String aggregateType,
        UUID aggregateId,
        T payload
) {
}
```

검증 기준:

```text
[ ] eventId 필드가 존재한다.
[ ] eventType 필드는 String이다.
[ ] occurredAt 필드가 존재한다.
[ ] aggregateType 필드가 존재한다.
[ ] aggregateId 필드가 존재한다.
[ ] payload 필드명이 유지된다.
[ ] version 또는 eventVersion 필드는 추가하지 않는다.
[ ] eventType에 공통 enum을 직접 사용하지 않는다.
```

---

### 3.2 EventType 인터페이스

`common` 모듈에는 선택적으로 아래 인터페이스를 둘 수 있다.

```java
public interface EventType {
    String code();
}
```

검증 기준:

```text
[ ] common 모듈에 모든 도메인 이벤트 enum을 몰아넣지 않는다.
[ ] PaymentEventType은 order-service 내부에 둔다.
[ ] OrderEventType은 order-service 내부에 둔다.
```

---

## 4. 정적 코드 검증

아래 항목은 코드 검색으로 먼저 확인한다.

### 4.1 기존 Envelope 제거 확인

검색어:

```bash
grep -R "OrderEventEnvelope" .
grep -R "PaymentEventEnvelope" .
```

기대 결과:

```text
[ ] 기존 OrderEventEnvelope<T> 사용이 제거되었거나 EventMessage<T>로 대체되었다.
[ ] 기존 PaymentEventEnvelope 사용이 제거되었거나 EventMessage<JsonNode>로 대체되었다.
[ ] 단, 마이그레이션 중 임시 호환 클래스가 있다면 제거 예정 TODO가 명확해야 한다.
```

---

### 4.2 EventMessage 사용 위치 확인

검색어:

```bash
grep -R "EventMessage<" common order-service
```

기대 사용 위치:

| 위치              | 기대 타입                              |
| --------------- | ---------------------------------- |
| Consumer 수신     | `EventMessage<JsonNode>`           |
| Handler 입력      | `EventMessage<JsonNode>`           |
| Outbox 저장       | `EventMessage<?>`                  |
| ORDER_PAID 생성   | `EventMessage<OrderPaidPayload>`   |
| ORDER_REFUND 생성 | `EventMessage<OrderRefundPayload>` |

금지 위치:

| 위치                       | 금지 기준                          |
| ------------------------ | ------------------------------ |
| REST Controller Response | `EventMessage<T>` 반환 금지        |
| Domain Entity            | 필드로 보관 금지                      |
| Repository 반환 타입         | Repository가 EventMessage 생성 금지 |
| gRPC Adapter             | gRPC DTO와 혼용 금지                |

---

### 4.3 eventType enum 직접 역직렬화 금지 확인

검색어:

```bash
grep -R "EventMessage<PaymentEventType" order-service
grep -R "EventMessage<OrderEventType" order-service
grep -R "Enum.valueOf" order-service/src/main/java
```

기대 결과:

```text
[ ] Consumer 수신 타입에 EventMessage<PaymentEventType>을 사용하지 않는다.
[ ] EventMessage의 eventType 필드는 String이다.
[ ] PaymentEventType 변환은 from(String) 메서드로 처리한다.
[ ] Router에서 Enum.valueOf()를 직접 사용하지 않는다.
```

---

### 4.4 토픽명 확인

검색어:

```bash
grep -R "payment.events" .
grep -R "order.events" .
grep -R "payment.approved" .
grep -R "payment.refunded" .
grep -R "payment-events" .
grep -R "order-events" .
```

기대 결과:

```text
[ ] payment.events 사용이 제거되었다.
[ ] order.events 사용이 제거되었다.
[ ] payment.approved 사용이 제거되었다.
[ ] payment.refunded 사용이 제거되었다.
[ ] payment-events를 사용한다.
[ ] order-events를 사용한다.
[ ] DLT 토픽은 payment-events.DLT, order-events.DLT 기준이다.
```

단, 기존 문서에 남아 있는 과거 표기는 별도 문서 수정 대상이다.

---

## 5. 단위 테스트 검증

## 5.1 EventMessage 직렬화 테스트

### TC-001. EventMessage가 공통 JSON 구조로 직렬화된다

Given:

```java
EventMessage<OrderPaidPayload> message = new EventMessage<>(
        eventId,
        "ORDER_PAID",
        occurredAt,
        "ORDER",
        orderId,
        payload
);
```

When:

```java
String json = objectMapper.writeValueAsString(message);
```

Then:

```text
[ ] json에 eventId가 포함된다.
[ ] json에 eventType이 포함된다.
[ ] json에 occurredAt이 포함된다.
[ ] json에 aggregateType이 포함된다.
[ ] json에 aggregateId가 포함된다.
[ ] json에 payload가 포함된다.
[ ] payload 필드명이 eventData, body, data로 바뀌지 않는다.
```

---

## 5.2 EventMessage 역직렬화 테스트

### TC-002. Consumer는 EventMessage<JsonNode>로 역직렬화할 수 있다

Given:

```json
{
  "eventId": "c58c0e77-0c12-46b5-b9e1-4fd74d5d6f01",
  "eventType": "PAYMENT_APPROVED",
  "occurredAt": "2026-07-08T11:30:00",
  "aggregateType": "ORDER",
  "aggregateId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
  "payload": {
    "orderId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
    "paymentId": "2f51a5c2-0e54-4d28-9e96-65e7f26ef111",
    "approvedAmount": 30000
  }
}
```

When:

```java
EventMessage<JsonNode> message = objectMapper.readValue(json, typeRef);
```

Then:

```text
[ ] message.eventType()은 PAYMENT_APPROVED이다.
[ ] message.aggregateType()은 ORDER이다.
[ ] message.aggregateId()는 orderId이다.
[ ] message.payload()는 JsonNode이다.
[ ] payload는 아직 PaymentApprovedPayload로 변환되지 않는다.
```

---

## 5.3 PaymentEventType 변환 테스트

### TC-003. 지원하는 eventType은 enum으로 변환된다

| 입력                 | 기대 결과                               |
| ------------------ | ----------------------------------- |
| `PAYMENT_APPROVED` | `PaymentEventType.PAYMENT_APPROVED` |
| `PAYMENT_REFUNDED` | `PaymentEventType.PAYMENT_REFUNDED` |
| `PAYMENT_FAILED`   | `PaymentEventType.PAYMENT_FAILED`   |
| `PAYMENT_CANCELED` | `PaymentEventType.PAYMENT_CANCELED` |

검증 기준:

```text
[ ] PaymentEventType.from(String)이 정상 enum을 반환한다.
[ ] code()는 name()과 동일한 문자열을 반환한다.
```

---

### TC-004. 미지원 eventType은 null을 반환한다

| 입력                   | 기대 결과  |
| -------------------- | ------ |
| `PAYMENT_CHARGEBACK` | `null` |
| `UNKNOWN_EVENT`      | `null` |
| `null`               | `null` |
| `""`                 | `null` |
| `" "`                | `null` |

검증 기준:

```text
[ ] 예외를 던지지 않는다.
[ ] Enum.valueOf() 직접 호출로 실패하지 않는다.
[ ] Router가 null을 받아 로그만 남기고 종료할 수 있다.
```

---

## 5.4 PaymentEventRouter 테스트

### TC-005. PAYMENT_APPROVED는 승인 Handler로 라우팅된다

Given:

```text
eventType = PAYMENT_APPROVED
```

Then:

```text
[ ] PaymentApprovedEventHandler.handle(message)가 1회 호출된다.
[ ] PaymentRefundedEventHandler는 호출되지 않는다.
```

---

### TC-006. PAYMENT_REFUNDED는 환불 Handler로 라우팅된다

Given:

```text
eventType = PAYMENT_REFUNDED
```

Then:

```text
[ ] PaymentRefundedEventHandler.handle(message)가 1회 호출된다.
[ ] PaymentApprovedEventHandler는 호출되지 않는다.
```

---

### TC-007. PAYMENT_FAILED는 무시된다

Given:

```text
eventType = PAYMENT_FAILED
```

Then:

```text
[ ] Handler가 호출되지 않는다.
[ ] 예외가 발생하지 않는다.
[ ] DLT 대상 예외를 던지지 않는다.
```

---

### TC-008. PAYMENT_CANCELED는 무시된다

Given:

```text
eventType = PAYMENT_CANCELED
```

Then:

```text
[ ] Handler가 호출되지 않는다.
[ ] 예외가 발생하지 않는다.
[ ] DLT 대상 예외를 던지지 않는다.
```

---

### TC-009. 알 수 없는 eventType은 정상 종료된다

Given:

```text
eventType = PAYMENT_CHARGEBACK
```

Then:

```text
[ ] Handler가 호출되지 않는다.
[ ] 예외가 발생하지 않는다.
[ ] DLT로 이동하지 않는다.
[ ] Unsupported eventType 로그만 남긴다.
```

---

## 5.5 Payload 매핑 테스트

### TC-010. PAYMENT_APPROVED payload가 구체 타입으로 변환된다

Given:

```text
EventMessage<JsonNode>
eventType = PAYMENT_APPROVED
payload = 정상 결제 승인 payload
```

When:

```java
PaymentApprovedPayload payload =
        objectMapper.treeToValue(message.payload(), PaymentApprovedPayload.class);
```

Then:

```text
[ ] orderId가 매핑된다.
[ ] paymentId가 매핑된다.
[ ] buyerId가 매핑된다.
[ ] pgTxId가 매핑된다.
[ ] paymentMethod가 매핑된다.
[ ] provider가 매핑된다.
[ ] approvedAmount가 매핑된다.
[ ] approvedAt이 매핑된다.
```

---

### TC-011. PAYMENT_APPROVED payload 필수 필드 누락 시 실패한다

Given:

```json
{
  "paymentId": "2f51a5c2-0e54-4d28-9e96-65e7f26ef111",
  "approvedAmount": 30000
}
```

Then:

```text
[ ] payload 검증 또는 매핑 단계에서 실패한다.
[ ] 해당 실패는 DLT 대상이다.
[ ] 주문 상태는 변경되지 않는다.
[ ] OutboxEvent는 저장되지 않는다.
[ ] processed_event는 저장되지 않는다.
```

---

### TC-012. PAYMENT_REFUNDED payload가 구체 타입으로 변환된다

Given:

```text
EventMessage<JsonNode>
eventType = PAYMENT_REFUNDED
payload = 정상 환불 payload
```

Then:

```text
[ ] orderId가 매핑된다.
[ ] paymentId가 매핑된다.
[ ] refundAmount가 매핑된다.
[ ] refundedAt이 매핑된다.
```

---

## 5.6 Service 처리 테스트

### TC-013. PAYMENT_APPROVED 수신 시 주문 상태가 PAID로 변경된다

Given:

```text
PENDING 상태의 Order
PENDING 상태의 OrderProduct
정상 PAYMENT_APPROVED payload
```

When:

```text
PaymentApprovedProcessor.process(...)
```

Then:

```text
[ ] Order 상태가 PAID로 변경된다.
[ ] OrderProduct 상태가 PAID로 변경된다.
[ ] OrderPayment가 저장된다.
[ ] ORDER_PAID OutboxEvent가 저장된다.
[ ] processed_event가 저장된다.
```

---

### TC-014. PAYMENT_REFUNDED 수신 시 주문 상태가 REFUNDED로 변경된다

Given:

```text
PAID 상태의 Order
PAID 상태의 OrderProduct
정상 PAYMENT_REFUNDED payload
```

When:

```text
PaymentRefundedProcessor.process(...)
```

Then:

```text
[ ] Order 상태가 REFUNDED로 변경된다.
[ ] OrderProduct 상태가 REFUNDED로 변경된다.
[ ] ORDER_REFUND OutboxEvent가 저장된다.
[ ] processed_event가 저장된다.
```

---

## 5.7 멱등성 테스트

### TC-015. 동일 eventId는 1회만 처리된다

Given:

```text
eventId = 동일
consumerGroup = order-service
동일 PAYMENT_APPROVED 메시지 2회 수신
```

When:

```text
processor.process(...) 2회 호출
```

Then:

```text
[ ] Order 상태 변경은 1회만 수행된다.
[ ] OrderPayment 저장은 1회만 수행된다.
[ ] ORDER_PAID OutboxEvent 저장은 1회만 수행된다.
[ ] processed_event 저장은 1회만 수행된다.
[ ] 두 번째 호출은 즉시 종료된다.
```

---

### TC-016. 같은 eventId라도 consumerGroup이 다르면 별도 처리 기준이다

Given:

```text
eventId = 동일
consumerGroup = order-service
consumerGroup = settlement-service
```

Then:

```text
[ ] processed_event는 eventId + consumerGroup 기준으로 구분된다.
```

---

## 5.8 OutboxEvent 저장 테스트

### TC-017. ORDER_PAID OutboxEvent가 EventMessage 구조로 저장된다

Given:

```text
PAYMENT_APPROVED 처리 완료
```

Then:

| Outbox 필드       | 기대 값              |
| --------------- | ----------------- |
| `aggregateType` | `ORDER`           |
| `aggregateId`   | `orderId`         |
| `eventType`     | `ORDER_PAID`      |
| `topic`         | `order-events`    |
| `status`        | `PENDING`         |
| `payload`       | EventMessage JSON |

추가 검증:

```text
[ ] OutboxEvent.payload JSON에 eventId가 있다.
[ ] OutboxEvent.payload JSON에 eventType이 있다.
[ ] OutboxEvent.payload JSON에 occurredAt이 있다.
[ ] OutboxEvent.payload JSON에 aggregateType이 있다.
[ ] OutboxEvent.payload JSON에 aggregateId가 있다.
[ ] OutboxEvent.payload JSON에 payload가 있다.
```

---

### TC-018. ORDER_REFUND OutboxEvent가 EventMessage 구조로 저장된다

Given:

```text
PAYMENT_REFUNDED 처리 완료
```

Then:

| Outbox 필드       | 기대 값              |
| --------------- | ----------------- |
| `aggregateType` | `ORDER`           |
| `aggregateId`   | `orderId`         |
| `eventType`     | `ORDER_REFUND`    |
| `topic`         | `order-events`    |
| `status`        | `PENDING`         |
| `payload`       | EventMessage JSON |

---

## 5.9 OutboxRelay 테스트

### TC-019. PENDING OutboxEvent가 order-events로 발행된다

Given:

```text
status = PENDING
topic = order-events
aggregateId = orderId
payload = EventMessage JSON
```

When:

```text
OutboxRelay 실행
```

Then:

```text
[ ] KafkaTemplate.send()의 topic은 order-events이다.
[ ] KafkaTemplate.send()의 key는 orderId.toString()이다.
[ ] KafkaTemplate.send()의 value는 EventMessage JSON이다.
[ ] 발행 성공 시 status가 PUBLISHED로 변경된다.
[ ] publishedAt이 기록된다.
```

---

### TC-020. 발행 실패 시 retryCount가 증가한다

Given:

```text
Kafka 발행 실패
```

Then:

```text
[ ] retryCount가 증가한다.
[ ] 최대 재시도 전이면 status는 PENDING 또는 재시도 가능 상태를 유지한다.
[ ] 최대 재시도 초과 시 status는 FAILED가 된다.
```

---

## 6. 통합 테스트 검증

## 6.1 PAYMENT_APPROVED 통합 플로우

### TC-INT-001. payment-events 수신 후 ORDER_PAID OutboxEvent가 생성된다

Given:

```text
PENDING Order
PENDING OrderProduct
payment-events에 정상 PAYMENT_APPROVED EventMessage 발행
```

When:

```text
PaymentEventConsumer가 메시지를 수신한다.
```

Then:

```text
[ ] EventMessage<JsonNode>로 수신된다.
[ ] PaymentEventRouter가 PAYMENT_APPROVED로 분기한다.
[ ] PaymentApprovedEventHandler가 payload를 변환한다.
[ ] Order 상태가 PAID로 변경된다.
[ ] OrderProduct 상태가 PAID로 변경된다.
[ ] OrderPayment가 저장된다.
[ ] ORDER_PAID OutboxEvent가 저장된다.
[ ] processed_event가 저장된다.
```

---

## 6.2 PAYMENT_REFUNDED 통합 플로우

### TC-INT-002. payment-events 수신 후 ORDER_REFUND OutboxEvent가 생성된다

Given:

```text
PAID Order
PAID OrderProduct
payment-events에 정상 PAYMENT_REFUNDED EventMessage 발행
```

Then:

```text
[ ] EventMessage<JsonNode>로 수신된다.
[ ] PaymentEventRouter가 PAYMENT_REFUNDED로 분기한다.
[ ] PaymentRefundedEventHandler가 payload를 변환한다.
[ ] Order 상태가 REFUNDED로 변경된다.
[ ] OrderProduct 상태가 REFUNDED로 변경된다.
[ ] ORDER_REFUND OutboxEvent가 저장된다.
[ ] processed_event가 저장된다.
```

---

## 6.3 미지원 eventType 통합 플로우

### TC-INT-003. 미지원 eventType은 정상 소비된다

Given:

```json
{
  "eventId": "7cc0bb89-71cd-4a54-a6e2-70f287597111",
  "eventType": "PAYMENT_CHARGEBACK",
  "occurredAt": "2026-07-08T11:35:00",
  "aggregateType": "ORDER",
  "aggregateId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
  "payload": {
    "orderId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111"
  }
}
```

Then:

```text
[ ] Consumer 예외가 발생하지 않는다.
[ ] Handler가 호출되지 않는다.
[ ] Order 상태가 변경되지 않는다.
[ ] OutboxEvent가 저장되지 않는다.
[ ] payment-events.DLT로 이동하지 않는다.
[ ] 로그만 남기고 정상 Ack 처리된다.
```

---

## 6.4 잘못된 payload 통합 플로우

### TC-INT-004. payload 매핑 실패는 DLT 대상이다

Given:

```text
eventType = PAYMENT_APPROVED
payload.orderId 누락
```

Then:

```text
[ ] PaymentEventRouter는 PAYMENT_APPROVED로 분기한다.
[ ] PaymentApprovedEventHandler에서 payload 변환 또는 검증이 실패한다.
[ ] Order 상태가 변경되지 않는다.
[ ] OutboxEvent가 저장되지 않는다.
[ ] processed_event가 저장되지 않는다.
[ ] payment-events.DLT로 이동한다.
```

---

## 6.5 중복 eventId 통합 플로우

### TC-INT-005. 동일 eventId 중복 수신 시 1회만 처리된다

Given:

```text
동일한 PAYMENT_APPROVED EventMessage 2회 발행
```

Then:

```text
[ ] 첫 번째 메시지는 정상 처리된다.
[ ] 두 번째 메시지는 processed_event 확인 후 종료된다.
[ ] OrderPayment는 1건만 저장된다.
[ ] ORDER_PAID OutboxEvent는 1건만 저장된다.
```

---

## 7. 수동 검증 체크리스트

PR 리뷰 또는 로컬 확인 시 아래 항목을 직접 확인한다.

```text
[ ] common 모듈에 EventMessage<T>가 존재한다.
[ ] EventMessage.eventType은 String이다.
[ ] EventMessage.payload 필드명이 유지되었다.
[ ] EventMessage에 aggregateType, aggregateId가 포함되었다.
[ ] EventMessage에 version 필드가 없다.
[ ] PaymentEventType은 common이 아니라 order-service 내부에 있다.
[ ] OrderEventType은 common이 아니라 order-service 내부에 있다.
[ ] PaymentEventConsumer는 EventMessage<JsonNode>로 수신한다.
[ ] PaymentEventConsumer는 payment-events만 구독한다.
[ ] PaymentEventConsumer 내부에 비즈니스 로직이 없다.
[ ] PaymentEventRouter가 eventType 분기를 담당한다.
[ ] 미지원 eventType은 로그만 남기고 return한다.
[ ] Handler에서 payload를 구체 타입으로 변환한다.
[ ] payload 매핑 실패는 DLT 대상 예외로 처리된다.
[ ] PAYMENT_FAILED는 상태 변경 없이 무시된다.
[ ] PAYMENT_CANCELED는 상태 변경 없이 무시된다.
[ ] ORDER_PAID OutboxEvent.topic은 order-events이다.
[ ] ORDER_REFUND OutboxEvent.topic은 order-events이다.
[ ] OutboxEvent.payload는 EventMessage 전체 JSON이다.
[ ] OutboxRelay는 aggregateId를 Kafka key로 사용한다.
[ ] Application Service에서 KafkaTemplate을 직접 호출하지 않는다.
[ ] REST Controller에서 EventMessage를 응답 DTO로 사용하지 않는다.
[ ] Domain Entity에서 EventMessage를 필드로 가지지 않는다.
```

---

## 8. 테스트 실행 명령어

### common 모듈 테스트

```bash
./gradlew :common:test
```

### order-service 테스트

```bash
./gradlew :order-service:test
```

### 전체 테스트

```bash
./gradlew clean test
```

### 특정 테스트 실행 예시

```bash
./gradlew :order-service:test --tests "*PaymentEventRouterTest"
./gradlew :order-service:test --tests "*PaymentApprovedEventHandlerTest"
./gradlew :order-service:test --tests "*OutboxEventAppenderTest"
./gradlew :order-service:test --tests "*OutboxRelayTest"
```

---

## 9. 완료 기준

아래 조건을 모두 만족하면 EventMessage 적용 작업이 정상 완료된 것으로 판단한다.

```text
[ ] EventMessage<T>가 common 모듈에 추가되었다.
[ ] order-service가 기존 도메인별 Envelope 대신 EventMessage<T>를 사용한다.
[ ] Consumer 수신 타입은 EventMessage<JsonNode>이다.
[ ] Producer/Outbox 생성 타입은 EventMessage<구체 Payload>이다.
[ ] eventType은 String으로 유지된다.
[ ] payload 필드명이 유지된다.
[ ] aggregateType은 ORDER, PAYMENT 등 표준 문자열을 사용한다.
[ ] aggregateId는 orderId 기준으로 저장된다.
[ ] payment-events, order-events 토픽명이 적용되었다.
[ ] payment.events, order.events, payment.approved, payment.refunded 참조가 제거되었다.
[ ] 미지원 eventType은 DLT로 이동하지 않는다.
[ ] payload 매핑 실패는 DLT로 이동한다.
[ ] 중복 eventId는 1회만 처리된다.
[ ] ORDER_PAID OutboxEvent가 EventMessage JSON으로 저장된다.
[ ] ORDER_REFUND OutboxEvent가 EventMessage JSON으로 저장된다.
[ ] OutboxRelay가 order-events로 발행한다.
[ ] 관련 단위 테스트가 통과한다.
[ ] 관련 통합 테스트가 통과하거나, 통합 테스트 미작성 사유가 PR에 명시되어 있다.
```

---

## 10. PR 테스트 계획 작성 예시

PR 본문에는 아래처럼 작성한다.

```text
## ✅ 테스트 계획

- [x] EventMessage 직렬화/역직렬화 테스트
- [x] PaymentEventType from(String) 변환 테스트
- [x] PaymentEventRouter 라우팅 테스트
- [x] 미지원 eventType 정상 소비 테스트
- [x] PAYMENT_APPROVED payload 매핑 테스트
- [x] PAYMENT_REFUNDED payload 매핑 테스트
- [x] 동일 eventId 중복 처리 방지 테스트
- [x] ORDER_PAID OutboxEvent 저장 테스트
- [x] ORDER_REFUND OutboxEvent 저장 테스트
- [x] OutboxRelay order-events 발행 테스트
- [x] payment-events/order-events 토픽명 적용 확인
- [x] ./gradlew :common:test
- [x] ./gradlew :order-service:test
```
