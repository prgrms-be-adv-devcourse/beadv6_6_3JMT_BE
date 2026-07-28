# Order Purchase Query APIs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Provide frontend-only order query APIs that report whether a buyer can still access a product and return the buyer's accessible purchased product IDs.

**Architecture:** Keep the existing OrderController -> OrderQueryUseCase -> OrderQueryService -> OrderRepository -> OrderPersistence read path. Repository JPQL applies the same eligibility predicate as Order.canAccessContent: an order in COMPLETED, PARTIAL_REFUNDED, or REFUND_REQUESTED and an OrderProduct in PAID; no external service call or schema change is required.

**Tech Stack:** Java 21, Spring Boot MVC, Spring Data JPA, JUnit 5, Mockito, H2 PostgreSQL mode, MockMvc.

## Global Constraints

- Both endpoints require Gateway-injected X-User-Id; preserve GlobalExceptionHandler's missing-header 401 A003 behavior.
- Return ApiResult<Boolean> and ApiResult<List<UUID>>; use false and [] for no matching purchase, never 404.
- The product-ID list is duplicate-free and represents a membership set; consumers must not rely on list order.
- A refund-requested line is excluded because its OrderProductStatus is REFUND_REQUESTED; another PAID line in the same refund-requested order remains included.
- Do not alter order state, call product-service, create migrations, stage files, or create a commit unless separately requested.

## File Map

- Modify src/main/java/com/prompthub/order/domain/repository/OrderRepository.java to expose the two eligibility queries.
- Modify src/main/java/com/prompthub/order/infra/persistence/order/OrderPersistence.java and OrderAdapter.java to execute and adapt JPQL.
- Modify src/main/java/com/prompthub/order/application/usecase/OrderQueryUseCase.java and application/service/order/OrderQueryService.java to add read-only use cases.
- Modify src/main/java/com/prompthub/order/presentation/OrderController.java to publish the two documented v2 routes.
- Extend OrderPersistenceImplTest, OrderAdapterTest, OrderQueryServiceTest, OrderControllerTest, and OrderWebContractTest at their matching layer.

### Task 1: Persist and expose the accessible-purchase predicate

**Files:**

- Modify: src/main/java/com/prompthub/order/domain/repository/OrderRepository.java
- Modify: src/main/java/com/prompthub/order/infra/persistence/order/OrderPersistence.java
- Modify: src/main/java/com/prompthub/order/infra/persistence/order/OrderAdapter.java
- Test: src/test/java/com/prompthub/order/infra/persistence/OrderPersistenceImplTest.java
- Test: src/test/java/com/prompthub/order/infra/persistence/order/OrderAdapterTest.java

**Interfaces:**

~~~java
boolean existsAccessiblePaidOrderProductByBuyerIdAndProductId(UUID buyerId, UUID productId);

List<UUID> findAccessiblePaidProductIdsByBuyerId(UUID buyerId);
~~~

- [ ] **Step 1: Write the failing persistence tests.**

Persist completed, partially-refunded, refund-requested, pending, and other-buyer orders through TestEntityManager. The refund-requested order must contain a requested-refund product and a separate PAID product. Repeat one accessible purchase to prove distinctness.

~~~java
assertThat(orderPersistence.existsAccessiblePaidOrderProductByBuyerIdAndProductId(
    BUYER_ID, PRODUCT_ID_1
)).isTrue();
assertThat(orderPersistence.existsAccessiblePaidOrderProductByBuyerIdAndProductId(
    BUYER_ID, UNKNOWN_PRODUCT_ID
)).isFalse();
assertThat(orderPersistence.findAccessiblePaidProductIdsByBuyerId(BUYER_ID))
    .containsExactlyInAnyOrder(PRODUCT_ID_1, PRODUCT_ID_2);
~~~

Assert that refunded and refund-requested lines, pending orders, and another buyer's lines are excluded.

- [ ] **Step 2: Run the test to verify it fails.**

Run:

~~~bash
../gradlew :order-service:test --tests "com.prompthub.order.infra.persistence.OrderPersistenceImplTest"
~~~

Expected: compilation failure because the two OrderPersistence methods do not exist.

- [ ] **Step 3: Add the repository methods and adapter delegation.**

Use this exact predicate in both JPQL queries:

~~~java
@Query("""
    select case when count(op) > 0 then true else false end
    from Order o join o.orderProducts op
    where o.buyerId = :buyerId
      and op.productId = :productId
      and o.orderStatus in (
        com.prompthub.order.domain.enums.OrderStatus.COMPLETED,
        com.prompthub.order.domain.enums.OrderStatus.PARTIAL_REFUNDED,
        com.prompthub.order.domain.enums.OrderStatus.REFUND_REQUESTED
      )
      and op.orderStatus = com.prompthub.order.domain.enums.OrderProductStatus.PAID
    """)
boolean existsAccessiblePaidOrderProductByBuyerIdAndProductId(
    @Param("buyerId") UUID buyerId,
    @Param("productId") UUID productId
);

@Query("""
    select distinct op.productId
    from Order o join o.orderProducts op
    where o.buyerId = :buyerId
      and o.orderStatus in (
        com.prompthub.order.domain.enums.OrderStatus.COMPLETED,
        com.prompthub.order.domain.enums.OrderStatus.PARTIAL_REFUNDED,
        com.prompthub.order.domain.enums.OrderStatus.REFUND_REQUESTED
      )
      and op.orderStatus = com.prompthub.order.domain.enums.OrderProductStatus.PAID
    """)
List<UUID> findAccessiblePaidProductIdsByBuyerId(@Param("buyerId") UUID buyerId);
~~~

Add those names to OrderRepository and return the OrderPersistence values unchanged from OrderAdapter. Remove the existing unused, narrower existsPaidOrderProductByBuyerIdAndProductId method so one predicate defines this capability.

- [ ] **Step 4: Add adapter delegation tests and run the focused tests.**

~~~java
given(orderPersistence.findAccessiblePaidProductIdsByBuyerId(BUYER_ID))
    .willReturn(List.of(PRODUCT_ID_1));

assertThat(orderAdapter.findAccessiblePaidProductIdsByBuyerId(BUYER_ID))
    .containsExactly(PRODUCT_ID_1);
then(orderPersistence).should().findAccessiblePaidProductIdsByBuyerId(BUYER_ID);
~~~

~~~bash
../gradlew :order-service:test --tests "com.prompthub.order.infra.persistence.OrderPersistenceImplTest" --tests "com.prompthub.order.infra.persistence.order.OrderAdapterTest"
~~~

Expected: both test classes pass.

### Task 2: Add application-level purchase queries

**Files:**

- Modify: src/main/java/com/prompthub/order/application/usecase/OrderQueryUseCase.java
- Modify: src/main/java/com/prompthub/order/application/service/order/OrderQueryService.java
- Test: src/test/java/com/prompthub/order/application/service/order/OrderQueryServiceTest.java

**Interfaces:**

~~~java
boolean hasAccessiblePaidProduct(UUID buyerId, UUID productId);

List<UUID> getAccessiblePaidProductIds(UUID buyerId);
~~~

- [ ] **Step 1: Write failing Mockito service tests.**

~~~java
given(orderRepository.existsAccessiblePaidOrderProductByBuyerIdAndProductId(
    BUYER_ID, PRODUCT_ID_1
)).willReturn(true);

assertThat(orderQueryService.hasAccessiblePaidProduct(BUYER_ID, PRODUCT_ID_1)).isTrue();
then(orderRepository).should().existsAccessiblePaidOrderProductByBuyerIdAndProductId(
    BUYER_ID, PRODUCT_ID_1
);
~~~

~~~java
given(orderRepository.findAccessiblePaidProductIdsByBuyerId(BUYER_ID))
    .willReturn(List.of(PRODUCT_ID_1, PRODUCT_ID_2));

assertThat(orderQueryService.getAccessiblePaidProductIds(BUYER_ID))
    .containsExactly(PRODUCT_ID_1, PRODUCT_ID_2);
then(orderRepository).should().findAccessiblePaidProductIdsByBuyerId(BUYER_ID);
~~~

Cover false and List.of() too, and assert ProductClient has no interactions for both queries.

- [ ] **Step 2: Run the test to verify it fails.**

~~~bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderQueryServiceTest"
~~~

Expected: compilation failure for the missing use-case methods.

- [ ] **Step 3: Add the contract and the minimal read-only implementation.**

OrderQueryService already has class-level @Transactional(readOnly = true), so do not add a new transaction boundary or ProductClient call.

~~~java
@Override
public boolean hasAccessiblePaidProduct(UUID buyerId, UUID productId) {
    return orderRepository.existsAccessiblePaidOrderProductByBuyerIdAndProductId(buyerId, productId);
}

@Override
public List<UUID> getAccessiblePaidProductIds(UUID buyerId) {
    return orderRepository.findAccessiblePaidProductIdsByBuyerId(buyerId);
}
~~~

- [ ] **Step 4: Rerun the service test.**

~~~bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderQueryServiceTest"
~~~

Expected: pass.

### Task 3: Publish the frontend API contract

**Files:**

- Modify: src/main/java/com/prompthub/order/presentation/OrderController.java
- Test: src/test/java/com/prompthub/order/presentation/OrderControllerTest.java
- Test: src/test/java/com/prompthub/order/global/web/OrderWebContractTest.java

**Interfaces:**

~~~http
GET /api/v2/orders/product/{productId}/paid
X-User-Id: <buyer UUID>
200 {"success":true,"message":"success","data":true}

GET /api/v2/orders/users
X-User-Id: <buyer UUID>
200 {"success":true,"message":"success","data":["<product UUID>"]}
~~~

- [ ] **Step 1: Write failing MockMvc tests.**

~~~java
when(orderQueryUseCase.hasAccessiblePaidProduct(BUYER_ID, PRODUCT_ID_1)).thenReturn(true);

mockMvc.perform(get("/api/v2/orders/product/{productId}/paid", PRODUCT_ID_1)
        .header(AuthHeaders.USER_ID, BUYER_ID.toString()))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.success").value(true))
    .andExpect(jsonPath("$.data").value(true));
~~~

~~~java
when(orderQueryUseCase.getAccessiblePaidProductIds(BUYER_ID))
    .thenReturn(List.of(PRODUCT_ID_1, PRODUCT_ID_2));

mockMvc.perform(get("/api/v2/orders/users")
        .header(AuthHeaders.USER_ID, BUYER_ID.toString()))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data[0]").value(PRODUCT_ID_1.toString()))
    .andExpect(jsonPath("$.data[1]").value(PRODUCT_ID_2.toString()));
~~~

Add false and empty-array variants, invalid UUID path-variable 400 V001, and missing header 401 A003; verify the use case is never called when binding or authentication fails.

- [ ] **Step 2: Run the MVC tests to verify they fail.**

~~~bash
../gradlew :order-service:test --tests "com.prompthub.order.presentation.OrderControllerTest" --tests "com.prompthub.order.global.web.OrderWebContractTest"
~~~

Expected: the new route assertions fail with 404.

- [ ] **Step 3: Add controller routes and Swagger metadata.**

Place the static routes above @GetMapping("/{orderId}") for readability.

~~~java
@GetMapping("/product/{productId}/paid")
@Operation(summary = "상품 구매 여부 조회", description = "구매자가 현재 상품 콘텐츠를 열람할 수 있는 결제 상태인지 반환합니다.")
public ApiResult<Boolean> hasAccessiblePaidProduct(
    @Parameter(in = ParameterIn.HEADER, name = USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
    @RequestHeader(USER_ID) UUID buyerId,
    @PathVariable UUID productId
) {
    return ApiResult.success(orderQueryUseCase.hasAccessiblePaidProduct(buyerId, productId));
}

@GetMapping("/users")
@Operation(summary = "구매 상품 ID 목록 조회", description = "구매자가 현재 열람할 수 있는 상품 ID 목록을 중복 없이 반환합니다.")
public ApiResult<List<UUID>> getAccessiblePaidProductIds(
    @Parameter(in = ParameterIn.HEADER, name = USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
    @RequestHeader(USER_ID) UUID buyerId
) {
    return ApiResult.success(orderQueryUseCase.getAccessiblePaidProductIds(buyerId));
}
~~~

Declare 200, 400, and 401 Swagger responses. Add one success case per endpoint to OrderWebContractTest so the full MVC context protects the injected-header contract.

- [ ] **Step 4: Rerun the MVC tests.**

~~~bash
../gradlew :order-service:test --tests "com.prompthub.order.presentation.OrderControllerTest" --tests "com.prompthub.order.global.web.OrderWebContractTest"
~~~

Expected: pass with ApiResult envelopes and A003 header failures.

### Task 4: Regression and delivery review

**Files:** No production-file changes.

- [ ] **Step 1: Run the module regression suite.**

~~~bash
../gradlew :order-service:test
~~~

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Inspect the final diff.**

~~~bash
git diff --check
git diff -- src/main/java/com/prompthub/order src/test/java/com/prompthub/order docs/superpowers/plans/2026-07-21-order-purchase-query-apis.md
~~~

Expected: no whitespace errors; only the eligibility predicate, the two read endpoints, their tests, and this plan are changed.

- [ ] **Step 3: Confirm no-purchase response examples.**

~~~json
{"success":true,"message":"success","data":false}
{"success":true,"message":"success","data":[]}
~~~

Confirm they are normal successful responses, not errors, and that fully refunded or refund-requested product lines never appear.

## Self-Review

- Coverage: both routes, the agreed accessible-content semantics, gateway header handling, duplicate removal, Swagger, unit tests, JPA integration tests, and module regression are covered.
- Type consistency: controller, use case, service, port, adapter, and JPA method names are identical; values remain boolean and List<UUID> end-to-end.
- Scope: no DTO, Kafka, gRPC, Redis, external-call, schema, or authentication-flow change is introduced.
