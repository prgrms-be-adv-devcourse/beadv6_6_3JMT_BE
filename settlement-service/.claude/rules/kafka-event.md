# Kafka 이벤트 컨벤션

서비스 간 **내부 비동기 통신(Kafka 이벤트)** 의 메시지 구조·네이밍·발행/소비 규칙을 정의한다.
팀 공통 규칙이며, 전문(배경·JSON 예시·체크리스트)은 `docs/architecture/common-kafka-event-message.md` 를 본다.

> 관련 문서
> - 계층·패키지(발행/소비 어댑터 위치): `clean-architecture.md` (§2 messaging, §4 포트&어댑터)
> - 예외 처리(발행 실패 → `SettlementException`): `controller-exception.md`
> - 현재 통신 상태(무엇이 구현/비활성/대기): `docs/architecture/settlement-internal-comm-topology.md`

## 1. 통신 방식 구분

| 통신 | 방식 |
| --- | --- |
| 외부 클라이언트 ↔ 백엔드 | REST |
| 내부 서비스 ↔ 내부 서비스 **동기** | gRPC |
| 내부 서비스 ↔ 내부 서비스 **비동기** | **Kafka 이벤트 (이 문서)** |

비동기 이벤트는 아래 규칙을 예외 없이 따른다. 개별 서비스가 자체 envelope 를 새로 정의하지 않는다.

## 2. 공통 래퍼 — `EventMessage<T>`

모든 Kafka 이벤트는 common-module 의 `EventMessage<T>` 로 발행·소비한다.

```java
package com.prompthub.common.event;

public record EventMessage<T>(
        UUID eventId,          // 이벤트 고유 ID — Consumer 멱등성 기준
        String eventType,      // UPPER_SNAKE 문자열 (enum 아님, §4)
        LocalDateTime occurredAt,
        String aggregateType,  // 대상 도메인 UPPER (§3)
        UUID aggregateId,      // 대상 식별자 — Kafka key 로 사용
        T payload              // 이벤트별 상세 (~Payload, §5)
) {}
```

- **개별 Envelope 타입을 새로 만들지 않는다.** (예외: 타 서비스가 이미 자기 포맷으로 발행하는 이벤트를
  소비만 하는 경우, 그 발행처 계약에 맞춘 수신 DTO 는 `application/event` 에 둘 수 있다.)
- `version`/`eventVersion` 필드는 현재 두지 않는다. 스키마 호환 관리가 필요해지면 `Integer eventVersion` 으로 확장한다.

## 3. Topic · aggregateType 네이밍

- **토픽: `{domain}-events`** (하이픈). 이벤트 타입별로 토픽을 쪼개지 않는다.
  예: `settlement-events`, `order-events`, `payment-events`. 토픽명은 yml 프로퍼티로 주입한다.
- **DLT 토픽: `{topic}.DLT`** (예: `order-events.DLT`).
- **`aggregateType`: 대문자 도메인명.** `ORDER` · `PAYMENT` · `PRODUCT` · `SETTLEMENT` · `USER`.
- **Kafka key = `aggregateId`.** 같은 애그리거트 이벤트의 순서를 보장한다(예: `orderId`, `settlementId`).

## 4. eventType 규칙

- `eventType` 은 `EventMessage` 상에서 **`String`** 으로 유지한다(enum 직접 역직렬화 금지 — 단일 토픽에
  미지원 타입이 섞여 들어와도 역직렬화 단계에서 깨지지 않게).
- 값은 **UPPER_SNAKE** (예: `SETTLEMENT_CREATED`, `ORDER_PAID`).
- **도메인별 `EventType` enum 은 common 이 아니라 각 서비스 `application/event` 에 둔다.** common 에는
  인터페이스 `EventType { String code(); }` 만 둔다. 도메인 enum 은 이를 `implements` 하고 `code()` 는 `name()` 을 반환한다.

```java
public enum SettlementEventType implements EventType {
    SETTLEMENT_CREATED;
    @Override public String code() { return name(); }
}
```

## 5. payload 규칙

- payload DTO 는 **`~Payload`** 로 명명하고 `application/event` 에 둔다. (예: `SettlementCreatedPayload`)
- 변환은 정적 팩토리(`from(...)`)에 둔다. (예: `SettlementCreatedPayload.from(settlement)`)
- 소비측이 발행측 payload 를 미러링할 때 **필드명을 그대로 유지한다**(직렬화 계약).

## 6. Producer 규칙

- **발행 어댑터는 `infrastructure/messaging/kafka/producer`, 아웃바운드 포트는 `application/port`**
  (예: `SettlementEventPublisher` ← `KafkaSettlementEventPublisher`). Application Service 는
  `KafkaTemplate` 을 직접 호출하지 않고 **포트를 통해** 발행한다.
- `EventMessage` 생성 시 eventType 은 `도메인enum.code()` 로 문자열을 넣는다.
- 발행 시점은 도메인 상태 변경 트랜잭션 **커밋 후(AFTER_COMMIT)** 를 기본으로 한다.
- 발행 실패 정책: 비동기 전송 실패는 로깅(at-most-once), 동기 직렬화/설정 실패는
  `SettlementException(ErrorCode)` 으로 던진다(`controller-exception.md`).

## 7. Consumer 규칙

- **수신 어댑터는 `infrastructure/messaging/kafka/consumer/<발행처>`** (예: `consumer/order`).
- **바로 구체 payload 로 받지 않는다.** `EventMessage<JsonNode>`(또는 `readTree`)로 수신 → `eventType`
  확인 → 지원 타입이면 payload 를 구체 타입으로 매핑 → **usecase 호출**. 컨슈머는 얇게 유지하고
  비즈니스 로직(상태 변경·저장)을 직접 넣지 않는다.
- **미지원 eventType 은 DLT 로 보내지 않는다.** 로그만 남기고 정상 Ack 한다.
- **DLT 대상**은 역직렬화 실패·필수 필드 누락·payload 매핑 실패·복구 불가 처리 예외다.
- **멱등성 기준: `eventId + consumerGroup`.** 이미 처리한 이벤트는 재처리(상태 변경·Outbox 저장)하지 않는다.

## 8. Outbox (도입 시)

- Application Service 는 도메인 상태 변경과 OutboxEvent 저장을 **한 트랜잭션**으로 처리하고, 실제 Kafka
  발행은 `OutboxRelay`(발행 어댑터, `infrastructure/messaging/kafka/producer`)가 담당한다.
- Outbox 는 `aggregateType`·`aggregateId`·`eventType`·`topic`·`payload`(EventMessage JSON)·`occurredAt`·
  `status(PENDING)` 를 저장한다.
