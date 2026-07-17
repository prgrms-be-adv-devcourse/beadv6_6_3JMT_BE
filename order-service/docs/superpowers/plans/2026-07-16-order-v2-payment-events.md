# Order Service Multi-Order Payment Events Implementation Plan

> **For agentic workers:** User override: do not invoke `superpowers:subagent-driven-development` or `superpowers:executing-plans`. Execute this plan directly in the current session, task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Payment Service의 다건 `PAYMENT_APPROVED`와 `PAYMENT_FAILED` 한 건을 여러 주문에 원자적으로 반영하고, 주문별 `ORDER_PAID`, 장바구니, processed event, Redis 만료 정리를 멱등하게 처리한다.

**Architecture:** 기존 Consumer → Router → Handler → Processor 흐름을 유지한다. `OrderRepository`가 주문 Root와 주문상품을 UUID 순서로 명시적으로 비관적 잠금하고, 두 Processor가 하나의 DB 트랜잭션 안에서 상태·장바구니·Outbox·처리 이력을 반영한다. 결제 성공 후 Redis 정리는 내부 애플리케이션 이벤트와 `AFTER_COMMIT` listener로 분리한다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Data JPA, Hibernate, H2 PostgreSQL mode, PostgreSQL, Spring Kafka, Embedded Kafka, JUnit 5, Mockito, AssertJ, Gradle Groovy DSL

## Global Constraints

- 작업 브랜치는 `feat/#369-order-v2-payment-events`를 사용한다.
- 변경 범위는 `order-service/**`와 모듈 내부 `docs/**`로 제한한다.
- `payment-service`, `product-service`, `common-module`, Gateway, `.github`는 수정하지 않는다.
- 외부 Payment Service의 다건 이벤트 발행은 선행 조건이며 이 계획에서 구현하지 않는다.
- `OrderPayment`는 `PaymentApprovedProcessor`의 저장 흐름만 제거한다. Entity·Repository·조회 API 전체 제거는 `#373` 범위다.
- `PAYMENT_CANCELED` Handler·Processor·payload·지원 event type은 제거하고, 이후 수신 메시지는 미지원 이벤트로 ACK한다.
- `PAYMENT_REFUNDED`, HTTP API, gRPC 계약, 현재 `ORDER_PAID` payload는 변경하지 않는다.
- Checkout 전체 주문 누락과 `totalAmount` 대조는 수행하지 않는다. payload에 적힌 주문상품의 소속만 검증한다.
- processed event, 주문·주문상품, 장바구니, Outbox는 같은 DB 트랜잭션에 둔다.
- Redis 정리는 DB 커밋 이후 best-effort로 수행한다.
- Kafka Consumer의 수동 ACK, 1초 간격 3회 재시도, 동일 partition `.DLT` 정책을 유지한다.
- 기존 미추적 파일 `docs/superpowers/plans/2026-07-16-order-v2-checkout-refund-refactoring.md`와 `docs/superpowers/plans/2026-07-16-order-v2-publish.md`는 stage하지 않는다.
- 커밋 메시지는 `feat`, `refactor`, `test` 중 해당 목적에 맞는 타입을 사용하고 소괄호 scope를 사용하지 않는다.

---

## File Structure

| 파일 | 책임 |
|---|---|
| `src/main/java/com/prompthub/order/domain/repository/OrderRepository.java` | 다건 주문 Aggregate 잠금 조회 포트 |
| `src/main/java/com/prompthub/order/infra/persistence/order/OrderPersistence.java` | 주문 Root 잠금과 다건 fetch 쿼리 |
| `src/main/java/com/prompthub/order/infra/persistence/order/OrderProductPersistence.java` | 주문상품 명시적 잠금 쿼리 |
| `src/main/java/com/prompthub/order/infra/persistence/order/OrderAdapter.java` | UUID 정렬과 2단계 잠금 조율 |
| `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentApprovedOrderPayload.java` | 승인 이벤트의 주문별 상품 ID 계약 |
| `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentApprovedPayload.java` | 다건 승인 계약 |
| `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentFailedPayload.java` | 다건 실패 계약 |
| `src/main/java/com/prompthub/order/application/service/event/PaymentEventValidator.java` | envelope와 다건 payload 구조 검증 |
| `src/main/java/com/prompthub/order/application/service/event/PaymentApprovedProcessor.java` | 다건 성공 상태·장바구니·Outbox·processed event 처리 |
| `src/main/java/com/prompthub/order/application/service/event/PaymentFailedProcessor.java` | 다건 실패 상태·processed event 처리 |
| `src/main/java/com/prompthub/order/application/event/order/OrderPaidEvent.java` | 커밋 후 Redis 정리 대상 주문 ID 전달 |
| `src/main/java/com/prompthub/order/infra/redis/OrderExpirationRemover.java` | `AFTER_COMMIT` 만료·재시도 정보 제거 |
| `src/main/java/com/prompthub/order/infra/messaging/kafka/router/PaymentEventRouter.java` | canceled 분기 제거와 3개 지원 이벤트 라우팅 |
| `src/main/java/com/prompthub/order/infra/messaging/kafka/consumer/payment/PaymentEventType.java` | Consumer가 지원하는 event type 판별 |
| `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentEventType.java` | Router event type 판별 |
| `src/test/java/com/prompthub/order/fixture/PaymentEventFixture.java` | 다건 결제 테스트 주문·payload fixture |
| `src/test/java/com/prompthub/order/application/service/event/PaymentEventTransactionIntegrationTest.java` | 실제 DB 트랜잭션 원자성·멱등성 검증 |
| `src/test/java/com/prompthub/order/infra/messaging/kafka/PaymentEventConsumerIntegrationTest.java` | Embedded Kafka ACK·재시도·DLT 검증 |

### Task 1: 결정적 주문·주문상품 잠금 저장소

**Files:**
- Modify: `src/main/java/com/prompthub/order/domain/repository/OrderRepository.java`
- Modify: `src/main/java/com/prompthub/order/infra/persistence/order/OrderPersistence.java`
- Create: `src/main/java/com/prompthub/order/infra/persistence/order/OrderProductPersistence.java`
- Modify: `src/main/java/com/prompthub/order/infra/persistence/order/OrderAdapter.java`
- Create: `src/test/java/com/prompthub/order/infra/persistence/order/OrderAdapterTest.java`
- Create: `src/test/java/com/prompthub/order/infra/persistence/OrderLockPersistenceTest.java`

**Interfaces:**
- Consumes: `List<UUID> orderIds`
- Produces: `List<Order> OrderRepository.findAllByIdsWithOrderProductsForUpdate(List<UUID> orderIds)`
- Guarantee: 주문 ID 오름차순 Root 잠금 후 주문별 상품 ID 오름차순 잠금

- [ ] **Step 1: Adapter 잠금 순서 단위 테스트 작성**

`OrderAdapterTest`에 입력 중복을 제거하고 Root 전체를 먼저 잠근 뒤 상품을 잠그는 호출 순서를 고정한다.

```java
@ExtendWith(MockitoExtension.class)
class OrderAdapterTest {

    private static final UUID ORDER_A = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID ORDER_B = UUID.fromString("00000000-0000-0000-0000-000000000502");

    @Mock
    private OrderPersistence orderPersistence;

    @Mock
    private OrderProductPersistence orderProductPersistence;

    @InjectMocks
    private OrderAdapter orderAdapter;

    @Test
    @DisplayName("주문 Root와 주문상품을 UUID 순서로 잠근다")
    void findAllByIdsWithOrderProductsForUpdate_locksDeterministically() {
        List<UUID> requestedIds = List.of(ORDER_B, ORDER_A, ORDER_B);
        given(orderPersistence.findAllByIdsWithOrderProducts(List.of(ORDER_A, ORDER_B)))
            .willReturn(List.of());

        orderAdapter.findAllByIdsWithOrderProductsForUpdate(requestedIds);

        InOrder inOrder = inOrder(orderPersistence, orderProductPersistence);
        inOrder.verify(orderPersistence).findByIdForUpdate(ORDER_A);
        inOrder.verify(orderPersistence).findByIdForUpdate(ORDER_B);
        inOrder.verify(orderProductPersistence).findAllByOrderIdForUpdate(ORDER_A);
        inOrder.verify(orderProductPersistence).findAllByOrderIdForUpdate(ORDER_B);
        inOrder.verify(orderPersistence).findAllByIdsWithOrderProducts(List.of(ORDER_A, ORDER_B));
    }
}
```

- [ ] **Step 2: 잠금 테스트가 컴파일 실패하는지 확인**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.infra.persistence.order.OrderAdapterTest"
```

Expected: `OrderProductPersistence`와 `findAllByIdsWithOrderProductsForUpdate`가 없어 `compileTestJava`가 실패한다.

- [ ] **Step 3: Repository 포트와 JPA 잠금 쿼리 구현**

`OrderRepository`에 다음 계약을 추가한다.

```java
List<Order> findAllByIdsWithOrderProductsForUpdate(List<UUID> orderIds);
```

`OrderPersistence`에 다음 메서드를 추가한다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select o from Order o where o.id = :orderId")
Optional<Order> findByIdForUpdate(@Param("orderId") UUID orderId);

@Query("""
    select distinct o
    from Order o
    left join fetch o.orderProducts
    where o.id in :orderIds
    order by o.id
""")
List<Order> findAllByIdsWithOrderProducts(@Param("orderIds") List<UUID> orderIds);
```

필요한 import는 다음과 같다.

```java
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import java.util.List;
```

새 `OrderProductPersistence`는 상품 행을 명시적으로 잠근다.

```java
package com.prompthub.order.infra.persistence.order;

import com.prompthub.order.domain.model.OrderProduct;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OrderProductPersistence extends JpaRepository<OrderProduct, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select op
        from OrderProduct op
        where op.order.id = :orderId
        order by op.id
    """)
    List<OrderProduct> findAllByOrderIdForUpdate(@Param("orderId") UUID orderId);
}
```

`OrderAdapter`에 `OrderProductPersistence` 의존성과 구현을 추가한다.

```java
private final OrderPersistence orderPersistence;
private final OrderProductPersistence orderProductPersistence;

@Override
public List<Order> findAllByIdsWithOrderProductsForUpdate(List<UUID> orderIds) {
    List<UUID> sortedOrderIds = orderIds.stream()
        .distinct()
        .sorted()
        .toList();

    sortedOrderIds.forEach(orderPersistence::findByIdForUpdate);
    sortedOrderIds.forEach(orderProductPersistence::findAllByOrderIdForUpdate);

    return orderPersistence.findAllByIdsWithOrderProducts(sortedOrderIds);
}
```

- [ ] **Step 4: 실제 JPA 잠금과 Aggregate 초기화 테스트 작성**

`OrderLockPersistenceTest`는 두 주문과 상품을 저장한 뒤 정렬 순서, 자식 초기화, lock mode를 검증한다.

```java
@DataJpaTest
@ActiveProfiles("test")
@Import({OrderAdapter.class, QuerydslConfig.class, TestJpaConfig.class})
class OrderLockPersistenceTest {

    private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ORDER_A = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID ORDER_B = UUID.fromString("00000000-0000-0000-0000-000000000502");
    private static final UUID ORDER_PRODUCT_A = UUID.fromString("00000000-0000-0000-0000-000000000601");
    private static final UUID ORDER_PRODUCT_B = UUID.fromString("00000000-0000-0000-0000-000000000602");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("주문과 주문상품을 잠그고 초기화된 Aggregate를 반환한다")
    void findAllByIdsWithOrderProductsForUpdate_returnsLockedAggregates() {
        entityManager.persist(order(ORDER_B, ORDER_PRODUCT_B, "ORD-B", 202));
        entityManager.persist(order(ORDER_A, ORDER_PRODUCT_A, "ORD-A", 201));
        entityManager.flush();
        entityManager.clear();

        List<Order> result = orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_B, ORDER_A));

        assertThat(result).extracting(Order::getId).containsExactly(ORDER_A, ORDER_B);
        assertThat(result).allSatisfy(order -> assertThat(order.getOrderProducts()).hasSize(1));
        assertThat(result).allSatisfy(order ->
            assertThat(entityManager.getEntityManager().getLockMode(order))
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE));
        assertThat(result).flatExtracting(Order::getOrderProducts).allSatisfy(product ->
            assertThat(entityManager.getEntityManager().getLockMode(product))
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE));
    }

    private Order order(UUID orderId, UUID orderProductId, String orderNumber, long productSuffix) {
        UUID productId = UUID.fromString("00000000-0000-0000-0000-%012d".formatted(productSuffix));
        Order order = Order.create(BUYER_ID, SELLER_ID, orderNumber, 1_000);
        OrderProduct product = OrderProduct.create(productId, "상품", 1_000);
        ReflectionTestUtils.setField(order, "id", orderId);
        ReflectionTestUtils.setField(product, "id", orderProductId);
        order.addOrderProduct(product);
        return order;
    }
}
```

- [ ] **Step 5: 저장소 테스트 통과 확인**

Run:

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.infra.persistence.order.OrderAdapterTest" \
  --tests "com.prompthub.order.infra.persistence.OrderLockPersistenceTest"
```

Expected: 두 테스트 클래스가 모두 PASS한다.

- [ ] **Step 6: 잠금 저장소 커밋**

```bash
git add src/main/java/com/prompthub/order/domain/repository/OrderRepository.java \
  src/main/java/com/prompthub/order/infra/persistence/order/OrderPersistence.java \
  src/main/java/com/prompthub/order/infra/persistence/order/OrderProductPersistence.java \
  src/main/java/com/prompthub/order/infra/persistence/order/OrderAdapter.java \
  src/test/java/com/prompthub/order/infra/persistence/order/OrderAdapterTest.java \
  src/test/java/com/prompthub/order/infra/persistence/OrderLockPersistenceTest.java
git commit -m "feat: order-service 결제 대상 다건 잠금 조회 추가"
```

### Task 2: 다건 PAYMENT_FAILED 계약과 실패 Processor

**Files:**
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentFailedPayload.java`
- Create: `src/main/java/com/prompthub/order/application/service/event/PaymentEventValidator.java`
- Modify: `src/main/java/com/prompthub/order/application/service/event/PaymentFailedProcessor.java`
- Create: `src/test/java/com/prompthub/order/fixture/PaymentEventFixture.java`
- Replace: `src/test/java/com/prompthub/order/application/service/event/PaymentFailedProcessorTest.java`
- Create: `src/test/java/com/prompthub/order/application/service/event/PaymentFailedEventHandlerTest.java`
- Create: `src/test/java/com/prompthub/order/application/service/event/PaymentEventValidatorTest.java`

**Interfaces:**
- Consumes: `PaymentFailedPayload(UUID paymentId, List<UUID> orderIds, String failureCode, String failureReason, LocalDateTime failedAt)`
- Produces: `CREATED/PENDING → FAILED/FAILED`, processed event 한 건, 장바구니·Outbox 변경 없음
- Uses: Task 1의 `findAllByIdsWithOrderProductsForUpdate`

- [ ] **Step 1: 다건 실패 fixture와 Processor 실패 테스트 작성**

새 `PaymentEventFixture`에 고정 UUID 주문 두 건을 만든다.

```java
package com.prompthub.order.fixture;

import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class PaymentEventFixture {

    public static final UUID BUYER_ID = uuid(1);
    public static final UUID OTHER_BUYER_ID = uuid(2);
    public static final UUID PAYMENT_ID = uuid(401);
    public static final UUID ORDER_A = uuid(501);
    public static final UUID ORDER_B = uuid(502);
    public static final UUID ORDER_PRODUCT_A = uuid(601);
    public static final UUID ORDER_PRODUCT_B = uuid(602);
    public static final UUID PRODUCT_A = uuid(701);
    public static final UUID PRODUCT_B = uuid(702);
    public static final UUID SELLER_A = uuid(801);
    public static final UUID SELLER_B = uuid(802);
    public static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 7, 16, 10, 0, 5);
    public static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 7, 16, 10, 0, 6);

    private PaymentEventFixture() {
    }

    public static List<Order> createdOrders() {
        return List.of(
            order(ORDER_A, ORDER_PRODUCT_A, PRODUCT_A, SELLER_A, BUYER_ID, "ORD-A", 10_000),
            order(ORDER_B, ORDER_PRODUCT_B, PRODUCT_B, SELLER_B, BUYER_ID, "ORD-B", 20_000)
        );
    }

    public static Order order(
        UUID orderId,
        UUID orderProductId,
        UUID productId,
        UUID sellerId,
        UUID buyerId,
        String orderNumber,
        int amount
    ) {
        Order order = Order.create(buyerId, sellerId, orderNumber, amount);
        OrderProduct product = OrderProduct.create(productId, "상품-" + orderNumber, amount);
        ReflectionTestUtils.setField(order, "id", orderId);
        ReflectionTestUtils.setField(product, "id", orderProductId);
        order.addOrderProduct(product);
        return order;
    }

    public static PaymentFailedPayload failedPayload() {
        return new PaymentFailedPayload(
            PAYMENT_ID,
            List.of(ORDER_B, ORDER_A),
            "PAY_FAILED",
            "PG 결제 실패",
            FAILED_AT
        );
    }

    private static UUID uuid(long suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
```

`PaymentFailedProcessorTest`는 다음 핵심 네 경로를 먼저 고정한다.

```java
@ExtendWith(MockitoExtension.class)
class PaymentFailedProcessorTest {

    @Mock ProcessedEventService processedEventService;
    @Mock OrderRepository orderRepository;
    @Spy PaymentEventValidator validator = new PaymentEventValidator();
    @InjectMocks PaymentFailedProcessor processor;

    @Test
    void process_multipleCreatedOrders_marksEveryOrderAndProductFailed() {
        List<Order> orders = PaymentEventFixture.createdOrders();
        UUID eventId = UUID.randomUUID();
        given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
        given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
            .willReturn(orders);

        processor.process(eventId, "PAYMENT_FAILED", FAILED_AT, failedPayload());

        assertThat(orders).extracting(Order::getOrderStatus)
            .containsOnly(OrderStatus.FAILED);
        assertThat(orders).flatExtracting(Order::getOrderProducts)
            .extracting(OrderProduct::getOrderStatus)
            .containsOnly(OrderProductStatus.FAILED);
        then(processedEventService).should()
            .markProcessed(eventId, "order-service", "PAYMENT_FAILED", FAILED_AT);
    }

    @Test
    void process_lateFailure_doesNotReverseCompletedOrFailedOrders() {
        List<Order> orders = PaymentEventFixture.createdOrders();
        orders.get(0).markFailed();
        orders.get(1).markCompleted(APPROVED_AT);
        UUID eventId = UUID.randomUUID();
        given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
        given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
            .willReturn(orders);

        processor.process(eventId, "PAYMENT_FAILED", FAILED_AT, failedPayload());

        assertThat(orders.get(0).getOrderStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(orders.get(1).getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void process_missingOrder_throwsBeforeStateChange() {
        List<Order> orders = PaymentEventFixture.createdOrders();
        UUID eventId = UUID.randomUUID();
        given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
        given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
            .willReturn(List.of(orders.getFirst()));

        assertThatThrownBy(() ->
            processor.process(eventId, "PAYMENT_FAILED", FAILED_AT, failedPayload()))
            .isInstanceOf(OrderException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_FOUND);
        assertThat(orders.getFirst().getOrderStatus()).isEqualTo(OrderStatus.CREATED);
        then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
    }

    @Test
    void process_sameEventId_returnsBeforeLocking() {
        UUID eventId = UUID.randomUUID();
        given(processedEventService.isProcessed(eventId, "order-service")).willReturn(true);

        processor.process(eventId, "PAYMENT_FAILED", FAILED_AT, failedPayload());

        then(orderRepository).shouldHaveNoInteractions();
        then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
    }

    @Test
    void process_eventCompletedWhileWaitingForLock_returnsWithoutStateChange() {
        List<Order> orders = PaymentEventFixture.createdOrders();
        UUID eventId = UUID.randomUUID();
        given(processedEventService.isProcessed(eventId, "order-service"))
            .willReturn(false, true);
        given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
            .willReturn(orders);

        processor.process(eventId, "PAYMENT_FAILED", FAILED_AT, failedPayload());

        assertThat(orders).extracting(Order::getOrderStatus).containsOnly(OrderStatus.CREATED);
        then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
    }
}
```

`PaymentEventValidatorTest`로 정렬과 계약 오류를 고정한다.

```java
class PaymentEventValidatorTest {

    private final PaymentEventValidator validator = new PaymentEventValidator();

    @Test
    void validateFailed_returnsSortedOrderIds() {
        assertThat(validator.validate(failedPayload())).containsExactly(ORDER_A, ORDER_B);
    }

    @Test
    void validateFailed_rejectsDuplicateOrderIds() {
        PaymentFailedPayload payload = new PaymentFailedPayload(
            PAYMENT_ID,
            List.of(ORDER_A, ORDER_A),
            "PAY_FAILED",
            "PG 결제 실패",
            FAILED_AT
        );

        assertThatThrownBy(() -> validator.validate(payload))
            .isInstanceOf(OrderException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void validateEnvelope_rejectsMissingOccurredAt() {
        assertThatThrownBy(() -> validator.validateEnvelope(UUID.randomUUID(), "PAYMENT_FAILED", null))
            .isInstanceOf(OrderException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
    }
}
```

- [ ] **Step 2: 다건 실패 테스트의 RED 확인**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.event.PaymentFailedProcessorTest"
```

Expected: 새 payload 생성자와 다건 잠금 호출이 구현되지 않아 컴파일 또는 테스트가 실패한다.

- [ ] **Step 3: 다건 실패 payload와 공통 validator 구현**

`PaymentFailedPayload`를 전체 교체한다.

```java
package com.prompthub.order.infra.messaging.kafka.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PaymentFailedPayload(
    UUID paymentId,
    List<UUID> orderIds,
    String failureCode,
    String failureReason,
    LocalDateTime failedAt
) {
}
```

새 `PaymentEventValidator`는 envelope와 실패 payload를 검증하고 정렬된 주문 ID를 반환한다.

```java
package com.prompthub.order.application.service.event;

import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class PaymentEventValidator {

    public void validateEnvelope(UUID eventId, String eventType, LocalDateTime occurredAt) {
        if (eventId == null || eventType == null || eventType.isBlank() || occurredAt == null) {
            throw invalidInput();
        }
    }

    public List<UUID> validate(PaymentFailedPayload payload) {
        if (payload == null
            || payload.paymentId() == null
            || payload.failedAt() == null
            || payload.failureCode() == null
            || payload.failureCode().isBlank()
            || payload.failureReason() == null
            || payload.failureReason().isBlank()
            || payload.orderIds() == null
            || payload.orderIds().isEmpty()) {
            throw invalidInput();
        }

        Set<UUID> uniqueOrderIds = new HashSet<>();
        for (UUID orderId : payload.orderIds()) {
            if (orderId == null || !uniqueOrderIds.add(orderId)) {
                throw invalidInput();
            }
        }

        return uniqueOrderIds.stream().sorted().toList();
    }

    private OrderException invalidInput() {
        return new OrderException(ErrorCode.INVALID_INPUT_VALUE);
    }
}
```

- [ ] **Step 4: 실패 Processor를 다건 원자 처리로 교체**

`PaymentFailedProcessor`의 본문을 다음 구조로 교체한다.

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentFailedProcessor {

    private static final String CONSUMER_GROUP = "order-service";

    private final ProcessedEventService processedEventService;
    private final OrderRepository orderRepository;
    private final PaymentEventValidator validator;

    @Transactional
    public void process(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        PaymentFailedPayload payload
    ) {
        validator.validateEnvelope(eventId, eventType, occurredAt);
        if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
            return;
        }

        List<UUID> orderIds = validator.validate(payload);
        List<Order> orders = orderRepository.findAllByIdsWithOrderProductsForUpdate(orderIds);

        if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
            return;
        }
        validateAllOrdersLoaded(orderIds, orders);

        orders.stream()
            .filter(order -> order.getOrderStatus() == OrderStatus.CREATED)
            .forEach(Order::markFailed);

        processedEventService.markProcessed(eventId, CONSUMER_GROUP, eventType, occurredAt);

        log.info(
            "결제 실패 이벤트 처리 완료. eventId={}, paymentId={}, orderIds={}, failureCode={}, failureReason={}, failedAt={}, statuses={}, consumerGroup={}",
            eventId,
            payload.paymentId(),
            orderIds,
            payload.failureCode(),
            payload.failureReason(),
            payload.failedAt(),
            orders.stream().map(order -> order.getId() + ":" + order.getOrderStatus()).toList(),
            CONSUMER_GROUP
        );
    }

    private void validateAllOrdersLoaded(List<UUID> orderIds, List<Order> orders) {
        Set<UUID> loadedOrderIds = orders.stream().map(Order::getId).collect(toSet());
        if (orders.size() != orderIds.size() || !loadedOrderIds.equals(Set.copyOf(orderIds))) {
            throw new OrderException(ErrorCode.ORDER_NOT_FOUND);
        }
    }
}
```

추가 import:

```java
import java.util.List;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
```

- [ ] **Step 5: 실패 Handler 다건 역직렬화 테스트 작성**

새 `PaymentFailedEventHandlerTest`에서 다음 JSON을 사용한다.

테스트 클래스에는 `@ExtendWith(MockitoExtension.class)`를 선언하고 다음 field와 setup을 둔다.

```java
private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
private final EventPayloadMapper eventPayloadMapper = new EventPayloadMapper(objectMapper);

@Mock
private PaymentFailedProcessor processor;

private PaymentFailedEventHandler handler;

@BeforeEach
void setUp() {
    handler = new PaymentFailedEventHandler(eventPayloadMapper, processor);
}
```

같은 class body에 다음 두 테스트 메서드를 넣는다.

```java
@Test
void handle_mapsMultiOrderFailureAndDelegates() throws Exception {
    UUID eventId = UUID.randomUUID();
    JsonNode payload = objectMapper.readTree("""
        {
          "paymentId": "%s",
          "orderIds": ["%s", "%s"],
          "failureCode": "PAY_FAILED",
          "failureReason": "PG 결제 실패",
          "failedAt": "2026-07-16T10:00:06"
        }
        """.formatted(PAYMENT_ID, ORDER_A, ORDER_B));
    EventMessage<JsonNode> message = new EventMessage<>(
        eventId, "PAYMENT_FAILED", FAILED_AT, "PAYMENT", PAYMENT_ID, payload
    );

    handler.handle(message);

    ArgumentCaptor<PaymentFailedPayload> captor = ArgumentCaptor.forClass(PaymentFailedPayload.class);
    then(processor).should().process(eq(eventId), eq("PAYMENT_FAILED"), eq(FAILED_AT), captor.capture());
    assertThat(captor.getValue().orderIds()).containsExactly(ORDER_A, ORDER_B);
    assertThat(captor.getValue().failureCode()).isEqualTo("PAY_FAILED");
}
```

같은 테스트 클래스에 잘못된 UUID가 Processor 호출 전에 매핑 오류가 되는 경로를 추가한다.

```java
@Test
void handle_invalidUuid_doesNotCallProcessor() throws Exception {
    JsonNode payload = objectMapper.readTree("""
        {
          "paymentId": "not-a-uuid",
          "orderIds": [],
          "failureCode": "PAY_FAILED",
          "failureReason": "PG 결제 실패",
          "failedAt": "2026-07-16T10:00:06"
        }
        """);
    EventMessage<JsonNode> message = new EventMessage<>(
        UUID.randomUUID(), "PAYMENT_FAILED", FAILED_AT, "PAYMENT", PAYMENT_ID, payload
    );

    assertThatThrownBy(() -> handler.handle(message)).isInstanceOf(OrderException.class);
    then(processor).shouldHaveNoInteractions();
}
```

- [ ] **Step 6: 실패 계약 테스트 통과 확인**

Run:

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.application.service.event.PaymentFailedProcessorTest" \
  --tests "com.prompthub.order.application.service.event.PaymentFailedEventHandlerTest" \
  --tests "com.prompthub.order.application.service.event.PaymentEventValidatorTest"
```

Expected: 다건 상태 전이, 늦은 실패, 누락 주문, 중복 eventId, typed payload 테스트가 PASS한다.

- [ ] **Step 7: 다건 실패 처리 커밋**

```bash
git add src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentFailedPayload.java \
  src/main/java/com/prompthub/order/application/service/event/PaymentEventValidator.java \
  src/main/java/com/prompthub/order/application/service/event/PaymentFailedProcessor.java \
  src/test/java/com/prompthub/order/fixture/PaymentEventFixture.java \
  src/test/java/com/prompthub/order/application/service/event/PaymentFailedProcessorTest.java \
  src/test/java/com/prompthub/order/application/service/event/PaymentFailedEventHandlerTest.java \
  src/test/java/com/prompthub/order/application/service/event/PaymentEventValidatorTest.java
git commit -m "feat: order-service 다건 결제 실패 이벤트 처리 추가"
```

### Task 3: 다건 PAYMENT_APPROVED와 주문별 ORDER_PAID

**Files:**
- Create: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentApprovedOrderPayload.java`
- Replace: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentApprovedPayload.java`
- Modify: `src/main/java/com/prompthub/order/application/service/event/PaymentEventValidator.java`
- Replace: `src/main/java/com/prompthub/order/application/service/event/PaymentApprovedProcessor.java`
- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderPolicyService.java`
- Replace: `src/test/java/com/prompthub/order/application/service/event/PaymentApprovedProcessorTest.java`
- Replace: `src/test/java/com/prompthub/order/application/service/event/PaymentApprovedEventHandlerTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderPolicyServiceTest.java`
- Modify: `src/test/java/com/prompthub/order/fixture/OrderFixture.java`
- Modify: `src/test/java/com/prompthub/order/fixture/PaymentEventFixture.java`
- Modify: `src/test/java/com/prompthub/order/application/service/event/PaymentEventValidatorTest.java`

**Interfaces:**
- Consumes: `PaymentApprovedPayload(paymentId, buyerId, totalAmount, orders, approvedAt)`
- Produces: 새로 완료된 주문마다 `ORDER_PAID` Outbox 한 건, 장바구니 대상 상품 제거, processed event 한 건
- State precedence: `CREATED/FAILED → COMPLETED`, 완료·환불 상태는 no-op

- [ ] **Step 1: 다건 승인 payload와 fixture를 사용하는 RED 테스트 작성**

`PaymentEventFixture`에 다음 승인 payload factory를 추가한다.

추가 import:

```java
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedOrderPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
```

```java
public static PaymentApprovedPayload approvedPayload(List<Order> orders) {
    List<PaymentApprovedOrderPayload> targets = orders.stream()
        .map(order -> new PaymentApprovedOrderPayload(
            order.getId(),
            order.getOrderProducts().stream().map(OrderProduct::getId).toList()
        ))
        .toList();
    int totalAmount = orders.stream().mapToInt(Order::getTotalOrderAmount).sum();
    return new PaymentApprovedPayload(PAYMENT_ID, BUYER_ID, totalAmount, targets, APPROVED_AT);
}
```

`PaymentApprovedProcessorTest`는 다음 Mockito 필드를 사용한다.

```java
@Mock private ProcessedEventService processedEventService;
@Mock private OrderRepository orderRepository;
@Mock private CartRepository cartRepository;
@Mock private OrderEventMessageFactory orderEventMessageFactory;
@Mock private OutboxEventAppender outboxEventAppender;
@Mock private OrderExpirationStore orderExpirationStore;
@Spy private PaymentEventValidator validator = new PaymentEventValidator();
@InjectMocks private PaymentApprovedProcessor processor;
```

`PaymentApprovedProcessorTest`의 정상 경로를 다음처럼 작성한다.

```java
@Test
void process_multipleOrders_completesAllAndCreatesOutboxPerOrder() {
    List<Order> orders = PaymentEventFixture.createdOrders();
    PaymentApprovedPayload payload = approvedPayload(orders);
    Cart cart = mock(Cart.class);
    UUID eventId = UUID.randomUUID();
    given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
    given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
        .willReturn(orders);
    given(cartRepository.findByBuyerIdWithCartProducts(BUYER_ID)).willReturn(Optional.of(cart));
    given(orderEventMessageFactory.createOrderPaidMessage(any(), any()))
        .willAnswer(invocation -> new EventMessage<>(
            UUID.randomUUID(),
            "ORDER_PAID",
            APPROVED_AT,
            "ORDER",
            invocation.getArgument(0),
            invocation.getArgument(1)
        ));

    processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload);

    assertThat(orders).extracting(Order::getOrderStatus).containsOnly(OrderStatus.COMPLETED);
    assertThat(orders).flatExtracting(Order::getOrderProducts)
        .extracting(OrderProduct::getOrderStatus)
        .containsOnly(OrderProductStatus.PAID);
    then(orderEventMessageFactory).should().createOrderPaidMessage(eq(ORDER_A), any());
    then(orderEventMessageFactory).should().createOrderPaidMessage(eq(ORDER_B), any());
    then(outboxEventAppender).should(times(2)).append(any());
    then(cart).should().removeProductsByProductIds(List.of(PRODUCT_A, PRODUCT_B));
    then(processedEventService).should()
        .markProcessed(eventId, "order-service", "PAYMENT_APPROVED", APPROVED_AT);
    then(orderExpirationStore).should().removeExpiration(ORDER_A);
    then(orderExpirationStore).should().clearRetryCount(ORDER_A);
    then(orderExpirationStore).should().removeExpiration(ORDER_B);
    then(orderExpirationStore).should().clearRetryCount(ORDER_B);
}
```

상태 우선순위와 계약 오류 테스트도 같은 파일에 추가한다.

```java
@Test
void process_failedOrder_recoversToCompleted() {
    List<Order> orders = PaymentEventFixture.createdOrders();
    orders.forEach(Order::markFailed);
    stubSuccessfulApproval(orders);
    stubOrderPaidMessages();

    processor.process(UUID.randomUUID(), "PAYMENT_APPROVED", APPROVED_AT, approvedPayload(orders));

    assertThat(orders).extracting(Order::getOrderStatus).containsOnly(OrderStatus.COMPLETED);
}

@Test
void process_completedOrders_doNotCreateDuplicateOutbox() {
    List<Order> orders = PaymentEventFixture.createdOrders();
    orders.forEach(order -> order.markCompleted(APPROVED_AT));
    stubSuccessfulApproval(orders);

    processor.process(UUID.randomUUID(), "PAYMENT_APPROVED", APPROVED_AT, approvedPayload(orders));

    then(orderEventMessageFactory).shouldHaveNoInteractions();
    then(outboxEventAppender).shouldHaveNoInteractions();
}

@Test
void process_foreignOrderProduct_throwsInvalidInput() {
    List<Order> orders = PaymentEventFixture.createdOrders();
    PaymentApprovedPayload payload = new PaymentApprovedPayload(
        PAYMENT_ID,
        BUYER_ID,
        30_000,
        List.of(
            new PaymentApprovedOrderPayload(ORDER_A, List.of(UUID.randomUUID())),
            new PaymentApprovedOrderPayload(ORDER_B, List.of(ORDER_PRODUCT_B))
        ),
        APPROVED_AT
    );
    given(processedEventService.isProcessed(any(), eq("order-service"))).willReturn(false);
    given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
        .willReturn(orders);

    assertThatThrownBy(() ->
        processor.process(UUID.randomUUID(), "PAYMENT_APPROVED", APPROVED_AT, payload))
        .isInstanceOf(OrderException.class)
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
    assertThat(orders).extracting(Order::getOrderStatus).containsOnly(OrderStatus.CREATED);
}

@Test
void process_missingOrder_throwsBeforeMutation() {
    List<Order> orders = PaymentEventFixture.createdOrders();
    PaymentApprovedPayload payload = approvedPayload(orders);
    UUID eventId = UUID.randomUUID();
    given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
    given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
        .willReturn(List.of(orders.getFirst()));

    assertThatThrownBy(() ->
        processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload))
        .isInstanceOf(OrderException.class)
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_FOUND);
    assertThat(orders).extracting(Order::getOrderStatus).containsOnly(OrderStatus.CREATED);
    then(outboxEventAppender).shouldHaveNoInteractions();
}

@Test
void process_buyerMismatch_throwsBeforeMutation() {
    List<Order> orders = new ArrayList<>(PaymentEventFixture.createdOrders());
    orders.set(1, PaymentEventFixture.order(
        ORDER_B,
        ORDER_PRODUCT_B,
        PRODUCT_B,
        SELLER_B,
        OTHER_BUYER_ID,
        "ORD-B",
        20_000
    ));
    PaymentApprovedPayload payload = approvedPayload(orders);
    UUID eventId = UUID.randomUUID();
    given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
    given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
        .willReturn(orders);

    assertThatThrownBy(() ->
        processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload))
        .isInstanceOf(OrderException.class)
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
    assertThat(orders).extracting(Order::getOrderStatus).containsOnly(OrderStatus.CREATED);
}

@Test
void process_totalAmountIsNotCorrelatedInThisIssue() {
    List<Order> orders = PaymentEventFixture.createdOrders();
    PaymentApprovedPayload original = approvedPayload(orders);
    PaymentApprovedPayload payload = new PaymentApprovedPayload(
        original.paymentId(),
        original.buyerId(),
        1,
        original.orders(),
        original.approvedAt()
    );
    stubSuccessfulApproval(orders);
    stubOrderPaidMessages();

    processor.process(UUID.randomUUID(), "PAYMENT_APPROVED", APPROVED_AT, payload);

    assertThat(orders).extracting(Order::getOrderStatus).containsOnly(OrderStatus.COMPLETED);
}
```

`PaymentEventValidatorTest`에는 승인 목록 중복을 거절하는 테스트를 추가한다.

```java
@Test
void validateApproved_rejectsDuplicateOrderProductIds() {
    PaymentApprovedPayload payload = new PaymentApprovedPayload(
        PAYMENT_ID,
        BUYER_ID,
        30_000,
        List.of(
            new PaymentApprovedOrderPayload(ORDER_A, List.of(ORDER_PRODUCT_A)),
            new PaymentApprovedOrderPayload(ORDER_B, List.of(ORDER_PRODUCT_A))
        ),
        APPROVED_AT
    );

    assertThatThrownBy(() -> validator.validate(payload))
        .isInstanceOf(OrderException.class)
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
}

@Test
void validateApproved_returnsSortedOrderIds() {
    assertThat(validator.validate(approvedPayload(PaymentEventFixture.createdOrders())))
        .containsExactly(ORDER_A, ORDER_B);
}
```

`stubSuccessfulApproval`은 테스트 클래스에 다음 코드로 둔다.

```java
private void stubSuccessfulApproval(List<Order> orders) {
    given(processedEventService.isProcessed(any(), eq("order-service"))).willReturn(false);
    given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
        .willReturn(orders);
    given(cartRepository.findByBuyerIdWithCartProducts(BUYER_ID)).willReturn(Optional.empty());
}

private void stubOrderPaidMessages() {
    given(orderEventMessageFactory.createOrderPaidMessage(any(), any()))
        .willAnswer(invocation -> new EventMessage<>(
            UUID.randomUUID(),
            "ORDER_PAID",
            APPROVED_AT,
            "ORDER",
            invocation.getArgument(0),
            invocation.getArgument(1)
        ));
}
```

- [ ] **Step 2: 다건 승인 테스트의 RED 확인**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.event.PaymentApprovedProcessorTest"
```

Expected: `PaymentApprovedOrderPayload`과 새 승인 계약이 없어 컴파일이 실패한다.

- [ ] **Step 3: 승인 payload 계약과 validator 확장 구현**

새 주문별 payload를 만든다.

```java
package com.prompthub.order.infra.messaging.kafka.event;

import java.util.List;
import java.util.UUID;

public record PaymentApprovedOrderPayload(
    UUID orderId,
    List<UUID> orderProductIds
) {
}
```

`PaymentApprovedPayload`를 교체한다.

```java
package com.prompthub.order.infra.messaging.kafka.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PaymentApprovedPayload(
    UUID paymentId,
    UUID buyerId,
    int totalAmount,
    List<PaymentApprovedOrderPayload> orders,
    LocalDateTime approvedAt
) {
}
```

`PaymentEventValidator`에 다음 overload를 추가한다.

추가 import는 다음과 같다.

```java
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedOrderPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
```

```java
public List<UUID> validate(PaymentApprovedPayload payload) {
    if (payload == null
        || payload.paymentId() == null
        || payload.buyerId() == null
        || payload.totalAmount() < 0
        || payload.approvedAt() == null
        || payload.orders() == null
        || payload.orders().isEmpty()) {
        throw invalidInput();
    }

    Set<UUID> uniqueOrderIds = new HashSet<>();
    Set<UUID> uniqueOrderProductIds = new HashSet<>();
    for (PaymentApprovedOrderPayload order : payload.orders()) {
        if (order == null
            || order.orderId() == null
            || !uniqueOrderIds.add(order.orderId())
            || order.orderProductIds() == null
            || order.orderProductIds().isEmpty()) {
            throw invalidInput();
        }
        for (UUID orderProductId : order.orderProductIds()) {
            if (orderProductId == null || !uniqueOrderProductIds.add(orderProductId)) {
                throw invalidInput();
            }
        }
    }
    return uniqueOrderIds.stream().sorted().toList();
}
```

- [ ] **Step 4: 승인 Processor를 다건 트랜잭션으로 교체**

Task 4 전까지 기존 Redis 동작을 유지하기 위해 `OrderExpirationStore`를 주문별로 호출한다. 전체 클래스의 핵심 구현은 다음과 같다.

사용할 application·domain·event import는 다음과 같다.

```java
import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.OrderPaidPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedOrderPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
```

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApprovedProcessor {

    private static final String CONSUMER_GROUP = "order-service";

    private final ProcessedEventService processedEventService;
    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final OrderEventMessageFactory orderEventMessageFactory;
    private final OutboxEventAppender outboxEventAppender;
    private final PaymentEventValidator validator;
    private final OrderExpirationStore orderExpirationStore;

    @Transactional
    public void process(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        PaymentApprovedPayload payload
    ) {
        validator.validateEnvelope(eventId, eventType, occurredAt);
        if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
            return;
        }

        List<UUID> orderIds = validator.validate(payload);
        List<Order> orders = orderRepository.findAllByIdsWithOrderProductsForUpdate(orderIds);

        if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
            return;
        }
        validateAllOrdersLoaded(orderIds, orders);
        validateApprovalTargets(payload, orders);

        List<Order> completedOrders = new ArrayList<>();
        for (Order order : orders) {
            if (order.getOrderStatus() == OrderStatus.CREATED
                || order.getOrderStatus() == OrderStatus.FAILED) {
                order.markCompleted(payload.approvedAt());
                completedOrders.add(order);
                EventMessage<OrderPaidPayload> message = orderEventMessageFactory.createOrderPaidMessage(
                    order.getId(),
                    OrderPaidPayload.from(order)
                );
                outboxEventAppender.append(message);
            }
        }

        List<UUID> productIds = orders.stream()
            .flatMap(order -> order.getOrderProducts().stream())
            .map(OrderProduct::getProductId)
            .distinct()
            .sorted()
            .toList();
        cartRepository.findByBuyerIdWithCartProducts(payload.buyerId())
            .ifPresent(cart -> cart.removeProductsByProductIds(productIds));

        processedEventService.markProcessed(eventId, CONSUMER_GROUP, eventType, occurredAt);
        completedOrders.forEach(order -> removeExpirationQuietly(order.getId()));

        log.info(
            "결제 승인 이벤트 처리 완료. eventId={}, paymentId={}, orderIds={}, statuses={}, consumerGroup={}",
            eventId,
            payload.paymentId(),
            orderIds,
            orders.stream().map(order -> order.getId() + ":" + order.getOrderStatus()).toList(),
            CONSUMER_GROUP
        );
    }

    private void validateAllOrdersLoaded(List<UUID> orderIds, List<Order> orders) {
        Set<UUID> loadedOrderIds = orders.stream().map(Order::getId).collect(Collectors.toSet());
        if (orders.size() != orderIds.size() || !loadedOrderIds.equals(Set.copyOf(orderIds))) {
            throw new OrderException(ErrorCode.ORDER_NOT_FOUND);
        }
    }

    private void validateApprovalTargets(PaymentApprovedPayload payload, List<Order> orders) {
        Map<UUID, Order> ordersById = orders.stream()
            .collect(Collectors.toMap(Order::getId, Function.identity()));
        for (PaymentApprovedOrderPayload target : payload.orders()) {
            Order order = ordersById.get(target.orderId());
            if (order == null || !order.getBuyerId().equals(payload.buyerId())) {
                throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
            }
            Set<UUID> actualOrderProductIds = order.getOrderProducts().stream()
                .map(OrderProduct::getId)
                .collect(Collectors.toSet());
            if (!actualOrderProductIds.containsAll(target.orderProductIds())) {
                throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
            }
        }
    }

    private void removeExpirationQuietly(UUID orderId) {
        try {
            orderExpirationStore.removeExpiration(orderId);
            orderExpirationStore.clearRetryCount(orderId);
        } catch (Exception exception) {
            log.warn("결제 완료 주문의 Redis 만료 대상 제거에 실패했습니다. orderId={}", orderId, exception);
        }
    }
}
```

`OrderPayment`, `OrderPaymentRepository`, `OrderPolicyService` 의존성과 저장 호출은 이 클래스에서 제거한다.

- [ ] **Step 5: 이전 단건 승인 정책 제거**

`OrderPolicyService`에서 다음 import와 메서드를 삭제한다.

```java
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
```

```java
public void validatePaymentApproval(Order order, PaymentApprovedPayload payload) {
    if (!order.isPending() && order.getOrderStatus() != OrderStatus.FAILED) {
        throw new OrderException(ErrorCode.ORDER_PAYMENT_STATUS_INVALID);
    }
    if (order.getTotalOrderAmount() != payload.approvedAmount()) {
        throw new OrderException(ErrorCode.ORDER_PAYMENT_AMOUNT_MISMATCH);
    }
}
```

`OrderPolicyServiceTest`의 `PaymentApprovalPolicy` nested class 전체와 `OrderFixture`의 단건 `createPaymentApprovedPayload` 두 메서드 및 관련 import를 삭제한다. 승인 총액 불일치 테스트는 새 계획에서 의도적으로 유지하지 않는다.

- [ ] **Step 6: 승인 Handler의 typed payload 테스트 교체**

`PaymentApprovedEventHandlerTest`의 정상 JSON과 assertion을 다음 계약으로 바꾼다.

```java
String payloadJson = """
    {
      "paymentId": "%s",
      "buyerId": "%s",
      "totalAmount": 30000,
      "orders": [
        {"orderId": "%s", "orderProductIds": ["%s"]},
        {"orderId": "%s", "orderProductIds": ["%s"]}
      ],
      "approvedAt": "2026-07-16T10:00:05"
    }
    """.formatted(PAYMENT_ID, BUYER_ID, ORDER_A, ORDER_PRODUCT_A, ORDER_B, ORDER_PRODUCT_B);
```

```java
assertThat(payload.paymentId()).isEqualTo(PAYMENT_ID);
assertThat(payload.buyerId()).isEqualTo(BUYER_ID);
assertThat(payload.totalAmount()).isEqualTo(30_000);
assertThat(payload.orders()).extracting(PaymentApprovedOrderPayload::orderId)
    .containsExactly(ORDER_A, ORDER_B);
```

잘못된 UUID JSON 테스트는 기존 `processor 미호출` assertion을 유지한다.

- [ ] **Step 7: 승인 관련 테스트 통과 확인**

Run:

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.application.service.event.PaymentApprovedProcessorTest" \
  --tests "com.prompthub.order.application.service.event.PaymentApprovedEventHandlerTest" \
  --tests "com.prompthub.order.application.service.event.PaymentEventValidatorTest" \
  --tests "com.prompthub.order.application.service.order.OrderPolicyServiceTest"
```

Expected: 다건 완료, 실패 후 성공, 늦은 중복 성공, 상품 소속 검증, Handler 매핑이 PASS한다.

- [ ] **Step 8: 다건 승인 처리 커밋**

```bash
git add src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentApprovedOrderPayload.java \
  src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentApprovedPayload.java \
  src/main/java/com/prompthub/order/application/service/event/PaymentEventValidator.java \
  src/main/java/com/prompthub/order/application/service/event/PaymentApprovedProcessor.java \
  src/main/java/com/prompthub/order/application/service/order/OrderPolicyService.java \
  src/test/java/com/prompthub/order/application/service/event/PaymentApprovedProcessorTest.java \
  src/test/java/com/prompthub/order/application/service/event/PaymentApprovedEventHandlerTest.java \
  src/test/java/com/prompthub/order/application/service/order/OrderPolicyServiceTest.java \
  src/test/java/com/prompthub/order/fixture/OrderFixture.java \
  src/test/java/com/prompthub/order/fixture/PaymentEventFixture.java \
  src/test/java/com/prompthub/order/application/service/event/PaymentEventValidatorTest.java
git commit -m "feat: order-service 다건 결제 성공 이벤트 처리 추가"
```

### Task 4: 결제 완료 Redis 정리를 AFTER_COMMIT으로 이동

**Files:**
- Create: `src/main/java/com/prompthub/order/application/event/order/OrderPaidEvent.java`
- Create: `src/main/java/com/prompthub/order/infra/redis/OrderExpirationRemover.java`
- Modify: `src/main/java/com/prompthub/order/application/service/event/PaymentApprovedProcessor.java`
- Modify: `src/test/java/com/prompthub/order/application/service/event/PaymentApprovedProcessorTest.java`
- Create: `src/test/java/com/prompthub/order/infra/redis/OrderExpirationRemoverTest.java`

**Interfaces:**
- Produces: `OrderPaidEvent(List<UUID> orderIds)`
- Consumes: `@TransactionalEventListener(phase = AFTER_COMMIT)`
- Guarantee: 롤백 시 Redis 정리 미실행, 주문별 정리 실패 격리

- [ ] **Step 1: 커밋 후 이벤트 발행과 Redis 실패 격리 테스트 작성**

`PaymentApprovedProcessorTest`에서 `OrderExpirationStore` mock과 `removeExpiration`·`clearRetryCount` 검증 네 줄을 제거하고 `ApplicationEventPublisher` mock으로 바꾼 뒤 다음 assertion을 추가한다.

```java
ArgumentCaptor<OrderPaidEvent> eventCaptor = ArgumentCaptor.forClass(OrderPaidEvent.class);
then(applicationEventPublisher).should().publishEvent(eventCaptor.capture());
assertThat(eventCaptor.getValue().orderIds()).containsExactly(ORDER_A, ORDER_B);
```

`process_completedOrders_doNotCreateDuplicateOutbox`에는 다음 검증을 추가한다.

```java
then(applicationEventPublisher).shouldHaveNoInteractions();
```

새 `OrderExpirationRemoverTest`는 첫 주문 실패 후 두 번째 주문을 계속 처리하는지 검증한다.

```java
@ExtendWith(MockitoExtension.class)
class OrderExpirationRemoverTest {

    @Mock
    private OrderExpirationStore orderExpirationStore;

    @InjectMocks
    private OrderExpirationRemover remover;

    @Test
    void removeOrderExpiration_continuesAfterOneOrderFails() {
        willThrow(new RuntimeException("redis unavailable"))
            .given(orderExpirationStore).removeExpiration(ORDER_A);

        remover.removeOrderExpiration(new OrderPaidEvent(List.of(ORDER_A, ORDER_B)));

        then(orderExpirationStore).should().removeExpiration(ORDER_A);
        then(orderExpirationStore).should(never()).clearRetryCount(ORDER_A);
        then(orderExpirationStore).should().removeExpiration(ORDER_B);
        then(orderExpirationStore).should().clearRetryCount(ORDER_B);
    }
}
```

- [ ] **Step 2: AFTER_COMMIT 테스트의 RED 확인**

Run:

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.application.service.event.PaymentApprovedProcessorTest" \
  --tests "com.prompthub.order.infra.redis.OrderExpirationRemoverTest"
```

Expected: `OrderPaidEvent`와 `OrderExpirationRemover`가 없어 실패한다.

- [ ] **Step 3: 내부 완료 이벤트와 Redis listener 구현**

```java
package com.prompthub.order.application.event.order;

import com.prompthub.order.domain.model.Order;

import java.util.List;
import java.util.UUID;

public record OrderPaidEvent(List<UUID> orderIds) {

    public OrderPaidEvent {
        orderIds = List.copyOf(orderIds);
    }

    public static OrderPaidEvent from(List<Order> orders) {
        return new OrderPaidEvent(orders.stream().map(Order::getId).sorted().toList());
    }
}
```

```java
package com.prompthub.order.infra.redis;

import com.prompthub.order.application.event.order.OrderPaidEvent;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpirationRemover {

    private final OrderExpirationStore orderExpirationStore;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void removeOrderExpiration(OrderPaidEvent event) {
        event.orderIds().forEach(this::removeQuietly);
    }

    private void removeQuietly(UUID orderId) {
        try {
            orderExpirationStore.removeExpiration(orderId);
            orderExpirationStore.clearRetryCount(orderId);
        } catch (Exception exception) {
            log.warn("결제 완료 주문의 Redis 만료 대상 제거에 실패했습니다. orderId={}", orderId, exception);
        }
    }
}
```

- [ ] **Step 4: Processor를 애플리케이션 이벤트 발행으로 전환**

`PaymentApprovedProcessor`에서 `OrderExpirationStore` 필드와 `removeExpirationQuietly` 메서드를 제거하고 다음 필드와 발행 코드를 추가한다.

```java
private final ApplicationEventPublisher applicationEventPublisher;
```

```java
if (!completedOrders.isEmpty()) {
    applicationEventPublisher.publishEvent(OrderPaidEvent.from(completedOrders));
}
```

발행 코드는 `processedEventService.markProcessed` 이후에 둔다. `@TransactionalEventListener`는 실제 DB commit이 성공한 경우에만 실행된다.

- [ ] **Step 5: 커밋 후 정리 단위 테스트 통과 확인**

Run:

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.application.service.event.PaymentApprovedProcessorTest" \
  --tests "com.prompthub.order.infra.redis.OrderExpirationRemoverTest"
```

Expected: Processor가 정렬된 주문 ID 이벤트를 한 번 발행하고 listener가 주문별 오류를 격리한다.

- [ ] **Step 6: AFTER_COMMIT 전환 커밋**

```bash
git add src/main/java/com/prompthub/order/application/event/order/OrderPaidEvent.java \
  src/main/java/com/prompthub/order/infra/redis/OrderExpirationRemover.java \
  src/main/java/com/prompthub/order/application/service/event/PaymentApprovedProcessor.java \
  src/test/java/com/prompthub/order/application/service/event/PaymentApprovedProcessorTest.java \
  src/test/java/com/prompthub/order/infra/redis/OrderExpirationRemoverTest.java
git commit -m "refactor: order-service 결제 완료 만료 정리를 커밋 이후로 이동"
```

### Task 5: 다건 결제 DB 트랜잭션 원자성 통합 테스트

**Files:**
- Create: `src/test/java/com/prompthub/order/application/service/event/PaymentEventTransactionIntegrationTest.java`

**Interfaces:**
- Uses: 실제 `PaymentApprovedProcessor`, `PaymentFailedProcessor`, H2 JPA, Cart·Outbox·Processed repositories
- Mocks: `ProductClient`, `SellerClient`, `OrderExpirationStore`
- Spies: `OutboxEventRepository`, `ProcessedEventRepository`

- [ ] **Step 1: 성공·실패·롤백·중복 통합 테스트 작성**

새 통합 테스트는 Spring bean Processor를 직접 호출해 실제 트랜잭션을 연다.

```java
@SpringBootTest
@ActiveProfiles("test")
class PaymentEventTransactionIntegrationTest {

    private static final UUID UNRELATED_PRODUCT = UUID.fromString("00000000-0000-0000-0000-000000000799");

    @Autowired PaymentApprovedProcessor approvedProcessor;
    @Autowired PaymentFailedProcessor failedProcessor;
    @Autowired OrderPersistence orderPersistence;
    @Autowired CartPersistence cartPersistence;
    @Autowired OutboxEventPersistence outboxEventPersistence;
    @PersistenceContext EntityManager entityManager;

    @MockitoBean ProductClient productClient;
    @MockitoBean SellerClient sellerClient;
    @MockitoBean OrderExpirationStore orderExpirationStore;
    @MockitoSpyBean OutboxEventRepository outboxEventRepository;
    @MockitoSpyBean ProcessedEventRepository processedEventRepository;

    @AfterEach
    void tearDown() {
        reset(outboxEventRepository, processedEventRepository, orderExpirationStore);
        processedEventRepository.deleteAll();
        outboxEventPersistence.deleteAll();
        orderPersistence.deleteAll();
        cartPersistence.deleteAll();
    }

    @Test
    void approvedEvent_commitsOrdersCartOutboxProcessedEventAndAfterCommitCleanup() {
        List<Order> orders = saveScenario();
        UUID eventId = UUID.randomUUID();

        approvedProcessor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, approvedPayload(orders));

        assertThat(reloadOrders()).extracting(Order::getOrderStatus)
            .containsOnly(OrderStatus.COMPLETED);
        assertThat(reloadOrders()).flatExtracting(Order::getOrderProducts)
            .extracting(OrderProduct::getOrderStatus)
            .containsOnly(OrderProductStatus.PAID);
        assertThat(cartProductIds()).containsExactly(UNRELATED_PRODUCT);
        assertThat(outboxEventPersistence.findAll()).extracting(OutboxEvent::getAggregateId)
            .containsExactlyInAnyOrder(ORDER_A, ORDER_B);
        assertThat(processedEventRepository.count()).isEqualTo(1);
        then(orderExpirationStore).should().removeExpiration(ORDER_A);
        then(orderExpirationStore).should().removeExpiration(ORDER_B);
    }

    @Test
    void secondOutboxFailure_rollsBackEveryDatabaseChangeAndSkipsRedisCleanup() {
        List<Order> orders = saveScenario();
        AtomicInteger saves = new AtomicInteger();
        willAnswer(invocation -> {
            if (saves.incrementAndGet() == 2) {
                throw new RuntimeException("second outbox failure");
            }
            return invocation.callRealMethod();
        }).given(outboxEventRepository).save(any());

        assertThatThrownBy(() ->
            approvedProcessor.process(UUID.randomUUID(), "PAYMENT_APPROVED", APPROVED_AT, approvedPayload(orders)))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("second outbox failure");

        entityManager.clear();
        assertThat(reloadOrders()).extracting(Order::getOrderStatus).containsOnly(OrderStatus.CREATED);
        assertThat(cartProductIds()).containsExactlyInAnyOrder(PRODUCT_A, PRODUCT_B, UNRELATED_PRODUCT);
        assertThat(outboxEventPersistence.count()).isZero();
        assertThat(processedEventRepository.count()).isZero();
        then(orderExpirationStore).shouldHaveNoInteractions();
    }

    @Test
    void sameApprovedEventTwice_keepsOneProcessedEventAndOneOutboxPerOrder() {
        List<Order> orders = saveScenario();
        UUID eventId = UUID.randomUUID();
        PaymentApprovedPayload payload = approvedPayload(orders);

        approvedProcessor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload);
        approvedProcessor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload);

        assertThat(outboxEventPersistence.count()).isEqualTo(2);
        assertThat(processedEventRepository.count()).isEqualTo(1);
        then(orderExpirationStore).should(times(1)).removeExpiration(ORDER_A);
        then(orderExpirationStore).should(times(1)).removeExpiration(ORDER_B);
    }

    @Test
    void failedEvent_commitsFailedStatesAndKeepsCartAndOutboxUnchanged() {
        saveScenario();
        UUID eventId = UUID.randomUUID();

        failedProcessor.process(eventId, "PAYMENT_FAILED", FAILED_AT, failedPayload());

        assertThat(reloadOrders()).extracting(Order::getOrderStatus).containsOnly(OrderStatus.FAILED);
        assertThat(reloadOrders()).flatExtracting(Order::getOrderProducts)
            .extracting(OrderProduct::getOrderStatus)
            .containsOnly(OrderProductStatus.FAILED);
        assertThat(cartProductIds()).containsExactlyInAnyOrder(PRODUCT_A, PRODUCT_B, UNRELATED_PRODUCT);
        assertThat(outboxEventPersistence.count()).isZero();
        assertThat(processedEventRepository.count()).isEqualTo(1);
        then(orderExpirationStore).shouldHaveNoInteractions();
    }

    @Test
    void failedEvent_processedEventFailure_rollsBackAllOrderStatesAndKeepsCart() {
        saveScenario();
        willThrow(new RuntimeException("processed event failure"))
            .given(processedEventRepository).save(any());

        assertThatThrownBy(() ->
            failedProcessor.process(UUID.randomUUID(), "PAYMENT_FAILED", FAILED_AT, failedPayload()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("processed event failure");

        entityManager.clear();
        assertThat(reloadOrders()).extracting(Order::getOrderStatus).containsOnly(OrderStatus.CREATED);
        assertThat(cartProductIds()).containsExactlyInAnyOrder(PRODUCT_A, PRODUCT_B, UNRELATED_PRODUCT);
        assertThat(outboxEventPersistence.count()).isZero();
        assertThat(processedEventRepository.count()).isZero();
    }

    private List<Order> saveScenario() {
        List<Order> orders = orderPersistence.saveAllAndFlush(PaymentEventFixture.createdOrders());
        Cart cart = Cart.create(BUYER_ID);
        cart.addProduct(PRODUCT_A);
        cart.addProduct(PRODUCT_B);
        cart.addProduct(UNRELATED_PRODUCT);
        cartPersistence.saveAndFlush(cart);
        return orders;
    }

    private List<Order> reloadOrders() {
        return List.of(
            orderPersistence.findByIdWithOrderProducts(ORDER_A).orElseThrow(),
            orderPersistence.findByIdWithOrderProducts(ORDER_B).orElseThrow()
        );
    }

    private List<UUID> cartProductIds() {
        return cartPersistence.findByBuyerIdWithCartProducts(BUYER_ID).orElseThrow()
            .getCartProducts().stream()
            .map(CartProduct::getProductId)
            .sorted()
            .toList();
    }
}
```

- [ ] **Step 2: 트랜잭션 통합 테스트 실행**

Run:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.event.PaymentEventTransactionIntegrationTest"
```

Expected: 정상 승인 commit, 두 번째 Outbox 실패 rollback, 동일 eventId 멱등성, 정상 실패 처리의 장바구니 유지, 실패 processed-event rollback이 모두 PASS한다.

- [ ] **Step 3: 트랜잭션 테스트 커밋**

```bash
git add src/test/java/com/prompthub/order/application/service/event/PaymentEventTransactionIntegrationTest.java
git commit -m "test: order-service 다건 결제 이벤트 트랜잭션 검증 추가"
```

### Task 6: PAYMENT_CANCELED 지원 제거와 실패 계약 단일화

**Files:**
- Delete: `src/main/java/com/prompthub/order/application/service/event/PaymentCanceledEventHandler.java`
- Delete: `src/main/java/com/prompthub/order/application/service/event/PaymentCanceledProcessor.java`
- Delete: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentCanceledPayload.java`
- Delete: `src/test/java/com/prompthub/order/application/service/event/PaymentCanceledProcessorTest.java`
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentEventType.java`
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/consumer/payment/PaymentEventType.java`
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/router/PaymentEventRouter.java`
- Replace: `src/test/java/com/prompthub/order/infra/messaging/kafka/router/PaymentEventRouterTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/messaging/kafka/consumer/payment/PaymentEventTypeTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/messaging/kafka/PaymentEventConsumerIntegrationTest.java`

**Interfaces:**
- Supported: `PAYMENT_APPROVED`, `PAYMENT_REFUNDED`, `PAYMENT_FAILED`
- Unsupported and ACK: `PAYMENT_CANCELED`

- [ ] **Step 1: canceled가 미지원임을 나타내는 RED 테스트 작성**

`PaymentEventTypeTest`에 다음 assertion을 추가한다.

```java
assertThat(PaymentEventType.from("PAYMENT_CANCELED")).isNull();
```

`PaymentEventRouterTest`에 canceled가 어떤 Handler도 호출하지 않는 테스트를 추가한다.

기존 테스트 클래스에서 `PaymentCanceledEventHandler` import와 `canceledHandler` mock, 기존 세 테스트의 `verify(canceledHandler, never())` 호출을 먼저 제거한다.

```java
@Test
void route_paymentCanceled_isUnsupported() {
    EventMessage<JsonNode> message = new EventMessage<>(
        UUID.randomUUID(), "PAYMENT_CANCELED", LocalDateTime.now(), "PAYMENT", UUID.randomUUID(), dummyPayload
    );

    paymentEventRouter.route(message);

    then(approvedHandler).shouldHaveNoInteractions();
    then(refundedHandler).shouldHaveNoInteractions();
    then(failedHandler).shouldHaveNoInteractions();
}
```

- [ ] **Step 2: canceled 미지원 테스트가 실패하는지 확인**

Run:

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.infra.messaging.kafka.router.PaymentEventRouterTest" \
  --tests "com.prompthub.order.infra.messaging.kafka.consumer.payment.PaymentEventTypeTest"
```

Expected: 현재 `PAYMENT_CANCELED`가 지원되므로 assertion이 실패한다.

- [ ] **Step 3: canceled 코드와 Router 의존성 제거**

두 `PaymentEventType` Enum을 다음 값만 갖도록 바꾼다.

```java
PAYMENT_APPROVED,
PAYMENT_REFUNDED,
PAYMENT_FAILED;
```

`PaymentEventRouter` 생성자 필드와 switch를 다음 세 Handler로 제한한다.

```java
private final PaymentApprovedEventHandler approvedHandler;
private final PaymentRefundedEventHandler refundedHandler;
private final PaymentFailedEventHandler failedHandler;
```

```java
switch (eventType) {
    case PAYMENT_APPROVED -> approvedHandler.handle(message);
    case PAYMENT_REFUNDED -> refundedHandler.handle(message);
    case PAYMENT_FAILED -> failedHandler.handle(message);
}
```

다음 파일 네 개를 삭제한다.

```text
src/main/java/com/prompthub/order/application/service/event/PaymentCanceledEventHandler.java
src/main/java/com/prompthub/order/application/service/event/PaymentCanceledProcessor.java
src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentCanceledPayload.java
src/test/java/com/prompthub/order/application/service/event/PaymentCanceledProcessorTest.java
```

- [ ] **Step 4: Embedded Kafka 테스트에서 canceled mock 제거**

`PaymentEventConsumerIntegrationTest`에서 `PaymentCanceledEventHandler` import, `@MockitoBean` 필드, `clearInvocations` 인자를 제거한다. canceled 메시지는 다음 테스트로 미지원 ACK와 DLT 미전달을 검증한다.

```java
@Test
void consumePaymentCanceled_acknowledgesAsUnsupportedWithoutDlt() throws Exception {
    try (Consumer<String, String> dltConsumer = stringConsumer()) {
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, true, PAYMENT_EVENTS_DLT_TOPIC);
        rawStringKafkaTemplate().send(
            PAYMENT_EVENTS_TOPIC,
            PAYMENT_ID.toString(),
            ignoredEvent("PAYMENT_CANCELED")
        ).get(5, TimeUnit.SECONDS);

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(paymentApprovedEventHandler, never()).handle(any());
            verify(paymentRefundedEventHandler, never()).handle(any());
            verify(paymentFailedEventHandler, never()).handle(any());
        });
        assertThat(dltConsumer.poll(Duration.ofSeconds(2))).isEmpty();
    }
}
```

- [ ] **Step 5: canceled 제거 회귀 테스트 실행**

Run:

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.infra.messaging.kafka.router.PaymentEventRouterTest" \
  --tests "com.prompthub.order.infra.messaging.kafka.consumer.payment.PaymentEventTypeTest" \
  --tests "com.prompthub.order.infra.messaging.kafka.PaymentEventConsumerIntegrationTest"
```

Expected: approved/refunded/failed 라우팅은 PASS하고 canceled는 미지원 ACK로 PASS한다.

- [ ] **Step 6: 실패 계약 단일화 커밋**

```bash
git add src/main/java/com/prompthub/order/application/service/event/PaymentCanceledEventHandler.java \
  src/main/java/com/prompthub/order/application/service/event/PaymentCanceledProcessor.java \
  src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentCanceledPayload.java \
  src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentEventType.java \
  src/main/java/com/prompthub/order/infra/messaging/kafka/consumer/payment/PaymentEventType.java \
  src/main/java/com/prompthub/order/infra/messaging/kafka/router/PaymentEventRouter.java \
  src/test/java/com/prompthub/order/application/service/event/PaymentCanceledProcessorTest.java \
  src/test/java/com/prompthub/order/infra/messaging/kafka/router/PaymentEventRouterTest.java \
  src/test/java/com/prompthub/order/infra/messaging/kafka/consumer/payment/PaymentEventTypeTest.java \
  src/test/java/com/prompthub/order/infra/messaging/kafka/PaymentEventConsumerIntegrationTest.java
git commit -m "refactor: order-service 결제 실패 이벤트 계약 단일화"
```

### Task 7: Embedded Kafka ACK·재시도·DLT 계약 강화

**Files:**
- Modify: `src/test/java/com/prompthub/order/infra/messaging/kafka/consumer/PaymentEventConsumerTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/messaging/kafka/PaymentEventConsumerIntegrationTest.java`

**Interfaces:**
- ACK: 지원 이벤트 Handler 정상 반환, 미지원 event type
- Retry/DLT: Handler·Processor 예외, 잘못된 JSON, payload 누락
- Retry count: 최초 시도 1회 + 재시도 3회 = Handler 호출 4회

- [ ] **Step 1: Consumer가 Handler 오류에서 ACK하지 않는 단위 테스트 작성**

```java
@Test
void consume_routerFailure_doesNotAcknowledge() throws Exception {
    EventMessage<JsonNode> message = new EventMessage<>(
        UUID.randomUUID(),
        "PAYMENT_APPROVED",
        LocalDateTime.now(),
        "PAYMENT",
        UUID.randomUUID(),
        objectMapper.createObjectNode().put("paymentId", UUID.randomUUID().toString())
    );
    String jsonMessage = objectMapper.writeValueAsString(message);
    willThrow(new OrderException(ErrorCode.INVALID_INPUT_VALUE))
        .given(paymentEventRouter).route(any());

    assertThatThrownBy(() -> consumer.consume(jsonMessage, acknowledgment))
        .isInstanceOf(OrderException.class);

    then(acknowledgment).shouldHaveNoInteractions();
}
```

- [ ] **Step 2: Embedded Kafka Handler 재시도·DLT 테스트 작성**

`PaymentEventConsumerIntegrationTest`의 `@BeforeEach`는 네 Handler의 invocation만 지우지 말고 세 Handler mock을 `reset`한다.

```java
@BeforeEach
void resetMocks() {
    reset(paymentApprovedEventHandler, paymentRefundedEventHandler, paymentFailedEventHandler);
}
```

다음 테스트로 실제 재시도 횟수와 DLT 값을 검증한다.

```java
@Test
void consumePaymentApproved_handlerFailureRetriesThreeTimesThenSendsDlt() throws Exception {
    willThrow(new OrderException(ErrorCode.INVALID_INPUT_VALUE))
        .given(paymentApprovedEventHandler).handle(any(EventMessage.class));

    try (Consumer<String, String> dltConsumer = stringConsumer()) {
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, true, PAYMENT_EVENTS_DLT_TOPIC);
        String message = """
            {
              "eventId": "%s",
              "eventType": "PAYMENT_APPROVED",
              "occurredAt": "2026-07-16T10:00:05",
              "aggregateType": "PAYMENT",
              "aggregateId": "%s",
              "payload": {
                "paymentId": "%s",
                "buyerId": "%s",
                "totalAmount": 30000,
                "orders": [
                  {"orderId": "%s", "orderProductIds": ["%s"]},
                  {"orderId": "%s", "orderProductIds": ["%s"]}
                ],
                "approvedAt": "2026-07-16T10:00:05"
              }
            }
            """.formatted(
                UUID.randomUUID(), PAYMENT_ID, PAYMENT_ID, BUYER_ID,
                ORDER_A, ORDER_PRODUCT_A, ORDER_B, ORDER_PRODUCT_B
            );

        rawStringKafkaTemplate().send(PAYMENT_EVENTS_TOPIC, PAYMENT_ID.toString(), message)
            .get(5, TimeUnit.SECONDS);

        ConsumerRecord<String, String> dltRecord = KafkaTestUtils.getSingleRecord(
            dltConsumer,
            PAYMENT_EVENTS_DLT_TOPIC,
            Duration.ofSeconds(10)
        );
        verify(paymentApprovedEventHandler, timeout(10_000).times(4)).handle(any(EventMessage.class));
        assertThat(dltRecord.value()).contains("PAYMENT_APPROVED").contains(PAYMENT_ID.toString());
    }
}
```

- [ ] **Step 3: 성공·실패 Embedded Kafka 메시지를 다건 payload로 갱신**

승인 테스트의 payload Map은 다음 구조를 사용한다.

```java
Map<String, Object> payload = new HashMap<>();
payload.put("paymentId", PAYMENT_ID.toString());
payload.put("buyerId", BUYER_ID.toString());
payload.put("totalAmount", 30_000);
payload.put("orders", List.of(
    Map.of("orderId", ORDER_A.toString(), "orderProductIds", List.of(ORDER_PRODUCT_A.toString())),
    Map.of("orderId", ORDER_B.toString(), "orderProductIds", List.of(ORDER_PRODUCT_B.toString()))
));
payload.put("approvedAt", APPROVED_AT.toString());
```

실패 테스트의 payload Map은 다음 구조를 사용한다.

```java
Map<String, Object> payload = new HashMap<>();
payload.put("paymentId", PAYMENT_ID.toString());
payload.put("orderIds", List.of(ORDER_A.toString(), ORDER_B.toString()));
payload.put("failureCode", "PAY_FAILED");
payload.put("failureReason", "PG 결제 실패");
payload.put("failedAt", FAILED_AT.toString());
```

두 메시지 모두 envelope의 `aggregateType`은 `PAYMENT`, `aggregateId`와 Kafka key는 `paymentId`를 사용한다.

- [ ] **Step 4: Consumer와 Embedded Kafka 테스트 실행**

Run:

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.infra.messaging.kafka.consumer.PaymentEventConsumerTest" \
  --tests "com.prompthub.order.infra.messaging.kafka.PaymentEventConsumerIntegrationTest" \
  --tests "com.prompthub.order.infra.messaging.kafka.config.KafkaConfigTest"
```

Expected: 정상 ACK, 미지원 canceled ACK, Handler 4회 호출 후 DLT, invalid JSON DLT, payload 누락 DLT가 PASS한다.

- [ ] **Step 5: Kafka 회귀 테스트 커밋**

```bash
git add src/test/java/com/prompthub/order/infra/messaging/kafka/consumer/PaymentEventConsumerTest.java \
  src/test/java/com/prompthub/order/infra/messaging/kafka/PaymentEventConsumerIntegrationTest.java
git commit -m "test: order-service 결제 이벤트 Kafka 재시도와 DLT 검증 추가"
```

### Task 8: 전체 회귀·범위·보안 검증

**Files:**
- Verify only: `order-service/**`, `order-service/docs/**`

**Interfaces:**
- Produces: 테스트·build·diff check 결과와 깨끗한 기능 커밋 구조

- [ ] **Step 1: 관련 테스트 묶음 실행**

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.application.service.event.*" \
  --tests "com.prompthub.order.infra.persistence.OrderLockPersistenceTest" \
  --tests "com.prompthub.order.infra.persistence.order.OrderAdapterTest" \
  --tests "com.prompthub.order.infra.redis.OrderExpirationRemoverTest" \
  --tests "com.prompthub.order.infra.messaging.kafka.PaymentEventConsumerIntegrationTest"
```

Expected: 결제 이벤트 관련 단위·JPA·트랜잭션·Embedded Kafka 테스트가 모두 PASS한다.

- [ ] **Step 2: order-service 전체 테스트 실행**

```bash
../gradlew :order-service:test
```

Expected: `BUILD SUCCESSFUL`이며 실패 테스트가 없다.

- [ ] **Step 3: order-service build 실행**

```bash
../gradlew :order-service:build
```

Expected: `BUILD SUCCESSFUL`이다. 기존 생성 코드 Checkstyle warning이 출력되더라도 새 오류와 task 실패가 없어야 한다.

- [ ] **Step 4: diff와 변경 범위 확인**

```bash
git diff --check feat/#368-order-v2-create...HEAD
git status --short
git diff --name-only feat/#368-order-v2-create...HEAD
```

Expected:

- `git diff --check feat/#368-order-v2-create...HEAD` 출력이 없다.
- 기존 미추적 계획 파일 두 개와 이 구현 계획 파일 외에 미커밋 변경이 없다.
- 커밋된 변경은 `order-service/**` 안에만 있다.
- `payment-service`, `product-service`, `common-module`, Gateway, `.github` 변경이 없다.

- [ ] **Step 5: 민감정보와 제거 범위 확인**

```bash
rg -n "API[_-]?KEY|SECRET|TOKEN|PASSWORD|BEGIN (RSA|OPENSSH) PRIVATE KEY" \
  src/main/java src/test/java docs/superpowers/specs
rg -n "PaymentCanceled|PAYMENT_CANCELED" src/main/java src/test/java
rg -n "orderPaymentRepository\.save|OrderPayment\.create" \
  src/main/java/com/prompthub/order/application/service/event/PaymentApprovedProcessor.java
```

Expected:

- 신규 민감정보가 없다.
- canceled 구현 참조는 없고 미지원 이벤트 테스트 문자열만 남는다.
- `PaymentApprovedProcessor`에 `OrderPayment` 저장 호출이 없다.

- [ ] **Step 6: 커밋 구조 확인**

```bash
git log --oneline feat/#368-order-v2-create..HEAD
```

Expected commit order:

```text
test: order-service 결제 이벤트 Kafka 재시도와 DLT 검증 추가
refactor: order-service 결제 실패 이벤트 계약 단일화
test: order-service 다건 결제 이벤트 트랜잭션 검증 추가
refactor: order-service 결제 완료 만료 정리를 커밋 이후로 이동
feat: order-service 다건 결제 성공 이벤트 처리 추가
feat: order-service 다건 결제 실패 이벤트 처리 추가
feat: order-service 결제 대상 다건 잠금 조회 추가
docs: order-service 다건 결제 이벤트 처리 설계 추가
```

최종 검증 단계에서는 모든 구현 변경이 앞선 목적별 커밋에 포함돼 있어야 하며 별도의 catch-all 커밋을 만들지 않는다.
