# 환불 API 구현 결과

`02-refund-api.md` 계획 대비 실제 구현 중 달라진 결정과 발견된 이슈를 기록한다.
설계 의도·트레이드오프는 02번 파일을 참조한다.

---

## 계획 대비 변경 결정

### 1. `@Transactional(REQUIRES_NEW)` AOP → `TransactionTemplate`

**원래 계획**: `RefundEventHandler.onRefundRequested()`에 `@Transactional(propagation = REQUIRES_NEW)` AOP 적용  
**실제 구현**: `TransactionTemplate` 수동 제어. `execute()` 반환 직후 `KafkaPaymentEventPublisher.publishRefunded()` 직접 호출

**이유**: Spring Boot 4.1(Spring Framework 7) 제한 — 자세한 원인은 아래 이슈 섹션 참조.  
`execute()` 반환 = 커밋 완료이므로, 반환 직후 Kafka를 직접 발행하는 방식으로 전환.

---

### 2. `PaymentRefundedEvent` 이벤트 연쇄 → `publishRefunded()` 직접 호출

**원래 계획**: `RefundEventHandler` → `applicationEventPublisher.publishEvent(PaymentRefundedEvent)` → `KafkaPaymentEventPublisher.onPaymentRefunded()` 연쇄  
**실제 구현**: `RefundEventHandler`가 `TransactionTemplate.execute()` 완료 후 `KafkaPaymentEventPublisher.publishRefunded(Payment, Refund)` 직접 호출

**이유**: Spring Boot 4.1에서 `@TransactionalEventListener(AFTER_COMMIT)` 내부에서 발행한 Spring 내부 이벤트가 다시 `@TransactionalEventListener`를 트리거하지 않는다(중첩 이벤트 연쇄 제한).  
`PaymentRefundedEvent` 파일은 생성됐지만 사용하지 않는다.

---

### 3. 스케줄러: `applicationEventPublisher.publishEvent()` → `TransactionSynchronizationManager.registerSynchronization().afterCommit()`

**원래 계획**: `PaymentRefundRetryScheduler`에서 성공 시 `applicationEventPublisher.publishEvent(new PaymentRefundedEvent(payment, refund))` 호출  
**실제 구현**: `TransactionSynchronizationManager.registerSynchronization().afterCommit()` 내에서 `kafkaPaymentEventPublisher.publishRefunded()` 직접 호출

**이유**: `PaymentRefundedEvent` 이벤트 연쇄가 제거됨에 따라(변경 결정 2번), 스케줄러도 직접 호출 방식으로 변경.  
스케줄러는 일반 `@Transactional` 컨텍스트에서 실행되므로 `isSynchronizationActive() = true`이고 `afterCommit()`이 정상 호출된다(`@TransactionalEventListener(AFTER_COMMIT)` 내부와 달리 중첩 트랜잭션 문제가 없음).

---

## 구현 중 발견된 이슈

### 1. Spring Boot 4.1 — `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` 제한

**증상**: `RefundEventHandler.onRefundRequested()`가 호출되고 PG 환불까지 성공하지만 DB에 변경이 저장되지 않고 Kafka 메시지도 발행되지 않는다.

**근본 원인**: Spring Framework 7 소스 추적 결과:

```
AFTER_COMMIT 콜백 실행 시점:
  isSynchronizationActive() = true  ← 외부 트랜잭션 동기화가 아직 활성

REQUIRES_NEW 트랜잭션 시작 시:
  actualNewSynchronization
    = newSynchronization && !isSynchronizationActive()
    = true && !true
    = false

triggerAfterCommit() 조건:
  if (actualNewSynchronization) → 실행 안 됨
```

`@Transactional(REQUIRES_NEW)` AOP로 시작한 새 트랜잭션은 `actualNewSynchronization = false`로 설정되어, 해당 트랜잭션 커밋 후 `registerSynchronization().afterCommit()`이 호출되지 않는다.

**해결책**: `TransactionTemplate.execute()`로 수동 트랜잭션 제어. `execute()` 반환 시점 = 커밋 완료 시점이므로, 반환 직후 Kafka 직접 발행.

| 컨텍스트 | 패턴 | 이유 |
|---|---|---|
| `@TransactionalEventListener(AFTER_COMMIT)` 내부 | `TransactionTemplate` | `@Transactional(REQUIRES_NEW)` AOP는 `actualNewSynchronization=false`로 동작 불가 |
| 일반 `@Transactional` 메서드 내부 (스케줄러) | `registerSynchronization().afterCommit()` | 외부 트랜잭션이 활성이므로 정상 동작 |

---

### 2. Kafka Consumer `seekToBeginning()` 메타데이터 미초기화

**증상**: 통합 테스트에서 `seekToBeginning()` 후 발행된 메시지를 간헐적으로 수신하지 못한다.

**원인**: `seekToBeginning()`은 lazy — 실제 `poll()` 시점에 offset 이동이 적용된다. `assign()` 직후 바로 호출하면 파티션 메타데이터가 초기화되기 전이라 seek이 의도대로 동작하지 않는다.

**해결책**: `seekToBeginning()` 전에 `consumer.poll(Duration.ZERO)`로 메타데이터 강제 초기화.

```java
consumer.assign(List.of(partition));
consumer.poll(Duration.ZERO);          // 메타데이터 초기화 — 이 줄이 핵심
consumer.seekToBeginning(List.of(partition));
```

---

### 3. `KafkaTestUtils.getSingleRecord()` — 다중 메시지 실패

**증상**: `IllegalStateException: Got more than one record` 로 테스트 실패.

**원인**: 동일 토픽(`payment.refunded`)을 사용하는 다른 테스트가 먼저 실행되면 메시지가 2개 이상 존재. `getSingleRecord()`는 정확히 1개일 때만 통과.

**해결책**: `getSingleRecord()` 대신 30초 폴링 루프로 `orderId` key 기준 메시지 탐색.

```java
long deadline = System.currentTimeMillis() + 30_000;
boolean found = false;
while (!found && System.currentTimeMillis() < deadline) {
    var polled = consumer.poll(Duration.ofMillis(500));
    for (var r : polled) {
        if (orderId.toString().equals(r.key())) { found = true; break; }
    }
}
assertThat(found).withFailMessage("30초 내 payment.refunded Kafka 메시지 수신 실패").isTrue();
```

---

## 검증 완료 항목

| 테스트 | 방식 | 결과 |
|---|---|---|
| `RefundPaymentInteractorTest` | Mockito 단위 | ✅ |
| `RefundPaymentIntegrationTest` | Testcontainers PostgreSQL + Kafka | ✅ |
