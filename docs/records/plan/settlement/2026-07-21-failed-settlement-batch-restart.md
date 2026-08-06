# Failed Settlement Batch Restart Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 실패한 주간 정산을 기존 Spring Batch JobInstance와 `settlement_batch`를 유지한 채 재시작하고, 이미 커밋된 청크는 중복 처리하지 않으며 최종 완료 전에는 Outbox 이벤트를 발행하지 않는다.

**Architecture:** `SettlementBatch`가 JobInstance ID, 재시작 상태 전이와 낙관적 락을 소유한다. 재시작 애플리케이션 서비스가 도메인 상태를 검증한 뒤 `SettlementJobRestarter` 포트를 호출하고, 인프라 어댑터가 `JobRepository`의 최근 실패 실행을 검증해 동기 `JobOperator.restart(JobExecution)`을 호출한다. Spring Batch의 Step 재시작과 `settlement_source_line.settlement_id is null` 조건을 함께 사용해 미완료 청크만 다시 처리한다.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Batch 6.0, Spring Data JPA/Hibernate, PostgreSQL 18/Flyway, JUnit 5, AssertJ, Mockito, Testcontainers 2.0

## Global Constraints

- 작업 범위는 `settlement-service/`의 재시작 코어와 실행 계약으로 제한한다.
- admin-service API, Kubernetes 일회성 Job/RBAC, 월간 지급, 감사 이력은 구현하지 않는다.
- 신규 배치는 `jobInstanceId`를 반드시 기록하지만 연결 불가능한 레거시 행을 보존하기 위해 DB 컬럼은 nullable로 둔다.
- 레거시 보정은 `batch_no`의 숫자 접미사가 실제 `settlementJob` 실행에 유일하게 연결되는 행만 갱신한다. 삭제나 추정 연결은 하지 않는다.
- 상태 전이는 `PROCESSING -> COMPLETED|FAILED`, `FAILED -> RETRY_REQUESTED`, `RETRY_REQUESTED -> PROCESSING|FAILED`만 허용한다.
- 재시작은 가장 최근 JobExecution이 `FAILED`일 때만 허용한다. `STARTED`, `STARTING`, `STOPPING`은 자동 복구하지 않는다.
- 재시작은 동일 JobInstance, 동일 `settlement_batch`, 동일 JobParameters를 사용한다.
- 일반 Outbox 재시도는 `COMPLETED` 배치의 이벤트만 조회한다. 현재 배치 flush 조회는 기존 배치 ID 계약을 유지한다.
- 구현은 각 Task에서 실패 테스트를 먼저 확인하고 최소 코드로 통과시킨 뒤 커밋한다.

---

## File Map

### 신규 파일

- `settlement-service/src/main/resources/db/migration/V2__add_settlement_batch_restart_metadata.sql`
- `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/persistence/migration/SettlementBatchRestartMigrationIntegrationTest.java`
- `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/persistence/SettlementBatchOptimisticLockIntegrationTest.java`
- `settlement-service/src/main/java/com/prompthub/settlement/application/dto/RestartSettlementBatchCommand.java`
- `settlement-service/src/main/java/com/prompthub/settlement/application/usecase/RestartSettlementBatchUseCase.java`
- `settlement-service/src/main/java/com/prompthub/settlement/application/port/SettlementJobRestarter.java`
- `settlement-service/src/main/java/com/prompthub/settlement/application/service/SettlementBatchRetryStateService.java`
- `settlement-service/src/main/java/com/prompthub/settlement/application/service/SettlementBatchRestartApplicationService.java`
- `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/launcher/SettlementJobRestartAdapter.java`
- `settlement-service/src/test/java/com/prompthub/settlement/application/service/SettlementBatchRestartApplicationServiceTest.java`
- `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/launcher/SettlementJobRestartAdapterTest.java`
- `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/runner/SettlementBatchRestartRunner.java`
- `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/runner/SettlementBatchRestartRunnerTest.java`
- `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/runner/SettlementBatchRestartRunnerConditionTest.java`
- `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/SettlementBatchRestartIntegrationTest.java`

### 수정·이름 변경 파일

- `SettlementBatch`, `SettlementBatchStatus`, `SettlementBatchInvalidStateException`, 관련 도메인 테스트
- `CreateSettlementBatchTasklet`과 테스트
- `SettlementBatchFailureListener`/테스트를 `SettlementBatchExecutionListener`/테스트로 이름 변경
- `SettlementJobConfig`, `SettlementStepConfig`, `application.yml`, `SettlementApplication`
- `SettlementErrorCode`
- `OutboxEventJpaRepository`, `OutboxEventRepositoryAdapter`, 관련 저장소 테스트
- `settlement-service/build.gradle`

---

### Task 1: 정산 배치 재시작 상태 모델

**Files:**
- Modify: `settlement-service/src/test/java/com/prompthub/settlement/domain/model/SettlementBatchTest.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/domain/model/SettlementBatch.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/domain/model/enums/SettlementBatchStatus.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/domain/exception/SettlementBatchInvalidStateException.java`
- Modify: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/listener/SettlementBatchFailureListenerTest.java`

**Contract:**

```java
public static SettlementBatch start(
        String batchNo,
        long jobInstanceId,
        LocalDate periodStart,
        LocalDate periodEnd,
        TriggerType triggerType);

public void requestRetry();     // FAILED -> RETRY_REQUESTED
public void startRetry();       // RETRY_REQUESTED -> PROCESSING, 실패 정보 초기화
public void restoreFailed(String reason); // RETRY_REQUESTED -> FAILED
public boolean isRetryRequested();
```

- [ ] **Step 1: 상태 전이 실패 테스트를 추가한다**

`SettlementBatchTest`에 다음 사례를 추가한다.

```java
@Test
void retryTransitions_keepJobInstanceAndClearPreviousFailure() {
    SettlementBatch batch = batch();
    batch.fail("first failure");

    batch.requestRetry();
    assertThat(batch.getStatus()).isEqualTo(RETRY_REQUESTED);

    batch.startRetry();
    assertThat(batch.getStatus()).isEqualTo(PROCESSING);
    assertThat(batch.getFailureReason()).isNull();
    assertThat(batch.getExecutedAt()).isNull();
    assertThat(batch.getJobInstanceId()).isEqualTo(41L);

    batch.complete();
    assertThat(batch.getStatus()).isEqualTo(COMPLETED);
}

@Test
void restoreFailed_returnsRetryRequestedBatchToFailed() {
    SettlementBatch batch = failedBatch();
    batch.requestRetry();

    batch.restoreFailed("restart launch failed");

    assertThat(batch.getStatus()).isEqualTo(FAILED);
    assertThat(batch.getFailureReason()).isEqualTo("restart launch failed");
    assertThat(batch.getExecutedAt()).isNotNull();
}

@ParameterizedTest
@EnumSource(value = SettlementBatchStatus.class, names = "FAILED", mode = EXCLUDE)
void requestRetry_rejectsStateOtherThanFailed(SettlementBatchStatus status) {
    SettlementBatch batch = batchIn(status);

    assertThatThrownBy(batch::requestRetry)
            .isInstanceOf(SettlementBatchInvalidStateException.class)
            .hasMessageContaining("expected=FAILED")
            .hasMessageContaining("current=" + status);
}
```

- [ ] **Step 2: 도메인 테스트의 컴파일/상태 전이 실패를 확인한다**

Run: `./gradlew :settlement-service:test --tests com.prompthub.settlement.domain.model.SettlementBatchTest`

Expected: `RETRY_REQUESTED`, 신규 메서드와 `jobInstanceId` 부재로 FAIL

- [ ] **Step 3: 상태와 엔티티를 최소 구현한다**

`SettlementBatchStatus`에 `RETRY_REQUESTED`를 추가한다. `SettlementBatch`에는 다음 필드를 추가한다.

```java
@Column(name = "job_instance_id", unique = true)
private Long jobInstanceId;

@Version
@Column(name = "version", nullable = false)
private Long version;
```

공통 상태 검증은 기대 상태를 받도록 바꾼다.

```java
private void verifyStatus(SettlementBatchStatus expected) {
    if (status != expected) {
        throw new SettlementBatchInvalidStateException(expected, status);
    }
}
```

`start(...)`는 `jobInstanceId`가 양수인지 검증한다. 실패 사유는 기존 컬럼 길이에 맞게 1,000자로 자른다. `requestRetry`, `startRetry`, `restoreFailed`, `complete`, `fail`은 Global Constraints의 상태 전이만 구현한다.

- [ ] **Step 4: 모든 호출부를 새 생성 계약으로 맞추고 도메인 테스트를 통과시킨다**

Run: `./gradlew :settlement-service:test --tests '*SettlementBatchTest' --tests '*SettlementBatchFailureListenerTest' --tests '*CreateSettlementBatchTaskletTest'`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋한다**

```bash
git add settlement-service/src/main/java/com/prompthub/settlement/domain settlement-service/src/test/java/com/prompthub/settlement/domain settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch
git commit -m "feat: 정산 배치 재시작 상태 전이 추가"
```

---

### Task 2: 재시작 메타데이터 마이그레이션과 낙관적 락

**Files:**
- Modify: `settlement-service/build.gradle`
- Create: `settlement-service/src/main/resources/db/migration/V2__add_settlement_batch_restart_metadata.sql`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/persistence/migration/SettlementBatchRestartMigrationIntegrationTest.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/persistence/SettlementBatchOptimisticLockIntegrationTest.java`

- [ ] **Step 1: 테스트 의존성을 추가한다**

```groovy
testImplementation 'org.springframework.batch:spring-batch-test'
testImplementation 'org.testcontainers:testcontainers-junit-jupiter'
testImplementation 'org.testcontainers:testcontainers-postgresql'
```

- [ ] **Step 2: PostgreSQL 마이그레이션 실패 테스트를 작성한다**

`SettlementBatchRestartMigrationIntegrationTest`는 `postgres:18.4-alpine`과 `@Testcontainers(disabledWithoutDocker = true)`를 사용한다. 테스트는 Flyway를 V1까지만 적용한 다음 아래 데이터를 JDBC로 넣는다.

- JobInstance 11 / JobExecution 101 / `settlementJob` / `SETTLE-20260713-20260719-SCHEDULED-101` 배치: 연결 대상
- JobInstance 12 / JobExecution 102 / 다른 잡 이름 / `SETTLE-20260713-20260719-SCHEDULED-102` 배치: 미연결 유지
- 숫자 접미사가 없는 레거시 배치: 미연결 유지

최신 마이그레이션 후 다음을 한 테스트에서 검증한다.

```java
assertThat(jobInstanceId(linkedBatchId)).isEqualTo(11L);
assertThat(jobInstanceId(otherJobBatchId)).isNull();
assertThat(jobInstanceId(unmatchedBatchId)).isNull();
assertThat(rowCount("settlement_batch")).isEqualTo(3);
assertThat(version(linkedBatchId)).isZero();

jdbc.update("update settlement_batch set status = 'RETRY_REQUESTED' where batch_id = ?", linkedBatchId);
assertThatThrownBy(() -> insertBatchWithJobInstanceId(anotherBatchId, 11L))
        .isInstanceOf(DataAccessException.class);
```

Run: `./gradlew :settlement-service:test --tests '*SettlementBatchRestartMigrationIntegrationTest'`

Expected: V2 파일 부재로 컬럼/상태 검증 FAIL

- [ ] **Step 3: V2 마이그레이션을 구현한다**

```sql
ALTER TABLE settlement_batch
    ADD COLUMN job_instance_id bigint;

ALTER TABLE settlement_batch
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

WITH candidates AS (
    SELECT sb.batch_id, bji.job_instance_id
    FROM settlement_batch sb
    JOIN batch_job_execution bje
      ON sb.batch_no ~ '-[0-9]+$'
     AND bje.job_execution_id = substring(sb.batch_no FROM '([0-9]+)$')::bigint
    JOIN batch_job_instance bji
      ON bji.job_instance_id = bje.job_instance_id
    WHERE bji.job_name = 'settlementJob'
), unambiguous AS (
    SELECT min(batch_id::text)::uuid AS batch_id, job_instance_id
    FROM candidates
    GROUP BY job_instance_id
    HAVING count(*) = 1
)
UPDATE settlement_batch sb
SET job_instance_id = u.job_instance_id
FROM unambiguous u
WHERE sb.batch_id = u.batch_id;

CREATE UNIQUE INDEX uk_settlement_batch_job_instance_id
    ON settlement_batch (job_instance_id)
    WHERE job_instance_id IS NOT NULL;

ALTER TABLE settlement_batch
    DROP CONSTRAINT IF EXISTS settlement_batch_status_check;

ALTER TABLE settlement_batch
    ADD CONSTRAINT settlement_batch_status_check
    CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED', 'RETRY_REQUESTED', 'CANCELLED'));
```

- [ ] **Step 4: 낙관적 락 통합 테스트를 작성한다**

`@DataJpaTest`에서 같은 FAILED 배치를 두 번 detach해 읽고 각각 `requestRetry()`한다. 첫 엔티티를 `saveAndFlush`한 뒤 두 번째 엔티티 저장이 `ObjectOptimisticLockingFailureException`인지 확인한다.

Run: `./gradlew :settlement-service:test --tests '*SettlementBatchOptimisticLockIntegrationTest' --tests '*SettlementBatchRestartMigrationIntegrationTest'`

Expected: `BUILD SUCCESSFUL`; Docker가 없으면 마이그레이션 테스트만 JUnit 정책에 따라 skipped

- [ ] **Step 5: 커밋한다**

```bash
git add settlement-service/build.gradle settlement-service/src/main/resources/db/migration settlement-service/src/test/java/com/prompthub/settlement/infrastructure/persistence
git commit -m "feat: 정산 배치 재시작 메타데이터 추가"
```

---

### Task 3: 최초 실행 연결과 재시작 수명주기

**Files:**
- Modify: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/tasklet/CreateSettlementBatchTaskletTest.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/tasklet/CreateSettlementBatchTasklet.java`
- Rename: `SettlementBatchFailureListener.java` -> `SettlementBatchExecutionListener.java`
- Rename: `SettlementBatchFailureListenerTest.java` -> `SettlementBatchExecutionListenerTest.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/config/SettlementJobConfig.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/config/SettlementJobConfigTest.java`

- [ ] **Step 1: 생성 tasklet과 listener 실패 테스트를 추가한다**

생성 tasklet 테스트는 `JobExecution`의 JobInstance ID가 저장된 배치와 같은지 검증한다.

```java
then(repository).should().save(argThat(batch ->
        batch.getJobInstanceId().equals(jobExecution.getJobInstance().getInstanceId())
                && batch.getStatus() == PROCESSING));
```

리스너 테스트에는 다음 계약을 추가한다.

```java
@Test
void beforeJob_restartStartsRetryOnExistingBatch() {
    SettlementBatch batch = failedBatch();
    batch.requestRetry();
    JobExecution execution = execution(STARTING, batch.getId());
    given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

    listener.beforeJob(execution);

    assertThat(batch.getStatus()).isEqualTo(PROCESSING);
    then(repository).should().save(batch);
}

@Test
void beforeJob_initialExecutionWithoutBatchContextDoesNothing() {
    listener.beforeJob(executionWithoutBatchContext());
    then(repository).shouldHaveNoInteractions();
}
```

Run: `./gradlew :settlement-service:test --tests '*CreateSettlementBatchTaskletTest' --tests '*SettlementBatchExecutionListenerTest'`

Expected: 새 생성 계약과 listener 클래스 부재로 FAIL

- [ ] **Step 2: 최초 실행 JobInstance 연결을 구현한다**

```java
JobExecution jobExecution = chunkContext.getStepContext().getStepExecution().getJobExecution();
long jobInstanceId = jobExecution.getJobInstance().getInstanceId();
long jobExecutionId = jobExecution.getId();

SettlementBatch saved = repository.save(SettlementBatch.start(
        generateBatchNo(period, triggerType, jobExecutionId),
        jobInstanceId,
        period.periodStart(),
        period.periodEnd(),
        triggerType));
jobExecution.getExecutionContext().putString(BATCH_ID_KEY, saved.getId().toString());
```

- [ ] **Step 3: listener를 실행 수명주기 listener로 바꾼다**

`SettlementBatchExecutionListener.beforeJob`은 execution context에 배치 ID가 있을 때만 배치를 조회하고 `startRetry()` 후 저장한다. 배치가 없으면 `SETTLEMENT_BATCH_NOT_FOUND`를 던져 실행을 중단한다. `afterJob`은 기존처럼 미완료 Job의 PROCESSING 배치만 `fail(...)`한다.

- [ ] **Step 4: Step 순서를 바꾼다**

```java
.start(createSettlementBatchStep)
.next(retryPendingOutboxStep)
.next(loadSettlementSourceStep)
.next(settlementStep)
.next(completeSettlementBatchStep)
.next(flushCurrentBatchOutboxStep)
```

- [ ] **Step 5: 관련 테스트를 통과시킨다**

Run: `./gradlew :settlement-service:test --tests '*CreateSettlementBatchTaskletTest' --tests '*SettlementBatchExecutionListenerTest' --tests '*SettlementJobConfigTest'`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋한다**

```bash
git add settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch
git commit -m "feat: 정산 배치와 잡 실행 수명주기 연결"
```

---

### Task 4: 완료 배치 Outbox만 일반 재시도

**Files:**
- Modify: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/persistence/outbox/OutboxEventRepositoryAdapterTest.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/persistence/outbox/OutboxEventJpaRepository.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/persistence/outbox/OutboxEventRepositoryAdapter.java`

- [ ] **Step 1: 배치 상태별 조회 실패 테스트를 작성한다**

테스트 helper는 실제 `SettlementBatch`를 영속화하고 그 ID로 Outbox를 만든다. `COMPLETED`, `FAILED`, `RETRY_REQUESTED`, `PROCESSING` 배치에 각각 PENDING 이벤트를 저장한다.

```java
List<OutboxCandidate> candidates = repository.findPendingBefore(
        BASE_TIME.plusMinutes(5), null, null, 10);

assertThat(candidates)
        .extracting(OutboxCandidate::eventId)
        .containsExactly(completedEvent.getEventId());
```

cursor 경로도 같은 상태 필터가 적용되도록 완료/실패 이벤트를 섞어 별도 검증한다. `findPendingByBatchId`는 현재 배치 완료 후 flush 계약이므로 상태 필터를 추가하지 않는다.

Run: `./gradlew :settlement-service:test --tests '*OutboxEventRepositoryAdapterTest'`

Expected: FAILED/RETRY_REQUESTED/PROCESSING 이벤트도 반환되어 FAIL

- [ ] **Step 2: 두 일반 조회 JPQL에 완료 배치 조건을 추가한다**

```java
and exists (
    select b.id
    from SettlementBatch b
    where b.id = e.settlementBatchId
      and b.status = :batchStatus
)
```

두 JPA 메서드에 `SettlementBatchStatus batchStatus` 파라미터를 추가하고 어댑터가 `SettlementBatchStatus.COMPLETED`를 전달한다.

- [ ] **Step 3: 저장소 테스트를 통과시킨다**

Run: `./gradlew :settlement-service:test --tests '*OutboxEventRepositoryAdapterTest'`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋한다**

```bash
git add settlement-service/src/main/java/com/prompthub/settlement/infrastructure/persistence/outbox settlement-service/src/test/java/com/prompthub/settlement/infrastructure/persistence/outbox
git commit -m "fix: 완료 전 정산 Outbox 발행 차단"
```

---

### Task 5: 재시작 유스케이스와 Spring Batch 어댑터

**Files:**
- Create: `settlement-service/src/main/java/com/prompthub/settlement/application/dto/RestartSettlementBatchCommand.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/application/usecase/RestartSettlementBatchUseCase.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/application/port/SettlementJobRestarter.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/application/service/SettlementBatchRetryStateService.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/application/service/SettlementBatchRestartApplicationService.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/launcher/SettlementJobRestartAdapter.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/global/exception/SettlementErrorCode.java`
- Create: corresponding service/adapter tests

**Interfaces:**

```java
public record RestartSettlementBatchCommand(UUID batchId, UUID actorId) {
    public RestartSettlementBatchCommand {
        Objects.requireNonNull(batchId, "batchId는 필수입니다.");
        Objects.requireNonNull(actorId, "actorId는 필수입니다.");
    }
}

public interface RestartSettlementBatchUseCase {
    SettlementJobResult restart(RestartSettlementBatchCommand command);
}

public interface SettlementJobRestarter {
    SettlementJobResult restart(UUID batchId, long jobInstanceId);
}
```

오류 코드는 다음을 추가한다.

```java
SETTLEMENT_BATCH_JOB_INSTANCE_NOT_LINKED(
        "S-020", "정산 배치와 잡 실행 이력이 연결되지 않았습니다.", HttpStatus.CONFLICT),
SETTLEMENT_JOB_NOT_RESTARTABLE(
        "S-021", "정산 배치 잡을 재시작할 수 없는 상태입니다.", HttpStatus.CONFLICT),
SETTLEMENT_JOB_BATCH_MISMATCH(
        "S-022", "정산 배치와 잡 실행 이력이 일치하지 않습니다.", HttpStatus.CONFLICT),
```

- [ ] **Step 1: 애플리케이션 서비스 실패 테스트를 작성한다**

다음을 각각 검증한다.

- 없는 배치: `SETTLEMENT_BATCH_NOT_FOUND`, restarter 미호출, 상태 복원 미호출
- FAILED/PROCESSING 배치: `SettlementBatchInvalidStateException`, 미호출
- `jobInstanceId == null` 레거시 배치: `S-020`, restarter 미호출, 배치 `FAILED` 복원
- 정상 RETRY_REQUESTED: restarter에 배치 ID와 JobInstance ID 전달
- restarter가 시작 전 예외: `SettlementBatchRetryStateService.restoreFailed(batchId, reason)` 호출 후 원래 예외 재던짐
- 복원도 실패: 원래 예외를 재던지고 복원 예외가 suppressed에 포함됨

Run: `./gradlew :settlement-service:test --tests '*SettlementBatchRestartApplicationServiceTest'`

Expected: 신규 타입 부재로 FAIL

- [ ] **Step 2: 재시작 상태 트랜잭션 경계를 구현한다**

`SettlementBatchRetryStateService`는 다음 두 메서드를 제공한다.

```java
@Transactional(readOnly = true)
public SettlementBatch requireRetryRequested(UUID batchId);

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void restoreFailed(UUID batchId, String reason);
```

첫 메서드는 배치 존재와 `RETRY_REQUESTED` 상태를 검증한다. `SettlementBatchRestartApplicationService`가
복원 대상 경계 안에서 JobInstance ID 연결 여부를 검증해, 미연결 레거시 배치도 `S-020`을 던진 뒤
`FAILED`로 복원한다. 복원 메서드는 다시 조회한 상태가 아직 `RETRY_REQUESTED`일 때만
`restoreFailed`하고, 이미 `PROCESSING`이면 새 실행이 시작된 것이므로 변경하지 않는다.

`SettlementBatchRestartApplicationService`에는 긴 트랜잭션을 걸지 않는다. 검증 후 restarter를 호출하고 예외일 때 별도 트랜잭션 복원을 호출한다. 복원 실패는 로그와 suppressed 예외에 남기고 원래 예외를 던진다.

- [ ] **Step 3: 재시작 어댑터 실패 테스트를 작성한다**

Mockito로 `JobRepository`, 동기 `JobOperator`를 구성해 다음을 검증한다.

- JobInstance 없음 또는 잡 이름 불일치: `S-008`
- 최근 실행 없음: `S-008`
- 최근 상태가 FAILED가 아님: `S-021`, restart 미호출
- execution context 배치 ID 없음/불일치: `S-022`, restart 미호출
- 유효한 FAILED 실행: `jobOperator.restart(lastExecution)` 정확히 1회, 반환 실행을 `SettlementJobResult`로 변환
- Spring Batch restart 예외: `S-002`로 래핑

Run: `./gradlew :settlement-service:test --tests '*SettlementJobRestartAdapterTest'`

Expected: 어댑터 부재로 FAIL

- [ ] **Step 4: Spring Batch 6 재시작 어댑터를 구현한다**

핵심 호출은 deprecated 숫자 API가 아닌 다음 형태다.

```java
JobInstance instance = jobRepository.getJobInstance(jobInstanceId);
JobExecution lastExecution = jobRepository.getLastJobExecution(instance);
JobExecution restarted = jobOperator.restart(lastExecution);
```

잡 이름은 `SettlementJobConfig.SETTLEMENT_JOB_NAME`, execution context 키는 공통 상수 `settlementBatchId`를 사용한다. 반환 매핑은 기존 launcher와 동일하다.

- [ ] **Step 5: 서비스와 어댑터 테스트를 통과시킨다**

Run: `./gradlew :settlement-service:test --tests '*SettlementBatchRestartApplicationServiceTest' --tests '*SettlementJobRestartAdapterTest'`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋한다**

```bash
git add settlement-service/src/main/java/com/prompthub/settlement/application settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/launcher settlement-service/src/main/java/com/prompthub/settlement/global/exception settlement-service/src/test/java/com/prompthub/settlement/application settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/launcher
git commit -m "feat: 실패 정산 배치 재시작 유스케이스 추가"
```

---

### Task 6: 재시작 one-shot runner와 설정

**Files:**
- Create: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/runner/SettlementBatchRestartRunner.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/runner/SettlementBatchRestartRunnerTest.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/runner/SettlementBatchRestartRunnerConditionTest.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/SettlementApplication.java`
- Create or Modify: `settlement-service/src/test/java/com/prompthub/settlement/SettlementApplicationTest.java`
- Modify: `settlement-service/src/main/resources/application.yml`

- [ ] **Step 1: runner와 프로세스 모드 실패 테스트를 작성한다**

Runner 테스트는 `COMPLETED -> 0`, 다른 상태와 예외 `-> 1`, 전달 command의 batch/actor UUID를 검증한다. 조건 테스트는 `restart` 모드에서만 bean이 생기는지 확인한다. 애플리케이션 테스트는 `cronjob`, `restart`만 one-shot이고 `service`, null은 아닌지 확인한다.

Run: `./gradlew :settlement-service:test --tests '*SettlementBatchRestartRunnerTest' --tests '*SettlementBatchRestartRunnerConditionTest' --tests '*SettlementApplicationTest'`

Expected: 신규 runner와 one-shot 판별 부재로 FAIL

- [ ] **Step 2: restart runner를 구현한다**

```java
@Component
@ConditionalOnProperty(name = "settlement.execution.mode", havingValue = "restart")
public class SettlementBatchRestartRunner implements ApplicationRunner, ExitCodeGenerator {
    // @Value("${settlement.restart.batch-id}") UUID batchId
    // @Value("${settlement.restart.actor-id}") UUID actorId
}
```

유스케이스를 동기 호출하고 구조화 로그에 `batchId`, `actorId`, `jobExecutionId`, `status`를 남긴다. 종료 코드는 Cron runner와 같은 규칙을 사용한다.

- [ ] **Step 3: 재시작 환경 계약과 종료를 연결한다**

`application.yml`:

```yaml
settlement:
  execution:
    mode: service
  restart:
    batch-id: ${SETTLEMENT_RESTART_BATCH_ID:}
    actor-id: ${SETTLEMENT_RESTART_ACTOR_ID:}
  batch:
    chunk-size: ${SETTLEMENT_BATCH_CHUNK_SIZE:100}
```

`SettlementApplication`은 package-private helper `isOneShotMode(String mode)`로 `cronjob`과 `restart`를 명시적으로 판별하고 둘 모두 `SpringApplication.exit` 결과로 종료한다.

- [ ] **Step 4: runner·모드 테스트를 통과시킨다**

Run: `./gradlew :settlement-service:test --tests '*SettlementBatchRestartRunner*' --tests '*SettlementApplicationTest' --tests '*SettlementCronJobRunner*'`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋한다**

```bash
git add settlement-service/src/main/java/com/prompthub/settlement/SettlementApplication.java settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/runner settlement-service/src/main/resources/application.yml settlement-service/src/test/java/com/prompthub/settlement
git commit -m "feat: 정산 배치 재시작 실행 모드 추가"
```

---

### Task 7: 설정 가능한 청크와 재시작 통합 테스트

**Files:**
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/config/SettlementStepConfig.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/SettlementBatchRestartIntegrationTest.java`

- [ ] **Step 1: 청크 크기 설정을 구현한다**

`SettlementStepConfig`의 상수 `CHUNK_SIZE`를 제거하고 생성자 주입 값으로 바꾼다.

```java
public SettlementStepConfig(
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        @Value("${settlement.batch.chunk-size:100}") int chunkSize) {
    if (chunkSize <= 0) {
        throw new IllegalArgumentException("settlement.batch.chunk-size는 1 이상이어야 합니다.");
    }
    this.jobRepository = jobRepository;
    this.transactionManager = transactionManager;
    this.chunkSize = chunkSize;
}
```

- [ ] **Step 2: 첫 청크 커밋 후 실패하는 통합 테스트를 작성한다**

`@SpringBootTest(properties = {"settlement.execution.mode=service", "settlement.batch.chunk-size=1"})`에서 세 판매자의 source line을 저장한다. `@MockitoSpyBean SettlementCalculationApplicationService`가 두 번째 calculate 호출에서 예외를 던지게 해 최초 실행을 FAILED로 만든다.

최초 실패 후 다음을 검증한다.

```java
assertThat(firstResult.status()).isEqualTo("FAILED");
assertThat(batch.getStatus()).isEqualTo(FAILED);
assertThat(batch.getJobInstanceId()).isNotNull();
assertThat(settlementRepository.count()).isEqualTo(1);
assertThat(sourceRepository.findAll()).filteredOn(SettlementSourceLine::isSettled).hasSize(1);
assertThat(outboxRepository.count()).isEqualTo(1);
assertThat(globalOutboxCandidates()).isEmpty();
then(eventPublisher).shouldHaveNoInteractions();
```

배치를 `requestRetry()`해 저장하고 spy를 실제 호출로 돌린 뒤 `RestartSettlementBatchUseCase`를 호출한다. 최종 완료 후 다음을 검증한다.

```java
assertThat(restarted.status()).isEqualTo("COMPLETED");
assertThat(reloadedBatch.getId()).isEqualTo(originalBatchId);
assertThat(reloadedBatch.getStatus()).isEqualTo(COMPLETED);
assertThat(reloadedBatch.getJobInstanceId()).isEqualTo(originalJobInstanceId);
assertThat(jobRepository.getJobExecutions(jobInstance)).hasSize(2);
assertThat(settlementRepository.count()).isEqualTo(3);
assertThat(sourceRepository.findAll()).allMatch(SettlementSourceLine::isSettled);
assertThat(outboxRepository.count()).isEqualTo(3);
then(eventPublisher).should(times(3)).publish(anyString(), any(UUID.class), anyString());
```

정산 행의 seller ID와 source line의 settlement ID가 각각 유일한지 함께 확인해 완료 청크 중복 처리가 없음을 고정한다.

Run: `./gradlew :settlement-service:test --tests '*SettlementBatchRestartIntegrationTest'`

Expected: 구현 누락이나 재시작 계약 오류가 있으면 FAIL

- [ ] **Step 3: 소스 적재 단계 실패 재시작 사례를 같은 통합 테스트에 추가한다**

`LoadSettlementSourceUseCase`를 첫 호출에서 실패시키고 두 번째 호출에서 성공시킨다. 첫 실행에도 배치가 먼저 생성돼 FAILED로 남는지, 재시작은 같은 배치에서 load step부터 이어지는지 검증한다.

- [ ] **Step 4: 통합 테스트를 통과시킨다**

Run: `./gradlew :settlement-service:test --tests '*SettlementBatchRestartIntegrationTest'`

Expected: `BUILD SUCCESSFUL`, 2 tests passed

- [ ] **Step 5: 커밋한다**

```bash
git add settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/config/SettlementStepConfig.java settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/SettlementBatchRestartIntegrationTest.java
git commit -m "test: 실패 정산 배치 재시작 흐름 검증"
```

---

### Task 8: 전체 회귀·규칙 검증

**Files:**
- Verify: `settlement-service/` 전체 변경

- [ ] **Step 1: 변경 파일과 금지 범위를 확인한다**

Run: `git status --short && git diff --name-only origin/develop...HEAD`

Expected: 변경은 `settlement-service/` 안에만 있고 사용자 변경이나 다른 모듈 파일이 없음

- [ ] **Step 2: 빠른 정적 검사를 실행한다**

Run: `git diff --check origin/develop...HEAD`

Expected: 출력 없음, exit 0

- [ ] **Step 3: 정산 서비스 전체 테스트를 실행한다**

Run: `./gradlew :settlement-service:test`

Expected: `BUILD SUCCESSFUL`; Docker 사용 가능 시 PostgreSQL 마이그레이션 테스트 포함

- [ ] **Step 4: 정산 서비스 빌드를 실행한다**

Run: `./gradlew :settlement-service:build`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: `verify-project-changes` 기준으로 전체 diff를 검토한다**

확인 항목:

- 설계의 상태 전이, JobInstance 연결, 레거시 정책, Outbox 차단이 모두 테스트됨
- Spring Batch 6의 `restart(JobExecution)`을 사용하고 숫자 restart API를 사용하지 않음
- `STARTED` 실행을 자동 변경하지 않음
- 빈 catch, wildcard import, 범위 밖 파일 변경이 없음
- 실패 복원 중 원래 예외와 복원 예외가 모두 보존됨

- [ ] **Step 6: 검증 중 수정이 생기면 관련 테스트와 전체 검증을 다시 실행하고 별도 커밋한다**

수정 파일만 명시적으로 stage한 뒤 `git commit -m "fix: 정산 배치 재시작 검증 보완"`로 커밋한다.
