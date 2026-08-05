# AI 정산 패키지 아키텍처 리팩토링 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AI Service의 `settlement` bounded context를 승인된 클린 아키텍처 패키지와 Client/Gateway/Adapter 네이밍으로 이동하되 실행 동작과 공개 계약은 유지한다.

**Architecture:** `com.prompthub.ai.settlement`를 bounded context 기준 패키지로 유지하고 그 아래를 presentation/application/domain/infrastructure 계층으로 정리한다. 내부 User Service gRPC는 application `Client`와 infrastructure `GrpcClientAdapter`, OpenAI는 application `Gateway`와 infrastructure external Adapter 경계로 분리한다.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AI, gRPC, Redis, SSE, JUnit 5, Mockito, Gradle

## Global Constraints

- 변경 범위는 `.claude/rules/clean-architecture.md`, `ai-service/src/main/java/com/prompthub/ai/settlement/**`, 대응 테스트, 본 설계·계획 문서로 제한한다.
- `ai.inspection`, `ai.recommendation`, REST·gRPC·Redis·SSE 계약과 설정 key를 변경하지 않는다.
- Redis→SSE 역의존, `TokenCountEstimator`, `MeterRegistry`, `AiSettlementProperties` 구조 개선은 2차로 남긴다.
- ArchUnit을 추가하지 않는다.
- 기존 Settlement Service와 User Service의 staged/unstaged/untracked 변경을 수정하거나 정리하지 않는다.
- stage, commit, push, PR을 수행하지 않는다.
- 파일 이동과 내용 변경은 `apply_patch`를 사용하고 대량 import 치환은 AI settlement main/test 경로로 제한한다.

---

### Task 1: 다중 bounded context 규칙 명시

**Files:**
- Modify: `.claude/rules/clean-architecture.md`
- Reference: `docs/superpowers/specs/2026-08-05-ai-settlement-package-architecture-refactor-design.md`

**Interfaces:**
- Consumes: 기존 §2의 서비스 base package 계층 우선 규칙
- Produces: 각 bounded context package를 architecture base로 사용할 수 있다는 공용 규칙

- [ ] **Step 1: 기존 규칙 확인**

Run: `sed -n '35,150p' .claude/rules/clean-architecture.md`

Expected: 다중 bounded context 예외가 없다.

- [ ] **Step 2: `§2-2. 여러 bounded context를 가진 서비스` 추가**

다음 조건을 명시한다.

```text
하나의 서비스 모듈에 독립된 bounded context가 여러 개 있으면
com.prompthub.<service>.<context>를 각 context의 architecture base package로 사용할 수 있다.
각 context base 아래는 presentation/application/domain/infrastructure 계층을 유지한다.
context 간 내부 구현 직접 의존을 금지하고 공개 유스케이스 또는 계약으로 연결한다.
```

- [ ] **Step 3: 규칙 검증**

Run: `rg -n "bounded context|architecture base|context 간" .claude/rules/clean-architecture.md`

Expected: 세 핵심 문구가 검색된다.

- [ ] **Step 4: 기존 사용자 변경 보존 확인**

Run: `git diff -- .claude/rules/clean-architecture.md`

Expected: 기존 변경과 새 bounded context 설명만 존재한다.

### Task 2: 도메인 모델과 Run 이벤트 표준 위치 이동

**Files:**
- Move: `domain/conversation/*.java` → `domain/model/conversation/`
- Move: `domain/run/*.java` → `domain/model/run/`
- Move: `application/event/RunEvent.java` → `domain/event/RunEvent.java`
- Move tests: `domain/conversation/ChatPairTest.java`, `domain/run/AgentRunTest.java` → 대응 `domain/model/**` 경로
- Modify imports: AI settlement의 모든 main/test 소비자

**Interfaces:**
- Consumes: 기존 도메인 타입의 메서드, record 필드와 상태 전이
- Produces: 동일 API의 `domain.model.*`, `domain.event.RunEvent`

- [ ] **Step 1: 테스트를 목표 패키지로 먼저 이동**

`ChatPairTest`, `AgentRunTest`의 경로/package/import만 바꾸고 단언은 유지한다.

- [ ] **Step 2: RED 확인**

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.domain.model.conversation.ChatPairTest" --tests "com.prompthub.ai.settlement.domain.model.run.AgentRunTest"
```

Expected: 새 프로덕션 package가 없어 `compileTestJava`가 실패한다.

- [ ] **Step 3: 프로덕션 도메인과 이벤트 이동**

```java
package com.prompthub.ai.settlement.domain.model.conversation;
package com.prompthub.ai.settlement.domain.model.run;
package com.prompthub.ai.settlement.domain.event;
```

모든 AI settlement main/test import를 새 경로로 갱신하고 동작 코드는 수정하지 않는다.

- [ ] **Step 4: GREEN 확인**

Run은 Step 2와 같다.

Expected: 두 테스트 클래스가 통과한다.

- [ ] **Step 5: 이전 경로 제거 확인**

Run: `rg -n "ai\.settlement\.(domain\.(conversation|run)|application\.event)" ai-service/src/main/java/com/prompthub/ai/settlement ai-service/src/test/java/com/prompthub/ai/settlement`

Expected: 결과 없음.

### Task 3: application 포트와 gRPC/OpenAI 어댑터 이동

**Files:**
- Move/rename: `application/port/SellerSettlementAnalysisQuery.java` → `application/client/user/SellerSettlementAnalysisClient.java`
- Move/rename: `application/port/SettlementAgent.java` → `application/gateway/external/SettlementAgentGateway.java`
- Move: `application/port/SettlementRunEventPublisher.java` → `application/usecase/SettlementRunEventPublisher.java`
- Move/rename: `infrastructure/client/user/SellerSettlementQueryClient.java` → `infrastructure/grpc/client/user/SellerSettlementAnalysisGrpcClientAdapter.java`
- Move/rename: `infrastructure/client/user/config/UserGrpcClientConfig.java` → `infrastructure/grpc/client/user/SellerSettlementAnalysisGrpcClientConfig.java`
- Move: `infrastructure/client/openai/{FinalAnswerPolicy,OpenAiCallRetryExecutor,SellerSettlementAnalysisTools,SettlementPromptFactory,ToolExecutionGuard}.java` → `infrastructure/external/openai/`
- Move/rename: `infrastructure/client/openai/SpringAiSettlementAgent.java` → `infrastructure/external/openai/SpringAiSettlementAgentAdapter.java`
- Move/rename: 대응 infrastructure client 테스트
- Modify: application services, Redis publisher/subscriber, fixtures and affected imports

**Interfaces:**
- Consumes: `SellerSettlementAnalysisQuery`와 `SettlementAgent`의 기존 메서드 및 중첩 record
- Produces: 동일 signature의 `SellerSettlementAnalysisClient`, `SettlementAgentGateway`; 각 기술 Adapter가 구현

- [ ] **Step 1: 테스트를 목표 이름과 패키지로 먼저 변경**

```text
SellerSettlementQueryClientTest
  → infrastructure.grpc.client.user.SellerSettlementAnalysisGrpcClientAdapterTest
SpringAiSettlementAgentTest
  → infrastructure.external.openai.SpringAiSettlementAgentAdapterTest
나머지 OpenAI helper 테스트
  → infrastructure.external.openai
```

생성 대상과 import만 바꾸고 단언은 유지한다.

- [ ] **Step 2: RED 확인**

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.infrastructure.grpc.client.user.SellerSettlementAnalysisGrpcClientAdapterTest" --tests "com.prompthub.ai.settlement.infrastructure.external.openai.SpringAiSettlementAgentAdapterTest"
```

Expected: 목표 port/adapter가 없어 `compileTestJava`가 실패한다.

- [ ] **Step 3: application 포트 이동·이름 변경**

```java
public interface SellerSettlementAnalysisClient {
    DashboardSummaryResult getDashboardSummary(UUID actorId);
    SettlementSummaryResult getSummary(UUID actorId, String periodType, String period);
    SettlementComparisonResult comparePeriods(
            UUID actorId, String periodType, String currentPeriod, String comparisonPeriod);
    WeeklyBreakdownResult getWeeklyBreakdown(UUID actorId, String month);
    PayoutStatusResult getPayoutStatus(UUID actorId, String settlementMonth);
}

public interface SettlementAgentGateway {
    AgentResult answer(AgentRequest request);
}

public interface SettlementRunEventPublisher {
    void progress(UUID runId, RunStage stage, Instant occurredAt);
    void delta(UUID runId, long sequence, String text, Instant occurredAt);
    void done(UUID runId, String answer, Instant completedAt);
    void failed(UUID runId, String code, String message, Instant failedAt);
    void cancelled(UUID runId, Instant cancelledAt);
}
```

`SellerSettlementAnalysisClient`의 결과 record와 `SettlementAgentGateway`의 `AgentRequest`,
`ProgressListener`, `AgentResult`는 기존 validation을 포함해 그대로 옮기고 domain import만 Task 2의 새 경로로 바꾼다.

- [ ] **Step 4: gRPC 어댑터와 설정 이동·이름 변경**

```java
public class SellerSettlementAnalysisGrpcClientAdapter implements SellerSettlementAnalysisClient
public class SellerSettlementAnalysisGrpcClientConfig
```

stub, metadata, deadline, token, mapping과 예외 처리는 변경하지 않는다.

- [ ] **Step 5: OpenAI 어댑터 이동·이름 변경**

```java
public class SpringAiSettlementAgentAdapter implements SettlementAgentGateway
```

helper를 같은 패키지로 옮기고 prompt/tool/retry/final-answer 코드는 변경하지 않는다.

- [ ] **Step 6: 소비자 갱신**

`SettlementRunExecutor`, `SellerSettlementAnalysisTools`, Redis publisher/subscriber, fixtures와 테스트 import를 목표 이름으로 바꾼다.

- [ ] **Step 7: GREEN 확인**

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.infrastructure.grpc.client.user.SellerSettlementAnalysisGrpcClientAdapterTest" --tests "com.prompthub.ai.settlement.infrastructure.external.openai.*" --tests "com.prompthub.ai.settlement.application.service.*"
```

Expected: gRPC, OpenAI, application service 테스트 통과.

- [ ] **Step 8: 이전 포트/클래스 제거 확인**

Run:

```bash
rg -n "application\.port|infrastructure\.client|SellerSettlementAnalysisQuery|SellerSettlementQueryClient|SpringAiSettlementAgent\b|SettlementAgent\b|UserGrpcClientConfig" ai-service/src/main/java/com/prompthub/ai/settlement ai-service/src/test/java/com/prompthub/ai/settlement
```

Expected: 결과 없음.

### Task 4: presentation과 web 패키지 응집 정리

**Files:**
- Move: `presentation/rest/SettlementChatController.java` → `presentation/controller/SettlementChatController.java`
- Move: `presentation/sse/SettlementRunEventController.java` → `presentation/controller/SettlementRunEventController.java`
- Move: `presentation/rest/dto/CreateSettlementChatMessageRequest.java` → `presentation/dto/request/`
- Move: `presentation/rest/dto/{AcceptedRunResponse,AiApiResponse,ConversationResponse}.java` → `presentation/dto/response/`
- Keep: `presentation/sse/{SseEmitterRegistry,SseHeartbeatScheduler}.java`
- Move: `infrastructure/web/config/AiSettlementWebMvcConfig.java` → `infrastructure/web/AiSettlementWebMvcConfig.java`
- Move: `infrastructure/web/interceptor/AiSettlementFeatureInterceptor.java` → `infrastructure/web/AiSettlementFeatureInterceptor.java`
- Move: Controller tests to `presentation/controller/`
- Modify: Redis subscriber, web config and tests imports

**Interfaces:**
- Consumes: 기존 REST mapping, request/response record, SSE와 interceptor behavior
- Produces: 동일 HTTP/SSE 동작의 역할 기반 패키지

- [ ] **Step 1: Controller 테스트 선이동**

두 Controller 테스트의 경로/package와 DTO import만 변경한다.

- [ ] **Step 2: RED 확인**

Run: `./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.presentation.controller.*"`

Expected: 새 Controller/DTO가 없어 `compileTestJava` 실패.

- [ ] **Step 3: Controller와 DTO 이동**

Controller는 `presentation.controller`, 요청은 `presentation.dto.request`, 응답은 `presentation.dto.response`로 옮긴다. annotation, path, status와 JSON 필드는 유지한다.

- [ ] **Step 4: infrastructure web 평탄화**

두 web 클래스를 `infrastructure.web`로 옮기고 import만 갱신한다. interceptor path와 feature flag는 유지한다.

- [ ] **Step 5: GREEN 확인**

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.presentation.controller.*" --tests "com.prompthub.ai.settlement.presentation.sse.*"
```

Expected: Controller/SSE 테스트 통과.

- [ ] **Step 6: 이전 패키지 제거 확인**

Run:

```bash
rg -n "presentation\.rest|presentation\.sse\.SettlementRunEventController|infrastructure\.web\.(config|interceptor)" ai-service/src/main/java/com/prompthub/ai/settlement ai-service/src/test/java/com/prompthub/ai/settlement
```

Expected: 결과 없음.

### Task 5: 전체 회귀와 구조 검증

**Files:**
- Verify: `ai-service/src/main/**`, `ai-service/src/test/**`
- Review: `.claude/rules/clean-architecture.md`, design and plan documents

**Interfaces:**
- Consumes: Tasks 1–4의 최종 package/class names
- Produces: AI Service 검증 결과와 2차 리팩토링 권장 계획

- [ ] **Step 1: 최종 파일 트리 확인**

Run: `find ai-service/src/main/java/com/prompthub/ai/settlement -type f | sort`

Expected: 승인된 설계 트리와 일치.

- [ ] **Step 2: 금지된 이전 경로·이름 검색**

Run:

```bash
rg -n "application\.(port|event)|domain\.(conversation|run)|infrastructure\.client|presentation\.rest|infrastructure\.web\.(config|interceptor)|SellerSettlementAnalysisQuery|SellerSettlementQueryClient|UserGrpcClientConfig|SpringAiSettlementAgent\b|SettlementAgent\b" ai-service/src/main/java/com/prompthub/ai/settlement ai-service/src/test/java/com/prompthub/ai/settlement
```

Expected: 결과 없음.

- [ ] **Step 3: 새 application→infrastructure 의존 확인**

Run: `rg -n "^import .*\.infrastructure\." ai-service/src/main/java/com/prompthub/ai/settlement/application`

Expected: 결과 없음.

- [ ] **Step 4: AI Service 전체 테스트**

Run: `./gradlew :ai-service:test --rerun-tasks`

Expected: `BUILD SUCCESSFUL`, 실패 0건.

- [ ] **Step 5: diff 검증**

Run:

```bash
git diff --check
git diff --cached --check
git status --short
```

Expected: whitespace 오류가 없고 기존 Settlement/User 변경이 보존되며 새 변경은 합의한 경로에만 존재한다.

- [ ] **Step 6: 2차 리팩토링 계획 보고**

최종 응답에서 다음 순서로 변경 대상, 대안, 권장안, 테스트와 운영 영향을 제시한다.

```text
1. Redis Subscriber → application 이벤트 처리 경계 → SSE 출력 어댑터로 의존 역전
2. TokenCountEstimator를 기술 중립 TokenCounter 경계로 분리하거나 OpenAI adapter로 이동
3. MeterRegistry를 application 밖 decorator/observer 또는 metrics port로 분리
4. AiSettlementProperties를 application 정책, OpenAI, User gRPC, SSE 설정으로 분해
5. Redis Pub/Sub 유실과 SSE 재연결·다중 Pod 취소 시나리오 검증
```
