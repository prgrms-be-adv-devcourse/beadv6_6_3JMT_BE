# Order V2 Commit and Pull Request Publication Plan

> **For agentic workers:** Execute inline in the current session. The user limited superpowers usage to brainstorming and writing-plans, so no execution or subagent skill is used.

**Goal:** 주문 v2 생성 리팩터링 변경을 기능별 커밋으로 분리하고, 저장소 PR 템플릿에 맞춘 Draft PR을 통합 브랜치 대상으로 게시한다.

**Architecture:** 독립적으로 컴파일 가능한 기반 변경을 먼저 커밋하고, 주문 생성의 API·애플리케이션·이벤트·만료 처리는 하나의 수직 기능 커밋으로 묶는다. 추가 단위·통합·직렬화 테스트는 검증 목적별 커밋으로 분리한다. 마지막에 전체 테스트와 빌드를 실행한 뒤 현재 브랜치를 push하고 Draft PR을 생성한다.

**Tech Stack:** Git, GitHub CLI, Gradle, JUnit 5, Spring Boot, GitHub Pull Request

## Global Constraints

- 작업 브랜치는 `feat/#368-order-v2-create`를 유지한다.
- 변경 범위는 `order-service/**`와 `docs/**`로 제한한다.
- `payment-service`, `product-service`, `gateway`, `common-module`을 수정하지 않는다.
- 기존 사용자 문서 `docs/superpowers/plans/2026-07-16-order-v2-checkout-refund-refactoring.md`를 stage하지 않는다.
- 이 게시 계획 문서도 기능 PR에 stage하지 않는다.
- `git add -A`를 사용하지 않고 커밋별 명시 경로만 stage한다.
- 커밋 메시지는 `type: 변경 대상 작업 요약` 형식과 허용 타입만 사용한다.
- PR은 통합 브랜치를 base로 하는 Draft로 생성한다.
- 각 커밋 전 staged diff를 확인하고, 전체 게시 전 `:order-service:test`, `:order-service:build`, `git diff --check`를 통과시킨다.

---

### Task 1: GitHub 인증과 통합 브랜치 확인

**Files:** 없음

**Interfaces:**
- Consumes: 로컬 Git 저장소와 GitHub 계정 인증
- Produces: 유효한 `gh` 세션, PR base와 선행 PR 상태

- [ ] **Step 1: GitHub CLI 재인증**

```bash
gh auth login -h github.com
gh auth status
```

Expected: `Logged in to github.com account oxix97`와 유효한 token scope가 표시된다.

- [ ] **Step 2: 선행 PR과 통합 브랜치 확인**

```bash
gh pr view 375 --json number,state,headRefName,baseRefName,url
```

Expected: `headRefName`은 `feat/#367-order-v2-domain-outbox`이며, 반환된 `baseRefName`을 #368 PR의 통합 base로 사용한다. 선행 PR이 열려 있으면 #368 PR 본문에 의존성을 기록한다.

---

### Task 2: HTTP 405 예외 처리 커밋

**Files:**
- Modify: `src/main/java/com/prompthub/order/global/exception/ErrorCode.java`
- Modify: `src/main/java/com/prompthub/order/global/exception/GlobalExceptionHandler.java`
- Modify: `src/test/java/com/prompthub/order/global/exception/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: Spring MVC `HttpRequestMethodNotSupportedException`
- Produces: HTTP 405와 `V002` 공통 오류 응답

- [ ] **Step 1: 관련 테스트 실행**

```bash
../gradlew :order-service:test --tests "com.prompthub.order.global.exception.GlobalExceptionHandlerTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: 명시 경로 stage와 diff 확인**

```bash
git add src/main/java/com/prompthub/order/global/exception/ErrorCode.java src/main/java/com/prompthub/order/global/exception/GlobalExceptionHandler.java src/test/java/com/prompthub/order/global/exception/GlobalExceptionHandlerTest.java
git diff --cached --check
git diff --cached --stat
```

- [ ] **Step 3: 커밋**

```bash
git commit -m "fix: order-service 지원하지 않는 HTTP 메서드 응답 처리 수정"
```

---

### Task 3: 주문 다건 저장 계약 커밋

**Files:**
- Modify: `src/main/java/com/prompthub/order/domain/repository/OrderRepository.java`
- Modify: `src/main/java/com/prompthub/order/infra/persistence/order/OrderAdapter.java`

**Interfaces:**
- Consumes: `List<Order>`
- Produces: `OrderRepository.saveAll(List<Order>)`

- [ ] **Step 1: 컴파일 검증**

```bash
../gradlew :order-service:compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: 명시 경로 stage와 커밋**

```bash
git add src/main/java/com/prompthub/order/domain/repository/OrderRepository.java src/main/java/com/prompthub/order/infra/persistence/order/OrderAdapter.java
git diff --cached --check
git commit -m "refactor: order-service 주문 저장소 다건 저장 계약 추가"
```

---

### Task 4: 주문 생성 명령과 결과 모델 커밋

**Files:**
- Create: `src/main/java/com/prompthub/order/application/dto/CreateOrderCommand.java`
- Create: `src/main/java/com/prompthub/order/application/dto/CreateOrderResult.java`
- Create: `src/main/java/com/prompthub/order/application/dto/OrderItem.java`

**Interfaces:**
- Consumes: 구매자 주문 상품 요청과 저장된 `Order` 목록
- Produces: `CreateOrderCommand`, `OrderItem`, `CreateOrderResult`

- [ ] **Step 1: 컴파일 검증**

```bash
../gradlew :order-service:compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: 명시 경로 stage와 커밋**

```bash
git add src/main/java/com/prompthub/order/application/dto/CreateOrderCommand.java src/main/java/com/prompthub/order/application/dto/CreateOrderResult.java src/main/java/com/prompthub/order/application/dto/OrderItem.java
git diff --cached --check
git commit -m "refactor: order-service 주문 생성 명령과 다건 결과 모델 분리"
```

---

### Task 5: 판매자별 주문 생성 수직 기능 커밋

**Files:**
- Modify: `src/main/java/com/prompthub/order/application/event/order/OrderCreatedEvent.java`
- Modify: `src/main/java/com/prompthub/order/application/service/event/OrderEventMessageFactory.java`
- Delete: `src/main/java/com/prompthub/order/application/service/order/CreateOrderCommandHandler.java`
- Create: `src/main/java/com/prompthub/order/application/service/order/OrderCommandHandler.java`
- Create: `src/main/java/com/prompthub/order/application/service/order/OrderCreator.java`
- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderPolicyService.java`
- Modify: `src/main/java/com/prompthub/order/application/usecase/CreateOrderUseCase.java`
- Modify: `src/main/java/com/prompthub/order/global/web/WebConfig.java`
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayload.java`
- Modify: `src/main/java/com/prompthub/order/infra/redis/OrderExpirationRegistrar.java`
- Modify: `src/main/java/com/prompthub/order/presentation/OrderController.java`
- Modify: `src/main/java/com/prompthub/order/presentation/dto/request/CreateOrderRequest.java`
- Modify: `src/main/java/com/prompthub/order/presentation/dto/response/CreateOrderResponse.java`
- Delete: `src/main/java/com/prompthub/order/presentation/dto/response/OrderProductsResponse.java`
- Modify: 기존 주문 생성 연관 테스트와 fixture
- Create: `src/test/java/com/prompthub/order/fixture/OrderV2Fixture.java`

**Interfaces:**
- Consumes: `CreateOrderCommand`, Product snapshot 목록, 주문 다건 저장 계약
- Produces: 판매자별 주문, 장바구니 보존, 단일 `ORDER_CREATED`, 전체 주문 만료 이벤트, `POST /api/v2/orders`

- [ ] **Step 1: 기존 테스트 호환 변경과 production 파일 stage**

```bash
git add src/main/java/com/prompthub/order/application/event/order/OrderCreatedEvent.java src/main/java/com/prompthub/order/application/service/event/OrderEventMessageFactory.java src/main/java/com/prompthub/order/application/service/order/CreateOrderCommandHandler.java src/main/java/com/prompthub/order/application/service/order/OrderCommandHandler.java src/main/java/com/prompthub/order/application/service/order/OrderCreator.java src/main/java/com/prompthub/order/application/service/order/OrderPolicyService.java src/main/java/com/prompthub/order/application/usecase/CreateOrderUseCase.java src/main/java/com/prompthub/order/global/web/WebConfig.java src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayload.java src/main/java/com/prompthub/order/infra/redis/OrderExpirationRegistrar.java src/main/java/com/prompthub/order/presentation/OrderController.java src/main/java/com/prompthub/order/presentation/dto/request/CreateOrderRequest.java src/main/java/com/prompthub/order/presentation/dto/response/CreateOrderResponse.java src/main/java/com/prompthub/order/presentation/dto/response/OrderProductsResponse.java src/test/java/com/prompthub/order/application/service/event/outbox/OutboxEventAppenderTest.java src/test/java/com/prompthub/order/application/service/order/CreateOrderCommandHandlerTest.java src/test/java/com/prompthub/order/application/service/order/OrderCreationResilienceIntegrationTest.java src/test/java/com/prompthub/order/application/service/order/OrderPolicyServiceTest.java src/test/java/com/prompthub/order/fixture/OrderFixture.java src/test/java/com/prompthub/order/fixture/OrderV2Fixture.java src/test/java/com/prompthub/order/infra/messaging/kafka/producer/OutboxRelayTest.java src/test/java/com/prompthub/order/infra/redis/OrderExpirationRegistrarTest.java src/test/java/com/prompthub/order/presentation/OrderControllerTest.java
```

- [ ] **Step 2: 회귀 테스트와 staged diff 검증**

```bash
../gradlew :order-service:test
git diff --cached --check
git diff --cached --stat
```

Expected: `BUILD SUCCESSFUL`, 계획 문서가 staged 목록에 없음.

- [ ] **Step 3: 커밋**

```bash
git commit -m "feat: order-service 판매자별 주문 생성과 단일 이벤트 발행 구현"
```

---

### Task 6: 주문 생성 애플리케이션 단위 테스트 커밋

**Files:**
- Create: `src/test/java/com/prompthub/order/application/service/order/OrderCommandHandlerTest.java`
- Create: `src/test/java/com/prompthub/order/application/service/order/OrderCreatorTest.java`
- Create: `src/test/java/com/prompthub/order/application/service/event/OrderEventMessageFactoryTest.java`

**Interfaces:**
- Consumes: 주문 생성 Handler, Creator, 이벤트 Factory
- Produces: snapshot 검증, 판매자 그룹 순서, 단일 group 이벤트 단위 테스트

- [ ] **Step 1: 테스트와 커밋**

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderCommandHandlerTest" --tests "com.prompthub.order.application.service.order.OrderCreatorTest" --tests "com.prompthub.order.application.service.event.OrderEventMessageFactoryTest"
git add src/test/java/com/prompthub/order/application/service/order/OrderCommandHandlerTest.java src/test/java/com/prompthub/order/application/service/order/OrderCreatorTest.java src/test/java/com/prompthub/order/application/service/event/OrderEventMessageFactoryTest.java
git diff --cached --check
git commit -m "test: order-service 판매자별 주문 생성 정책 검증 추가"
```

---

### Task 7: 주문 생성 API와 인증 테스트 커밋

**Files:**
- Create: `src/test/java/com/prompthub/order/presentation/OrderControllerCreateTest.java`
- Create: `src/test/java/com/prompthub/order/global/web/OrderV2WebConfigTest.java`

**Interfaces:**
- Consumes: `OrderController`, 실제 `WebConfig`
- Produces: v2 요청·응답·검증·인증 및 v1 회귀 테스트

- [ ] **Step 1: 테스트와 커밋**

```bash
../gradlew :order-service:test --tests "com.prompthub.order.presentation.OrderControllerCreateTest" --tests "com.prompthub.order.global.web.OrderV2WebConfigTest"
git add src/test/java/com/prompthub/order/presentation/OrderControllerCreateTest.java src/test/java/com/prompthub/order/global/web/OrderV2WebConfigTest.java
git diff --cached --check
git commit -m "test: order-service v2 주문 생성 API와 인증 검증 추가"
```

---

### Task 8: 주문 생성 트랜잭션 테스트 커밋

**Files:**
- Create: `src/test/java/com/prompthub/order/application/service/order/OrderCreationTransactionIntegrationTest.java`

**Interfaces:**
- Consumes: 실제 Spring 트랜잭션과 주문 생성 Handler
- Produces: 외부 조회 경계, 주문·Outbox 원자성, rollback 검증

- [ ] **Step 1: 테스트와 커밋**

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderCreationTransactionIntegrationTest"
git add src/test/java/com/prompthub/order/application/service/order/OrderCreationTransactionIntegrationTest.java
git diff --cached --check
git commit -m "test: order-service 주문 생성 트랜잭션 원자성 검증 추가"
```

---

### Task 9: ORDER_CREATED 직렬화 계약 테스트 커밋

**Files:**
- Create: `src/test/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayloadSerializationTest.java`
- Create: `src/test/resources/contracts/order-created-v2.json`

**Interfaces:**
- Consumes: `EventMessage<OrderCreatedPayload>`
- Produces: 주문 묶음 envelope와 payload Golden JSON 계약

- [ ] **Step 1: 테스트와 커밋**

```bash
../gradlew :order-service:test --tests "com.prompthub.order.infra.messaging.kafka.event.OrderCreatedPayloadSerializationTest"
git add src/test/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayloadSerializationTest.java src/test/resources/contracts/order-created-v2.json
git diff --cached --check
git commit -m "test: order-service ORDER_CREATED 직렬화 계약 검증 추가"
```

---

### Task 10: 주문 만료 AFTER_COMMIT 테스트 커밋

**Files:**
- Create: `src/test/java/com/prompthub/order/infra/redis/OrderExpirationAfterCommitIntegrationTest.java`

**Interfaces:**
- Consumes: `OrderCreatedEvent`, `OrderExpirationRegistrar`
- Produces: commit 이후 다건 등록과 rollback 미등록 검증

- [ ] **Step 1: 테스트와 커밋**

```bash
../gradlew :order-service:test --tests "com.prompthub.order.infra.redis.OrderExpirationAfterCommitIntegrationTest"
git add src/test/java/com/prompthub/order/infra/redis/OrderExpirationAfterCommitIntegrationTest.java
git diff --cached --check
git commit -m "test: order-service 주문 만료 등록 커밋 시점 검증 추가"
```

---

### Task 11: 전체 검증과 원격 push

**Files:** 없음

**Interfaces:**
- Consumes: Task 2부터 Task 10까지의 커밋
- Produces: 검증된 원격 `feat/#368-order-v2-create` 브랜치

- [ ] **Step 1: 전체 검증**

```bash
../gradlew :order-service:test
../gradlew :order-service:build
git diff --check
git status --short
git log --oneline --decorate -9
```

Expected: 두 Gradle 명령이 `BUILD SUCCESSFUL`, diff check 성공, 기능 코드 미커밋 파일 없음, 제외한 계획 문서만 untracked.

- [ ] **Step 2: 원격 push**

```bash
git push -u origin 'feat/#368-order-v2-create'
```

Expected: 원격 tracking branch가 설정된다.

---

### Task 12: PR 템플릿 작성과 Draft PR 생성

**Files:**
- Read: 저장소의 `.github/**` PR 템플릿
- Create: `/private/tmp/order-v2-pr-368.md`

**Interfaces:**
- Consumes: 저장소 PR 템플릿, issue #368, 선행 PR #375, 테스트 결과
- Produces: 통합 브랜치를 base로 하는 Draft PR

- [ ] **Step 1: PR 템플릿 검색과 확인**

```bash
rg --files .github | rg -i "pull_request_template|pr_template"
```

검색된 템플릿 전체를 읽고 항목 순서와 체크리스트를 그대로 유지한다.

- [ ] **Step 2: PR 본문 작성**

본문에는 다음 사실을 실제 템플릿 항목에 배치한다.

- Related 또는 Closed: `#368`
- 선행 의존성: `#375`가 열려 있으면 병합 후 diff가 축소됨
- 변경: 판매자별 주문 생성, 장바구니 보존, 단일 `ORDER_CREATED`, AFTER_COMMIT 만료 등록
- 외부 게이트: Payment Service, Gateway, Frontend는 변경하지 않음
- 검증: `../gradlew :order-service:test`, `../gradlew :order-service:build`

- [ ] **Step 3: Draft PR 생성**

```bash
gh pr create --draft --base 'feat/#366-order-v2-checkout-refund' --head 'feat/#368-order-v2-create' --title '[FEATURE] order-service - 판매자별 주문 생성 API 추가' --body-file /private/tmp/order-v2-pr-368.md
```

Expected: 생성된 PR URL이 반환되고 관련 이슈와 테스트 결과가 본문에 포함된다.

---

## Self-Review

- 범위: 모든 기능 및 테스트 변경이 `order-service/**`에 한정된다.
- 커밋: 허용된 타입과 소괄호 없는 메시지만 사용한다.
- 보안: secret, token, `.env`를 stage하지 않는다.
- 계약: `ORDER_CREATED`, `ORDER_GROUP`, `eventId == aggregateId`를 PR에 기록한다.
- 제외: 두 계획 문서는 feature commit과 PR diff에 포함하지 않는다.
- 게시: 인증, 전체 테스트, build, push가 성공한 뒤에만 Draft PR을 생성한다.
