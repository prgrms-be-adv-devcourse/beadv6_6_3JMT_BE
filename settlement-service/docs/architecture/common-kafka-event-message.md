# 공통 Kafka Event Message 규칙

## 1. 목적

이 문서는 PromptHub 서비스 간 Kafka 이벤트 메시지의 공통 구조와 처리 규칙을 정의한다.

서비스별로 이벤트 Envelope를 개별 정의하면 이벤트 구조가 달라질 수 있고, Consumer 멱등성 처리, Outbox 저장, DLT 처리, 로그 추적 기준이 불명확해질 수 있다.

따라서 모든 Kafka 이벤트는 `common` 모듈에 정의된 `EventMessage<T>` 구조를 기준으로 발행하고 소비한다.

---

## 2. 적용 범위

이 문서는 내부 비동기 통신에 사용하는 Kafka Event에만 적용한다.

```text
외부 클라이언트 ↔ 백엔드 서비스: RESTful API
내부 서비스 ↔ 내부 서비스 동기 통신: gRPC
내부 서비스 ↔ 내부 서비스 비동기 통신: Kafka Event
```

적용 대상 예시는 다음과 같다.

```text
payment-service → order-service
order-service → settlement-service
order-service → product-service
```

---

## 3. Topic 네이밍 규칙

Kafka Topic은 도메인 단위 이벤트 파이프라인으로 구성한다.
토픽명은 하이픈 기반으로 통일한다.

```text
{domain}-events
```

예시:

```text
payment-events
order-events
product-events
settlement-events
```

이벤트 타입별로 토픽을 분리하지 않는다.

### 사용하지 않는 방식

```text
payment-approved
payment-refunded
payment-failed
payment-canceled
```

이벤트 타입별 토픽 분리는 Consumer 설정과 DLT 모니터링 대상을 증가시키므로 사용하지 않는다.

---

## 4. 공통 EventMessage 구조

`common` 모듈에 다음 record를 추가한다.

```java
package com.prompthub.common.event;

import java.time.LocalDateTime;
import java.util.UUID;

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

---

## 5. 필드 정의

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `eventId` | `UUID` | 필수 | 이벤트 고유 ID. Consumer 멱등성 처리 기준 |
| `eventType` | `String` | 필수 | 이벤트 타입. 예: `PAYMENT_APPROVED`, `ORDER_PAID` |
| `occurredAt` | `LocalDateTime` | 필수 | 이벤트가 실제 발생한 시각 |
| `aggregateType` | `String` | 필수 | 이벤트 대상 도메인. 예: `ORDER`, `PAYMENT`, `PRODUCT`, `SETTLEMENT` |
| `aggregateId` | `UUID` | 필수 | 이벤트 대상 식별자. 예: `orderId`, `paymentId`, `productId` |
| `payload` | `T` | 필수 | 이벤트별 상세 데이터 |

---

## 6. aggregateType 규칙

`aggregateType`은 대문자 문자열로 통일한다.

| 도메인 | aggregateType |
| --- | --- |
| 주문 | `ORDER` |
| 결제 | `PAYMENT` |
| 상품 | `PRODUCT` |
| 정산 | `SETTLEMENT` |
| 사용자 | `USER` |

예시:

```json
{
  "eventType": "ORDER_PAID",
  "aggregateType": "ORDER",
  "aggregateId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111"
}
```

---

## 7. eventType 처리 규칙

`EventMessage<T>`의 `eventType`은 enum이 아니라 `String`으로 유지한다.

### 이유

Kafka 단일 토픽 구조에서는 현재 서비스가 처리하지 않는 이벤트 타입도 들어올 수 있다.
이때 `eventType`을 enum으로 직접 역직렬화하면 Consumer 비즈니스 로직에 도달하기 전에 역직렬화 실패가 발생할 수 있다.
미지원 이벤트 타입은 장애가 아니므로 DLT로 보내지 않고 로그만 남긴 뒤 정상 소비한다.

---

## 8. EventType enum 위치

공통 모듈에는 모든 도메인의 enum을 두지 않는다.
공통 모듈에는 필요 시 인터페이스만 둔다.

```java
package com.prompthub.common.event;

public interface EventType {
    String code();
}
```

도메인별 enum은 각 서비스 내부에 정의한다.

### OrderEventType 예시

```java
public enum OrderEventType implements EventType {
    ORDER_PAID,
    ORDER_REFUND,
    ORDER_CANCELED,
    ORDER_FAILED;

    @Override
    public String code() {
        return name();
    }
}
```

### PaymentEventType 예시

```java
public enum PaymentEventType implements EventType {
    PAYMENT_APPROVED,
    PAYMENT_REFUNDED,
    PAYMENT_FAILED,
    PAYMENT_CANCELED;

    @Override
    public String code() {
        return name();
    }

    public static PaymentEventType from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (PaymentEventType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        return null;
    }
}
```

---

## 9. Producer 규칙

Producer는 도메인 enum을 사용해 이벤트 타입을 생성하되, `EventMessage`에는 문자열 값을 넣는다.

`EventMessage<T>`의 `payload`에 담는 이벤트 상세 DTO는 `~Event`로 명명한다.
Java 타입명과 관계없이 JSON 필드명은 `payload`를 유지한다.

```java
EventMessage<OrderPaidEvent> message = new EventMessage<>(
        UUID.randomUUID(),
        OrderEventType.ORDER_PAID.code(),
        LocalDateTime.now(),
        "ORDER",
        orderId,
        payload);
```

Producer는 Kafka 메시지 발행 시 `aggregateId`를 Kafka key로 사용한다.

```text
Kafka key = aggregateId
```

주문/결제 흐름에서는 같은 주문에 대한 이벤트 순서 보장을 위해 `orderId`를 key로 사용한다.

| 이벤트 | Kafka key |
| --- | --- |
| `PAYMENT_APPROVED` | `orderId` |
| `PAYMENT_REFUNDED` | `orderId` |
| `ORDER_PAID` | `orderId` |
| `ORDER_REFUND` | `orderId` |

---

## 10. Consumer 규칙

Consumer는 메시지를 바로 구체 payload 타입으로 받지 않는다.
우선 `EventMessage<JsonNode>`로 수신한 뒤, `eventType`을 확인하고 payload를 구체 타입으로 변환한다.

```java
EventMessage<JsonNode> message
```

처리 순서는 다음과 같다.

```text
1. EventMessage<JsonNode> 역직렬화
2. eventType 확인
3. 지원하는 eventType이면 payload 매핑
4. 미지원 eventType이면 로그 후 정상 종료
5. payload 매핑 실패 시 DLT 처리
6. 비즈니스 로직 처리
7. 처리 성공 후 processed_event 저장
```

예시:

```java
PaymentEventType eventType = PaymentEventType.from(message.eventType());

if (eventType == null) {
    log.warn("Unsupported payment event type. eventId={}, eventType={}",
            message.eventId(),
            message.eventType());
    return;
}

switch (eventType) {
    case PAYMENT_APPROVED -> approvedHandler.handle(message);
    case PAYMENT_REFUNDED -> refundedHandler.handle(message);
    case PAYMENT_FAILED, PAYMENT_CANCELED -> log.info(
            "Ignored payment event. eventId={}, eventType={}",
            message.eventId(),
            message.eventType()
    );
}
```

Consumer는 얇게 유지한다.
Consumer 내부에 주문 상태 변경, DB 저장, Outbox 저장 같은 비즈니스 로직을 직접 넣지 않는다.

권장 구조:

```text
PaymentEventConsumer
    → PaymentEventRouter
        → PaymentApprovedEventHandler
        → PaymentRefundedEventHandler
```

---

## 11. DLT 처리 규칙

DLT Topic은 원본 Topic 뒤에 `.DLT`를 붙인다.

예시:

```text
payment-events
payment-events.DLT
order-events
order-events.DLT
```

### DLT로 보내는 경우

```text
JSON 역직렬화 실패
필수 필드 누락
payload 매핑 실패
DB 처리 실패
비즈니스 처리 중 복구 불가능한 예외 발생
```

### DLT로 보내지 않는 경우

```text
알 수 없는 eventType
현재 서비스가 처리하지 않는 이벤트 타입
PAYMENT_FAILED처럼 현재 order-service 처리 대상이 아닌 이벤트
PAYMENT_CANCELED처럼 현재 order-service 처리 대상이 아닌 이벤트
```

미지원 이벤트 타입은 로그만 남기고 정상 Ack 처리한다.

---

## 12. 멱등성 규칙

Kafka Consumer는 반드시 멱등성을 보장해야 한다.
멱등성 기준은 다음과 같다.

```text
eventId + consumerGroup
```

처리 순서:

```text
1. eventId + consumerGroup 기준으로 이미 처리된 이벤트인지 확인한다.
2. 이미 처리된 이벤트라면 상태 변경과 Outbox 저장을 수행하지 않는다.
3. 처음 수신한 이벤트라면 비즈니스 로직을 수행한다.
4. 필요한 경우 도메인 상태를 변경한다.
5. 필요한 경우 OutboxEvent를 저장한다.
6. 처리 성공 후 processed_event를 저장한다.
```

금지 사항:

```text
eventId 확인 없이 주문 상태를 변경하는 코드
eventId 확인 없이 OutboxEvent를 저장하는 코드
중복 이벤트 수신 시 OrderPayment를 중복 저장하는 코드
```

---

## 13. SettlementOutboxEvent 매핑 규칙

Application Service는 KafkaTemplate을 직접 호출하지 않는다.
도메인 상태 변경과 SettlementOutboxEvent 저장을 같은 트랜잭션으로 처리하고, 실제 Kafka 발행은 OutboxRelay가 담당한다.

SettlementOutboxEvent 저장 기준:

| Outbox 필드 | EventMessage 필드 |
| --- | --- |
| `aggregateType` | `aggregateType` |
| `aggregateId` | `aggregateId` |
| `eventType` | `eventType` |
| `topic` | 발행 대상 Topic |
| `payload` | `EventMessage` 전체 JSON 또는 `payload` JSON |
| `occurredAt` | `occurredAt` |
| `status` | `PENDING` |

`ORDER_PAID`, `ORDER_REFUND` 이벤트 저장 예시:

| 필드 | 값 |
| --- | --- |
| `aggregateType` | `ORDER` |
| `aggregateId` | `orderId` |
| `eventType` | `ORDER_PAID` / `ORDER_REFUND` |
| `topic` | `order-events` |
| `status` | `PENDING` |
| `payload` | EventMessage JSON |

---

## 14. EventMessage JSON 예시

### PAYMENT_APPROVED

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
    "buyerId": "7c2f6e91-2c1b-4a3b-9f99-3f527f7d1234",
    "pgTxId": "pg_20260708113000",
    "paymentMethod": "CARD",
    "provider": "TEST_PG",
    "approvedAmount": 30000,
    "approvedAt": "2026-07-08T11:30:00"
  }
}
```

### ORDER_PAID

```json
{
  "eventId": "f3bdb7f2-ec60-4c77-aab7-57d8b4d84e9a",
  "eventType": "ORDER_PAID",
  "occurredAt": "2026-07-08T11:30:05",
  "aggregateType": "ORDER",
  "aggregateId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
  "payload": {
    "orderId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
    "buyerId": "7c2f6e91-2c1b-4a3b-9f99-3f527f7d1234",
    "totalOrderAmount": 30000,
    "totalProductCount": 2,
    "paidAt": "2026-07-08T11:30:05",
    "products": [
      {
        "orderProductId": "op1c2a7e-4b8d-4e2a-9c11-2d3e4f5a2222",
        "productId": "p1b55b60-5e84-4f3f-b4f1-6c10e1a22222",
        "sellerId": "s1b55b60-5e84-4f3f-b4f1-6c10e1a33333",
        "productTitle": "면접 답변 프롬프트",
        "productType": "PROMPT",
        "productAmount": 15000
      }
    ]
  }
}
```

---

## 15. version 필드 정책

현재 `EventMessage<T>`에는 `version` 또는 `eventVersion` 필드를 추가하지 않는다.
이유는 현재 단계에서 이벤트 버전별 payload 분기 요구사항이 없기 때문이다.

추후 이벤트 스키마 호환성 관리가 필요해질 경우 아래 필드를 추가하는 방향으로 확장한다.

```java
Integer eventVersion
```

`int` 대신 `Integer`를 사용하는 이유는 기존 메시지와의 하위 호환성을 확보하기 위함이다.

---

## 16. 구현 체크리스트

```text
[ ] common 모듈에 EventMessage<T>를 추가했는가?
[ ] eventType을 String으로 유지했는가?
[ ] payload 필드명을 유지했는가?
[ ] aggregateType, aggregateId를 포함했는가?
[ ] 도메인별 EventType enum을 common이 아니라 각 서비스 내부에 두었는가?
[ ] Producer는 enum.code()로 eventType 문자열을 생성하는가?
[ ] Consumer는 EventMessage<JsonNode>로 수신하는가?
[ ] Consumer는 eventType 확인 후 payload를 구체 타입으로 매핑하는가?
[ ] 미지원 eventType을 DLT로 보내지 않는가?
[ ] payload 매핑 실패는 DLT 처리하는가?
[ ] Consumer 멱등성 기준으로 eventId + consumerGroup을 사용하는가?
[ ] Kafka key는 aggregateId 기준으로 사용하는가?
[ ] payment-events, order-events 형식의 하이픈 기반 토픽명을 사용하는가?
[ ] Application Service에서 KafkaTemplate을 직접 호출하지 않는가?
[ ] OutboxRelay가 Kafka 발행을 담당하는가?
```

---

## 17. 테스트 기준

### EventMessage 테스트

```text
[ ] EventMessage 직렬화 시 eventType이 문자열로 출력된다.
[ ] EventMessage 역직렬화 시 payload를 JsonNode로 받을 수 있다.
[ ] 필수 필드 누락 시 처리 실패 대상이 된다.
```

### Consumer 테스트

```text
[ ] 지원하는 eventType이면 적절한 Handler로 라우팅된다.
[ ] 미지원 eventType이면 로그만 남기고 정상 종료한다.
[ ] payload 매핑 실패 시 DLT 처리된다.
[ ] 동일 eventId 중복 수신 시 비즈니스 로직이 다시 수행되지 않는다.
```

### Outbox 테스트

```text
[ ] SettlementOutboxEvent 저장 시 aggregateType이 정상 저장된다.
[ ] SettlementOutboxEvent 저장 시 aggregateId가 정상 저장된다.
[ ] SettlementOutboxEvent 저장 시 eventType이 정상 저장된다.
[ ] SettlementOutboxEvent 저장 시 topic이 하이픈 기반 토픽명으로 저장된다.
[ ] SettlementOutboxEvent 저장 시 payload가 JSON 문자열로 직렬화된다.
[ ] OutboxRelay 발행 성공 시 상태가 PUBLISHED로 변경된다.
[ ] OutboxRelay 발행 실패 시 retryCount가 증가한다.
[ ] 최대 재시도 횟수 초과 시 상태가 FAILED로 변경된다.
```
