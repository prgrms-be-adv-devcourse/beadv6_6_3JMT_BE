# 구현 계획: 이벤트 발행 토픽 단일화 (`payment.events`)

> 참조: `.claude/docs/final/unify-payment-topic.md`

## 목표

`payment.approved`, `payment.refunded` 두 토픽을 `payment.events` 단일 토픽으로 통합한다.
페이로드 스키마(`PaymentApprovedMessage`, `PaymentRefundedMessage`)와 `eventType` 값은 변경하지 않는다.

---

## 변경 대상 (7개 파일)

### payment-service

| # | 파일 | 변경 요약 |
|---|---|---|
| 1 | `infrastructure/messaging/config/PaymentTopic.java` | 상수 2개 → `PAYMENT_EVENTS` 1개 |
| 2 | `infrastructure/messaging/config/KafkaConfig.java` | `NewTopic` 빈 2개 → 1개 |
| 3 | `infrastructure/messaging/KafkaPaymentEventPublisher.java` | `send()` 토픽 인수 2곳 교체 |
| 4 | `src/test/.../ConfirmPaymentIntegrationTest.java` | `TopicPartition` 생성 시 상수 교체 |
| 5 | `src/test/.../RefundPaymentIntegrationTest.java` | `TopicPartition` 생성 시 상수 교체 |
| 6 | `.claude/docs/events.md` | 발행 토픽 테이블 갱신 |

### 모노레포 공유 문서

| # | 파일 | 변경 요약 |
|---|---|---|
| 7 | `docs/api-spec/payment.md` | Kafka 이벤트 섹션 갱신 |

---

## 파일별 구체적 변경 내용

### 1. `PaymentTopic.java`

```java
// 변경 전
public static final String PAYMENT_APPROVED = "payment.approved";
public static final String PAYMENT_REFUNDED = "payment.refunded";

// 변경 후
public static final String PAYMENT_EVENTS = "payment.events";
```

### 2. `KafkaConfig.java`

```java
// 변경 전 — 빈 2개
@Bean
public NewTopic paymentApprovedTopic() {
    return TopicBuilder.name(PaymentTopic.PAYMENT_APPROVED).partitions(1).replicas(1).build();
}

@Bean
public NewTopic paymentRefundedTopic() {
    return TopicBuilder.name(PaymentTopic.PAYMENT_REFUNDED).partitions(1).replicas(1).build();
}

// 변경 후 — 빈 1개
@Bean
public NewTopic paymentEventsTopic() {
    return TopicBuilder.name(PaymentTopic.PAYMENT_EVENTS).partitions(1).replicas(1).build();
}
```

### 3. `KafkaPaymentEventPublisher.java`

```java
// onPaymentApproved() — 토픽 인수만 교체
kafkaTemplate.send(PaymentTopic.PAYMENT_EVENTS, payment.getOrderId().toString(), message);

// publishRefunded() — 토픽 인수만 교체
kafkaTemplate.send(PaymentTopic.PAYMENT_EVENTS, payment.getOrderId().toString(), message);
```

### 4. `ConfirmPaymentIntegrationTest.java`

```java
// 변경 전 (line 78)
TopicPartition partition = new TopicPartition(PaymentTopic.PAYMENT_APPROVED, 0);

// 변경 후
TopicPartition partition = new TopicPartition(PaymentTopic.PAYMENT_EVENTS, 0);
```

```java
// 변경 전 (line 123 — withFailMessage)
assertThat(found).withFailMessage("10초 내 payment.approved Kafka 메시지 수신 실패").isTrue();

// 변경 후
assertThat(found).withFailMessage("10초 내 payment.events Kafka 메시지 수신 실패").isTrue();
```

### 5. `RefundPaymentIntegrationTest.java`

```java
// 변경 전 (line 97)
TopicPartition partition = new TopicPartition(PaymentTopic.PAYMENT_REFUNDED, 0);

// 변경 후
TopicPartition partition = new TopicPartition(PaymentTopic.PAYMENT_EVENTS, 0);
```

```java
// 변경 전 (line 129 — withFailMessage)
assertThat(found).withFailMessage("10초 내 payment.refunded Kafka 메시지 수신 실패").isTrue();

// 변경 후
assertThat(found).withFailMessage("10초 내 payment.events Kafka 메시지 수신 실패").isTrue();
```

### 6. `.claude/docs/events.md`

발행 토픽 테이블을 단일 토픽으로 갱신한다.

```markdown
// 변경 전
| `payment.approved` | Toss confirm 성공 | Order | Order PAID 전환 + `is_download = true` |
| `payment.refunded` | PG 환불 성공     | Order | Order REFUNDED 전환 + `is_download = false` |

// 변경 후
| `payment.events` | Toss confirm 성공 또는 PG 환불 성공 | Order | `eventType` 필드로 분기 처리 |
```

페이로드 스키마 섹션 헤더도 `payment.approved` / `payment.refunded` 유지하되, 발행 토픽 주석을 `payment.events`로 수정한다.

### 7. `docs/api-spec/payment.md`

Kafka 이벤트 섹션(line 122~181)을 `payment.events` 단일 토픽 기준으로 갱신한다.

- 이벤트 토픽 테이블: 2행 → 1행(`payment.events`)
- 각 이벤트 페이로드 소제목의 토픽 표기 수정 (`payment.events`로 발행됨 명시)
- `eventType` 값(`payment.approved`, `payment.refunded`)은 변경 없음 명시

---

## 트레이드오프

### 얻는 것

| 항목 | 내용 |
|---|---|
| 토픽 관리 단순화 | 결제 도메인 이벤트 채널이 1개로 줄어 운영·모니터링 대상 감소 |
| DLT 단순화 | `payment.approved.DLT`, `payment.refunded.DLT` 2개 → `payment.events.DLT` 1개 |
| 미래 이벤트 타입 확장 | 새 이벤트 타입 추가 시 토픽 신설 불필요 — `PaymentTopic.java`와 `KafkaConfig.java` 변경 없이 발행 가능 |

### 잃는 것 / 주의할 것

| 항목 | 내용 | 현재 영향도 |
|---|---|---|
| 구독자 필터링 비용 | order-service는 모든 메시지를 수신한 뒤 `eventType`으로 분기. 토픽별 구독 시 불필요했던 역직렬화·분기 처리가 추가됨. | 낮음 — 현재 이벤트 타입 2종, 구독자 1개 |
| 단일 토픽에 복수 스키마 혼재 | `PaymentApprovedMessage`와 `PaymentRefundedMessage`가 동일 토픽으로 흐름. 현재 JSON + `eventType` 분기이므로 문제 없으나, 향후 Schema Registry 도입 시 union schema 처리 복잡도 증가. | 낮음 — Schema Registry 미도입 |
| `eventType` 없는 컨슈머의 타입 안전성 손실 | 토픽만 보고 메시지 타입을 가정하던 컨슈머는 `eventType` 검사를 추가해야 함. order-service `PaymentEventConsumer`는 이미 `eventType` 기반 분기를 구현하고 있어 영향 없음. | 없음 |

---

## 사이드 이펙트 (코드 변경 없음)

| 항목 | 내용 |
|---|---|
| DLT 토픽명 | `payment.approved.DLT`, `payment.refunded.DLT` → `payment.events.DLT` 하나로 통합. `DeadLetterPublishingRecoverer`가 `record.topic() + ".DLT"` 방식이므로 자동 반영. |
| 모니터링 | DLT 알럿 설정이 있다면 토픽명 변경 인지 필요. |

---

## 실행 순서

> order-service 구독자 전환이 선행된다고 가정한 후 실행한다.

1. `PaymentTopic.java` 상수 교체
2. `KafkaConfig.java` 빈 교체
3. `KafkaPaymentEventPublisher.java` 토픽 인수 교체
4. `ConfirmPaymentIntegrationTest.java` 상수 및 메시지 교체
5. `RefundPaymentIntegrationTest.java` 상수 및 메시지 교체
6. `./gradlew test` 실행 — 통합 테스트 포함 전체 통과 확인
7. `.claude/docs/events.md` 갱신
8. `docs/api-spec/payment.md` 갱신

---

## 테스트 확인 포인트

| 테스트 | 확인 내용 |
|---|---|
| `ConfirmPaymentIntegrationTest` | `payment.events` 토픽에서 `eventType = "payment.approved"` 메시지 수신 |
| `RefundPaymentIntegrationTest#환불_정상_플로우` | `payment.events` 토픽에서 `eventType = "payment.refunded"` 메시지 수신 |
| `RefundPaymentIntegrationTest#PG_환불_실패_시_PAID_복원` | 토픽 변경 영향 없음 — DB 상태만 검증 |
| `RefundPaymentIntegrationTest#타인_결제_환불_시_403` | 토픽 변경 영향 없음 — HTTP 응답만 검증 |
