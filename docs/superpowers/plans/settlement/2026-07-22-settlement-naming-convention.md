# 정산 서비스 배치 실행 경계 네이밍 정리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 정산 업무의 `SettlementBatch`와 Spring Batch 기술 경계의 `SettlementJob`을 구분하고, application UseCase·서비스 및 Spring Batch 실행 연동 타입의 이름을 일관되게 정리한다.

**Architecture:** presentation과 application은 정산 실행 단위를 `SettlementBatch`로 표현하고, `JobOperator`·`JobRepository`를 다루는 기술 경계만 `SettlementJob`을 사용한다. 동작·런타임 식별자는 유지하면서 Java 타입과 패키지만 리네임하고, 현재 규칙과 아키텍처 문서를 같은 계약으로 동기화한다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Batch 6, JUnit 5, Mockito, AssertJ, Gradle, Excalidraw

## Global Constraints

- 작업 기준은 `#495 (이슈)`와 `refactor/#495-settlement-naming-convention (브랜치)`다.
- 설계 원본은 `settlement-service/docs/superpowers/specs/2026-07-22-settlement-naming-convention-design.md`다.
- `origin/develop` 기준 `../gradlew :settlement-service:test` 기준선은 작업 시작 전에 성공했다.
- 업무·표현·application 경계는 `SettlementBatch`, Spring Batch `Job`·`JobExecution` 연동 경계는 `SettlementJob`을 사용한다.
- 작업형 인바운드 포트와 구현체는 `{Verb}{Object}UseCase` ↔ `{Verb}{Object}ApplicationService`의 어근과 단어 순서를 정확히 맞춘다.
- `application/port`의 비영속 아웃바운드 포트에는 중복 `Port` 접미사를 붙이지 않고, 어댑터는 같은 능력 어근을 유지한다.
- API 경로 `${api.init}/admin/settlements/batch`, 요청 필드 `periodStart`·`periodEnd`, 응답 JSON은 변경하지 않는다.
- 설정 프로퍼티, DB 스키마·데이터, 예외·트랜잭션 경계, 정산 상태 전이, Outbox 발행·재시도 동작은 변경하지 않는다.
- Spring Batch 런타임 식별자 `settlementJob`·`outboxRedriveJob`, 기존 Step 이름, `asyncSettlementJobOperator` 및 모든 명시적 `@Bean` 이름을 유지한다.
- 클래스 리네임에 따른 기본 Spring 컴포넌트 빈 이름 변경은 허용하지만 문자열·`@Qualifier` 참조를 새로 만들지 않는다.
- Domain·Persistence·Presentation 전반을 일괄 리네임하지 않는다. presentation에서는 실행 요청 DTO 한 개만 용어 경계에 맞춰 리네임한다.
- `docs/trade-offs/**`, `docs/trouble-shooting/**`, `docs/settlement-api-for-frontend.md`, 과거 `docs/superpowers/specs/**`·`plans/**`는 당시 결정과 구현 기록이므로 소급 수정하지 않는다.
- 리네임 중 행동 변경이 필요한 문제나 다이어그램의 기존 흐름 설명 불일치를 발견하면 이번 변경에 섞지 않고 별도 이슈 후보로 기록한다.
- 각 작업의 테스트·정적 검색이 통과한 뒤 해당 책임 묶음을 커밋한다.

---

## File Map

### Create

- `src/test/java/com/prompthub/settlement/application/service/RunSettlementBatchApplicationServiceTest.java` — 배치 실행 application 서비스가 명령과 결과를 그대로 잡 런처에 위임하는 동작을 고정한다.

### Rename — Application / Presentation

| 현재 경로 | 변경 경로 |
| --- | --- |
| `src/main/java/com/prompthub/settlement/presentation/dto/request/RunSettlementJobRequest.java` | `src/main/java/com/prompthub/settlement/presentation/dto/request/RunSettlementBatchRequest.java` |
| `src/main/java/com/prompthub/settlement/application/dto/RunSettlementJobCommand.java` | `src/main/java/com/prompthub/settlement/application/dto/RunSettlementBatchCommand.java` |
| `src/main/java/com/prompthub/settlement/application/port/OrderSettlementQueryPort.java` | `src/main/java/com/prompthub/settlement/application/port/OrderSettlementQuery.java` |
| `src/main/java/com/prompthub/settlement/application/service/SettlementCalculationApplicationService.java` | `src/main/java/com/prompthub/settlement/application/service/CalculateSettlementApplicationService.java` |
| `src/main/java/com/prompthub/settlement/application/service/SettlementSourceApplicationService.java` | `src/main/java/com/prompthub/settlement/application/service/LoadSettlementSourceApplicationService.java` |
| `src/main/java/com/prompthub/settlement/application/service/SettlementBatchApplicationService.java` | `src/main/java/com/prompthub/settlement/application/service/RunSettlementBatchApplicationService.java` |
| `src/main/java/com/prompthub/settlement/application/service/SettlementBatchRestartApplicationService.java` | `src/main/java/com/prompthub/settlement/application/service/RestartSettlementBatchApplicationService.java` |
| `src/main/java/com/prompthub/settlement/application/service/SettlementJobStatusApplicationService.java` | `src/main/java/com/prompthub/settlement/application/service/GetSettlementJobStatusApplicationService.java` |
| `src/main/java/com/prompthub/settlement/application/service/OutboxApplicationService.java` | `src/main/java/com/prompthub/settlement/application/service/OutboxEventApplicationService.java` |
| `src/test/java/com/prompthub/settlement/application/service/SettlementCalculationApplicationServiceTest.java` | `src/test/java/com/prompthub/settlement/application/service/CalculateSettlementApplicationServiceTest.java` |
| `src/test/java/com/prompthub/settlement/application/service/SettlementSourceLoadServiceTest.java` | `src/test/java/com/prompthub/settlement/application/service/LoadSettlementSourceApplicationServiceTest.java` |
| `src/test/java/com/prompthub/settlement/application/service/SettlementBatchRestartApplicationServiceTest.java` | `src/test/java/com/prompthub/settlement/application/service/RestartSettlementBatchApplicationServiceTest.java` |
| `src/test/java/com/prompthub/settlement/application/service/SettlementJobStatusApplicationServiceTest.java` | `src/test/java/com/prompthub/settlement/application/service/GetSettlementJobStatusApplicationServiceTest.java` |
| `src/test/java/com/prompthub/settlement/application/service/OutboxApplicationServiceTest.java` | `src/test/java/com/prompthub/settlement/application/service/OutboxEventApplicationServiceTest.java` |

### Rename / Move — Spring Batch

| 현재 경로 | 변경 경로 |
| --- | --- |
| `src/main/java/com/prompthub/settlement/infrastructure/batch/launcher/SettlementJobLauncherAdapter.java` | `src/main/java/com/prompthub/settlement/infrastructure/batch/execution/SettlementJobLauncherAdapter.java` |
| `src/main/java/com/prompthub/settlement/infrastructure/batch/launcher/SettlementJobQueryAdapter.java` | `src/main/java/com/prompthub/settlement/infrastructure/batch/execution/SettlementJobQueryAdapter.java` |
| `src/main/java/com/prompthub/settlement/infrastructure/batch/launcher/SettlementJobRestartAdapter.java` | `src/main/java/com/prompthub/settlement/infrastructure/batch/execution/SettlementJobRestarterAdapter.java` |
| `src/main/java/com/prompthub/settlement/infrastructure/batch/launcher/SettlementJobParametersFactory.java` | `src/main/java/com/prompthub/settlement/infrastructure/batch/execution/SettlementJobParametersFactory.java` |
| `src/test/java/com/prompthub/settlement/infrastructure/batch/launcher/SettlementJobParametersFactoryTest.java` | `src/test/java/com/prompthub/settlement/infrastructure/batch/execution/SettlementJobParametersFactoryTest.java` |
| `src/test/java/com/prompthub/settlement/infrastructure/batch/launcher/SettlementJobRestartAdapterTest.java` | `src/test/java/com/prompthub/settlement/infrastructure/batch/execution/SettlementJobRestarterAdapterTest.java` |
| `src/main/java/com/prompthub/settlement/infrastructure/batch/config/SettlementBatchConfig.java` | `src/main/java/com/prompthub/settlement/infrastructure/batch/config/SpringBatchInfrastructureConfig.java` |
| `src/main/java/com/prompthub/settlement/infrastructure/batch/listener/SettlementBatchExecutionListener.java` | `src/main/java/com/prompthub/settlement/infrastructure/batch/listener/SettlementBatchStateJobExecutionListener.java` |
| `src/test/java/com/prompthub/settlement/infrastructure/batch/listener/SettlementBatchExecutionListenerTest.java` | `src/test/java/com/prompthub/settlement/infrastructure/batch/listener/SettlementBatchStateJobExecutionListenerTest.java` |
| `src/main/java/com/prompthub/settlement/infrastructure/batch/model/SettlementItem.java` | `src/main/java/com/prompthub/settlement/infrastructure/batch/model/SettlementTarget.java` |

### Modify — Java References

- `src/main/java/com/prompthub/settlement/application/dto/SettleableLine.java`
- `src/main/java/com/prompthub/settlement/application/port/SettlementJobLauncher.java`
- `src/main/java/com/prompthub/settlement/application/usecase/RunSettlementBatchUseCase.java`
- `src/main/java/com/prompthub/settlement/infrastructure/batch/config/SettlementJobConfig.java`
- `src/main/java/com/prompthub/settlement/infrastructure/batch/config/SettlementStepConfig.java`
- `src/main/java/com/prompthub/settlement/infrastructure/batch/processor/SettlementProcessor.java`
- `src/main/java/com/prompthub/settlement/infrastructure/batch/reader/SettlementTargetReader.java`
- `src/main/java/com/prompthub/settlement/infrastructure/batch/runner/SettlementCronJobRunner.java`
- `src/main/java/com/prompthub/settlement/infrastructure/client/order/OrderSettlementQueryClient.java`
- `src/main/java/com/prompthub/settlement/presentation/controller/SettlementBatchController.java`
- `src/test/java/com/prompthub/settlement/application/service/SettlementOutboxCommitIntegrationTest.java`
- `src/test/java/com/prompthub/settlement/application/service/SettlementOutboxTransactionIntegrationTest.java`
- `src/test/java/com/prompthub/settlement/infrastructure/batch/SettlementBatchRestartIntegrationTest.java`
- `src/test/java/com/prompthub/settlement/infrastructure/batch/config/OutboxJobConfigTest.java`
- `src/test/java/com/prompthub/settlement/infrastructure/batch/reader/SettlementTargetReaderTest.java`

### Modify — Current Rules / Architecture

- `.claude/rules/clean-architecture.md`
- `.claude/rules/swagger.md`
- `docs/architecture/kafka-messaging-design.md`
- `docs/architecture/settlement-internal-comm-topology.md`
- `docs/architecture/settlement-spring-batch-flow.excalidraw`
- `docs/architecture/settlement-spring-batch-flow.png`

### Do Not Modify

- `src/main/resources/**`, `src/test/resources/**`
- domain·repository·persistence 타입과 파일
- `SettlementBatchRetryStateService`, `OutboxEventPublishService`
- `SettlementJobResult`, `SettlementJobStatusResult`
- `SettlementCronJobRunner`, `SettlementBatchRestartRunner`
- `SettlementJobConfig`, `SettlementStepConfig`, `OutboxRedriveJobConfig`의 클래스명
- 과거 `docs/superpowers/specs/**`, `docs/superpowers/plans/**` 문서(이번 설계·계획 파일 자체 제외)
- `docs/trade-offs/**`, `docs/trouble-shooting/**`, `docs/settlement-api-for-frontend.md`

---

### Task 1: 현재 아키텍처 규칙과 다이어그램을 새 네이밍 계약으로 동기화

**Files:**

- Modify: `.claude/rules/clean-architecture.md`
- Modify: `.claude/rules/swagger.md`
- Modify: `docs/architecture/kafka-messaging-design.md`
- Modify: `docs/architecture/settlement-internal-comm-topology.md`
- Modify: `docs/architecture/settlement-spring-batch-flow.excalidraw`
- Modify: `docs/architecture/settlement-spring-batch-flow.png`

**Contract:**

```text
업무 실행 경계: RunSettlementBatchRequest -> RunSettlementBatchCommand
  -> RunSettlementBatchUseCase -> RunSettlementBatchApplicationService
기술 실행 경계: SettlementJobLauncher -> SettlementJobLauncherAdapter
Spring Batch 실행 패키지: infrastructure.batch.execution
```

- [ ] **Step 1: clean architecture 규칙의 패키지 구조와 명명 표를 갱신**

`clean-architecture.md`의 Batch 패키지 예시에서 `launcher`를 `execution`으로 바꾸고 책임을
`JobOperator / JobRepository로 시작·조회·재시작`이라고 적는다. 계층 네이밍 절에 다음 규칙과 실제 예시를
추가한다.

```text
SettlementBatch: presentation/application의 업무 실행 단위
SettlementJob: Spring Batch Job/JobExecution 기술 경계
{Verb}{Object}UseCase <-> {Verb}{Object}ApplicationService
application/port의 비영속 포트: Port 접미사를 붙이지 않고 어댑터는 같은 능력 어근 유지
```

설정 예시는 `SpringBatchInfrastructureConfig`, Batch 내부 DTO 예시는 `SettlementTarget`으로 바꾼다.

- [ ] **Step 2: 현재 Swagger·연동 문서의 타입 예시를 갱신**

`.claude/rules/swagger.md`의 코드 예시만 `RunSettlementBatchRequest`로 바꾼다.
`kafka-messaging-design.md`는 `OutboxEventApplicationService`, `OrderSettlementQuery`를 사용하고,
`settlement-internal-comm-topology.md`는 `OrderSettlementQueryClient`가 `OrderSettlementQuery`를 구현한다고
기록한다. API 경로·메시지 흐름·동기 호출 방식은 수정하지 않는다.

- [ ] **Step 3: Excalidraw 원본의 두 타입 라벨을 변경**

원본의 `text`와 `originalText`를 함께 다음처럼 바꾼다. 다른 요소의 텍스트와 좌표는 유지한다.

```text
RunSettlementBatchUseCase\nSettlementBatchApplicationService
  -> RunSettlementBatchUseCase\nRunSettlementBatchApplicationService

OrderSettlementQueryPort
  -> OrderSettlementQuery
```

Excalidraw에서 원본을 열어 배경 포함·1x로 다시 내보내고
`docs/architecture/settlement-spring-batch-flow.png`를 교체한다. 긴 서비스명이 잘리지 않도록 해당 텍스트
요소의 자동 너비만 확인하며 흐름·도형·색상은 바꾸지 않는다.

- [ ] **Step 4: 문서의 이전 이름과 다이어그램 형식을 정적으로 검증**

Run:

```bash
rg -n '\b(RunSettlementJobRequest|RunSettlementJobCommand|SettlementCalculationApplicationService|SettlementSourceApplicationService|SettlementBatchApplicationService|SettlementBatchRestartApplicationService|SettlementJobStatusApplicationService|OutboxApplicationService|OrderSettlementQueryPort|SettlementJobRestartAdapter|SettlementBatchConfig|SettlementBatchExecutionListener|SettlementItem)\b|infrastructure\.batch\.launcher' .claude/rules docs/architecture --glob '*.md' --glob '*.excalidraw'
jq empty docs/architecture/settlement-spring-batch-flow.excalidraw
file docs/architecture/settlement-spring-batch-flow.png
```

Expected: `rg`는 출력 없이 종료 코드 1, `jq`는 성공, PNG는 `3840 x 2160` 이미지로 식별된다.

- [ ] **Step 5: PNG를 열어 두 라벨과 레이아웃을 육안 검증**

Expected: `RunSettlementBatchApplicationService`와 `OrderSettlementQuery`가 잘리지 않고 표시되며 기존 연결선과
박스가 겹치지 않는다.

- [ ] **Step 6: 문서 변경 커밋**

```bash
git add .claude/rules/clean-architecture.md .claude/rules/swagger.md docs/architecture
git diff --cached --check
git commit -m "docs: 정산 배치 네이밍 규칙 반영"
```

---

### Task 2: 배치 실행 요청·명령·application 서비스 용어를 SettlementBatch로 통일

**Files:**

- Create: `src/test/java/com/prompthub/settlement/application/service/RunSettlementBatchApplicationServiceTest.java`
- Rename: `RunSettlementJobRequest.java` -> `RunSettlementBatchRequest.java`
- Rename: `RunSettlementJobCommand.java` -> `RunSettlementBatchCommand.java`
- Rename: `SettlementBatchApplicationService.java` -> `RunSettlementBatchApplicationService.java`
- Modify: `SettlementBatchController.java`, `RunSettlementBatchUseCase.java`, `SettlementJobLauncher.java`
- Modify: `SettlementCronJobRunner.java`, `SettlementJobLauncherAdapter.java`, `SettlementJobParametersFactory.java`
- Modify: `SettlementJobParametersFactoryTest.java`, `SettlementBatchRestartIntegrationTest.java`

**Interfaces:**

```java
public record RunSettlementBatchCommand(
        SettlementPeriod period,
        UUID actorId,
        TriggerType triggerType
) {
    public static RunSettlementBatchCommand manual(SettlementPeriod period, UUID actorId);
    public static RunSettlementBatchCommand scheduled(SettlementPeriod period);
}

public interface RunSettlementBatchUseCase {
    SettlementJobResult run(RunSettlementBatchCommand command);
}

public interface SettlementJobLauncher {
    SettlementJobResult launch(RunSettlementBatchCommand command);
}
```

- [ ] **Step 1: 새 application 서비스 이름과 위임 계약을 요구하는 실패 테스트 작성**

`RunSettlementBatchApplicationServiceTest`에 `SettlementJobLauncher`를 mock하고 다음 동작을 고정한다.

```java
package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.settlement.application.dto.RunSettlementBatchCommand;
import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.port.SettlementJobLauncher;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RunSettlementBatchApplicationServiceTest {

    @Mock
    private SettlementJobLauncher settlementJobLauncher;

    @InjectMocks
    private RunSettlementBatchApplicationService service;

    @Test
    void 배치_실행_명령을_잡_런처에_위임한다() {
        SettlementPeriod period = SettlementPeriod.of(
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19));
        RunSettlementBatchCommand command = RunSettlementBatchCommand.scheduled(period);
        SettlementJobResult expected = new SettlementJobResult(
                42L, "settlementJob", "STARTED", LocalDateTime.of(2026, 7, 20, 0, 0));
        given(settlementJobLauncher.launch(command)).willReturn(expected);

        SettlementJobResult actual = service.run(command);

        assertThat(actual).isEqualTo(expected);
        then(settlementJobLauncher).should().launch(command);
    }
}
```

- [ ] **Step 2: 새 타입이 아직 없어 테스트 컴파일이 실패하는지 확인**

Run:

```bash
../gradlew :settlement-service:test --tests '*RunSettlementBatchApplicationServiceTest'
```

Expected: FAIL at `compileTestJava`; `RunSettlementBatchApplicationService`와
`RunSettlementBatchCommand`를 찾을 수 없다.

- [ ] **Step 3: 요청·명령·서비스 파일을 리네임하고 모든 Java 참조를 새 이름으로 변경**

파일명, public 타입명, 생성자·정적 팩토리 반환 타입, import와 변수 타입을 함께 바꾼다.
`SettlementJobResult`와 `SettlementJobLauncher`의 이름, Job/Step/Bean 문자열은 유지한다.

- [ ] **Step 4: 배치 실행 경계 관련 테스트 실행**

Run:

```bash
../gradlew :settlement-service:test --tests '*RunSettlementBatchApplicationServiceTest' --tests '*SettlementJobParametersFactoryTest' --tests '*SettlementCronJobRunnerTest'
```

Expected: PASS; 새 application 서비스의 위임, manual/scheduled JobParameters와 CronJob 실행 계산이 기존과 같다.

- [ ] **Step 5: 이전 실행 요청·명령·서비스 이름이 Java 소스에서 제거됐는지 확인**

Run:

```bash
rg -n '\b(RunSettlementJobRequest|RunSettlementJobCommand|SettlementBatchApplicationService)\b' src/main src/test
```

Expected: 출력 없음.

- [ ] **Step 6: application 실행 경계 커밋**

```bash
git add src/main/java src/test/java
git diff --cached --check
git commit -m "refactor: 정산 배치 실행 네이밍 정리"
```

---

### Task 3: UseCase·ApplicationService와 order 조회 포트 이름을 정확히 대응

**Files:**

- Rename: application 서비스 5개와 `OutboxEventApplicationService`
- Rename: 대응 단위 테스트 5개
- Rename: `OrderSettlementQueryPort.java` -> `OrderSettlementQuery.java`
- Modify: `SettleableLine.java`, `OrderSettlementQueryClient.java`
- Modify: `SettlementOutboxCommitIntegrationTest.java`, `SettlementOutboxTransactionIntegrationTest.java`
- Modify: `SettlementBatchRestartIntegrationTest.java`

**Name Contract:**

| UseCase / Port | 구현 타입 |
| --- | --- |
| `CalculateSettlementUseCase` | `CalculateSettlementApplicationService` |
| `LoadSettlementSourceUseCase` | `LoadSettlementSourceApplicationService` |
| `RestartSettlementBatchUseCase` | `RestartSettlementBatchApplicationService` |
| `GetSettlementJobStatusUseCase` | `GetSettlementJobStatusApplicationService` |
| `OutboxEventUseCase` | `OutboxEventApplicationService` |
| `OrderSettlementQuery` | `OrderSettlementQueryClient` |

- [ ] **Step 1: 단위 테스트 파일·클래스와 대상 타입 참조를 새 계약으로 먼저 리네임**

다음 테스트명을 사용하고 테스트 메서드·fixture·assertion은 변경하지 않는다.

```text
CalculateSettlementApplicationServiceTest
LoadSettlementSourceApplicationServiceTest
RestartSettlementBatchApplicationServiceTest
GetSettlementJobStatusApplicationServiceTest
OutboxEventApplicationServiceTest
```

`LoadSettlementSourceApplicationServiceTest`의 mock 타입도 `OrderSettlementQuery`로 바꾼다.

- [ ] **Step 2: 생산 타입이 아직 없어 테스트 컴파일이 실패하는지 확인**

Run:

```bash
../gradlew :settlement-service:test --tests '*ApplicationServiceTest'
```

Expected: FAIL at `compileTestJava`; 새 application 서비스와 `OrderSettlementQuery`를 찾을 수 없다.

- [ ] **Step 3: 생산 서비스·포트 파일을 리네임하고 참조를 수정**

서비스 로직, `@Transactional` 경계, 생성자 인자 순서와 메서드 본문은 유지한다. `SettleableLine` Javadoc과
`OrderSettlementQueryClient implements ...`도 새 포트 이름으로 맞춘다. 통합 테스트는 주입 타입만 바꾼다.

- [ ] **Step 4: application 단위·트랜잭션 통합 테스트 실행**

Run:

```bash
../gradlew :settlement-service:test --tests '*CalculateSettlementApplicationServiceTest' --tests '*LoadSettlementSourceApplicationServiceTest' --tests '*RestartSettlementBatchApplicationServiceTest' --tests '*GetSettlementJobStatusApplicationServiceTest' --tests '*OutboxEventApplicationServiceTest' --tests '*SettlementOutboxCommitIntegrationTest' --tests '*SettlementOutboxTransactionIntegrationTest'
```

Expected: PASS; 계산, 원천 적재, 재시작 위임, 상태 조회, Outbox flush/redrive와 트랜잭션 동작이 기존과 같다.

- [ ] **Step 5: 이전 application·포트 이름이 Java 소스에서 제거됐는지 확인**

Run:

```bash
rg -n '\b(SettlementCalculationApplicationService|SettlementSourceApplicationService|SettlementBatchRestartApplicationService|SettlementJobStatusApplicationService|OutboxApplicationService|OrderSettlementQueryPort)\b' src/main src/test
```

Expected: 출력 없음.

- [ ] **Step 6: application 서비스·포트 커밋**

```bash
git add src/main/java src/test/java
git diff --cached --check
git commit -m "refactor: 정산 애플리케이션 서비스 네이밍 정리"
```

---

### Task 4: Spring Batch 잡 연동 패키지를 execution으로 이동

**Files:**

- Move: `infrastructure/batch/launcher/SettlementJobLauncherAdapter.java` -> `infrastructure/batch/execution/SettlementJobLauncherAdapter.java`
- Move: `infrastructure/batch/launcher/SettlementJobQueryAdapter.java` -> `infrastructure/batch/execution/SettlementJobQueryAdapter.java`
- Rename / Move: `SettlementJobRestartAdapter.java` -> `execution/SettlementJobRestarterAdapter.java`
- Move: `infrastructure/batch/launcher/SettlementJobParametersFactory.java` -> `infrastructure/batch/execution/SettlementJobParametersFactory.java`
- Move: `SettlementJobParametersFactoryTest.java` -> `infrastructure/batch/execution/SettlementJobParametersFactoryTest.java`
- Rename / Move: `SettlementJobRestartAdapterTest.java` -> `execution/SettlementJobRestarterAdapterTest.java`

**Package Contract:**

```text
com.prompthub.settlement.infrastructure.batch.execution
├── SettlementJobLauncherAdapter implements SettlementJobLauncher
├── SettlementJobQueryAdapter implements SettlementJobQuery
├── SettlementJobRestarterAdapter implements SettlementJobRestarter
└── SettlementJobParametersFactory
```

- [ ] **Step 1: 실행 어댑터 테스트를 새 package·타입명으로 먼저 이동**

`SettlementJobParametersFactoryTest`의 package를 `...batch.execution`으로 바꾸고,
`SettlementJobRestarterAdapterTest`는 새 어댑터 타입을 생성하도록 바꾼다. 테스트 시나리오와 assertion은
유지한다.

- [ ] **Step 2: 새 package·타입이 없어 테스트 컴파일이 실패하는지 확인**

Run:

```bash
../gradlew :settlement-service:test --tests '*SettlementJobParametersFactoryTest' --tests '*SettlementJobRestarterAdapterTest'
```

Expected: FAIL at `compileTestJava`; `execution` package의 생산 타입을 찾을 수 없다.

- [ ] **Step 3: 네 생산 클래스를 execution package로 이동하고 Restarter 어댑터 이름을 맞춤**

package 선언과 필요한 import만 바꾼다. `SettlementJobRestarter` 포트, `restart(UUID, long)` 시그니처,
`JobOperator`·`JobRepository` 호출 순서와 예외 복원 로직은 유지한다.

- [ ] **Step 4: execution 어댑터 단위 테스트 실행**

Run:

```bash
../gradlew :settlement-service:test --tests '*SettlementJobParametersFactoryTest' --tests '*SettlementJobRestarterAdapterTest'
```

Expected: PASS; 파라미터 구성과 완료·실패·예외 재시작 시나리오가 기존과 같다.

- [ ] **Step 5: launcher package와 이전 어댑터 이름이 제거됐는지 확인**

Run:

```bash
rg -n 'infrastructure\.batch\.launcher|\bSettlementJobRestartAdapter\b' src/main src/test
test ! -d src/main/java/com/prompthub/settlement/infrastructure/batch/launcher
test ! -d src/test/java/com/prompthub/settlement/infrastructure/batch/launcher
```

Expected: `rg` 출력 없음. 두 `test ! -d` 명령이 성공해 launcher 디렉터리가 제거됐음을 확인한다.

- [ ] **Step 6: Spring Batch execution 패키지 커밋**

```bash
git add -A src/main/java/com/prompthub/settlement/infrastructure/batch src/test/java/com/prompthub/settlement/infrastructure/batch
git diff --cached --check
git commit -m "refactor: Spring Batch 실행 패키지 정리"
```

---

### Task 5: Spring Batch 설정·리스너·처리 대상 역할명을 구체화

**Files:**

- Rename: `SettlementBatchConfig.java` -> `SpringBatchInfrastructureConfig.java`
- Rename: `SettlementBatchExecutionListener.java` -> `SettlementBatchStateJobExecutionListener.java`
- Rename: `SettlementBatchExecutionListenerTest.java` -> `SettlementBatchStateJobExecutionListenerTest.java`
- Rename: `SettlementItem.java` -> `SettlementTarget.java`
- Modify: `SettlementJobLauncherAdapter.java`, `SettlementJobConfig.java`, `SettlementStepConfig.java`
- Modify: `SettlementProcessor.java`, `SettlementTargetReader.java`
- Modify: `OutboxJobConfigTest.java`, `SettlementTargetReaderTest.java`

**Role Contract:**

```java
public class SpringBatchInfrastructureConfig {
    public static final String ASYNC_JOB_OPERATOR = "asyncSettlementJobOperator";
}

public class SettlementBatchStateJobExecutionListener implements JobExecutionListener;

public record SettlementTarget(
        UUID sellerId,
        SettlementPeriod period,
        UUID settlementBatchId
);
```

- [ ] **Step 1: 리스너·Reader 테스트를 새 역할명으로 먼저 변경**

리스너 테스트 파일·클래스·fixture 타입은 `SettlementBatchStateJobExecutionListener`로 바꾸고,
`SettlementTargetReaderTest`의 기대 타입과 값은 `SettlementTarget`으로 바꾼다.
`OutboxJobConfigTest`의 listener mock도 새 타입을 사용한다. 테스트 행위와 assertion은 유지한다.

- [ ] **Step 2: 새 생산 타입이 없어 테스트 컴파일이 실패하는지 확인**

Run:

```bash
../gradlew :settlement-service:test --tests '*SettlementBatchStateJobExecutionListenerTest' --tests '*SettlementTargetReaderTest' --tests '*OutboxJobConfigTest'
```

Expected: FAIL at `compileTestJava`; 새 listener·model 타입을 찾을 수 없다.

- [ ] **Step 3: 설정·리스너·모델을 리네임하고 의존 타입을 수정**

`SpringBatchInfrastructureConfig.ASYNC_JOB_OPERATOR`의 상수 값과 모든 `@Bean` 메서드 이름은 유지한다.
`SettlementJobConfig`의 listener 인자 타입만 바꾸고 JobBuilder 구성 순서는 유지한다.
`SettlementStepConfig`의 chunk 제네릭, `SettlementTargetReader` 반환 타입, `SettlementProcessor` 입력 타입을
`SettlementTarget`으로 바꾼다. 필드 순서와 데이터 전달은 유지한다.

- [ ] **Step 4: Batch 역할 관련 단위 테스트 실행**

Run:

```bash
../gradlew :settlement-service:test --tests '*SettlementBatchStateJobExecutionListenerTest' --tests '*SettlementTargetReaderTest' --tests '*SettlementStepConfigTest' --tests '*OutboxJobConfigTest'
```

Expected: PASS; 배치 상태 동기화, Reader 반복 종료, chunk 제네릭과 Job 구성 주입이 기존과 같다.
launcher 동작은 Task 6의 재시작 통합 테스트와 전체 테스트에서 검증한다.

- [ ] **Step 5: 이전 Batch 역할 이름이 Java 소스에서 제거됐는지 확인**

Run:

```bash
rg -n '\b(SettlementBatchConfig|SettlementBatchExecutionListener|SettlementItem)\b' src/main src/test
```

Expected: 출력 없음.

- [ ] **Step 6: Spring Batch 역할명 커밋**

```bash
git add -A src/main/java/com/prompthub/settlement/infrastructure/batch src/test/java/com/prompthub/settlement/infrastructure/batch
git diff --cached --check
git commit -m "refactor: Spring Batch 역할 네이밍 정리"
```

---

### Task 6: 재시작 통합·전체 빌드·동작 보존 검증

**Files:**

- Verify: 전체 `settlement-service` 소스·테스트·현재 규칙·현재 아키텍처 문서
- Verify: `docs/architecture/settlement-spring-batch-flow.excalidraw`
- Verify: `docs/architecture/settlement-spring-batch-flow.png`

- [ ] **Step 1: #463의 핵심인 실패 배치 재시작 통합 테스트 실행**

Run:

```bash
../gradlew :settlement-service:test --tests '*SettlementBatchRestartIntegrationTest'
```

Expected: PASS; 계산 실패와 원천 적재 실패 뒤 같은 JobInstance를 재시작하는 흐름이 유지된다.

- [ ] **Step 2: clean·전체 테스트·build 실행**

Run:

```bash
../gradlew :settlement-service:clean :settlement-service:test :settlement-service:build
```

Expected: `BUILD SUCCESSFUL`; compile, 전체 JUnit, jar/bootJar와 검증 task가 모두 통과한다.

- [ ] **Step 3: 현재 코드·규칙·아키텍처에서 이전 이름을 전수 검색**

Run:

```bash
rg -n '\b(RunSettlementJobRequest|RunSettlementJobCommand|SettlementCalculationApplicationService|SettlementSourceApplicationService|SettlementBatchApplicationService|SettlementBatchRestartApplicationService|SettlementJobStatusApplicationService|OutboxApplicationService|OrderSettlementQueryPort|SettlementJobRestartAdapter|SettlementBatchConfig|SettlementBatchExecutionListener|SettlementItem)\b|infrastructure\.batch\.launcher' src/main src/test .claude/rules docs/architecture --glob '*.java' --glob '*.md' --glob '*.excalidraw'
```

Expected: 출력 없음. 과거 specs/plans와 trade-off/trouble-shooting 기록은 검색 범위에서 제외한다.

- [ ] **Step 4: 런타임 식별자와 외부 계약 보존 확인**

Run:

```bash
rg -n 'SETTLEMENT_JOB_NAME = "settlementJob"|OUTBOX_REDRIVE_JOB_NAME = "outboxRedriveJob"|new StepBuilder\("(retryPendingOutboxStep|loadSettlementSourceStep|createSettlementBatchStep|settlementStep|completeSettlementBatchStep|flushCurrentBatchOutboxStep|redriveOutboxStep)"|ASYNC_JOB_OPERATOR = "asyncSettlementJobOperator"|@RequestMapping\("\$\{api\.init\}/admin/settlements/batch"\)' src/main/java
git diff origin/develop -- src/main/resources src/test/resources
```

Expected: Job 2개, 기존 Step 7개(정산 6개와 별도 redrive 1개), async operator와 API 경로가 모두 검색된다.
리소스 diff는 출력이 없다.

- [ ] **Step 5: 다이어그램 원본·PNG와 전체 diff를 최종 검토**

Run:

```bash
jq empty docs/architecture/settlement-spring-batch-flow.excalidraw
file docs/architecture/settlement-spring-batch-flow.png
git diff --check origin/develop
git status --short --branch
git log --oneline origin/develop..HEAD
```

Expected: Excalidraw JSON 유효, PNG 3840×2160, whitespace 오류 없음, 의도한 파일만 변경되고 계획된 커밋이
브랜치에 표시된다. PNG를 다시 열어 두 변경 라벨이 읽히는지 확인한다.

- [ ] **Step 6: 완료 보고에 검증 근거와 남은 비범위 항목 기록**

보고에는 `#495 (이슈)`, `refactor/#495-settlement-naming-convention (브랜치)`, 생성한 커밋,
재시작 통합 테스트와 전체 빌드 결과를 포함한다. 리네임과 무관한 기존 문서 흐름 불일치나 행동 문제를
발견했다면 코드에 섞지 않고 별도 이슈 후보로 분리해 적는다.
