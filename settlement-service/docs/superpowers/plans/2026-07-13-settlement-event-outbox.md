# Settlement Event Transactional Outbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `SETTLEMENT_CREATED`를 정산 생성 트랜잭션에 Outbox로 적재하고 CronJob 배치 Step에서 at-least-once 발행하며, 3회 실패 이벤트를 별도 redrive Job으로 재처리한다.

**Architecture:** 정산 계산 서비스는 `OutboxEventAppender` 포트를 호출해 `Settlement`, SourceLine 처리, Outbox 저장을 한 트랜잭션으로 묶는다. 일반 정산 Job은 시작 시 과거 `PENDING`, 종료 시 현재 `settlementBatchId`의 `PENDING`을 flush한다. `outboxRedriveJob`은 지정한 `FAILED` 이벤트만 복구하고, 이벤트별 발행과 상태 변경은 `REQUIRES_NEW`로 격리한다.

**Tech Stack:** Java 21, Spring Boot, Spring Batch 6, Spring Data JPA, PostgreSQL/H2, Spring Kafka, Jackson 3 (`tools.jackson`), JUnit 5, AssertJ, Mockito

## Global Constraints

- 작업 범위는 `settlement-service/`로 제한한다.
- 이벤트 단위는 `Settlement` 1건당 `SETTLEMENT_CREATED` 1건이다.
- Outbox 상태는 `PENDING`, `PUBLISHED`, `FAILED`이며 최대 실패 횟수는 3이다.
- 최초 발행과 재발행은 저장된 동일 `eventId`와 JSON을 사용한다.
- Outbox relay는 `@Scheduled`가 아니라 Spring Batch Step이다.
- Kubernetes manifest, 배포 파이프라인, 배치 전용 JVM 종료 runner는 변경하지 않는다.
- 운영 DB용 실행 가능 DDL 문서를 제공한다.
- 기존 미추적 `settlement-service/.codex/`, `settlement-service/AGENTS.md`를 수정하거나 stage하지 않는다.
- 모든 기능 변경은 RED → GREEN 순서로 구현한다.

---

## File Map

### Create

- `domain/model/enums/OutboxEventStatus.java`: Outbox 상태
- `domain/model/OutboxEvent.java`: 엔티티와 상태 전이
- `domain/exception/OutboxEventInvalidStateException.java`: 잘못된 상태 전이
- `domain/repository/OutboxEventRepository.java`: 영속 포트와 후보 record
- `application/port/OutboxEventAppender.java`: 정산 트랜잭션 내 적재 포트
- `application/usecase/OutboxEventUseCase.java`: flush/redrive 인바운드 포트
- `application/service/OutboxApplicationService.java`: cursor 순회
- `application/service/OutboxEventPublishService.java`: 이벤트별 독립 발행
- `infrastructure/persistence/outbox/*`: JSON 적재와 JPA 어댑터
- `infrastructure/batch/tasklet/*OutboxTasklet.java`: 시작/마지막/redrive Tasklet
- `infrastructure/batch/config/OutboxRedriveJobConfig.java`: redrive Job
- `docs/sql/301-settlement-outbox.sql`: PostgreSQL DDL
- 각 클래스의 대응 단위·통합 테스트

### Modify/Delete

- `application/service/SettlementCalculationApplicationService.java`: AFTER_COMMIT 대신 Outbox
- `application/port/SettlementEventPublisher.java`: raw JSON 발행 계약
- `infrastructure/messaging/kafka/config/KafkaConfig.java`: String producer/template
- `infrastructure/messaging/kafka/producer/KafkaSettlementEventPublisher.java`: broker ack 동기 확인
- `infrastructure/messaging/kafka/producer/SettlementCreatedEventListener.java`: 삭제
- `infrastructure/batch/config/SettlementStepConfig.java`: Outbox Step
- `infrastructure/batch/config/SettlementJobConfig.java`: Step 순서
- `infrastructure/batch/launcher/SettlementJobLauncherAdapter.java`: 두 Job 중 `settlementJob` qualifier 명시
- `infrastructure/batch/listener/SettlementBatchFailureListener.java`: 완료 배치 보호
- `domain/model/SettlementBatch.java`: `isProcessing()`
- `global/exception/SettlementErrorCode.java`: Outbox 오류 코드

> 아래 경로는 모두 `settlement-service/src/main/java/com/prompthub/settlement/` 또는 대응하는 `src/test/java`를 기준으로 한다.

---

### Task 1: Outbox 도메인 상태 모델

**Files:**
- Create: `settlement-service/src/main/java/com/prompthub/settlement/domain/model/enums/OutboxEventStatus.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/domain/model/OutboxEvent.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/domain/exception/OutboxEventInvalidStateException.java`
- Test: `settlement-service/src/test/java/com/prompthub/settlement/domain/model/OutboxEventTest.java`

**Interfaces:**
- Produces: `create`, `markPublished`, `recordPublishFailure`, `requeueForRedrive`, `isPending`
- Produces: `OutboxEventStatus { PENDING, PUBLISHED, FAILED }`

- [ ] **Step 1: Write failing tests**

Test initial fields, declared indexes, success, failure 1~3, reason truncation, valid/invalid redrive.

```java
OutboxEvent event = OutboxEvent.create(
        eventId, batchId, "SETTLEMENT", settlementId,
        "SETTLEMENT_CREATED", "settlement-events", json, occurredAt);
assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
event.recordPublishFailure("broker down", occurredAt.plusMinutes(1), 3);
event.recordPublishFailure("broker down", occurredAt.plusMinutes(2), 3);
event.recordPublishFailure("broker down", occurredAt.plusMinutes(3), 3);
assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
```

- [ ] **Step 2: Run RED**

```bash
./gradlew :settlement-service:test --tests "com.prompthub.settlement.domain.model.OutboxEventTest"
```

Expected: compilation failure because Outbox types do not exist.

- [ ] **Step 3: Implement minimal domain code**

Use `BaseEntity`, UUID PK, `@Version`, protected no-args constructor, private constructor, static factory, and the three indexes from the spec.

```java
public void recordPublishFailure(String reason, LocalDateTime attemptedAt, int maxRetryCount) {
    verifyPending();
    retryCount++;
    lastAttemptedAt = attemptedAt;
    lastFailureReason = truncate(reason);
    if (retryCount >= maxRetryCount) {
        status = OutboxEventStatus.FAILED;
        failedAt = attemptedAt;
    }
}

public void requeueForRedrive() {
    if (status != OutboxEventStatus.FAILED) {
        throw new OutboxEventInvalidStateException(status);
    }
    status = OutboxEventStatus.PENDING;
    retryCount = 0;
    lastAttemptedAt = null;
    lastFailureReason = null;
    failedAt = null;
    publishedAt = null;
}
```

- [ ] **Step 4: Run GREEN and commit**

```bash
./gradlew :settlement-service:test --tests "com.prompthub.settlement.domain.model.OutboxEventTest"
git add settlement-service/src/main/java/com/prompthub/settlement/domain settlement-service/src/test/java/com/prompthub/settlement/domain/model/OutboxEventTest.java
git commit -m "feat: 정산 아웃박스 상태 모델 추가 (#301)"
```

Expected: test PASS and one domain commit.

### Task 2: Outbox 영속성과 JSON 적재

**Files:**
- Create: `domain/repository/OutboxEventRepository.java`
- Create: `application/port/OutboxEventAppender.java`
- Create: `infrastructure/persistence/outbox/JsonOutboxEventAppender.java`
- Create: `infrastructure/persistence/outbox/OutboxEventJpaRepository.java`
- Create: `infrastructure/persistence/outbox/OutboxEventRepositoryAdapter.java`
- Create: `settlement-service/docs/sql/301-settlement-outbox.sql`
- Modify: `global/exception/SettlementErrorCode.java`
- Test: `infrastructure/persistence/outbox/JsonOutboxEventAppenderTest.java`
- Test: `infrastructure/persistence/outbox/OutboxEventRepositoryAdapterTest.java`

**Interfaces:**
- Produces: `appendSettlementCreated(UUID, SettlementCreatedPayload)`
- Produces: `OutboxCandidate(UUID eventId, LocalDateTime occurredAt)` and keyset queries

- [ ] **Step 1: Write failing appender/repository tests**

Capture the saved entity, parse JSON, and assert the entity PK equals envelope `eventId`. Persist mixed batches/statuses in H2 and assert stable ordered candidates.

```java
appender.appendSettlementCreated(batchId, payload);
then(repository).should().save(captor.capture());
JsonNode json = objectMapper.readTree(captor.getValue().getPayload());
assertThat(json.get("eventId").asText()).isEqualTo(captor.getValue().getEventId().toString());
```

- [ ] **Step 2: Run RED**

```bash
./gradlew :settlement-service:test --tests "*JsonOutboxEventAppenderTest" --tests "*OutboxEventRepositoryAdapterTest"
```

Expected: missing port/adapter compilation failure.

- [ ] **Step 3: Implement the port and adapter**

```java
public interface OutboxEventRepository {
    OutboxEvent save(OutboxEvent event);
    Optional<OutboxEvent> findById(UUID eventId);
    List<OutboxCandidate> findPendingBefore(
            LocalDateTime attemptedBefore, LocalDateTime cursorAt, UUID cursorId, int limit);
    List<OutboxCandidate> findPendingByBatchId(
            UUID batchId, LocalDateTime cursorAt, UUID cursorId, int limit);
    record OutboxCandidate(UUID eventId, LocalDateTime occurredAt) {}
}
```

The appender creates one `EventMessage<SettlementCreatedPayload>`, serializes it once, and uses its values in `OutboxEvent`. Convert Jackson failure to `OUTBOX_EVENT_SERIALIZE_FAILED`.

- [ ] **Step 4: Add PostgreSQL DDL**

Create table and indexes idempotently. Include `version BIGINT NOT NULL DEFAULT 0`, all timestamps, and audit columns.

- [ ] **Step 5: Run GREEN and commit**

```bash
./gradlew :settlement-service:test --tests "*JsonOutboxEventAppenderTest" --tests "*OutboxEventRepositoryAdapterTest"
git add settlement-service/src/main/java/com/prompthub/settlement/application/port/OutboxEventAppender.java settlement-service/src/main/java/com/prompthub/settlement/domain/repository/OutboxEventRepository.java settlement-service/src/main/java/com/prompthub/settlement/infrastructure/persistence/outbox settlement-service/src/main/java/com/prompthub/settlement/global/exception/SettlementErrorCode.java settlement-service/src/test/java/com/prompthub/settlement/infrastructure/persistence/outbox settlement-service/docs/sql/301-settlement-outbox.sql
git commit -m "feat: 정산 아웃박스 영속 적재 구현 (#301)"
```

Expected: tests PASS.

### Task 3: 정산 생성 트랜잭션에 Outbox 연결

**Files:**
- Modify: `application/service/SettlementCalculationApplicationService.java`
- Delete: `infrastructure/messaging/kafka/producer/SettlementCreatedEventListener.java`
- Modify: `application/service/SettlementCalculationApplicationServiceTest.java`
- Create: `application/service/SettlementOutboxTransactionIntegrationTest.java`

**Interfaces:**
- Consumes: `OutboxEventAppender.appendSettlementCreated`
- Produces: Settlement, SourceLine 처리, Outbox 원자적 커밋

- [ ] **Step 1: Replace the old test with failing Outbox tests**

Replace `ApplicationEventPublisher` with `OutboxEventAppender`, verify one call after ID assignment and no call when no lines exist.

```java
then(outboxEventAppender).should().appendSettlementCreated(
        eq(command.settlementBatchId()),
        argThat(payload -> payload.settlementId().equals(settlement.getId())));
```

- [ ] **Step 2: Add rollback integration test and run RED**

Force Outbox save failure and assert no Settlement remains and SourceLines stay unsettled after clearing the persistence context.

```bash
./gradlew :settlement-service:test --tests "*SettlementCalculationApplicationServiceTest" --tests "*SettlementOutboxTransactionIntegrationTest"
```

Expected: failure because calculation still publishes a Spring event.

- [ ] **Step 3: Implement minimal wiring**

```java
outboxEventAppender.appendSettlementCreated(
        command.settlementBatchId(),
        SettlementCreatedPayload.from(settlement));
```

Keep it inside the existing transaction after settlement ID assignment and SourceLine marking. Delete the AFTER_COMMIT listener.

- [ ] **Step 4: Run GREEN and commit**

```bash
./gradlew :settlement-service:test --tests "*SettlementCalculationApplicationServiceTest" --tests "*SettlementOutboxTransactionIntegrationTest"
git add -u settlement-service/src/main/java/com/prompthub/settlement/infrastructure/messaging/kafka/producer/SettlementCreatedEventListener.java
git add settlement-service/src/main/java/com/prompthub/settlement/application/service/SettlementCalculationApplicationService.java settlement-service/src/test/java/com/prompthub/settlement/application/service
git commit -m "feat: 정산 생성과 아웃박스 적재 원자화 (#301)"
```

Expected: tests PASS.

### Task 4: 저장된 JSON 동기 Kafka 발행

**Files:**
- Modify: `application/port/SettlementEventPublisher.java`
- Modify: `infrastructure/messaging/kafka/config/KafkaConfig.java`
- Modify: `infrastructure/messaging/kafka/producer/KafkaSettlementEventPublisher.java`
- Modify: `infrastructure/messaging/kafka/producer/KafkaSettlementEventPublisherTest.java`

**Interfaces:**
- Produces: `publish(String topic, UUID aggregateId, String payload)`
- Blocks for broker ack up to `${settlement.outbox.publish-timeout-ms:10000}`

- [ ] **Step 1: Write failing raw publisher tests**

Assert exact topic/key/JSON for completed future, failed future, timeout, and interruption. Interruption must restore the interrupt flag.

- [ ] **Step 2: Run RED**

```bash
./gradlew :settlement-service:test --tests "*KafkaSettlementEventPublisherTest"
```

Expected: old payload-based method mismatch.

- [ ] **Step 3: Add named String producer/template and sync publisher**

Keep the existing object template for the DLT path. Add `outboxProducerFactory` and `outboxKafkaTemplate` with String serializers.

```java
try {
    kafkaTemplate.send(topic, aggregateId.toString(), payload)
            .get(publishTimeoutMs, TimeUnit.MILLISECONDS);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new SettlementException(SettlementErrorCode.SETTLEMENT_EVENT_PUBLISH_FAILED, e);
} catch (ExecutionException | TimeoutException e) {
    throw new SettlementException(SettlementErrorCode.SETTLEMENT_EVENT_PUBLISH_FAILED, e);
}
```

- [ ] **Step 4: Run GREEN and commit**

```bash
./gradlew :settlement-service:test --tests "*KafkaSettlementEventPublisherTest"
git add settlement-service/src/main/java/com/prompthub/settlement/application/port/SettlementEventPublisher.java settlement-service/src/main/java/com/prompthub/settlement/infrastructure/messaging/kafka settlement-service/src/test/java/com/prompthub/settlement/infrastructure/messaging/kafka/producer/KafkaSettlementEventPublisherTest.java
git commit -m "feat: 아웃박스 이벤트 동기 Kafka 발행 구현 (#301)"
```

Expected: tests PASS.

### Task 5: Outbox flush와 redrive 애플리케이션 로직

**Files:**
- Create: `application/usecase/OutboxEventUseCase.java`
- Create: `application/service/OutboxApplicationService.java`
- Create: `application/service/OutboxEventPublishService.java`
- Modify: `global/exception/SettlementErrorCode.java`
- Test: `application/service/OutboxApplicationServiceTest.java`
- Test: `application/service/OutboxEventPublishServiceTest.java`

**Interfaces:**
- Produces: `flushPendingBefore(LocalDateTime)`, `flushBatch(UUID)`, `redrive(UUID)`
- Consumes: repository candidate queries and publisher

- [ ] **Step 1: Write failing service tests**

Cover success, first failure, third failure, missing ID, two candidate pages, cursor advancement, failure continuation, and targeted redrive.

- [ ] **Step 2: Run RED**

```bash
./gradlew :settlement-service:test --tests "*OutboxApplicationServiceTest" --tests "*OutboxEventPublishServiceTest"
```

Expected: missing use case/services.

- [ ] **Step 3: Implement per-event transaction**

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void publish(UUID eventId) {
    OutboxEvent event = find(eventId);
    if (!event.isPending()) {
        return;
    }
    LocalDateTime attemptedAt = LocalDateTime.now();
    try {
        publisher.publish(event.getTopic(), event.getAggregateId(), event.getPayload());
        event.markPublished(attemptedAt);
    } catch (SettlementException e) {
        event.recordPublishFailure(resolveReason(e), attemptedAt, maxRetryCount);
    }
}
```

Do not catch persistence or optimistic-lock exceptions.

- [ ] **Step 4: Implement cursor loops and redrive**

Advance `(occurredAt,eventId)` from the last candidate until an empty page. Redrive must run `FAILED → PENDING → publish` in one `REQUIRES_NEW` method and use `OUTBOX_EVENT_NOT_FOUND` for missing IDs.

- [ ] **Step 5: Run GREEN and commit**

```bash
./gradlew :settlement-service:test --tests "*OutboxApplicationServiceTest" --tests "*OutboxEventPublishServiceTest"
git add settlement-service/src/main/java/com/prompthub/settlement/application settlement-service/src/main/java/com/prompthub/settlement/global/exception/SettlementErrorCode.java settlement-service/src/test/java/com/prompthub/settlement/application/service/OutboxApplicationServiceTest.java settlement-service/src/test/java/com/prompthub/settlement/application/service/OutboxEventPublishServiceTest.java
git commit -m "feat: 아웃박스 flush와 재처리 유스케이스 추가 (#301)"
```

Expected: tests PASS.

### Task 6: Spring Batch Step과 redrive Job

**Files:**
- Create: `infrastructure/batch/tasklet/RetryPendingOutboxTasklet.java`
- Create: `infrastructure/batch/tasklet/FlushCurrentBatchOutboxTasklet.java`
- Create: `infrastructure/batch/tasklet/RedriveOutboxTasklet.java`
- Create: `infrastructure/batch/config/OutboxRedriveJobConfig.java`
- Modify: `infrastructure/batch/config/SettlementStepConfig.java`
- Modify: `infrastructure/batch/config/SettlementJobConfig.java`
- Modify: `infrastructure/batch/launcher/SettlementJobLauncherAdapter.java`
- Test: `infrastructure/batch/tasklet/OutboxTaskletTest.java`
- Test: `infrastructure/batch/config/OutboxJobConfigTest.java`

**Interfaces:**
- Produces normal Step order and one-Step `outboxRedriveJob`

- [ ] **Step 1: Write failing Tasklet and Job tests**

Verify Tasklets pass parsed UUID/time parameters to the use case. Inspect Job step names in this exact order:

```text
retryPendingOutboxStep
loadSettlementSourceStep
createSettlementBatchStep
settlementStep
completeSettlementBatchStep
flushCurrentBatchOutboxStep
```

Verify `outboxRedriveJob` contains only `redriveOutboxStep`.

- [ ] **Step 2: Run RED**

```bash
./gradlew :settlement-service:test --tests "*OutboxTaskletTest" --tests "*OutboxJobConfigTest"
```

Expected: missing Tasklet/Job beans.

- [ ] **Step 3: Implement thin Tasklets and wire Steps**

Each Tasklet parses only Job Parameters/ExecutionContext, calls `OutboxEventUseCase`, and returns `RepeatStatus.FINISHED`. Missing or invalid UUID input must fail.

`Job` 빈이 `settlementJob`, `outboxRedriveJob` 두 개가 되므로 기존 launcher 생성자에는 명시적인 qualifier를 둔다.

```java
public SettlementJobLauncherAdapter(
        JobOperator jobOperator,
        @Qualifier(SettlementBatchConfig.ASYNC_JOB_OPERATOR) JobOperator asyncJobOperator,
        @Qualifier(SettlementJobConfig.SETTLEMENT_JOB_NAME) Job settlementJob) {
    // existing assignments
}
```

- [ ] **Step 4: Run GREEN and commit**

```bash
./gradlew :settlement-service:test --tests "*OutboxTaskletTest" --tests "*OutboxJobConfigTest"
git add settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch
git commit -m "feat: 정산 배치 아웃박스 flush와 redrive 잡 추가 (#301)"
```

Expected: tests PASS.

### Task 7: 완료 배치 실패 리스너 보호

**Files:**
- Modify: `domain/model/SettlementBatch.java`
- Modify: `infrastructure/batch/listener/SettlementBatchFailureListener.java`
- Modify: `domain/model/SettlementBatchTest.java`
- Create: `infrastructure/batch/listener/SettlementBatchFailureListenerTest.java`

**Interfaces:**
- Produces: `boolean SettlementBatch.isProcessing()`
- Listener fails only PROCESSING batches

- [ ] **Step 1: Write failing listener tests**

Cover PROCESSING batch failure, COMPLETED batch post-flush failure, and COMPLETED Job. Completed batch must remain COMPLETED and not be saved again.

- [ ] **Step 2: Run RED**

```bash
./gradlew :settlement-service:test --tests "*SettlementBatchFailureListenerTest" --tests "*SettlementBatchTest"
```

Expected: completed batch path throws or performs unwanted save.

- [ ] **Step 3: Implement guard**

```java
settlementBatchRepository.findById(batchId).ifPresent(batch -> {
    if (!batch.isProcessing()) {
        return;
    }
    batch.fail(resolveFailureReason(jobExecution));
    settlementBatchRepository.save(batch);
});
```

- [ ] **Step 4: Run GREEN and commit**

```bash
./gradlew :settlement-service:test --tests "*SettlementBatchFailureListenerTest" --tests "*SettlementBatchTest"
git add settlement-service/src/main/java/com/prompthub/settlement/domain/model/SettlementBatch.java settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/listener/SettlementBatchFailureListener.java settlement-service/src/test/java/com/prompthub/settlement/domain/model/SettlementBatchTest.java settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/listener/SettlementBatchFailureListenerTest.java
git commit -m "fix: 완료 정산 배치의 실패 상태 덮어쓰기 방지 (#301)"
```

Expected: tests PASS.

### Task 8: 통합 검증과 문서 정합성

**Files:**
- Modify: `settlement-service/docs/architecture/kafka-messaging-design.md`
- Modify: `settlement-service/docs/architecture/integration-catalog.md` only if it records implementation status
- Add/refine: focused integration tests under `settlement-service/src/test/java/com/prompthub/settlement/`

**Interfaces:**
- Verifies all design completion criteria

- [ ] **Step 1: Add missing integration coverage**

Use H2 plus publisher mocks. Verify atomic Outbox commit, stable event ID, Job Step order, FAILED redrive isolation, and next-run retry without a real Kafka broker.

- [ ] **Step 2: Run focused suite**

```bash
./gradlew :settlement-service:test --tests "*Outbox*" --tests "*SettlementCalculationApplicationServiceTest" --tests "*SettlementBatchFailureListenerTest" --tests "*KafkaSettlementEventPublisherTest"
```

Expected: PASS.

- [ ] **Step 3: Update architecture status**

Record implemented start/end flush Steps, retry 3, FAILED redrive Job, stable event ID, and Kubernetes deployment prerequisites. Keep payout events out of scope.

- [ ] **Step 4: Run full verification**

```bash
./gradlew :settlement-service:test
./gradlew :settlement-service:check
git diff --check
git status --short
```

Expected: commands exit 0; only task files plus preserved user untracked files appear.

- [ ] **Step 5: Commit final docs/integration changes**

```bash
git add settlement-service/docs/architecture settlement-service/src/test/java/com/prompthub/settlement
git commit -m "docs: 정산 아웃박스 운영 흐름 반영 (#301)"
```

## Final Verification

- [ ] 정산 1건당 Outbox 1건이 같은 트랜잭션에 저장된다.
- [ ] Outbox PK와 JSON `eventId`가 같고 재시도에서 변하지 않는다.
- [ ] 시작/마지막 flush가 같은 Step에서 이벤트를 반복하지 않는다.
- [ ] Kafka 실패 3회에 `FAILED`, redrive에서만 `PENDING` 복구가 가능하다.
- [ ] redrive Job에 정산 계산 Step이 없다.
- [ ] 개별 Kafka 실패는 다른 이벤트와 정산 계산 결과에 영향을 주지 않는다.
- [ ] DB·낙관적 잠금 오류는 Job 실패로 드러난다.
- [ ] 운영 DDL이 엔티티 스키마와 일치한다.
- [ ] 전체 정산 테스트와 check가 통과한다.
