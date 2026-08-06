# AI 정산 Run 이벤트 의존성 역전 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redis Run 이벤트 구독 어댑터가 presentation과 application 구현체를 직접 호출하지 않고 `SettlementRunUseCase`를 통해 SSE 전달과 로컬 실행 취소를 조율하도록 변경한다.

**Architecture:** `RedisSettlementRunEventSubscriber`는 `SettlementRunUseCase.handleEvent(RunEvent)`만 호출한다. `SettlementRunApplicationService`가 새 아웃바운드 포트 `SettlementRunEventBroadcaster`로 이벤트를 전달하고, `CANCELLED`일 때 `SettlementRunTaskRegistry`를 취소한다. `SseEmitterRegistry`가 broadcaster를 구현하며 외부 REST·Redis·SSE 계약과 기존 처리 순서는 유지한다.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data Redis, Spring MVC SSE, JUnit 5, Mockito, AssertJ, Testcontainers, Gradle

## Global Constraints

- 변경 범위는 `ai-service/src/main/java/com/prompthub/ai/settlement/**`, 대응 테스트와 본 설계·계획 문서로 제한한다.
- `ai.inspection`, `ai.recommendation`, Settlement Service와 User Service의 기존 미커밋 변경을 수정하지 않는다.
- REST 경로·JSON, Redis channel·payload, SSE event 이름·payload를 변경하지 않는다.
- 이벤트 전달 후 `CANCELLED` 실행을 취소하는 현재 처리 순서를 유지한다.
- Redis Pub/Sub 유실 복구, SSE 재연결·재생, 다중 Pod 실행 소유권 정책은 구현하지 않는다.
- `TokenCountEstimator`, Micrometer, `AiSettlementProperties` 구조는 이번 단계에서 변경하지 않는다.
- 새 프로덕션 타입은 `SettlementRunEventBroadcaster` 하나만 추가한다.
- 별도의 `HandleSettlementRunEventUseCase`나 `SettlementRunEventApplicationService`를 만들지 않는다.
- ArchUnit을 추가하지 않는다.
- 구현은 테스트를 먼저 실패시키는 RED → 최소 구현 GREEN 순서로 진행한다.
- 파일 수정은 `apply_patch`를 사용한다.
- stage, commit, push, PR은 별도 사용자 요청 전에는 수행하지 않는다.

---

## 파일 구조와 책임

**Create**

- `ai-service/src/main/java/com/prompthub/ai/settlement/application/usecase/SettlementRunEventBroadcaster.java`
  - 현재 Pod의 Run 이벤트 구독자에게 `RunEvent`를 전달하는 기술 중립 아웃바운드 포트다.

**Modify**

- `ai-service/src/main/java/com/prompthub/ai/settlement/application/usecase/SettlementRunUseCase.java`
  - `void handleEvent(RunEvent event)` 인바운드 연산을 추가한다.
- `ai-service/src/main/java/com/prompthub/ai/settlement/application/service/run/SettlementRunApplicationService.java`
  - broadcaster 호출과 `CANCELLED` 로컬 task 취소를 순서대로 조율한다.
- `ai-service/src/main/java/com/prompthub/ai/settlement/presentation/sse/SseEmitterRegistry.java`
  - `SettlementRunEventBroadcaster`를 구현하고 기존 `dispatch` 로직을 `broadcast`로 제공한다.
- `ai-service/src/main/java/com/prompthub/ai/settlement/infrastructure/messaging/redis/RedisSettlementRunEventSubscriber.java`
  - Redis 역직렬화 후 `SettlementRunUseCase.handleEvent`만 호출한다.
- `ai-service/src/test/java/com/prompthub/ai/settlement/application/service/run/SettlementRunApplicationServiceTest.java`
  - 일반 이벤트 전달과 `CANCELLED` 처리 순서를 검증한다.
- `ai-service/src/test/java/com/prompthub/ai/settlement/presentation/sse/SseEmitterRegistryTest.java`
  - broadcaster 계약으로 SSE 전송과 terminal 정리를 검증한다.
- `ai-service/src/test/java/com/prompthub/ai/settlement/infrastructure/messaging/redis/RedisSettlementRunEventSubscriberTest.java`
  - 정상 payload 위임과 잘못된 payload 폐기를 검증한다.
- `ai-service/src/test/java/com/prompthub/ai/settlement/infrastructure/messaging/redis/RedisSettlementRunEventPubSubIntegrationTest.java`
  - Redis Publisher → Subscriber → ApplicationService → SSE의 실제 연결을 유지한다.

---

### Task 1: Application Run 이벤트 계약과 조율

**Files:**

- Create: `ai-service/src/main/java/com/prompthub/ai/settlement/application/usecase/SettlementRunEventBroadcaster.java`
- Modify: `ai-service/src/main/java/com/prompthub/ai/settlement/application/usecase/SettlementRunUseCase.java`
- Modify: `ai-service/src/main/java/com/prompthub/ai/settlement/application/service/run/SettlementRunApplicationService.java`
- Test: `ai-service/src/test/java/com/prompthub/ai/settlement/application/service/run/SettlementRunApplicationServiceTest.java`

**Interfaces:**

- Consumes: `RunEvent`, `RunEvent.RunEventType.CANCELLED`, `SettlementRunTaskRegistry.cancel(UUID)`
- Produces: `SettlementRunEventBroadcaster.broadcast(RunEvent)`, `SettlementRunUseCase.handleEvent(RunEvent)`

- [ ] **Step 1: 일반 이벤트와 취소 이벤트의 실패 테스트 작성**

`SettlementRunApplicationServiceTest`에 broadcaster와 task registry mock을 사용한 두 테스트를 추가한다.
기존 `getOwnedRunReturnsActorOwnedRun`의 서비스 생성자도 아래 최종 생성자 순서로 변경한다.

```java
@Test
void handleEventBroadcastsNonCancelledEventWithoutCancellingTask() {
    SettlementChatStateRepository repository = mock(SettlementChatStateRepository.class);
    SettlementRunEventBroadcaster broadcaster = mock(SettlementRunEventBroadcaster.class);
    SettlementRunTaskRegistry taskRegistry = mock(SettlementRunTaskRegistry.class);
    SettlementRunApplicationService service = service(repository, broadcaster, taskRegistry);
    RunEvent event = RunEvent.progress(UUID.randomUUID(), RunStage.ANALYZING, NOW);

    service.handleEvent(event);

    verify(broadcaster).broadcast(event);
    verifyNoInteractions(taskRegistry);
}

@Test
void handleEventBroadcastsCancelledEventBeforeCancellingLocalTask() {
    SettlementChatStateRepository repository = mock(SettlementChatStateRepository.class);
    SettlementRunEventBroadcaster broadcaster = mock(SettlementRunEventBroadcaster.class);
    SettlementRunTaskRegistry taskRegistry = mock(SettlementRunTaskRegistry.class);
    SettlementRunApplicationService service = service(repository, broadcaster, taskRegistry);
    RunEvent event = RunEvent.cancelled(UUID.randomUUID(), NOW);

    service.handleEvent(event);

    InOrder order = inOrder(broadcaster, taskRegistry);
    order.verify(broadcaster).broadcast(event);
    order.verify(taskRegistry).cancel(event.runId());
}

private SettlementRunApplicationService service(
        SettlementChatStateRepository repository,
        SettlementRunEventBroadcaster broadcaster,
        SettlementRunTaskRegistry taskRegistry
) {
    return new SettlementRunApplicationService(
            repository,
            broadcaster,
            taskRegistry,
            AiSettlementTestFixtures.properties(true),
            Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
}
```

필요한 import는 다음 타입을 사용한다.

```java
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.prompthub.ai.settlement.application.usecase.SettlementRunEventBroadcaster;
import com.prompthub.ai.settlement.domain.event.RunEvent;
import com.prompthub.ai.settlement.domain.model.run.RunStage;
import org.mockito.InOrder;
```

- [ ] **Step 2: RED 확인**

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.application.service.run.SettlementRunApplicationServiceTest"
```

Expected: `SettlementRunEventBroadcaster` 또는 `handleEvent`가 없어 `compileTestJava`가 실패한다.

- [ ] **Step 3: broadcaster 포트와 Run 유스케이스 메서드 추가**

`SettlementRunEventBroadcaster.java`를 다음 내용으로 만든다.

```java
package com.prompthub.ai.settlement.application.usecase;

import com.prompthub.ai.settlement.domain.event.RunEvent;

public interface SettlementRunEventBroadcaster {

    void broadcast(RunEvent event);
}
```

`SettlementRunUseCase`에 domain event import와 메서드를 추가한다.

```java
import com.prompthub.ai.settlement.domain.event.RunEvent;

void handleEvent(RunEvent event);
```

- [ ] **Step 4: ApplicationService에 이벤트 조율 구현**

`SettlementRunApplicationService` 생성자 시그니처를 다음 순서로 확장한다.

```java
public SettlementRunApplicationService(
        SettlementChatStateRepository stateRepository,
        SettlementRunEventBroadcaster eventBroadcaster,
        SettlementRunTaskRegistry taskRegistry,
        AiSettlementProperties properties,
        Clock clock
)
```

필드와 메서드를 추가한다.

```java
private final SettlementRunEventBroadcaster eventBroadcaster;
private final SettlementRunTaskRegistry taskRegistry;

@Override
public void handleEvent(RunEvent event) {
    eventBroadcaster.broadcast(event);
    if (event.type() == RunEvent.RunEventType.CANCELLED) {
        taskRegistry.cancel(event.runId());
    }
}
```

이 메서드에는 Redis, `SseEmitter`, `ObjectMapper` 타입을 사용하지 않는다.

- [ ] **Step 5: GREEN 확인**

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.application.service.run.SettlementRunApplicationServiceTest"
```

Expected: `SettlementRunApplicationServiceTest`의 기존 테스트와 신규 두 테스트가 모두 통과한다.

- [ ] **Step 6: Task 1 변경 범위 확인**

Run:

```bash
git diff --check -- ai-service/src/main/java/com/prompthub/ai/settlement/application ai-service/src/test/java/com/prompthub/ai/settlement/application
```

Expected: 출력 없이 종료 코드 0.

---

### Task 2: SSE Broadcaster 어댑터 적용

**Files:**

- Modify: `ai-service/src/main/java/com/prompthub/ai/settlement/presentation/sse/SseEmitterRegistry.java`
- Test: `ai-service/src/test/java/com/prompthub/ai/settlement/presentation/sse/SseEmitterRegistryTest.java`

**Interfaces:**

- Consumes: `SettlementRunEventBroadcaster.broadcast(RunEvent)` from Task 1
- Produces: Spring Bean `SseEmitterRegistry implements SettlementRunEventBroadcaster`

- [ ] **Step 1: broadcaster 계약의 실패 테스트 작성**

`SseEmitterRegistryTest`에 terminal 이벤트가 연결에 전달되고 연결이 종료되는 테스트를 추가한다.

```java
@Test
void broadcastSendsTerminalEventAndRemovesConnection() {
    UUID runId = UUID.randomUUID();
    SseEmitterRegistry registry = new SseEmitterRegistry();
    RecordingEmitter emitter = new RecordingEmitter();
    registry.register(runId, emitter, true);
    RunEvent event = RunEvent.done(
            runId,
            "정산 답변",
            Instant.parse("2026-07-22T12:00:00Z"));

    registry.broadcast(event);

    assertThat(emitter.sentCount()).isEqualTo(1);
    assertThat(emitter.completed()).isTrue();
    assertThat(registry.connectionCount(runId)).isZero();
}
```

테스트 안에 다음 emitter를 추가한다.

```java
private static final class RecordingEmitter extends SseEmitter {

    private final AtomicInteger sentCount = new AtomicInteger();
    private boolean completed;

    @Override
    public void send(SseEventBuilder builder) {
        sentCount.incrementAndGet();
    }

    @Override
    public void complete() {
        completed = true;
    }

    private int sentCount() {
        return sentCount.get();
    }

    private boolean completed() {
        return completed;
    }
}
```

`RunEvent`와 `Instant` import를 추가한다.

- [ ] **Step 2: RED 확인**

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.presentation.sse.SseEmitterRegistryTest"
```

Expected: `SseEmitterRegistry.broadcast(RunEvent)`가 없어 `compileTestJava`가 실패한다.

- [ ] **Step 3: SseEmitterRegistry가 broadcaster 구현**

클래스 선언을 변경한다.

```java
public class SseEmitterRegistry implements SettlementRunEventBroadcaster {
```

기존 `dispatch` 메서드의 이름만 변경하고 구현은 유지한다.

```java
@Override
public void broadcast(RunEvent event) {
    CopyOnWriteArrayList<EmitterSession> sessions = sessionsByRun.get(event.runId());
    if (sessions == null) {
        return;
    }

    for (EmitterSession session : sessions) {
        if (event.type() == RunEvent.RunEventType.DELTA && !session.acceptDeltas()) {
            continue;
        }
        send(event.runId(), session, toSseEvent(event));
    }

    if (event.terminal()) {
        for (EmitterSession session : sessions) {
            completeAndRemove(event.runId(), session);
        }
    }
}
```

Task 3에서 Subscriber를 새 유스케이스 경계로 전환하기 전까지 기존 생산 코드가 컴파일되도록
다음 호환 메서드를 임시로 유지한다.

```java
public void dispatch(RunEvent event) {
    broadcast(event);
}
```

- [ ] **Step 4: GREEN 확인**

Gradle은 선택한 테스트만 실행하더라도 전체 테스트 소스를 컴파일한다. 따라서 다음 Task의
`RedisSettlementRunEventPubSubIntegrationTest` fixture를 Step 4 실행 전에 새 Subscriber 생성자와
실제 `SettlementRunApplicationService` 경로로 먼저 전환한다. Task 4 Step 1의 코드를 그대로 적용한다.

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.presentation.sse.SseEmitterRegistryTest"
```

Expected: heartbeat 기존 테스트와 broadcaster 신규 테스트가 통과한다.

- [ ] **Step 5: 이전 dispatch 참조 임시 확인**

Run:

```bash
rg -n "\.dispatch\(" ai-service/src/main/java/com/prompthub/ai/settlement ai-service/src/test/java/com/prompthub/ai/settlement
```

Expected: `SseEmitterRegistry`의 임시 호환 메서드, 아직 변경하지 않은
`RedisSettlementRunEventSubscriber`와 해당 단위 테스트에만 결과가 남는다.

---

### Task 3: Redis Subscriber를 Run 유스케이스에 연결

**Files:**

- Modify: `ai-service/src/main/java/com/prompthub/ai/settlement/infrastructure/messaging/redis/RedisSettlementRunEventSubscriber.java`
- Test: `ai-service/src/test/java/com/prompthub/ai/settlement/infrastructure/messaging/redis/RedisSettlementRunEventSubscriberTest.java`

**Interfaces:**

- Consumes: `SettlementRunUseCase.handleEvent(RunEvent)` from Task 1
- Produces: `RedisSettlementRunEventSubscriber(ObjectMapper, SettlementRunUseCase)`

- [ ] **Step 1: Subscriber 테스트를 새 경계로 변경**

기존 `cancelledEventClosesEmittersBeforeCancellingKnownLocalFuture` 테스트를 다음 테스트로 교체한다.

```java
@Test
void delegatesDeserializedEventToRunUseCase() throws Exception {
    ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    SettlementRunUseCase useCase = mock(SettlementRunUseCase.class);
    RedisSettlementRunEventSubscriber subscriber =
            new RedisSettlementRunEventSubscriber(objectMapper, useCase);
    RunEvent event = RunEvent.cancelled(
            UUID.randomUUID(),
            Instant.parse("2026-07-22T12:00:00Z"));
    Message message = mock(Message.class);
    when(message.getBody()).thenReturn(objectMapper.writeValueAsBytes(event));

    subscriber.onMessage(message, new byte[0]);

    verify(useCase).handleEvent(event);
}

@Test
void ignoresMalformedPayloadWithoutCallingRunUseCase() {
    ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    SettlementRunUseCase useCase = mock(SettlementRunUseCase.class);
    RedisSettlementRunEventSubscriber subscriber =
            new RedisSettlementRunEventSubscriber(objectMapper, useCase);
    Message message = mock(Message.class);
    when(message.getBody()).thenReturn("not-json".getBytes(StandardCharsets.UTF_8));

    subscriber.onMessage(message, new byte[0]);

    verifyNoInteractions(useCase);
}
```

기존 `SseEmitterRegistry`, `SettlementRunTaskRegistry`, `InOrder` import를 제거하고 다음 import를 사용한다.

```java
import com.prompthub.ai.settlement.application.usecase.SettlementRunUseCase;
import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
```

- [ ] **Step 2: RED 확인**

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.infrastructure.messaging.redis.RedisSettlementRunEventSubscriberTest"
```

Expected: 새 Subscriber 생성자가 없어 `compileTestJava`가 실패한다.

- [ ] **Step 3: Subscriber 직접 의존 제거**

`RedisSettlementRunEventSubscriber` 필드와 생성자를 다음 구조로 변경한다.

```java
private final ObjectMapper objectMapper;
private final SettlementRunUseCase runUseCase;

public RedisSettlementRunEventSubscriber(
        ObjectMapper objectMapper,
        SettlementRunUseCase runUseCase
) {
    this.objectMapper = objectMapper;
    this.runUseCase = runUseCase;
}
```

`onMessage`의 정상 처리 부분은 다음 두 줄만 수행한다.

```java
RunEvent event = objectMapper.readValue(message.getBody(), RunEvent.class);
runUseCase.handleEvent(event);
```

`JacksonException | IllegalArgumentException` catch와 기존 warn 로그는 유지한다.

Subscriber와 단위 테스트에서 `dispatch` 참조를 제거한 뒤 `SseEmitterRegistry`의 임시
`dispatch(RunEvent)` 호환 메서드도 삭제한다.

- [ ] **Step 4: GREEN 확인**

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.infrastructure.messaging.redis.RedisSettlementRunEventSubscriberTest" --tests "com.prompthub.ai.settlement.application.service.run.SettlementRunApplicationServiceTest" --tests "com.prompthub.ai.settlement.presentation.sse.SseEmitterRegistryTest"
```

Expected: 세 테스트 클래스가 모두 통과한다.

- [ ] **Step 5: 계층 직접 의존 제거 확인**

Run:

```bash
rg -n "presentation\.sse|SettlementRunTaskRegistry|\.dispatch\(" ai-service/src/main/java/com/prompthub/ai/settlement/infrastructure/messaging/redis/RedisSettlementRunEventSubscriber.java ai-service/src/test/java/com/prompthub/ai/settlement/infrastructure/messaging/redis/RedisSettlementRunEventSubscriberTest.java
```

Expected: 결과 없음.

---

### Task 4: Redis Pub/Sub 통합 연결과 전체 회귀 검증

**Files:**

- Modify: `ai-service/src/test/java/com/prompthub/ai/settlement/infrastructure/messaging/redis/RedisSettlementRunEventPubSubIntegrationTest.java`
- Verify: `ai-service/src/main/java/com/prompthub/ai/settlement/**`
- Verify: `ai-service/src/test/java/com/prompthub/ai/settlement/**`

**Interfaces:**

- Consumes: `RedisSettlementRunEventSubscriber(ObjectMapper, SettlementRunUseCase)`, `SseEmitterRegistry implements SettlementRunEventBroadcaster`
- Produces: Redis Publisher → Subscriber → ApplicationService → SSE 통합 검증

- [ ] **Step 1: 통합 테스트 fixture를 실제 ApplicationService 경유로 변경**

`setUp`에서 각 Pod 역할의 `SettlementRunApplicationService`를 생성해 Subscriber에 주입한다.

```java
SettlementRunTaskRegistry firstTaskRegistry =
        new SettlementRunTaskRegistry(new SimpleMeterRegistry());
SettlementRunTaskRegistry secondTaskRegistry =
        new SettlementRunTaskRegistry(new SimpleMeterRegistry());
SettlementChatStateRepository stateRepository = mock(SettlementChatStateRepository.class);
AiSettlementProperties properties = AiSettlementTestFixtures.properties(true);
Clock clock = Clock.systemUTC();

SettlementRunApplicationService firstRunService = new SettlementRunApplicationService(
        stateRepository,
        firstRegistry,
        firstTaskRegistry,
        properties,
        clock);
SettlementRunApplicationService secondRunService = new SettlementRunApplicationService(
        stateRepository,
        secondRegistry,
        secondTaskRegistry,
        properties,
        clock);

firstContainer = listenerContainer(
        new RedisSettlementRunEventSubscriber(objectMapper, firstRunService));
secondContainer = listenerContainer(
        new RedisSettlementRunEventSubscriber(objectMapper, secondRunService));
```

다음 import를 추가한다.

```java
import static org.mockito.Mockito.mock;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.settlement.AiSettlementTestFixtures;
import com.prompthub.ai.settlement.application.service.run.SettlementRunApplicationService;
import com.prompthub.ai.settlement.domain.repository.SettlementChatStateRepository;
import java.time.Clock;
```

기존 두 registry의 emitter 등록, delta/done 발행과 assertion은 변경하지 않는다.

- [ ] **Step 2: Redis Pub/Sub 통합 테스트 실행**

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.infrastructure.messaging.redis.RedisSettlementRunEventPubSubIntegrationTest"
```

Expected: Testcontainers Redis를 통해 첫 스트림은 delta와 done 2건, 재연결 스트림은 done 1건을 받고 두 연결이 모두 종료된다.

- [ ] **Step 3: 이전 직접 의존과 메서드명 제거 확인**

Run:

```bash
rg -n "SseEmitterRegistry|SettlementRunTaskRegistry|\.dispatch\(" ai-service/src/main/java/com/prompthub/ai/settlement/infrastructure/messaging/redis/RedisSettlementRunEventSubscriber.java ai-service/src/test/java/com/prompthub/ai/settlement/infrastructure/messaging/redis/RedisSettlementRunEventSubscriberTest.java
```

Expected: 결과 없음.

Run:

```bash
rg -n "import com\.prompthub\.ai\.settlement\.presentation" ai-service/src/main/java/com/prompthub/ai/settlement/infrastructure
```

Expected: 결과 없음.

- [ ] **Step 4: 새 포트와 구현 연결 확인**

Run:

```bash
rg -n "interface SettlementRunEventBroadcaster|implements SettlementRunEventBroadcaster|void handleEvent\(RunEvent event\)|runUseCase\.handleEvent\(event\)" ai-service/src/main/java/com/prompthub/ai/settlement
```

Expected: broadcaster 인터페이스, SSE 구현, 유스케이스 선언·구현과 Subscriber 위임이 각각 검색된다.

- [ ] **Step 5: AI Service 전체 테스트 재실행**

Run:

```bash
./gradlew :ai-service:test --rerun-tasks
```

Expected: AI Service 전체 테스트가 모두 통과한다. 로컬 Kafka 미기동 경고는 테스트 실패가 아니며, 최종 판단은 Gradle 종료 코드와 실패 테스트 수를 기준으로 한다.

- [ ] **Step 6: 전체 diff 정합성 검증**

Run:

```bash
git diff --check
git diff --cached --check
git status --short
```

Expected:

- 두 diff check가 출력 없이 종료 코드 0이다.
- status에는 기존 1차 Settlement/User/AI 패키지 리팩토링과 이번 2A 변경만 존재한다.
- `ai.inspection`, `ai.recommendation` 아래에는 이번 2A로 인한 변경이 없다.
- 테스트 및 실환경 검증을 실행하지 않은 항목은 성공으로 표현하지 않는다.
