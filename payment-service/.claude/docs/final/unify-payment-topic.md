# 작업 계획: 이벤트 발행 토픽 단일화 (`payment.events`)

> **2026-07-05 조정 (00-execution-order.md D4)**: 실행 순서 **1순위 선행**. 원안에 빠져 있던 order-service 컨슈머 전환을 범위에 추가하고, "구독자 먼저 전환" 순서로 진행한다.

## 결정 배경

| 항목 | 결정 |
|---|---|
| 동기 | 설계 원칙 — "결제 도메인 이벤트는 하나의 채널로" |
| 범위 | 결제 도메인의 모든 이벤트 (현재 + 미래 이벤트 타입 포함) |
| 구독자 협의 | Order 서비스 팀과 협의 완료 |
| 신규 토픽명 | `payment.events` |
| 페이로드 스키마 | 현행 유지 (`PaymentApprovedMessage`, `PaymentRefundedMessage` 별도 record 유지) |
| `eventType` 값 | 변경 없음 (`"payment.approved"`, `"payment.refunded"` 그대로) |
| 기존 토픽 | 삭제 — `KafkaConfig` 빈 제거, 기존 Kafka 토픽 정리 |

---

## 변경 파일 목록

### 1. `PaymentTopic.java`

**변경 전**
```java
public class PaymentTopic {
    public static final String PAYMENT_APPROVED = "payment.approved";
    public static final String PAYMENT_REFUNDED = "payment.refunded";
}
```

**변경 후**
```java
public class PaymentTopic {
    public static final String PAYMENT_EVENTS = "payment.events";
}
```

---

### 2. `KafkaConfig.java`

**변경 전** — 토픽 빈 2개

```java
@Bean
public NewTopic paymentApprovedTopic() {
    return TopicBuilder.name(PaymentTopic.PAYMENT_APPROVED).partitions(1).replicas(1).build();
}

@Bean
public NewTopic paymentRefundedTopic() {
    return TopicBuilder.name(PaymentTopic.PAYMENT_REFUNDED).partitions(1).replicas(1).build();
}
```

**변경 후** — 토픽 빈 1개

```java
@Bean
public NewTopic paymentEventsTopic() {
    return TopicBuilder.name(PaymentTopic.PAYMENT_EVENTS).partitions(1).replicas(1).build();
}
```

---

### 3. `KafkaPaymentEventPublisher.java`

`kafkaTemplate.send()` 호출 2곳의 토픽 인수 변경.

| 메서드 | 변경 전 | 변경 후 |
|---|---|---|
| `onPaymentApproved()` | `PaymentTopic.PAYMENT_APPROVED` | `PaymentTopic.PAYMENT_EVENTS` |
| `publishRefunded()` | `PaymentTopic.PAYMENT_REFUNDED` | `PaymentTopic.PAYMENT_EVENTS` |

---

### 4. `.claude/docs/events.md` (문서)

"발행 토픽" 테이블을 단일 토픽 `payment.events`로 갱신.

---

### 5. order-service 변경 (범위 추가)

> 구독자 전환 없이 발행만 바꾸면 order-service가 이벤트를 받지 못한다. 코드 확인 결과 `PaymentEventConsumer`는 `payment.approved`/`payment.refunded` 토픽을 직접 구독하고 DLT도 토픽명에서 파생된다.

- `PaymentEventConsumer` — 구독 토픽을 2개(`payment.approved`, `payment.refunded`)에서 `payment.events` 1개로 교체. eventType 분기(`PaymentEventType`)는 기존 로직 그대로 동작.
- DLT — `DeadLetterPublishingRecoverer`가 원본 토픽명에서 파생하므로 실패 메시지는 `payment.events.DLT`로 이동. 코드 변경 없음, 모니터링 대상 토픽명 변경만 인지.
- 통합 테스트 — `PaymentEventConsumerIntegrationTest` 등 토픽명 참조를 `payment.events`로 교체.

---

## 전환 순서 (구독자 먼저)

1. order-service: `payment.events` 구독으로 전환 — 전환 전 구토픽의 잔여 메시지가 모두 소비됐는지 확인
2. payment-service: 발행 토픽 전환 + 토픽 빈 교체
3. 기존 Kafka 토픽(`payment.approved`, `payment.refunded`) 정리

---

## 테스트 확인 포인트

- `ConfirmPaymentIntegrationTest` — Kafka Consumer가 `payment.events` 토픽에서 메시지 수신 + `eventType = "payment.approved"` 검증
- `RefundPaymentIntegrationTest` — `payment.events` 토픽에서 `eventType = "payment.refunded"` 검증
- 테스트 내 토픽 상수 참조가 있다면 `PAYMENT_EVENTS`로 교체

---

## 인지하고 가야 할 트레이드오프

| 항목 | 내용 |
|---|---|
| 구독자 필터링 비용 | Order 서비스는 모든 메시지를 소비 후 `eventType`으로 분기. 현재 2종류이므로 무시할 수준 |
| 미래 확장 시 주의 | 구독자가 다양해지고 관심 이벤트 타입이 달라지면 fan-out 패턴 재검토 필요 |
| Schema Registry 미사용 | 현재 JSON + 별도 record 구조이므로 단일 토픽에 복수 스키마 혼재 문제 없음 |

---

## 작업 순서

1. order-service: `PaymentEventConsumer` 구독 토픽 전환 + 관련 테스트 교체
2. `PaymentTopic.java` 상수 교체
3. `KafkaConfig.java` 빈 교체
4. `KafkaPaymentEventPublisher.java` 토픽 인수 교체
5. 테스트 코드 토픽 참조 확인 및 교체
6. `events.md` 문서 갱신 (order-service 구독 정보 포함)
7. 양 서비스 테스트 실행 확인
