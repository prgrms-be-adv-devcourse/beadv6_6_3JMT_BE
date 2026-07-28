# 주문 생성 Kafka 이벤트 제거 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 주문 생성 시 외부 Kafka `ORDER_CREATED` Outbox를 만들지 않고, DB 커밋 뒤 내부 `OrderCreatedEvent`를 통한 Redis 만료 예약만 유지한다.

**Architecture:** `OrderCreator`에서 외부 통합 이벤트 생성과 Outbox 저장 의존성을 제거한다. 주문 생성과 내부 Spring 이벤트 발행은 기존 트랜잭션에 유지하고, `OrderExpirationRegistrar`의 `AFTER_COMMIT` 경계는 변경하지 않는다. 공용 Outbox와 Relay는 결제 완료·환불 이벤트용으로 유지한다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Data JPA, Spring Transaction Event, Kafka Outbox, Redis, JUnit 5, Mockito, H2 PostgreSQL mode, Gradle Groovy DSL

## Global Constraints

- 작업 대상은 `feat/#392-seller-admin-settlement`이며 현재 dirty `develop` 작업트리와 분리된 worktree에서 실행한다.
- 변경 범위는 `order-service/**`로 제한한다.
- 외부 Kafka `ORDER_CREATED`만 제거하고 내부 `OrderCreatedEvent`와 Redis 만료 등록은 유지한다.
- `ORDER_PAID`, `ORDER_REFUND`, `OutboxEventAppender`, `OutboxRelay`, Outbox 테이블은 유지한다.
- 주문 생성 API 요청·응답과 Payment Service 코드는 변경하지 않는다.
- 기존 PENDING `ORDER_CREATED` Outbox 행을 삭제하거나 DB 마이그레이션을 추가하지 않는다.
- 사용자가 요청하지 않은 stage, commit, push, PR 갱신은 수행하지 않는다. 아래 작업은 검증 가능한 변경 단위로 나누되 커밋 단계는 의도적으로 포함하지 않는다.
- 현재 `develop`의 `AGENTS.md` 수정과 기존 untracked 설계·계획 문서는 건드리지 않는다.

---

## File Map

### 주문 생성 책임

- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderCreator.java` — 주문 저장과 내부 만료 이벤트만 조율한다.
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCreatorTest.java` — 주문 생성 결과와 내부 이벤트를 검증하고 외부 Outbox 협력 객체를 제거한다.
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCreationTransactionIntegrationTest.java` — 주문 생성 성공 시 Outbox 0건과 트랜잭션 롤백을 검증한다.
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCreationResilienceIntegrationTest.java` — Product 호출 성공·실패 뒤 주문 생성 Outbox가 항상 0건임을 검증한다.

### 제거되는 외부 주문 생성 계약

- Modify: `src/main/java/com/prompthub/order/application/service/event/OrderEventMessageFactory.java` — `ORDER_PAID`, `ORDER_REFUND` Factory만 남긴다.
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderEventType.java` — `ORDER_CREATED` 상수를 제거한다.
- Modify: `src/main/java/com/prompthub/order/domain/model/OutboxEvent.java` — `orderCreated` 편의 Factory 두 개를 제거한다.
- Delete: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayload.java` — 더 이상 발행하지 않는 외부 payload를 제거한다.
- Delete: `src/test/java/com/prompthub/order/application/service/event/OrderEventMessageFactoryTest.java` — `ORDER_CREATED`만 검증하는 테스트를 제거한다.
- Delete: `src/test/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayloadSerializationTest.java` — 제거된 payload 계약 테스트를 제거한다.

### 유지되는 공용 Outbox 회귀 검증

- Modify: `src/test/java/com/prompthub/order/application/service/event/outbox/OutboxEventAppenderTest.java` — generic 직렬화 검증을 `ORDER_PAID` 메시지로 전환한다.
- Modify: `src/test/java/com/prompthub/order/infra/messaging/kafka/producer/OutboxRelayTest.java` — Kafka key·상태 전이 검증을 `ORDER_PAID`로 전환한다.
- Modify: `src/test/java/com/prompthub/order/infra/messaging/kafka/OutboxRelayIntegrationTest.java` — `ORDER_CREATED` 전용 통합 테스트를 제거하고 기존 `ORDER_PAID`, `ORDER_REFUND` 테스트를 유지한다.

### 문서

- Add: `docs/superpowers/specs/2026-07-18-remove-order-created-kafka-event-design.md` — 승인된 이벤트 경계와 배포 주의사항을 기록한다.
- Add: `docs/superpowers/plans/2026-07-18-remove-order-created-kafka-event.md` — 이 구현·검증 순서를 기록한다.

---

### Task 1: 주문 생성 성공 시 외부 Outbox가 생기지 않는 실패 테스트 고정

**Files:**
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCreationTransactionIntegrationTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCreationResilienceIntegrationTest.java`

**Interfaces:**
- Consumes: `OrderCommandHandler.createOrder(UUID, CreateOrderCommand)`
- Produces: 주문 생성 성공 시 `OutboxEventPersistence.count() == 0`이라는 회귀 기준

- [ ] **Step 1: 성공 트랜잭션 테스트의 기대값을 Outbox 0건으로 변경**

`OrderCreationTransactionIntegrationTest`의 성공 테스트를 다음 의도로 변경한다.

```java
@Test
@DisplayName("단일 주문과 주문 상품 네 건을 저장하고 주문 생성 Outbox는 저장하지 않는다")
void createsOneOrderFourProductsWithoutOrderCreatedOutbox() {
    Cart cart = saveCart();
    List<UUID> beforeCartProducts = productIds(cart);

    CreateOrderResult result = orderCommandHandler.createOrder(BUYER_ID, command());

    assertThat(result.totalAmount()).isEqualTo(TOTAL_AMOUNT);
    assertThat(result.order()).isNotNull();
    assertThat(orderPersistence.count()).isEqualTo(1);
    assertThat(countOrderProducts()).isEqualTo(4);
    assertThat(outboxEventPersistence.count()).isZero();
    assertThat(productIds(loadCart())).containsExactlyInAnyOrderElementsOf(beforeCartProducts);
}
```

- [ ] **Step 2: 성공 회복력 테스트의 기대값을 Outbox 0건으로 변경**

`queryFailureDoesNotPreventSubsequentOrderCreation`의 성공 검증을 다음과 같이 바꾼다.

```java
orderCommandHandler.createOrder(BUYER_ID, command());

assertThat(orderPersistence.count()).isEqualTo(1);
assertThat(outboxEventPersistence.count()).isZero();
assertCartUnchanged();
```

실패 경로의 기존 `assertThat(outboxEventPersistence.count()).isZero()` 검증은 유지한다.

- [ ] **Step 3: 변경한 성공 테스트가 현재 구현에서 실패하는지 확인**

Run:

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.application.service.order.OrderCreationTransactionIntegrationTest.createsOneOrderFourProductsWithoutOrderCreatedOutbox" \
  --tests "com.prompthub.order.application.service.order.OrderCreationResilienceIntegrationTest.queryFailureDoesNotPreventSubsequentOrderCreation"
```

Expected: 두 성공 경로 중 적어도 하나가 실제 Outbox 1건 때문에 `expected: 0L`로 실패한다.

---

### Task 2: OrderCreator에서 외부 이벤트 생성과 Outbox 저장 제거

**Files:**
- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderCreator.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCreatorTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCreationTransactionIntegrationTest.java`

**Interfaces:**
- Consumes: `OrderRepository.save(Order)`, `ApplicationEventPublisher.publishEvent(Object)`
- Produces: `CreateOrderResult create(UUID buyerId, List<OrderItem> items)`; 외부 Outbox 없이 내부 `OrderCreatedEvent`를 발행한다.

- [ ] **Step 1: OrderCreatorTest에서 외부 이벤트 협력 객체 제거**

다음 import와 mock을 제거한다.

```java
// 제거할 import
import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.OrderEventMessageFactory;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.infra.messaging.kafka.event.OrderCreatedPayload;

// 제거할 필드
@Mock
private OrderEventMessageFactory orderEventMessageFactory;

@Mock
private OutboxEventAppender outboxEventAppender;
```

`stubSuccessfulCreation`에서는 주문 저장 시각 설정만 남긴다.

```java
private void stubSuccessfulCreation() {
    given(orderRepository.save(any(Order.class))).willAnswer(invocation -> {
        Order order = invocation.getArgument(0);
        ReflectionTestUtils.setField(order, "createdAt", CREATED_AT);
        ReflectionTestUtils.setField(order, "updatedAt", CREATED_AT);
        return order;
    });
}
```

- [ ] **Step 2: OrderCreatorTest를 주문 저장과 내부 이벤트 기준으로 재작성**

첫 번째 테스트 이름과 표시명을 외부 Outbox 표현 없이 변경하고 다음 검증을 유지한다.

```java
@Test
@DisplayName("A·B·C 판매자 상품을 주문 한 건으로 생성한다")
void createsSingleOrderWithMultipleSellerProducts() {
    stubSuccessfulCreation();
    given(orderNumberGenerator.generate()).willReturn("ORD-A");

    CreateOrderResult result = orderCreator.create(BUYER_ID, orderItems());

    ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
    then(orderRepository).should().save(orderCaptor.capture());
    Order order = orderCaptor.getValue();

    assertThat(order.getOrderProducts())
        .extracting(OrderProduct::getSellerId)
        .containsExactly(SELLER_A, SELLER_B, SELLER_A, SELLER_C);
    assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
    assertThat(order.getTotalOrderAmount()).isEqualTo(TOTAL_AMOUNT);
    assertThat(result.totalAmount()).isEqualTo(TOTAL_AMOUNT);
    then(orderNumberGenerator).should(times(1)).generate();
    then(orderRepository).should(times(1)).save(any(Order.class));
}
```

`outboxPayloadContainsSingleOrderPaymentServiceContract`는 삭제한다. `publishesCreatedOrderForExpiration`은 다음 주문 ID 검증을 추가해 유지한다.

```java
ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
then(orderRepository).should().save(orderCaptor.capture());
ArgumentCaptor<OrderCreatedEvent> eventCaptor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
then(applicationEventPublisher).should().publishEvent(eventCaptor.capture());
assertThat(eventCaptor.getValue().orderId()).isEqualTo(orderCaptor.getValue().getId());
assertThat(eventCaptor.getValue().createdAt()).isEqualTo(CREATED_AT);
```

단일 판매자 테스트는 `singleSellerStillCreatesSingleOrder`로 이름을 바꾸고 Outbox 검증을 삭제한다. overflow와 0원 테스트에서는 제거된 Factory·Appender의 `shouldHaveNoInteractions` 검증만 삭제하고 Repository·내부 이벤트 무호출 검증은 유지한다.

- [ ] **Step 3: OrderCreator의 외부 이벤트 의존성과 호출 제거**

`OrderCreator`의 필드와 `create` 후반부를 다음 형태로 만든다.

```java
private final OrderRepository orderRepository;
private final OrderNumberGenerator orderNumberGenerator;
private final ApplicationEventPublisher applicationEventPublisher;

@Transactional
public CreateOrderResult create(UUID buyerId, List<OrderItem> items) {
    int totalAmount = OrderAmountCalculator.sum(items, OrderItem::amount);
    Order order = Order.create(buyerId, orderNumberGenerator.generate(), totalAmount);
    items.stream()
        .map(item -> OrderProduct.create(
            item.productId(),
            item.sellerId(),
            item.productTitle(),
            item.amount()
        ))
        .forEach(order::addOrderProduct);

    Order savedOrder = orderRepository.save(order);
    applicationEventPublisher.publishEvent(OrderCreatedEvent.from(savedOrder));

    return CreateOrderResult.from(savedOrder);
}
```

`EventMessage`, `OrderEventMessageFactory`, `OutboxEventAppender`, `OrderCreatedPayload` import를 함께 제거한다.

- [ ] **Step 4: 더 이상 성립하지 않는 Outbox 저장 실패 롤백 테스트 제거**

`OrderCreationTransactionIntegrationTest`에서 다음 요소를 제거한다.

- `@MockitoSpyBean OutboxEventRepository outboxEventRepository`
- `willThrow(new RuntimeException("outbox failure"))`를 사용하는 `outboxFailureRollsBackOrdersAndProducts`
- `willThrow` static import
- `reset` 호출의 `outboxEventRepository`

주문 번호 충돌 롤백 테스트와 `then(orderExpirationStore).shouldHaveNoInteractions()` 검증은 유지한다.

- [ ] **Step 5: 주문 생성 단위·통합 테스트 통과 확인**

Run:

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.application.service.order.OrderCreatorTest" \
  --tests "com.prompthub.order.application.service.order.OrderCreationTransactionIntegrationTest" \
  --tests "com.prompthub.order.application.service.order.OrderCreationResilienceIntegrationTest"
```

Expected: 모든 선택 테스트가 PASS하고 성공 주문 생성 뒤 Outbox count가 0이다.

---

### Task 3: 외부 ORDER_CREATED 계약 타입과 전용 Factory 제거

**Files:**
- Modify: `src/main/java/com/prompthub/order/application/service/event/OrderEventMessageFactory.java`
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderEventType.java`
- Modify: `src/main/java/com/prompthub/order/domain/model/OutboxEvent.java`
- Delete: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayload.java`
- Delete: `src/test/java/com/prompthub/order/application/service/event/OrderEventMessageFactoryTest.java`
- Delete: `src/test/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayloadSerializationTest.java`

**Interfaces:**
- Consumes: `OrderPaidPayload`, `OrderRefundPayload`
- Produces: `createOrderPaidMessage(UUID, OrderPaidPayload)`, `createOrderRefundMessage(UUID, OrderRefundPayload)`만 제공하는 Factory

- [ ] **Step 1: OrderEventMessageFactory에서 주문 생성 메서드 제거**

다음 import와 메서드를 제거한다.

```java
import com.prompthub.order.infra.messaging.kafka.event.OrderCreatedPayload;

public EventMessage<OrderCreatedPayload> createOrderCreatedMessage(OrderCreatedPayload payload) {
    return new EventMessage<>(
        UUID.randomUUID(),
        OrderEventType.ORDER_CREATED.code(),
        LocalDateTime.now(),
        "ORDER",
        payload.orderId(),
        payload
    );
}
```

`createOrderPaidMessage`와 `createOrderRefundMessage`는 변경하지 않는다.

- [ ] **Step 2: Enum과 OutboxEvent의 주문 생성 전용 API 제거**

`OrderEventType`은 다음 이벤트만 남긴다.

```java
public enum OrderEventType implements EventType {
    ORDER_PAID,
    ORDER_REFUND,
    ORDER_CANCELED,
    ORDER_FAILED;
```

`OutboxEvent`에서는 `orderCreated(UUID, String, LocalDateTime)`와 `orderCreated(UUID, UUID, String, LocalDateTime)` 두 메서드를 삭제한다. `create`, `orderPaid`, `orderRefund`는 유지한다.

- [ ] **Step 3: 외부 payload와 전용 테스트 삭제**

다음 세 파일을 삭제한다.

```text
src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayload.java
src/test/java/com/prompthub/order/application/service/event/OrderEventMessageFactoryTest.java
src/test/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayloadSerializationTest.java
```

- [ ] **Step 4: main과 test를 컴파일해 남은 참조 확인**

Run:

```bash
../gradlew :order-service:compileJava :order-service:compileTestJava
```

Expected: 컴파일이 PASS한다. `OrderCreatedPayload`, `createOrderCreatedMessage`, `OrderEventType.ORDER_CREATED` 참조가 남아 있으면 컴파일 오류가 발생하며 Task 4의 공용 Outbox 테스트 전환 대상만 수정한다.

---

### Task 4: 공용 Outbox 테스트를 남아 있는 ORDER_PAID 계약으로 전환

**Files:**
- Modify: `src/test/java/com/prompthub/order/application/service/event/outbox/OutboxEventAppenderTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/messaging/kafka/producer/OutboxRelayTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/messaging/kafka/OutboxRelayIntegrationTest.java`

**Interfaces:**
- Consumes: `EventMessage<T>`, `OutboxEventAppender.append(EventMessage<?>)`, `OutboxRelay.publishPendingEvents()`
- Produces: 주문 ID Kafka key와 Outbox `PUBLISHED` 상태를 `ORDER_PAID`로 검증하는 회귀 테스트

- [ ] **Step 1: OutboxEventAppenderTest를 generic ORDER_PAID 메시지로 전환**

`OrderCreatedPayload` import를 제거하고 테스트 클래스 아래에 다음 record를 둔다.

```java
private record DummyPayload(UUID orderId) {
}
```

직렬화 테스트의 메시지와 기대값을 다음과 같이 바꾼다.

```java
EventMessage<DummyPayload> message = new EventMessage<>(
    eventId,
    "ORDER_PAID",
    occurredAt,
    "ORDER",
    orderId,
    new DummyPayload(orderId)
);

appender.append(message);

ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
then(outboxEventRepository).should().save(captor.capture());

OutboxEvent saved = captor.getValue();
assertThat(saved.getEventType()).isEqualTo("ORDER_PAID");
JsonNode json = objectMapper.readTree(saved.getPayload());
assertThat(json.path("eventType").asText()).isEqualTo("ORDER_PAID");
assertThat(json.path("payload").path("orderId").asText()).isEqualTo(orderId.toString());
```

표시명도 `ORDER_PAID EventMessage 전체를 JSON으로 직렬화해 Outbox에 저장한다`로 바꾼다.

- [ ] **Step 2: OutboxRelayTest의 ORDER_CREATED fixture를 ORDER_PAID로 변경**

단위 테스트 fixture는 다음 형태를 사용한다.

```java
String payload = """
    {"eventType":"ORDER_PAID","aggregateType":"ORDER","aggregateId":"%s","payload":{"orderId":"%s"}}
    """.formatted(ORDER_ID, ORDER_ID);
OutboxEvent event = OutboxEvent.create(
    eventId,
    ORDER_ID,
    "ORDER_PAID",
    payload,
    APPROVED_AT
);
```

테스트 이름은 `orderPaidEventUsesAggregateIdAndSendsOnce`, 표시명은 `ORDER_PAID Outbox는 orderId aggregateId를 key로 한 번 발행한다`로 변경한다. Kafka key, send 1회, `markPublished` 결과 검증은 유지한다.

- [ ] **Step 3: Embedded Kafka의 ORDER_CREATED 전용 테스트 제거**

`OutboxRelayIntegrationTest`에서 `outboxRelayPublishesOrderCreatedWithOrderIdKafkaKey` 메서드 전체를 삭제한다. 같은 클래스의 다음 테스트가 이미 공용 Relay와 실제 payload를 검증하므로 유지한다.

```text
outboxRelayPublishesProductServiceCompatibleOrderPaidEventToKafka
outboxRelayPublishesProductServiceCompatibleOrderRefundEventToKafka
```

삭제한 테스트에서만 사용된 local 변수나 import가 있으면 함께 제거한다.

- [ ] **Step 4: 공용 Outbox 회귀 테스트 통과 확인**

Run:

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.application.service.event.outbox.OutboxEventAppenderTest" \
  --tests "com.prompthub.order.infra.messaging.kafka.producer.OutboxRelayTest" \
  --tests "com.prompthub.order.infra.messaging.kafka.OutboxRelayIntegrationTest" \
  --tests "com.prompthub.order.domain.model.OutboxEventTest"
```

Expected: 모든 선택 테스트가 PASS하고 `ORDER_PAID`, `ORDER_REFUND` Relay 동작이 유지된다.

---

### Task 5: 내부 만료 이벤트 보존과 외부 계약 제거 정적 검증

**Files:**
- Verify: `src/main/java/com/prompthub/order/application/event/order/OrderCreatedEvent.java`
- Verify: `src/main/java/com/prompthub/order/infra/redis/OrderExpirationRegistrar.java`
- Modify: `src/test/java/com/prompthub/order/infra/redis/OrderExpirationAfterCommitIntegrationTest.java`
- Add: `docs/superpowers/specs/2026-07-18-remove-order-created-kafka-event-design.md`
- Add: `docs/superpowers/plans/2026-07-18-remove-order-created-kafka-event.md`

**Interfaces:**
- Consumes: `OrderCreatedEvent(UUID orderId, LocalDateTime createdAt)`
- Produces: DB 커밋 뒤 `OrderExpirationStore.registerExpiration(orderId, createdAt, timeoutMinutes)` 한 번 호출

- [ ] **Step 1: 외부 주문 생성 Kafka 계약 참조가 없는지 검색**

Run:

```bash
rg -n "ORDER_CREATED|OrderCreatedPayload|createOrderCreatedMessage|orderCreated\\(" src/main src/test
```

Expected: 출력이 없고 exit code가 1이다. 내부 `OrderCreatedEvent`는 이 패턴에 포함되지 않는다.

- [ ] **Step 2: 내부 만료 이벤트 경로가 남아 있는지 검색**

Run:

```bash
rg -n "OrderCreatedEvent|registerOrderExpiration" \
  src/main/java/com/prompthub/order/application \
  src/main/java/com/prompthub/order/infra/redis \
  src/test/java/com/prompthub/order
```

Expected: `OrderCreator`, `OrderCreatedEvent`, `OrderExpirationRegistrar`와 관련 단위·통합 테스트가 검색된다.

- [ ] **Step 3: AFTER_COMMIT 통합 테스트를 새 계약에 맞게 수정**

커밋 성공 테스트는 주문 생성 Outbox가 0건임을 검증한다. 롤백 테스트는 제거된 Outbox 저장 실패 mock 대신 중복 주문 번호로 DB 커밋을 실패시켜, 내부 이벤트의 `AFTER_COMMIT` listener가 실행되지 않는지 검증한다. 기존 주문 한 건은 유지되고 신규 주문과 만료 등록은 발생하지 않아야 한다.

- [ ] **Step 4: AFTER_COMMIT 만료 등록 테스트 실행**

Run:

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.infra.redis.OrderExpirationRegistrarTest" \
  --tests "com.prompthub.order.infra.redis.OrderExpirationAfterCommitIntegrationTest"
```

Expected: 두 테스트 클래스가 PASS하고 롤백된 주문은 Redis에 등록되지 않는다.

- [ ] **Step 5: 승인된 설계와 계획 문서를 대상 branch에 포함**

현재 dirty `develop` 작업트리의 문서를 복사 명령으로 옮기지 않는다. 대상 worktree에서 `apply_patch`로 동일 내용을 추가해 다른 사용자 파일과 섞이지 않게 한다.

```text
docs/superpowers/specs/2026-07-18-remove-order-created-kafka-event-design.md
docs/superpowers/plans/2026-07-18-remove-order-created-kafka-event.md
```

---

### Task 6: 전체 회귀 검증과 최종 diff 감사

**Files:**
- Verify: 모든 변경 파일

**Interfaces:**
- Consumes: Tasks 1–5의 production, test, docs 변경
- Produces: Order Service 전체 테스트·빌드와 diff 감사 결과

- [ ] **Step 1: Order Service 전체 테스트 실행**

Run:

```bash
../gradlew :order-service:test
```

Expected: 모든 Order Service 테스트가 PASS한다.

- [ ] **Step 2: Order Service 빌드 실행**

Run:

```bash
../gradlew :order-service:build
```

Expected: `BUILD SUCCESSFUL`이며 테스트 또는 컴파일 실패가 없다.

- [ ] **Step 3: whitespace와 잔여 계약 검사**

Run:

```bash
git diff --check
rg -n "ORDER_CREATED|OrderCreatedPayload|createOrderCreatedMessage|orderCreated\\(" src/main src/test
```

Expected: `git diff --check` 출력이 없고 `rg`도 출력 없이 exit code 1이다.

- [ ] **Step 4: 변경 범위와 민감정보 최종 감사**

Run:

```bash
git status --short
git diff --stat
git diff -- src/main src/test docs/superpowers/specs docs/superpowers/plans
rg -n "API[_-]?KEY|SECRET|TOKEN|PASSWORD|BEGIN (RSA|OPENSSH|EC) PRIVATE KEY" \
  docs/superpowers/specs/2026-07-18-remove-order-created-kafka-event-design.md \
  docs/superpowers/plans/2026-07-18-remove-order-created-kafka-event.md \
  src/main src/test
```

Expected: 변경은 위 File Map 범위뿐이며 신규 비밀값이 없다. 사용자의 `develop` 작업트리 변경은 대상 worktree diff에 나타나지 않는다.

- [ ] **Step 5: 결과 보고**

다음을 사용자에게 보고한다.

- 수정한 대상 branch와 worktree 경로
- 제거한 외부 `ORDER_CREATED` production·test 범위
- 유지한 내부 `OrderCreatedEvent`와 Redis 만료 흐름
- 실행한 테스트·빌드 명령과 결과
- 커밋·push를 수행하지 않았다는 사실
- 배포 환경에 기존 PENDING `ORDER_CREATED` Outbox가 있다면 별도 처리 정책 확인이 필요하다는 잔여 위험
