# Order Failure Cart Compensation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 결제 실패 이벤트 또는 결제 결과 미수신 타임아웃 시 주문 상태·주문상품 상태·장바구니·Kafka 처리 이력을 하나의 로컬 보상 트랜잭션으로 반영하고, 늦은 결제 승인이 도착하면 승인 상태와 장바구니 제거가 최종 결과가 되게 한다.

**Architecture:** `PaymentFailedProcessor`와 `OrderExpirationWorker`는 공통 `OrderFailureCompensationService`로 위임한다. 이 서비스만 보상 DB 트랜잭션을 소유하며 항상 `Order -> Cart` 순서로 루트 행을 잠근다. Redis 만료 정보는 DB 커밋 후 애플리케이션 이벤트 리스너가 제거하고, Kafka의 기존 processed-event 및 DLT 정책과 Redis Worker의 기존 재시도·DLQ 정책을 유지한다.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, PostgreSQL, Flyway, Kafka, Redis, JUnit 5, Mockito, Embedded Kafka, Testcontainers PostgreSQL, Gradle Groovy DSL

## Global Constraints

- 저장소 루트 기준 수정 가능 경로는 `order-service/**`뿐이다. 문서는 그 안의 `order-service/docs/**`에서만 수정한다.
- 현재 모듈 기준으로는 `src/**`, `build.gradle`, `docs/**`만 변경할 수 있다.
- `payment-service/**`, `common-module/**`, 다른 서비스, 저장소 루트의 공통 Gradle·Docker·CI 설정, 공유 `grpc/**`·Proto 계약은 수정하지 않는다.
- 외부 계약 불일치는 Order Service의 하위 호환 역직렬화로만 흡수한다. 흡수할 수 없는 불일치는 선행 조건 또는 잔여 위험으로 기록하고 구현 범위를 넓히지 않는다.
- 승인된 설계 문서 [2026-07-18-order-failure-cart-compensation-design.md](../specs/2026-07-18-order-failure-cart-compensation-design.md)가 이 계획의 기준이다.
- 이 계획은 이전 v2 초안의 다음 가정을 대체한다: 결제 한 건에 여러 주문, 다건 `orderIds` 결제 이벤트, 주문 생성 시 장바구니 유지, 실패·만료 시 장바구니 미복구.
- 결제 한 건당 주문은 한 건이다. 판매자가 여러 명이어도 주문을 판매자별로 분리하지 않는다.
- 장바구니 주문과 바로 구매를 구분하지 않는다. 실패하면 주문의 모든 상품을 장바구니에 add-if-absent로 복구한다.
- Product Service 호출을 보상 트랜잭션 안에 추가하지 않는다.
- 환불 성공·실패와 부분 환불 정책은 변경하지 않는다.
- PR #397의 최신 계약에 따라 주문 생성 시 외부 Kafka `ORDER_CREATED` payload·Factory·Outbox를 생성하지 않는다. 내부 `OrderCreatedEvent`와 `OrderExpirationRegistrar`의 `AFTER_COMMIT` Redis 만료 등록은 유지한다.
- 현재 사용자 소유의 미추적 계획 문서 세 개를 수정하거나 덮어쓰지 않는다.
- 아래 `Commit` 단계는 구현 세션에서 사용자가 commit을 명시적으로 승인한 경우에만 수행한다. 승인하지 않았다면 stage·commit 없이 검증 결과만 보고한다.

## Prerequisite Gate

이 계획은 승인된 v2 상태 모델을 전제로 한다. 계획 작성 시점의 checkout은 아직 구 상태인 `PENDING/PAID/CANCELED/REFUNDED`를 사용하므로 바로 구현을 시작하면 안 된다. 먼저 동일 브랜치에 다음 결과가 존재해야 한다.

- `OrderStatus`: `CREATED`, `COMPLETED`, `FAILED`, `PARTIAL_REFUNDED`, `ALL_REFUNDED`
- `OrderProductStatus`: `PENDING`, `PAID`, `FAILED`, `REFUNDED`
- `Order.markFailed()`가 `CREATED -> FAILED`와 소속 상품 `PENDING -> FAILED`를 수행
- `Order.markCompleted(LocalDateTime)`가 `CREATED/FAILED -> COMPLETED`와 소속 상품 `PENDING/FAILED -> PAID`를 수행
- 주문 생성은 판매자 수와 무관하게 주문 한 건을 만들고 대상 상품을 장바구니에서 제거
- 부분·전체 환불은 `PARTIAL_REFUNDED/ALL_REFUNDED` 상태를 사용
- `src/main/resources/db/migration/V5__add_cart_compensation_uniqueness.sql`은 아직 존재하지 않음

선행 v2 작업을 적용할 때도 이 문서가 대체한다고 명시한 다건 주문·다건 결제 이벤트·장바구니 미복구 부분은 적용하지 않는다.

## External Contract Compatibility

읽기 전용 조사 결과 현재 Payment Service의 단건 실패 이벤트 payload는 `paymentId`, `orderId`, `userId`를 발행하며 `failureCode`, `failureReason`, `failedAt`은 아직 발행하지 않는다. 이 작업에서 Payment Service를 변경하지 않는다.

- `PaymentFailedPayload.buyerId`에는 `@JsonAlias("userId")`를 적용한다.
- `failedAt`이 없으면 envelope의 `occurredAt`을 실패 시각으로 사용한다.
- `failureCode`와 `failureReason`은 nullable로 수신하고, 값이 있을 때만 로그 필드에 포함한다.
- 승인 payload도 현재 생산자의 `userId`, `amount`를 각각 `buyerId`, `approvedAmount` 별칭으로 수용한다.
- 현재 생산자의 `approvedAt`은 `+09:00` 오프셋 문자열이고 기존 v2 초안은 offset 없는 local 문자열이다. Order Service의 공통 parser가 두 형식을 모두 `LocalDateTime`으로 정규화한다.
- Order Service 내부에서 발행하지 않는 nullable PG metadata는 결제 승인 상태 전이의 필수 조건으로 사용하지 않는다.

## File Structure

| Path | Responsibility |
|---|---|
| `src/main/resources/db/migration/V5__add_cart_compensation_uniqueness.sql` | 기존 데이터 검증 후 Cart 유일 제약 추가 |
| `src/main/java/com/prompthub/order/domain/model/Cart.java` | 상품 add-if-absent 및 멱등 제거 |
| `src/main/java/com/prompthub/order/domain/model/CartProduct.java` | `(cart_id, product_id)` JPA 유일 제약 매핑 |
| `src/main/java/com/prompthub/order/domain/repository/OrderRepository.java` | 잠금 후 자식이 초기화된 Order aggregate 조회 포트 |
| `src/main/java/com/prompthub/order/domain/repository/CartRepository.java` | 잠금 후 자식이 초기화된 Cart aggregate 조회 포트 |
| `src/main/java/com/prompthub/order/infra/persistence/order/OrderPersistence.java` | Order 루트 `PESSIMISTIC_WRITE` 쿼리 |
| `src/main/java/com/prompthub/order/infra/persistence/order/OrderAdapter.java` | Order 루트 잠금 후 상품 fetch |
| `src/main/java/com/prompthub/order/infra/persistence/cart/CartPersistence.java` | Cart 루트 `PESSIMISTIC_WRITE` 쿼리 |
| `src/main/java/com/prompthub/order/infra/persistence/cart/CartAdapter.java` | Cart 루트 잠금 후 상품 fetch |
| `src/main/java/com/prompthub/order/application/event/order/OrderExpirationCleanupRequestedEvent.java` | DB commit 후 Redis 정리를 요청하는 내부 이벤트 |
| `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentEventTimeParser.java` | offset/local 결제 이벤트 시각을 KST `LocalDateTime`으로 정규화 |
| `src/main/java/com/prompthub/order/application/service/order/OrderFailureCompensationService.java` | 실패 이벤트·타임아웃 공통 보상 트랜잭션 |
| `src/main/java/com/prompthub/order/application/service/event/PaymentFailedProcessor.java` | 실패 이벤트 metadata를 공통 서비스로 전달 |
| `src/main/java/com/prompthub/order/application/service/event/PaymentApprovedProcessor.java` | 늦은 승인 우선, 잠금·장바구니 제거·Outbox·processed-event 원자 처리 |
| `src/main/java/com/prompthub/order/infra/redis/OrderExpirationCleanupListener.java` | `AFTER_COMMIT` Redis 만료·retry 제거 |
| `src/main/java/com/prompthub/order/infra/redis/OrderExpirationWorker.java` | 미수신 타임아웃을 공통 보상 서비스로 전달 |
| `src/main/java/com/prompthub/order/application/service/order/OrderExpirationService.java` | 공통 서비스로 대체 후 삭제 |
| `src/test/java/com/prompthub/order/support/PostgreSqlIntegrationTestSupport.java` | 배포 환경과 같은 PostgreSQL 18.4 Testcontainers 공통 설정 |

---

### Task 0: 선행 상태와 변경 범위 검증

**Files:**

- Read only: `src/main/java/com/prompthub/order/domain/enums/OrderStatus.java`
- Read only: `src/main/java/com/prompthub/order/domain/enums/OrderProductStatus.java`
- Read only: `src/main/java/com/prompthub/order/domain/model/Order.java`
- Read only: `src/main/java/com/prompthub/order/application/service/order/CreateOrderCommandHandler.java`
- Read only: `src/main/resources/db/migration/`

**Interfaces:**

- Consumes: 위 `Prerequisite Gate`의 v2 상태 전이와 단일 주문 생성 결과
- Produces: 보상 구현을 시작해도 되는 깨끗한 기준선

- [x] **Step 1: 작업 위치와 사용자 변경을 확인한다**

Run:

```bash
pwd
git status --short
git diff --check
```

Expected: 현재 위치는 `order-service`; 기존 미추적 v2 계획 3개와 승인 설계 문서는 사용자 변경으로 보존한다.

- [x] **Step 2: v2 상태 모델을 확인한다**

Run:

```bash
rg -n "CREATED|COMPLETED|FAILED|PARTIAL_REFUNDED|ALL_REFUNDED" src/main/java/com/prompthub/order/domain/enums/OrderStatus.java
rg -n "PENDING|PAID|FAILED|REFUNDED" src/main/java/com/prompthub/order/domain/enums/OrderProductStatus.java
rg -n "markFailed|markCompleted" src/main/java/com/prompthub/order/domain/model/Order.java
```

Expected: 모든 상태와 메서드가 존재한다. 파일 또는 항목이 없으면 이 계획을 중단하고 v2 상태 모델 작업을 먼저 완료한다.

- [x] **Step 3: 단일 주문·장바구니 제거 정책을 확인한다**

Run:

```bash
rg -n "removeProductsByProductIds|cartRepository" src/main/java/com/prompthub/order/application/service/order/CreateOrderCommandHandler.java
rg -n "orderIds|List<UUID> orderIds" src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentFailedPayload.java
test ! -f src/main/resources/db/migration/V5__add_cart_compensation_uniqueness.sql
```

Expected: 생성 시 장바구니 제거가 존재하고 실패 payload에 다건 `orderIds`가 없으며 V2 마이그레이션 파일은 아직 없다.

- [x] **Step 4: 저장소 전체 수정 경계를 기록한다**

Run:

```bash
git rev-parse --show-toplevel
git status --short
```

Expected: 이후 변경은 출력 경로 중 `order-service/**`에만 생긴다. 이 Task에는 commit이 없다.

---

### Task 0-A: 주문 생성 트랜잭션의 장바구니 제거 선행 보완

Task 0 검증에서 현재 통합 브랜치의 단일 주문 생성은 확인했지만, 승인된 설계가 전제한 주문 생성 시 장바구니 제거가 누락된 것을 확인했다. 이 보완은 실패 보상 구현 전에 수행하며, 주문·주문상품·장바구니 제거가 동일한 DB 트랜잭션에 포함되게 한다. 외부 Kafka `ORDER_CREATED` Outbox는 후속 Task 10에서 제거하고 내부 만료 이벤트만 유지한다. Task 2와 Task 3에서 Cart 루트 잠금 포트가 추가되면 이 경로도 같은 잠금 조회로 교체한다.

**Files:**

- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderCreator.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCreatorTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCreationTransactionIntegrationTest.java`

**Interfaces:**

- Consumes: 단일 주문의 구매자 ID와 주문 상품 ID 목록, 기존 `CartRepository`
- Produces: 주문 생성 시 대상 상품을 제거하는 원자적 checkout 트랜잭션

- [x] **Step 1: 주문 생성 시 대상 상품 제거 단위 테스트를 먼저 작성한다**

  `OrderCreatorTest`에 다음 행위를 고정한다.

  - 구매자의 장바구니가 있으면 주문 상품 ID 전체를 `removeProductsByProductIds`에 전달한다.
  - 변경된 Cart를 저장한다.
  - 장바구니가 없으면 Cart 저장을 시도하지 않는다.

- [x] **Step 2: 새 단위 테스트가 실패하는지 확인한다**

  Run:

  ```bash
  ../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderCreatorTest"
  ```

  Expected: 현재 `OrderCreator`가 `CartRepository`를 사용하지 않으므로 새 상호작용 검증이 실패한다.

- [x] **Step 3: 동일 트랜잭션 안의 최소 장바구니 제거 구현을 추가한다**

  `OrderCreator`에 `CartRepository`를 주입하고 주문을 저장한 뒤 주문 상품 ID로 장바구니 항목을 제거한다. 장바구니가 존재할 때만 변경된 aggregate를 저장하며, 원격 서비스 호출은 추가하지 않는다.

- [x] **Step 4: Cart 저장 실패 시 전체 rollback 통합 테스트를 보강한다**

  `OrderCreationTransactionIntegrationTest`에서 장바구니 저장이 실패하면 주문, 주문상품, 장바구니가 모두 트랜잭션 이전 상태로 돌아가는 시나리오를 검증한다.

- [x] **Step 5: 가까운 테스트를 통과시킨다**

  Run:

  ```bash
  ../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderCreatorTest"
  ../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderCreationTransactionIntegrationTest"
  ```

  Expected: 두 테스트가 모두 통과한다.

- [x] **Step 6: 승인된 경우에만 commit한다**

  사용자는 구현 진행만 승인했고 commit은 승인하지 않았으므로 stage·commit하지 않는다.

---

### Task 10: PR #397 주문 생성 Kafka 이벤트 제거 계약 반영

PR #397 최신 커밋 `d590006c`는 프론트엔드의 결제 API 직접 호출 전환에 따라 외부 Kafka `ORDER_CREATED` 발행을 제거했다. 현재 보상 worktree는 그보다 이전 SHA를 기준으로 하므로, 장바구니 제거를 유지하면서 주문 생성 Outbox만 제거한다. Redis 만료 등록용 내부 `OrderCreatedEvent`는 보상 타임아웃 경로의 시작점이므로 반드시 유지한다.

**Files:**

- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderCreator.java`
- Modify: `src/main/java/com/prompthub/order/application/service/event/OrderEventMessageFactory.java`
- Modify: `src/main/java/com/prompthub/order/domain/model/OutboxEvent.java`
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderEventType.java`
- Delete: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayload.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCreatorTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCreationTransactionIntegrationTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCreationResilienceIntegrationTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/redis/OrderExpirationAfterCommitIntegrationTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/event/outbox/OutboxEventAppenderTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/messaging/kafka/producer/OutboxRelayTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/messaging/kafka/OutboxRelayIntegrationTest.java`
- Delete: `src/test/java/com/prompthub/order/application/service/event/OrderEventMessageFactoryTest.java`
- Delete: `src/test/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayloadSerializationTest.java`

**Interfaces:**

- Preserves: `OrderRepository.save` -> Cart 루트 잠금·대상 상품 제거 -> `ApplicationEventPublisher.publishEvent(OrderCreatedEvent)`
- Produces: 주문 생성 성공 시 외부 `ORDER_CREATED` Outbox 0건, DB commit 후 Redis 만료 등록 1회
- Preserves: 결제 승인·환불의 `ORDER_PAID`, `ORDER_REFUND` Outbox와 공용 Relay

- [x] **Step 1: 외부 주문 생성 Outbox가 없어야 하는 실패 테스트를 작성한다**

  주문 생성 단위·통합 테스트에서 외부 Factory·Appender 기대를 제거하고, 성공 시 Outbox가 0건이면서 주문 상품만 Cart에서 제거되고 내부 `OrderCreatedEvent`가 한 번 발행되는 결과를 고정한다. `OrderCreationResilienceIntegrationTest`도 성공 주문 생성 후 Cart는 비워지되 Outbox는 0건임을 검증한다.

- [x] **Step 2: 변경한 테스트가 기존 구현에서 예상한 이유로 실패하는지 확인한다**

  Run:

  ```bash
  ../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderCreatorTest" --tests "com.prompthub.order.application.service.order.OrderCreationTransactionIntegrationTest" --tests "com.prompthub.order.application.service.order.OrderCreationResilienceIntegrationTest"
  ```

  Expected: 기존 `OrderCreator`가 `ORDER_CREATED` Outbox 한 건을 저장하므로 Outbox 0건 assertion 또는 제거된 협력 객체 계약에서 실패한다.

- [x] **Step 3: OrderCreator를 Cart 제거와 내부 만료 이벤트만 남도록 구현한다**

  `OrderCreator`의 처리 순서를 다음으로 고정한다.

  ```text
  Order 생성·저장 -> Cart 루트 잠금 -> 대상 상품 제거 -> 내부 OrderCreatedEvent 발행
  ```

  `OrderEventMessageFactory`, `OutboxEventAppender`, `OrderCreatedPayload`, 외부 `EventMessage` 의존성과 주문 생성용 Outbox 저장을 제거한다.

- [x] **Step 4: 외부 ORDER_CREATED 전용 계약과 테스트를 제거한다**

  `OrderCreatedPayload`, `createOrderCreatedMessage`, `OrderEventType.ORDER_CREATED`, `OutboxEvent.orderCreated`를 제거한다. 공용 Outbox Appender·Relay 테스트는 `ORDER_PAID` 계약으로 전환하고 `ORDER_PAID`, `ORDER_REFUND` 발행 경로를 유지한다.

- [x] **Step 5: 내부 AFTER_COMMIT 만료 등록과 관련 회귀를 검증한다**

  Run:

  ```bash
  ../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderCreatorTest" --tests "com.prompthub.order.application.service.order.OrderCreationTransactionIntegrationTest" --tests "com.prompthub.order.application.service.order.OrderCreationResilienceIntegrationTest" --tests "com.prompthub.order.infra.redis.OrderExpirationRegistrarTest" --tests "com.prompthub.order.infra.redis.OrderExpirationAfterCommitIntegrationTest" --tests "com.prompthub.order.application.service.event.outbox.OutboxEventAppenderTest" --tests "com.prompthub.order.infra.messaging.kafka.producer.OutboxRelayTest" --tests "com.prompthub.order.infra.messaging.kafka.OutboxRelayIntegrationTest"
  rg -n "ORDER_CREATED|OrderCreatedPayload|createOrderCreatedMessage|orderCreated\\(" src/main src/test
  ```

  Expected: 선택 테스트가 모두 통과하고 외부 주문 생성 Kafka 계약 검색 결과는 없다. 내부 `OrderCreatedEvent`와 `OrderExpirationRegistrar`는 검색·테스트에 남아 있다.

- [ ] **Step 6: 승인된 경우에만 commit한다**

  사용자가 commit을 명시적으로 요청하지 않았으므로 stage·commit하지 않는다.

---

### Task 1: Cart 멱등 연산과 데이터베이스 유일 제약

**Files:**

- Create: `src/test/java/com/prompthub/order/domain/model/CartTest.java`
- Modify: `src/main/java/com/prompthub/order/domain/model/Cart.java`
- Modify: `src/main/java/com/prompthub/order/domain/model/CartProduct.java`
- Create: `src/main/resources/db/migration/V5__add_cart_compensation_uniqueness.sql`

**Interfaces:**

- Consumes: `Collection<UUID>` 주문 상품 ID
- Produces: `int Cart.addProductsIfAbsent(Collection<UUID> productIds)`
- Preserves: `void Cart.removeProductsByProductIds(Collection<UUID> productIds)`의 멱등 제거
- DB invariants: `UNIQUE cart(buyer_id)`, `UNIQUE cart_product(cart_id, product_id)`

- [x] **Step 1: add-if-absent 실패 테스트를 작성한다**

`CartTest`에 다음 동작을 고정한다.

```java
@Test
void addProductsIfAbsent_addsOnlyMissingDistinctProducts() {
    Cart cart = Cart.create(BUYER_ID);
    cart.addProduct(PRODUCT_ID_1);

    int added = cart.addProductsIfAbsent(List.of(
        PRODUCT_ID_1,
        PRODUCT_ID_2,
        PRODUCT_ID_2,
        PRODUCT_ID_3,
        PRODUCT_ID_4
    ));

    assertThat(added).isEqualTo(3);
    assertThat(cart.getCartProducts())
        .extracting(CartProduct::getProductId)
        .containsExactlyInAnyOrder(PRODUCT_ID_1, PRODUCT_ID_2, PRODUCT_ID_3, PRODUCT_ID_4);
}
```

바로 구매 단건, 빈 입력, 이미 모두 존재, 무관한 상품 유지 테스트도 같은 클래스에 추가한다.

- [x] **Step 2: 도메인 테스트가 실패하는지 확인한다**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.domain.model.CartTest"
```

Expected: `addProductsIfAbsent`가 없어 컴파일 실패한다.

- [x] **Step 3: 최소 add-if-absent 구현을 추가한다**

`Cart`에 다음 메서드를 추가한다.

```java
public int addProductsIfAbsent(Collection<UUID> productIds) {
    if (productIds == null || productIds.isEmpty()) {
        return 0;
    }

    int before = cartProducts.size();
    new java.util.LinkedHashSet<>(productIds).stream()
        .filter(java.util.Objects::nonNull)
        .filter(productId -> !containsProduct(productId))
        .forEach(this::addProduct);
    return cartProducts.size() - before;
}
```

`Cart`와 `CartProduct`의 `@Table`에 다음 제약 이름을 동일하게 매핑한다.

```java
@UniqueConstraint(name = "uk_cart_buyer_id", columnNames = "buyer_id")
@UniqueConstraint(name = "uk_cart_product_cart_product", columnNames = {"cart_id", "product_id"})
```

- [x] **Step 4: 새 Flyway 마이그레이션을 작성한다**

현재 통합 브랜치에 V2~V4가 이미 있으므로 `V5__add_cart_compensation_uniqueness.sql`의 내용은 다음과 같이 고정한다.

```sql
ALTER TABLE ONLY cart
    ADD CONSTRAINT uk_cart_buyer_id UNIQUE (buyer_id);

ALTER TABLE ONLY cart_product
    ADD CONSTRAINT uk_cart_product_cart_product UNIQUE (cart_id, product_id);
```

운영 적용 전 다음 조회가 0행인지 확인한다. 결과가 있으면 이 작업에서 임의 삭제·병합하지 않고 배포를 차단한다.

```sql
SELECT buyer_id, COUNT(*)
FROM cart
GROUP BY buyer_id
HAVING COUNT(*) > 1;

SELECT cart_id, product_id, COUNT(*)
FROM cart_product
GROUP BY cart_id, product_id
HAVING COUNT(*) > 1;
```

- [x] **Step 5: 도메인 회귀 테스트를 통과시킨다**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.domain.model.CartTest" --tests "com.prompthub.order.application.service.cart.CartServiceTest"
```

Expected: PASS.

- [x] **Step 6: 승인된 경우에만 commit한다**

```bash
git add order-service/src/main/java/com/prompthub/order/domain/model/Cart.java order-service/src/main/java/com/prompthub/order/domain/model/CartProduct.java order-service/src/main/resources/db/migration/V5__add_cart_compensation_uniqueness.sql order-service/src/test/java/com/prompthub/order/domain/model/CartTest.java
git commit -m "feat: order-service 장바구니 복구 멱등성 제약 추가"
```

저장소 루트에서 실행하는 명령이다. 현재 모듈에서 실행할 때는 각 경로의 `order-service/` 접두사를 제거한다.

---

### Task 2: Order·Cart 루트 잠금 저장소

**Files:**

- Read only: `src/main/java/com/prompthub/order/domain/repository/OrderRepository.java`
- Modify: `src/main/java/com/prompthub/order/domain/repository/CartRepository.java`
- Read only: `src/main/java/com/prompthub/order/infra/persistence/order/OrderPersistence.java`
- Read only: `src/main/java/com/prompthub/order/infra/persistence/order/OrderAdapter.java`
- Modify: `src/main/java/com/prompthub/order/infra/persistence/cart/CartPersistence.java`
- Modify: `src/main/java/com/prompthub/order/infra/persistence/cart/CartAdapter.java`
- Create: `src/test/java/com/prompthub/order/infra/persistence/AggregateRootLockPersistenceTest.java`

**Interfaces:**

- Reuses: 기존 `Optional<Order> findByIdWithOrderProductsForUpdate(UUID orderId)`
- Produces: `Optional<Cart> findByBuyerIdForUpdateWithCartProducts(UUID buyerId)`
- Lock order: 호출자는 반드시 Order aggregate를 먼저 조회하고 Cart aggregate를 나중에 조회

- [x] **Step 1: 잠금 포트의 실패 테스트를 작성한다**

`AggregateRootLockPersistenceTest`는 실제 JPA EntityManager로 다음을 검증한다.

```java
Order lockedOrder = orderRepository.findByIdWithOrderProductsForUpdate(orderId).orElseThrow();
assertThat(entityManager.getLockMode(lockedOrder)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
assertThat(lockedOrder.getOrderProducts()).hasSize(expectedProductCount);

Cart lockedCart = cartRepository.findByBuyerIdForUpdateWithCartProducts(buyerId).orElseThrow();
assertThat(entityManager.getLockMode(lockedCart)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
assertThat(lockedCart.getCartProducts()).hasSize(expectedCartProductCount);
```

- [x] **Step 2: 테스트가 컴파일 실패하는지 확인한다**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.infra.persistence.AggregateRootLockPersistenceTest"
```

Expected: 새 Repository 메서드가 없어 컴파일 실패한다.

- [x] **Step 3: 루트 전용 잠금 쿼리를 추가한다**

`OrderPersistence`의 기존 fetch join 없는 루트 잠금을 유지한다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select o from Order o where o.id = :orderId")
Optional<Order> findByIdForUpdate(@Param("orderId") UUID orderId);
```

`CartPersistence`에 같은 방식으로 추가한다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select c from Cart c where c.buyerId = :buyerId")
Optional<Cart> findByBuyerIdForUpdate(@Param("buyerId") UUID buyerId);
```

- [x] **Step 4: Adapter에서 루트 잠금 후 자식을 초기화한다**

`OrderAdapter`의 기존 루트 잠금 후 자식 초기화 흐름을 유지하고, `CartAdapter`도 잠금 쿼리가 먼저 실행된 뒤 기존 fetch 쿼리를 실행한다.

```java
public Optional<Cart> findByBuyerIdForUpdateWithCartProducts(UUID buyerId) {
    return cartPersistence.findByBuyerIdForUpdate(buyerId)
        .flatMap(ignored -> cartPersistence.findByBuyerIdWithCartProducts(buyerId));
}
```

두 메서드는 반드시 이미 열린 쓰기 트랜잭션 안에서 호출한다. nullable collection fetch join에 직접 `PESSIMISTIC_WRITE`를 적용하지 않는다.

- [x] **Step 5: 잠금 테스트를 통과시킨다**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.infra.persistence.AggregateRootLockPersistenceTest" --tests "com.prompthub.order.infra.persistence.OrderLockPersistenceTest"
```

Expected: PASS.

- [ ] **Step 6: 승인된 경우에만 commit한다**

```bash
git add order-service/src/main/java/com/prompthub/order/domain/repository/CartRepository.java order-service/src/main/java/com/prompthub/order/infra/persistence/cart/CartPersistence.java order-service/src/main/java/com/prompthub/order/infra/persistence/cart/CartAdapter.java order-service/src/test/java/com/prompthub/order/infra/persistence/AggregateRootLockPersistenceTest.java
git commit -m "feat: order-service 주문 장바구니 루트 잠금 조회 추가"
```

---

### Task 3: 모든 Cart 쓰기 경로의 동일 잠금 참여

**Files:**

- Modify: `src/main/java/com/prompthub/order/application/service/cart/CartService.java`
- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderCreator.java`
- Modify: `src/test/java/com/prompthub/order/application/service/cart/CartServiceTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCreatorTest.java`

**Interfaces:**

- Consumes: Task 2의 `findByBuyerIdForUpdateWithCartProducts`
- Preserves: 장바구니 조회는 기존 non-locking 메서드 사용
- Produces: 상품 추가·삭제·주문 생성 시 장바구니 제거가 모두 Cart 루트 잠금에 참여

- [x] **Step 1: 쓰기 메서드가 잠금 조회를 요구하는 테스트로 변경한다**

`CartServiceTest`에서 추가·삭제는 잠금 메서드를 stub·verify하고, `getCart`만 기존 조회를 사용한다고 검증한다. `OrderCreatorTest`는 저장된 주문을 만든 뒤 잠금 Cart에서 주문 상품 ID를 제거하는지 검증한다.

```java
given(cartRepository.findByBuyerIdForUpdateWithCartProducts(BUYER_ID))
    .willReturn(Optional.of(cart));

then(cartRepository).should().findByBuyerIdForUpdateWithCartProducts(BUYER_ID);
then(cart).should().removeProductsByProductIds(request.productIds());
```

- [x] **Step 2: 변경된 테스트가 실패하는지 확인한다**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.cart.CartServiceTest" --tests "com.prompthub.order.application.service.order.OrderCreatorTest"
```

Expected: 구현이 아직 기존 non-locking 조회를 호출해 Mockito 검증이 실패한다.

- [x] **Step 3: Cart 쓰기 경로를 잠금 조회로 교체한다**

- `CartService.addCartProduct`: Product snapshot 검증 후 잠금 Cart를 조회하고 없으면 생성한다.
- `CartService.deleteCartProduct`: 기존 read 조회로 소유자를 검증한 뒤 구매자의 Cart 루트를 잠그고, 잠긴 aggregate에서 해당 항목을 제거한다.
- `OrderCreator`: 주문을 생성·저장한 뒤 Cart 루트를 잠그고 주문 상품 ID를 제거한다.
- 조회 전용 `getCart`는 잠금을 사용하지 않는다.

주문 생성의 상태 변경 순서는 다음으로 고정한다.

```text
Order 생성·저장 -> Cart 루트 잠금 -> 대상 상품 제거 -> 내부 OrderCreatedEvent 발행
```

- [x] **Step 4: 가까운 회귀 테스트를 통과시킨다**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.cart.CartServiceTest" --tests "com.prompthub.order.application.service.order.OrderCreatorTest" --tests "com.prompthub.order.application.service.order.OrderCreationResilienceIntegrationTest"
```

Expected: PASS.

- [ ] **Step 5: 승인된 경우에만 commit한다**

```bash
git add order-service/src/main/java/com/prompthub/order/application/service/cart/CartService.java order-service/src/main/java/com/prompthub/order/application/service/order/OrderCreator.java order-service/src/test/java/com/prompthub/order/application/service/cart/CartServiceTest.java order-service/src/test/java/com/prompthub/order/application/service/order/OrderCreatorTest.java
git commit -m "fix: order-service 장바구니 쓰기 잠금 순서 통일"
```

---

### Task 4: 공통 실패 보상 트랜잭션

**Files:**

- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentFailedPayload.java`
- Create: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentEventTimeParser.java`
- Create: `src/main/java/com/prompthub/order/application/event/order/OrderExpirationCleanupRequestedEvent.java`
- Create: `src/main/java/com/prompthub/order/application/service/order/OrderFailureCompensationService.java`
- Create: `src/test/java/com/prompthub/order/application/service/order/OrderFailureCompensationServiceTest.java`

**Interfaces:**

- Consumes: `void compensatePaymentFailure(UUID eventId, String eventType, LocalDateTime occurredAt, PaymentFailedPayload payload)`
- Consumes: `boolean compensateTimeout(UUID orderId, LocalDateTime timedOutAt)`
- Produces: `OrderExpirationCleanupRequestedEvent(UUID orderId)`
- Transaction boundary: 두 public 메서드에만 `@Transactional`; 호출 Adapter에는 DB 트랜잭션 없음

- [x] **Step 1: 실패 보상 서비스의 단위 테스트를 먼저 작성한다**

다음 케이스를 모두 고정한다.

- `CREATED` + 장바구니 없음: 4개 상품 복구, 주문·상품 `FAILED`, processed-event 기록
- `CREATED` + 기존 동일 상품: 없는 상품만 추가
- 바로 구매 단건: 장바구니 생성 후 한 건 추가
- 중복 eventId: Order·Cart 미조회, cleanup 이벤트만 발행
- Order 잠금 후 eventId 중복 발견: 상태·Cart 미변경
- `FAILED/COMPLETED/PARTIAL_REFUNDED/ALL_REFUNDED`: 상태·Cart no-op, 최초 eventId는 처리 이력 기록
- payload 구매자와 주문 구매자가 다름: `ORDER_ACCESS_DENIED` 예외, 처리 이력 미기록
- Cart 저장 또는 processed-event 저장 예외: 호출자까지 예외 전파
- timeout에서 주문 없음 또는 비-`CREATED`: `true` 반환 및 cleanup 이벤트 발행

핵심 정상 경로 assertion은 다음과 같다.

```java
service.compensatePaymentFailure(EVENT_ID, "PAYMENT_FAILED", OCCURRED_AT, payload);

assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
assertThat(order.getOrderProducts())
    .extracting(OrderProduct::getOrderStatus)
    .containsOnly(OrderProductStatus.FAILED);
assertThat(cart.getCartProducts())
    .extracting(CartProduct::getProductId)
    .containsExactlyInAnyOrderElementsOf(orderedProductIds);
then(processedEventService).should()
    .markProcessed(EVENT_ID, "order-service", "PAYMENT_FAILED", OCCURRED_AT);
```

- [x] **Step 2: 새 테스트가 컴파일 실패하는지 확인한다**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderFailureCompensationServiceTest"
```

Expected: 공통 서비스와 cleanup 이벤트가 없어 컴파일 실패한다.

- [x] **Step 3: 단건·하위 호환 실패 payload를 구현한다**

`PaymentFailedPayload`를 다음 계약으로 맞춘다.

```java
public record PaymentFailedPayload(
    UUID paymentId,
    UUID orderId,
    @JsonAlias("userId") UUID buyerId,
    String failureCode,
    String failureReason,
    @JsonProperty("failedAt") String failedAtValue
) {
    public LocalDateTime failedAtOr(LocalDateTime occurredAt) {
        return PaymentEventTimeParser.parseOrElse(failedAtValue, occurredAt);
    }
}
```

`paymentId`, `orderId`, `buyerId`, envelope `eventId/eventType/occurredAt`은 필수다. 실패 상세 세 필드 중 `failedAt`은 envelope로 보완하고 code/reason은 선택값이다.

같은 package의 parser는 오프셋 형식을 먼저 파싱하고, 실패하면 local 형식을 파싱한다.

```java
final class PaymentEventTimeParser {
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private PaymentEventTimeParser() {
    }

    static LocalDateTime parseRequired(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("payment event time is required");
        }
        return parse(value);
    }

    static LocalDateTime parseOrElse(String value, LocalDateTime fallback) {
        return value == null || value.isBlank()
            ? java.util.Objects.requireNonNull(fallback)
            : parse(value);
    }

    private static LocalDateTime parse(String value) {
        try {
            return OffsetDateTime.parse(value)
                .withOffsetSameInstant(KST)
                .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.parse(value);
        }
    }
}
```

- [x] **Step 4: 공통 보상 서비스의 최소 구현을 추가한다**

구현 골격을 다음과 같이 고정한다.

```java
@Service
@RequiredArgsConstructor
public class OrderFailureCompensationService {
    private static final String CONSUMER_GROUP = "order-service";

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final ProcessedEventService processedEventService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void compensatePaymentFailure(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        PaymentFailedPayload payload
    ) {
        validateFailureEvent(eventId, eventType, occurredAt, payload);
        if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
            publishCleanup(payload.orderId());
            return;
        }

        Order order = orderRepository.findByIdWithOrderProductsForUpdate(payload.orderId())
            .orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));

        if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
            publishCleanup(order.getId());
            return;
        }
        if (!order.getBuyerId().equals(payload.buyerId())) {
            throw new OrderException(ErrorCode.ORDER_ACCESS_DENIED);
        }

        compensateCreatedOrder(order);
        processedEventService.markProcessed(eventId, CONSUMER_GROUP, eventType, occurredAt);
        publishCleanup(order.getId());
    }

    @Transactional
    public boolean compensateTimeout(UUID orderId, LocalDateTime timedOutAt) {
        Optional<Order> locked = orderRepository.findByIdWithOrderProductsForUpdate(orderId);
        if (locked.isEmpty()) {
            publishCleanup(orderId);
            return true;
        }

        compensateCreatedOrder(locked.get());
        publishCleanup(orderId);
        return true;
    }
}
```

`compensateCreatedOrder`는 `CREATED`에서만 다음 순서를 실행한다.

```text
Order 잠금 완료 -> Cart 잠금 또는 신규 생성 -> 주문 상품 ID distinct -> addProductsIfAbsent
-> Cart 저장 -> Order.markFailed -> cleanup 이벤트 발행 예약
```

`FAILED/COMPLETED/PARTIAL_REFUNDED/ALL_REFUNDED`는 Cart를 조회하지 않는 성공 no-op이다. 서비스는 대상 수·실제 추가 수와 전·후 상태를 구조화 로그로 남긴다.

- [x] **Step 5: 공통 서비스 단위 테스트를 통과시킨다**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderFailureCompensationServiceTest"
```

Expected: PASS.

- [ ] **Step 6: 승인된 경우에만 commit한다**

```bash
git add order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentFailedPayload.java order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentEventTimeParser.java order-service/src/main/java/com/prompthub/order/application/event/order/OrderExpirationCleanupRequestedEvent.java order-service/src/main/java/com/prompthub/order/application/service/order/OrderFailureCompensationService.java order-service/src/test/java/com/prompthub/order/application/service/order/OrderFailureCompensationServiceTest.java
git commit -m "feat: order-service 결제 실패 장바구니 보상 트랜잭션 추가"
```

---

### Task 5: 실패 이벤트·만료 Worker 연결과 AFTER_COMMIT 정리

**Files:**

- Modify: `src/main/java/com/prompthub/order/application/service/event/PaymentFailedProcessor.java`
- Modify: `src/main/java/com/prompthub/order/infra/redis/OrderExpirationWorker.java`
- Create: `src/main/java/com/prompthub/order/infra/redis/OrderExpirationCleanupListener.java`
- Delete: `src/main/java/com/prompthub/order/application/service/order/OrderExpirationService.java`
- Replace: `src/test/java/com/prompthub/order/application/service/event/PaymentFailedProcessorTest.java`
- Create: `src/test/java/com/prompthub/order/application/service/event/PaymentFailedEventHandlerTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/redis/OrderExpirationWorkerTest.java`
- Create: `src/test/java/com/prompthub/order/infra/redis/OrderExpirationCleanupListenerTest.java`
- Delete: `src/test/java/com/prompthub/order/application/service/order/OrderExpirationServiceTest.java`

**Interfaces:**

- `PaymentFailedProcessor.process(...) -> OrderFailureCompensationService.compensatePaymentFailure(...)`
- `OrderExpirationWorker -> OrderFailureCompensationService.compensateTimeout(orderId, now)`
- `OrderExpirationCleanupListener.cleanup(OrderExpirationCleanupRequestedEvent)` at `AFTER_COMMIT`

- [x] **Step 1: Adapter 위임 테스트와 Redis 정리 테스트를 먼저 변경한다**

`PaymentFailedProcessorTest`는 metadata가 그대로 공통 서비스에 전달되고 Processor에 `@Transactional` 책임이 없음을 검증한다. `PaymentFailedEventHandlerTest`는 현재 생산자 형식인 `userId`와 최소 3필드 payload가 `buyerId`로 매핑되는지 검증한다.

```json
{
  "paymentId": "00000000-0000-0000-0000-000000000101",
  "orderId": "00000000-0000-0000-0000-000000000201",
  "userId": "00000000-0000-0000-0000-000000000301"
}
```

`OrderExpirationWorkerTest`는 성공 시 만료·retry 제거, 예외 시 retry 증가, 한도 초과 시 DLQ 이동을 그대로 검증한다. `OrderExpirationCleanupListenerTest`는 Redis 예외를 전파하지 않는지 검증한다.

- [x] **Step 2: 가까운 테스트가 실패하는지 확인한다**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.event.PaymentFailedProcessorTest" --tests "com.prompthub.order.application.service.event.PaymentFailedEventHandlerTest" --tests "com.prompthub.order.infra.redis.OrderExpirationWorkerTest" --tests "com.prompthub.order.infra.redis.OrderExpirationCleanupListenerTest"
```

Expected: 기존 Processor·Worker 의존성과 누락된 listener 때문에 실패한다.

- [x] **Step 3: Processor를 트랜잭션 없는 얇은 Adapter로 만든다**

```java
@Service
@RequiredArgsConstructor
public class PaymentFailedProcessor {
    private final OrderFailureCompensationService compensationService;

    public void process(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        PaymentFailedPayload payload
    ) {
        compensationService.compensatePaymentFailure(eventId, eventType, occurredAt, payload);
    }
}
```

- [x] **Step 4: Worker를 공통 서비스로 교체하고 기존 재시도 정책을 유지한다**

`OrderExpirationWorker`에서 `OrderExpirationService`를 제거하고 다음 호출로 교체한다.

```java
boolean completed = compensationService.compensateTimeout(orderId, now);
if (completed) {
    orderExpirationStore.removeExpiration(orderId);
    orderExpirationStore.clearRetryCount(orderId);
}
```

Worker의 `incrementRetryCount`, `maxRetryCount`, `moveToDeadLetter` 조건은 변경하지 않는다. 별도 미수신 이벤트나 Scheduler를 추가하지 않는다.

- [x] **Step 5: AFTER_COMMIT listener를 구현한다**

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpirationCleanupListener {
    private final OrderExpirationStore orderExpirationStore;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void cleanup(OrderExpirationCleanupRequestedEvent event) {
        try {
            orderExpirationStore.removeExpiration(event.orderId());
            orderExpirationStore.clearRetryCount(event.orderId());
        } catch (RuntimeException exception) {
            log.warn("주문 만료 Redis 정리에 실패했습니다. orderId={}", event.orderId(), exception);
        }
    }
}
```

DB 보상이 커밋되지 않으면 listener가 실행되지 않아야 한다. Redis 실패는 DB 결과를 되돌리지 않는다.

- [x] **Step 6: 대체된 만료 서비스를 삭제하고 테스트를 통과시킨다**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.event.PaymentFailedProcessorTest" --tests "com.prompthub.order.application.service.event.PaymentFailedEventHandlerTest" --tests "com.prompthub.order.infra.redis.OrderExpirationWorkerTest" --tests "com.prompthub.order.infra.redis.OrderExpirationCleanupListenerTest"
```

Expected: PASS; `OrderExpirationService` 참조가 0건이다.

```bash
rg -n "OrderExpirationService" src/main src/test
```

- [ ] **Step 7: 승인된 경우에만 commit한다**

```bash
git add order-service/src/main/java/com/prompthub/order/application/service/event/PaymentFailedProcessor.java order-service/src/main/java/com/prompthub/order/infra/redis/OrderExpirationWorker.java order-service/src/main/java/com/prompthub/order/infra/redis/OrderExpirationCleanupListener.java order-service/src/main/java/com/prompthub/order/application/service/order/OrderExpirationService.java order-service/src/test/java/com/prompthub/order/application/service/event/PaymentFailedProcessorTest.java order-service/src/test/java/com/prompthub/order/application/service/event/PaymentFailedEventHandlerTest.java order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationWorkerTest.java order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationCleanupListenerTest.java order-service/src/test/java/com/prompthub/order/application/service/order/OrderExpirationServiceTest.java
git commit -m "refactor: order-service 결제 실패와 주문 만료 보상 흐름 통합"
```

---

### Task 6: 늦은 결제 승인 우선과 장바구니 재제거

**Files:**

- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentApprovedPayload.java`
- Modify: `src/main/java/com/prompthub/order/application/service/event/PaymentApprovedProcessor.java`
- Read only: `src/main/java/com/prompthub/order/application/service/event/PaymentRefundedProcessor.java`
- Read only: `src/main/java/com/prompthub/order/application/service/order/OrderPolicyService.java`
- Modify: `src/test/java/com/prompthub/order/application/service/event/PaymentApprovedProcessorTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/event/PaymentApprovedEventHandlerTest.java`
- Read only: `src/test/java/com/prompthub/order/application/service/event/PaymentRefundedProcessorTest.java`
- Read only: `src/test/java/com/prompthub/order/application/service/order/OrderPolicyServiceTest.java`

**Interfaces:**

- Accepts: `CREATED -> COMPLETED`, `FAILED -> COMPLETED`
- Produces: 모든 주문 상품 `PENDING/FAILED -> PAID`, 주문상품 ID의 Cart 항목 제거
- Preserves: 결제 승인 Outbox와 processed-event가 같은 DB 트랜잭션에 저장
- Publishes: `OrderExpirationCleanupRequestedEvent` for AFTER_COMMIT Redis cleanup

- [x] **Step 1: 늦은 승인과 경합 정책 테스트를 먼저 고정한다**

다음 케이스를 `PaymentApprovedProcessorTest`에 둔다.

- `CREATED` 승인: `COMPLETED/PAID`, Cart 동일 상품 제거
- 보상된 `FAILED` 승인: `COMPLETED/PAID`, 복구된 동일 상품 제거
- 결제 대기 중 사용자가 동일 상품을 재추가한 경우에도 승인 시 제거
- Cart의 무관한 상품 유지
- `COMPLETED/PARTIAL_REFUNDED/ALL_REFUNDED`의 늦은 승인: 상태·Cart no-op, 처리 이력 기록
- 중복 eventId: 아무 DB 변경 없음
- Order 잠금 후 중복 eventId 발견: 아무 DB 변경 없음
- 구매자 또는 승인 금액 불일치: 예외와 전체 rollback
- 성공 시 Outbox·processed-event·cleanup 이벤트 발행
- 부분·전체 환불 Processor도 같은 Order 루트 잠금과 잠금 후 processed-event 재확인을 사용하되 환불 정책은 변경하지 않음

핵심 늦은 승인 assertion은 다음과 같다.

```java
order.markFailed();
cart.addProductsIfAbsent(orderedProductIds);

processor.process(EVENT_ID, "PAYMENT_APPROVED", OCCURRED_AT, payload);

assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
assertThat(order.getOrderProducts())
    .extracting(OrderProduct::getOrderStatus)
    .containsOnly(OrderProductStatus.PAID);
assertThat(cart.getCartProducts())
    .extracting(CartProduct::getProductId)
    .doesNotContainAnyElementsOf(orderedProductIds);
```

- [x] **Step 2: 승인 테스트가 실패하는지 확인한다**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.event.PaymentApprovedProcessorTest" --tests "com.prompthub.order.application.service.event.PaymentApprovedEventHandlerTest" --tests "com.prompthub.order.application.service.event.PaymentRefundedProcessorTest" --tests "com.prompthub.order.application.service.order.OrderPolicyServiceTest"
```

Expected: 기존 non-locking 조회·직접 Redis 접근·구 payload 매핑 때문에 실패한다.

- [x] **Step 3: 현재 생산자와 호환되는 승인 payload를 만든다**

기존 JSON 이름은 유지하되 현재 producer alias와 유연한 시각 파싱을 적용한다.

```java
@JsonAlias("userId") UUID buyerId,
@JsonAlias("amount") int approvedAmount,
@JsonProperty("approvedAt") String approvedAtValue
```

기존 호출부가 `LocalDateTime`을 계속 사용하도록 record에 다음 accessor를 둔다.

```java
public LocalDateTime approvedAt() {
    return PaymentEventTimeParser.parseRequired(approvedAtValue);
}
```

`pgTxId`, `paymentMethod`, `provider`가 현재 producer payload에 없더라도 상태 전이 검증에서 필수로 요구하지 않는다. 기존 v2 작업이 `OrderPayment` 복제 저장을 제거한 상태를 유지하며 이 작업에서 다시 추가하지 않는다.

- [x] **Step 4: 승인 Processor의 잠금과 처리 순서를 구현한다**

처리 순서를 다음으로 고정한다.

```text
processed-event 1차 확인
-> Order 루트 잠금 및 상품 fetch
-> processed-event 2차 확인
-> 구매자·금액 검증
-> CREATED/FAILED이면 Order.markCompleted
-> Cart 루트 잠금 및 주문 상품 ID 제거
-> ORDER_PAID Outbox
-> processed-event 기록
-> cleanup 이벤트 발행
-> DB commit
```

`OrderExpirationStore` 직접 호출은 Processor에서 제거한다. `COMPLETED/PARTIAL_REFUNDED/ALL_REFUNDED`는 Cart와 Outbox를 변경하지 않고 processed-event와 cleanup 이벤트만 기록한다.

`PaymentRefundedProcessor`는 이미 다음 순서를 사용하므로 기존 부분·전체 환불 계산과 Outbox payload를 그대로 유지하고 회귀 테스트로 확인한다.

```text
processed-event 1차 확인 -> Order 루트 잠금 및 상품 fetch
-> processed-event 2차 확인 -> 기존 환불 상태 전이·Outbox·processed-event 기록
```

이 변경은 승인·실패와 환불이 같은 Order aggregate를 동시에 변경하지 못하게 하기 위한 잠금 참여이며 환불 대상·금액·최종 상태 규칙은 건드리지 않는다.

- [x] **Step 5: 승인 테스트를 통과시킨다**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.event.PaymentApprovedProcessorTest" --tests "com.prompthub.order.application.service.event.PaymentApprovedEventHandlerTest" --tests "com.prompthub.order.application.service.event.PaymentRefundedProcessorTest" --tests "com.prompthub.order.application.service.order.OrderPolicyServiceTest"
```

Expected: PASS.

- [ ] **Step 6: 승인된 경우에만 commit한다**

```bash
git add order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentApprovedPayload.java order-service/src/main/java/com/prompthub/order/application/service/event/PaymentApprovedProcessor.java order-service/src/main/java/com/prompthub/order/application/service/event/PaymentRefundedProcessor.java order-service/src/main/java/com/prompthub/order/application/service/order/OrderPolicyService.java order-service/src/test/java/com/prompthub/order/application/service/event/PaymentApprovedProcessorTest.java order-service/src/test/java/com/prompthub/order/application/service/event/PaymentApprovedEventHandlerTest.java order-service/src/test/java/com/prompthub/order/application/service/event/PaymentRefundedProcessorTest.java order-service/src/test/java/com/prompthub/order/application/service/order/OrderPolicyServiceTest.java
git commit -m "fix: order-service 늦은 결제 승인 장바구니 정합성 보장"
```

---

### Task 7: PostgreSQL 마이그레이션과 보상 원자성 통합 테스트

**Files:**

- Modify: `build.gradle`
- Create: `src/test/java/com/prompthub/order/support/PostgreSqlIntegrationTestSupport.java`
- Create: `src/test/java/com/prompthub/order/infra/persistence/CartUniquenessMigrationTest.java`
- Create: `src/test/java/com/prompthub/order/application/service/order/OrderFailureCompensationTransactionIntegrationTest.java`

**Interfaces:**

- Uses: `postgres:18.4-alpine`
- Uses: 실제 Flyway V1~V5, 실제 JPA repositories, 실제 transaction manager
- Mocks only: Redis store와 테스트에서 의도적으로 실패시키는 repository spy

- [x] **Step 1: Testcontainers 의존성을 추가하기 전 통합 테스트를 작성한다**

`build.gradle`에 들어갈 의존성은 저장소의 다른 서비스와 같은 좌표를 사용한다.

```groovy
testImplementation 'org.springframework.boot:spring-boot-testcontainers'
testImplementation 'org.testcontainers:testcontainers-junit-jupiter'
testImplementation 'org.testcontainers:testcontainers-postgresql'
```

지원 클래스는 JVM당 하나의 PostgreSQL 컨테이너를 시작하고 `@DynamicPropertySource`로 datasource를 연결한다. Flyway 테스트는 `spring.flyway.enabled=true`, `spring.jpa.hibernate.ddl-auto=validate`를 사용한다.

컨테이너 버전은 루트 `docker-compose.yml`과 Kubernetes 배포 설정의 `postgres:18.4-alpine`에 맞춘다. V1 baseline의 `transaction_timeout`은 PostgreSQL 17 이상 설정이므로 PostgreSQL 16을 사용하면 실제 배포 환경과 다른 이유로 migration이 실패한다. 이미 배포된 V1의 Flyway checksum을 변경하지 않는다.

- [x] **Step 2: 유일 제약과 원자성 시나리오를 작성한다**

`CartUniquenessMigrationTest`:

- 같은 `buyer_id` Cart 두 건 insert가 `uk_cart_buyer_id`로 실패
- 같은 `(cart_id, product_id)` 두 건 insert가 `uk_cart_product_cart_product`로 실패
- 서로 다른 상품과 서로 다른 구매자는 성공

`OrderFailureCompensationTransactionIntegrationTest`:

- 주문·상품·Cart·processed-event가 함께 commit
- Cart 저장 실패 시 주문은 `CREATED`, 상품은 `PENDING`, Cart와 processed-event는 변경 없음
- processed-event 저장 실패 시 주문·상품·Cart 모두 rollback
- 이미 처리한 eventId 재호출 시 Cart row 수 불변
- timeout 재호출 시 Cart row 수 불변
- rollback된 트랜잭션에서는 Redis cleanup listener가 호출되지 않음
- commit된 트랜잭션에서는 Redis cleanup listener가 호출됨

- [x] **Step 3: 의존성 추가 전 예상 실패를 확인한다**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.infra.persistence.CartUniquenessMigrationTest" --tests "com.prompthub.order.application.service.order.OrderFailureCompensationTransactionIntegrationTest"
```

Expected: Testcontainers 타입을 찾지 못해 컴파일 실패한다.

- [x] **Step 4: Testcontainers 지원과 실제 rollback 검증을 구현한다**

지원 클래스의 핵심 설정은 다음과 같다.

```java
public abstract class PostgreSqlIntegrationTestSupport {
    protected static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18.4-alpine"));
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
```

rollback assertion은 테스트 메서드 자체를 `@Transactional`로 감싸지 말고, 서비스 호출 후 새 `TransactionTemplate` 또는 repository 조회로 commit된 DB 상태를 다시 읽는다.

- [x] **Step 5: PostgreSQL 원자성 테스트를 통과시킨다**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.infra.persistence.CartUniquenessMigrationTest" --tests "com.prompthub.order.application.service.order.OrderFailureCompensationTransactionIntegrationTest"
```

Expected: Docker 실행 환경에서 PASS. Docker를 사용할 수 없으면 미실행 이유와 PostgreSQL 잠금·제약 검증 미완료 위험을 결과에 명시한다.

- [ ] **Step 6: 승인된 경우에만 commit한다**

```bash
git add order-service/build.gradle order-service/src/test/java/com/prompthub/order/support/PostgreSqlIntegrationTestSupport.java order-service/src/test/java/com/prompthub/order/infra/persistence/CartUniquenessMigrationTest.java order-service/src/test/java/com/prompthub/order/application/service/order/OrderFailureCompensationTransactionIntegrationTest.java
git commit -m "test: order-service 보상 트랜잭션 원자성 검증 추가"
```

---

### Task 8: PostgreSQL 동시성 시나리오

**Files:**

- Create: `src/test/java/com/prompthub/order/application/service/order/OrderFailureCompensationConcurrencyTest.java`

**Interfaces:**

- Uses: 실제 `OrderFailureCompensationService`, `PaymentApprovedProcessor`, `CartService`
- Coordinates: `ExecutorService`, `CountDownLatch`, 별도 Spring transactions
- Final invariant: 승인 사실이 있으면 항상 `COMPLETED/PAID`이고 주문 상품은 Cart에 없음

- [x] **Step 1: 세 동시성 테스트를 작성한다**

1. `PAYMENT_FAILED`와 `PAYMENT_APPROVED` 동시 실행
2. `PAYMENT_FAILED`와 `compensateTimeout` 동시 실행
3. 사용자 장바구니 동일 상품 추가와 실패 보상 동시 실행

각 작업은 같은 테스트 트랜잭션을 공유하지 않고 Spring proxy를 통해 별도 트랜잭션으로 호출한다. Future는 제한 시간 안에 완료되어야 하고 deadlock·lock timeout을 허용하지 않는다.

```java
assertThat(finalOrder.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
assertThat(finalOrder.getOrderProducts())
    .extracting(OrderProduct::getOrderStatus)
    .containsOnly(OrderProductStatus.PAID);
assertThat(finalCart.getCartProducts())
    .extracting(CartProduct::getProductId)
    .doesNotContainAnyElementsOf(orderedProductIds);
```

실패와 timeout 경합은 최종 `FAILED` 및 상품별 Cart row 최대 한 건을 검증한다. 사용자 추가 경합은 한 요청이 중복 예외로 끝날 수 있지만 DB에는 동일 `(cart_id, product_id)`가 정확히 한 건만 남아야 한다.

- [x] **Step 2: 현재 구현에 동시성 테스트를 실행한다**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderFailureCompensationConcurrencyTest"
```

Expected: 잠금 순서나 늦은 승인 처리가 누락되면 상태 또는 Cart assertion이 실패한다.

- [x] **Step 3: 실패 원인을 최소 범위로 수정한다**

- 모든 경로가 `Order -> Cart` 순서를 지키는지 확인한다.
- Cart만 변경하는 사용자 경로는 Cart 루트 잠금부터 시작한다.
- absent Cart 생성 경합은 DB unique 제약 위반으로 한 트랜잭션을 rollback하고 Kafka/Worker 경로는 기존 재시도로 회복한다.
- deadlock을 숨기기 위한 무제한 retry나 전역 JVM lock은 추가하지 않는다.

- [x] **Step 4: 동시성 테스트를 반복 통과시킨다**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderFailureCompensationConcurrencyTest" --rerun-tasks
../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderFailureCompensationConcurrencyTest" --rerun-tasks
```

Expected: 두 번 모두 PASS.

- [ ] **Step 5: 승인된 경우에만 commit한다**

```bash
git add order-service/src/test/java/com/prompthub/order/application/service/order/OrderFailureCompensationConcurrencyTest.java
git commit -m "test: order-service 결제 결과 장바구니 동시성 검증 추가"
```

---

### Task 8-A: 코드 리뷰 보완 — 조기 만료 방지와 cleanup 재시도

**Files:**

- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderFailureCompensationService.java`
- Modify: `src/main/java/com/prompthub/order/application/service/event/PaymentApprovedProcessor.java`
- Modify: 관련 단위·트랜잭션·동시성 테스트

- [x] **Step 1: 읽기 전용 코드 리뷰 결과를 분류한다**

기존 중복 Cart 데이터 정리와 absent Cart 최초 생성 경합은 이 계획의 Residual Risks에 이미 기록한 별도 운영·후속 범위로 유지한다. 이미 적용된 migration의 checksum을 변경하거나 승인 없이 기존 Cart 데이터를 병합·삭제하지 않는다.

- [x] **Step 2: 타임아웃 보상 전에 실제 만료 여부를 다시 확인한다**

Order 루트 잠금 뒤 `OrderExpirationPolicy.paymentTimeoutMinutes()`로 `CREATED` 주문의 만료 여부를 재검증한다. 아직 만료되지 않았으면 `false`를 반환하고 상태·Cart·processed-event·cleanup을 모두 변경하지 않아 Redis 예약을 다음 Worker 주기까지 유지한다.

- [x] **Step 3: 중복 결제 승인도 AFTER_COMMIT cleanup을 다시 요청한다**

최초 승인 커밋 뒤 Redis 장애가 발생한 경우 같은 `eventId` 재수신이 cleanup을 재시도할 수 있게 한다. 중복 승인에서는 Order·Cart·Outbox·processed-event를 변경하지 않는다.

- [x] **Step 4: 보완 회귀와 PostgreSQL 동시성 테스트를 통과시킨다**

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderFailureCompensationServiceTest" --tests "com.prompthub.order.application.service.event.PaymentApprovedProcessorTest" --tests "com.prompthub.order.application.service.event.PaymentEventTransactionIntegrationTest" --tests "com.prompthub.order.application.service.order.OrderFailureCompensationTransactionIntegrationTest"
../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderFailureCompensationConcurrencyTest" --rerun-tasks
```

Expected: 두 명령 모두 PASS.

---

### Task 8-B: 코드 리뷰 보완 — 실패 전이 캡슐화와 no-op 불변식 강화

**Files:**

- Modify: `src/main/java/com/prompthub/order/domain/model/Order.java`
- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderFailureCompensationService.java`
- Modify: `src/test/java/com/prompthub/order/domain/model/OrderTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/event/PaymentApprovedProcessorTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderFailureCompensationServiceTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderFailureCompensationJpaTest.java`
- Modify: `src/test/java/com/prompthub/order/fixture/OrderFixture.java`

**Interfaces:**

- Produces: `void Order.markFailed(LocalDateTime failedAt)`
- Removes: 상태 검증을 우회하는 `Order.updateOrderStatus(OrderStatus)`
- Preserves: `CREATED` 주문의 `PENDING` 상품만 `FAILED`로 전이하고 기존 `PAID/FAILED` 상품의 상태·timestamp는 유지
- Preserves: 실패 이벤트 처리 순서 `processed-event 1차 확인 -> Order 잠금 -> processed-event 2차 확인`
- Preserves: `FAILED/COMPLETED/PARTIAL_REFUNDED/ALL_REFUNDED` 보상은 주문·주문상품·Cart 행을 변경하지 않는 성공 no-op

- [x] **Step 1: 실패 전이 도메인 테스트를 먼저 작성하고 RED를 확인한다**

  `OrderTest`에 `markFailed(LocalDateTime)`의 다음 계약을 추가한다.

  - `CREATED` 주문의 `PENDING` 상품은 `FAILED`가 되고 전달한 실패 시각을 보존한다.
  - 혼합 상태의 기존 `PAID/FAILED` 상품은 상태와 `updatedAt`이 바뀌지 않는다.
  - `COMPLETED` 주문에서는 Aggregate 전체를 변경하지 않고 `INVALID_ORDER_STATUS_TRANSITION`을 던진다.

  Run:

  ```bash
  ../gradlew :order-service:test --tests "com.prompthub.order.domain.model.OrderTest"
  ```

  Expected: `markFailed(LocalDateTime)`가 없어 컴파일 실패한다.

- [x] **Step 2: 잠금 후 processed-event 재확인과 no-op 불변식 테스트를 보강한다**

  `OrderFailureCompensationServiceTest`는 같은 `(eventId, consumerGroup)`을 잠금 전·후 정확히 두 번 확인하고, 두 번째 확인이 Order 잠금 뒤 실행되는지 `InOrder`로 검증한다. `OrderFailureCompensationJpaTest`는 혼합 상태 보상에서 기존 `PAID/FAILED` 상품의 ID·`updated_at`을 보존하고, 이미 `FAILED`인 주문의 timeout 및 새 실패 이벤트가 주문·주문상품·Cart·CartProduct의 ID와 timestamp를 바꾸지 않는지 새 트랜잭션 snapshot으로 비교한다.

- [x] **Step 3: Aggregate 실패 전이의 최소 구현을 추가한다**

  `Order.markFailed(LocalDateTime failedAt)`에서 `CREATED -> FAILED` 전이를 검증하고 각 주문상품의 `expirePending(failedAt)`을 호출한다. 기존 `markFailed()`는 현재 시각으로 새 overload에 위임하고, `expirePending(LocalDateTime)`도 같은 전이 메서드를 사용한다. 보상 서비스는 상품 직접 순회와 범용 setter 대신 다음 한 줄만 호출한다.

  ```java
  order.markFailed(compensatedAt);
  ```

  프로덕션 호출처가 없는 `updateOrderStatus`는 제거하고, 테스트 픽스처는 실제 도메인 전이 메서드로 상태를 만든다.

- [x] **Step 4: 가까운 단위·PostgreSQL 테스트를 GREEN으로 만든다**

  Run:

  ```bash
  ../gradlew :order-service:test --tests "com.prompthub.order.domain.model.OrderTest" --tests "com.prompthub.order.application.service.order.OrderFailureCompensationServiceTest" --tests "com.prompthub.order.application.service.event.PaymentApprovedProcessorTest" --tests "com.prompthub.order.application.service.order.OrderFailureCompensationJpaTest"
  ```

  Expected: PASS; `rg -n "updateOrderStatus" src/main src/test`의 결과가 없다.

- [x] **Step 5: 보상 원자성·동시성 회귀를 통과시킨다**

  Run:

  ```bash
  ../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderFailureCompensationTransactionIntegrationTest" --tests "com.prompthub.order.application.service.order.OrderFailureCompensationConcurrencyTest"
  ```

  Expected: PASS; 상태 전이 캡슐화 이후에도 rollback과 최종 수렴 불변식이 유지된다.

- [ ] **Step 6: 승인된 경우에만 commit한다**

  사용자는 순차 구현을 승인했지만 commit은 명시적으로 요청하지 않았으므로 stage·commit하지 않는다.

---

### Task 9: Kafka·Redis 회귀와 최종 범위 검증

**Files:**

- Modify: `src/test/java/com/prompthub/order/infra/messaging/kafka/PaymentEventConsumerIntegrationTest.java`
- Create: `src/test/java/com/prompthub/order/infra/redis/OrderExpirationCleanupAfterCommitIntegrationTest.java`
- Modify only if implementation deviates: `docs/superpowers/specs/2026-07-18-order-failure-cart-compensation-design.md`

**Interfaces:**

- Kafka: `payment-events`, manual ACK, 1초 간격 최대 3회 retry, `payment-events.DLT`
- Redis: `order:expiration`, `order:expiration:retry`, `order:expiration:dlq`
- Scope gate: changed path must start with `order-service/`

- [x] **Step 1: Kafka 실패 이벤트와 DLT 테스트를 추가한다**

`PaymentEventConsumerIntegrationTest`에 다음을 추가한다.

- 현재 producer 형식의 `PAYMENT_FAILED`가 `PaymentFailedEventHandler`로 전달됨
- Handler가 예외를 던지면 최초 시도와 3회 재시도 후 동일 partition의 `payment-events.DLT`로 이동
- 성공 Handler만 ACK되어 DLT record가 없음
- 누락 payload는 기존처럼 DLT 이동

DLT 테스트는 record key와 value의 `eventId/orderId`를 함께 검증한다.

- [x] **Step 2: AFTER_COMMIT Redis 테스트를 추가한다**

`OrderExpirationCleanupAfterCommitIntegrationTest`는 실제 Spring transaction과 mock `OrderExpirationStore`로 다음을 검증한다.

```text
transaction 내부 -> removeExpiration 미호출
commit 완료 -> removeExpiration 및 clearRetryCount 호출
rollback -> 두 메서드 모두 미호출
Redis 예외 -> DB의 FAILED/Cart 복구 결과 유지
```

- [x] **Step 3: Kafka·Redis 테스트를 실행한다**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.infra.messaging.kafka.PaymentEventConsumerIntegrationTest" --tests "com.prompthub.order.infra.redis.OrderExpirationWorkerTest" --tests "com.prompthub.order.infra.redis.OrderExpirationCleanupListenerTest" --tests "com.prompthub.order.infra.redis.OrderExpirationCleanupAfterCommitIntegrationTest"
```

Expected: PASS.

- [x] **Step 4: 기능 관련 전체 회귀를 실행한다**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.domain.model.CartTest" --tests "com.prompthub.order.application.service.cart.CartServiceTest" --tests "com.prompthub.order.application.service.order.OrderCreatorTest" --tests "com.prompthub.order.application.service.order.OrderFailureCompensationServiceTest" --tests "com.prompthub.order.application.service.event.PaymentFailedProcessorTest" --tests "com.prompthub.order.application.service.event.PaymentApprovedProcessorTest" --tests "com.prompthub.order.infra.redis.OrderExpirationWorkerTest"
```

Expected: PASS.

- [x] **Step 5: 모듈 전체 테스트와 build를 실행한다**

Run:

```bash
../gradlew :order-service:test
../gradlew :order-service:build
```

Expected: 두 명령 모두 PASS.

- [x] **Step 6: diff·민감정보·수정 범위를 최종 확인한다**

Run:

```bash
git diff --check
git status --short
git diff --name-only
git diff -- src/main src/test build.gradle docs
```

검토 항목:

- `payment-service/**`, `common-module/**`, 다른 서비스, 루트 공통 파일 변경 0건
- API·gRPC·Proto 변경 0건
- Kafka topic과 event type 변경 0건
- 새 migration이 데이터 삭제를 수행하지 않음
- 로그에 개인정보·토큰·PG 비밀값 없음
- 기존 사용자 미추적 계획 문서 3개에 diff 없음

- [x] **Step 7: 설계와 구현 차이가 있을 때만 설계 문서를 갱신한다**

차이가 없다면 설계 문서를 수정하지 않는다. 외부 producer가 여전히 실패 code/reason/time을 보내지 않는 사실은 잔여 계약 위험으로 결과에 기록한다.

- [ ] **Step 8: 승인된 경우에만 마지막 commit을 수행한다**

```bash
git add order-service/src/test/java/com/prompthub/order/infra/messaging/kafka/PaymentEventConsumerIntegrationTest.java order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationCleanupAfterCommitIntegrationTest.java order-service/docs/superpowers/specs/2026-07-18-order-failure-cart-compensation-design.md order-service/docs/superpowers/plans/2026-07-18-order-failure-cart-compensation.md
git commit -m "test: order-service 결제 실패 보상 통합 검증 추가"
```

설계 문서가 변경되지 않았다면 해당 파일은 `git add` 대상에서 제외한다.

## Completion Criteria

- [x] `PAYMENT_FAILED` 최초 처리에서 주문·상품이 `FAILED`이고 주문 상품 4개가 Cart에 최대 한 번씩 복구된다.
- [x] 바로 구매 단건 실패도 같은 방식으로 Cart에 복구된다.
- [x] 결과 이벤트 미수신 시 기존 Worker가 같은 공통 보상 트랜잭션을 호출한다.
- [x] 상태·Cart·processed-event 중 하나라도 실패하면 전체 DB 트랜잭션이 rollback된다.
- [x] 중복 이벤트와 Worker 재실행에서 Cart 중복 row가 생기지 않는다.
- [x] 보상 후 늦은 승인은 `COMPLETED/PAID`로 전환하고 복구 상품을 Cart에서 제거한다.
- [x] 성공 주문과 Cart에 대한 늦은 실패는 no-op이고 processed-event만 기록한다.
- [x] 모든 Cart write가 Cart 루트 잠금을 사용하고, 주문 관련 경로는 `Order -> Cart` 순서를 지킨다.
- [x] DB commit 후 Redis cleanup이 실행되고 Redis 실패가 DB 결과를 되돌리지 않는다.
- [x] Kafka 실패는 기존 retry 후 `.DLT`, Worker 실패는 기존 retry 후 Redis DLQ로 이동한다.
- [x] PostgreSQL에서 유일 제약·원자성·동시성 테스트가 통과한다.
- [x] `../gradlew :order-service:test`와 `../gradlew :order-service:build`가 통과한다.
- [x] 변경 파일이 `order-service/**`와 그 내부 `docs/**` 밖에 존재하지 않는다.

## Residual Risks

- 현재 Payment Service가 실패 code/reason/time을 발행하지 않아 Order Service 로그는 envelope 시각과 nullable 실패 상세만 기록할 수 있다.
- 기존 운영 DB에 중복 Cart 또는 중복 CartProduct가 있으면 V5 migration이 실패한다. 데이터 정리는 별도 승인과 별도 작업이 필요하다.
- Docker를 사용할 수 없는 환경에서는 PostgreSQL의 실제 lock·constraint 검증이 완료되지 않는다.
- absent Cart 동시 생성에서 한 트랜잭션은 unique 제약 위반으로 rollback될 수 있다. Kafka와 만료 Worker는 기존 retry로 회복하지만 사용자 HTTP 요청의 자동 재시도는 이 작업 범위가 아니다.
