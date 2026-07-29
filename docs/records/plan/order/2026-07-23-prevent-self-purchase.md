# 주문 생성 시 본인 상품 구매 방지 Implementation Plan

> **For agentic workers:** 이 계획은 현재 격리 worktree에서 단계별로 실행한다. 각 단계는 독립적으로 검증하고, 저장소 지침에 따라 사용자가 요청하지 않은 stage·commit·push는 수행하지 않는다.

**Goal:** 주문 생성 전에 구매자와 상품 판매자가 같은지 검증해 셀프 구매를 무료·유료, 단건·다건, 직접·장바구니 주문에서 모두 차단한다.

**Architecture:** `OrderCommandHandler`가 Product Service 스냅샷을 검증한 직후 `OrderPolicyService.validateSelfPurchase`를 호출한다. 검증 통과 후 요청 데이터와 스냅샷을 `OrderCreationItem`으로 결합해 `OrderCreator`에 전달하며, 실패 시 주문 저장·장바구니 변경·Outbox·이벤트 호출에 도달하지 않는다. 기존 JPA 엔티티 `OrderProduct`는 `OrderCreator` 내부에서만 생성한다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, JUnit 5, Mockito, MockMvc, springdoc-openapi, Gradle Groovy DSL.

## Global Constraints

- 오류 코드는 `SELF_PURCHASE_NOT_ALLOWED`, `O015`, HTTP 403, 메시지 `본인이 판매하는 상품은 구매할 수 없습니다.`를 사용한다.
- 기존 중복 구매 오류 `ORDER_PRODUCT_ALREADY_OWNED(O018)`와 구분한다.
- `ProductOrderSnapshot.sellerId`를 사용하고 추가 원격 호출을 만들지 않는다.
- Controller에 Repository·Kafka·Redis 의존성을 추가하지 않는다.
- JPA Entity를 Controller 응답이나 Kafka payload로 노출하지 않는다.
- 외부 REST, Kafka, gRPC, Redis, DB 계약은 변경하지 않는다.
- 내부 DTO 이름 변경은 `OrderItem`에서 `OrderCreationItem`으로만 수행하고 동작은 유지한다.
- 요청과 무관한 리팩터링·포맷 변경은 포함하지 않는다.

## File Map

- Create: `src/main/java/com/prompthub/order/application/dto/OrderCreationItem.java` — 영속화 전 주문 생성 입력값 record.
- Delete: `src/main/java/com/prompthub/order/application/dto/OrderItem.java` — `OrderCreationItem`으로 이름을 대체.
- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderCommandHandler.java` — 셀프 구매 정책 호출 및 새 타입 사용.
- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderPolicyService.java` — 구매자·판매자 동일성 정책 추가.
- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderCreator.java` — `OrderCreationItem` 입력 타입 사용.
- Modify: `src/main/java/com/prompthub/order/global/exception/ErrorCode.java` — `O015` 오류 코드 추가.
- Modify: `src/main/java/com/prompthub/order/presentation/OrderController.java` — 주문 생성 설명과 403 응답 설명 갱신.
- Modify: `src/test/java/com/prompthub/order/fixture/OrderV2Fixture.java` — 새 입력 타입 사용 및 셀프 구매 스냅샷 fixture 추가.
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderPolicyServiceTest.java` — 정책 단위 테스트 추가.
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCommandHandlerTest.java` — 검증 실패와 OrderCreator 미호출 테스트 추가.
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCreatorTest.java` — 새 타입명으로 컴파일·동작 회귀 확인.
- Modify: `src/test/java/com/prompthub/order/presentation/OrderControllerCreateTest.java` — 403/O015 응답 계약 테스트 추가.
- Modify: `src/test/java/com/prompthub/order/presentation/config/OpenApiContractTest.java` — 주문 생성 셀프 구매 정책 문서 테스트 추가.

---

### Task 1: OrderCreationItem 내부 DTO 이름 변경

**Files:**

- Create: `src/main/java/com/prompthub/order/application/dto/OrderCreationItem.java`
- Delete: `src/main/java/com/prompthub/order/application/dto/OrderItem.java`
- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderCommandHandler.java`
- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderCreator.java`
- Modify: `src/test/java/com/prompthub/order/fixture/OrderV2Fixture.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCommandHandlerTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCreatorTest.java`

**Interfaces:**

- Produces: `com.prompthub.order.application.dto.OrderCreationItem` record with `(UUID productId, UUID sellerId, String productTitle, int amount)`.
- Consumes: 기존 `OrderCommandHandler.toOrderCreationItem`의 결합 결과와 `OrderCreator.create(UUID, List<OrderCreationItem>)` 입력.

- [ ] **Step 1: Add the replacement record**

  `OrderCreationItem.java`에 다음 record를 추가한다.

  ```java
  package com.prompthub.order.application.dto;

  import java.util.UUID;

  public record OrderCreationItem(
      UUID productId,
      UUID sellerId,
      String productTitle,
      int amount
  ) {
  }
  ```

- [ ] **Step 2: Update all type references**

  `OrderCommandHandler`, `OrderCreator`, `OrderV2Fixture`, `OrderCommandHandlerTest`, `OrderCreatorTest`의 import·generic·메서드 타입을 `OrderCreationItem`으로 바꾼다. Handler의 private 메서드 이름도 `toOrderCreationItem`으로 바꾼다. 기존 `OrderItem.java`는 삭제한다.

- [ ] **Step 3: Run the focused compile/test**

  Run: `./gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderCommandHandlerTest" --tests "com.prompthub.order.application.service.order.OrderCreatorTest"`

  Expected: 기존 주문 생성·주문 엔티티 테스트가 PASS하고, 이름 변경으로 인한 `OrderItem` 참조가 남지 않는다.

---

### Task 2: Self-purchase policy and O015 error contract

**Files:**

- Modify: `src/main/java/com/prompthub/order/global/exception/ErrorCode.java`
- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderPolicyService.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderPolicyServiceTest.java`
- Modify: `src/test/java/com/prompthub/order/fixture/OrderV2Fixture.java`

**Interfaces:**

- Produces: `OrderPolicyService.validateSelfPurchase(UUID buyerId, List<ProductOrderSnapshot> snapshots)` returning `void` or throwing `OrderException`.
- Error: `ErrorCode.SELF_PURCHASE_NOT_ALLOWED` has `HttpStatus.FORBIDDEN`, code `O015`, message `본인이 판매하는 상품은 구매할 수 없습니다.`.

- [ ] **Step 1: Write the failing policy tests**

  `OrderPolicyServiceTest`에 다음 테스트를 추가한다.

  ```java
  @Test
  @DisplayName("구매자와 판매자가 모두 다르면 셀프 구매 검증을 통과한다")
  void validateSelfPurchase_differentSeller_success() {
      orderPolicyService.validateSelfPurchase(BUYER_ID, shuffledSnapshots());
  }

  @Test
  @DisplayName("무료 본인 상품이면 O015 예외가 발생한다")
  void validateSelfPurchase_freeOwnProduct_throwsO015() {
      List<ProductOrderSnapshot> snapshots = List.of(
          snapshot(PRODUCT_A1, BUYER_ID, "서버-A1", 0),
          snapshot(PRODUCT_B1, SELLER_B, "서버-B1", AMOUNT_B1)
      );

      assertThatThrownBy(() -> orderPolicyService.validateSelfPurchase(BUYER_ID, snapshots))
          .isInstanceOf(OrderException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SELF_PURCHASE_NOT_ALLOWED);
  }

  @Test
  @DisplayName("유료 상품 중 하나라도 본인 상품이면 O015 예외가 발생한다")
  void validateSelfPurchase_mixedProducts_throwsO015() {
      List<ProductOrderSnapshot> snapshots = List.of(
          snapshot(PRODUCT_A1, SELLER_A, "서버-A1", AMOUNT_A1),
          snapshot(PRODUCT_B1, BUYER_ID, "서버-B1", AMOUNT_B1)
      );

      assertThatThrownBy(() -> orderPolicyService.validateSelfPurchase(BUYER_ID, snapshots))
          .isInstanceOf(OrderException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SELF_PURCHASE_NOT_ALLOWED);
  }
  ```

- [ ] **Step 2: Run the policy tests and verify failure**

  Run: `./gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderPolicyServiceTest"`

  Expected: 새 메서드와 오류 코드가 아직 없어 컴파일이 실패한다.

- [ ] **Step 3: Add the O015 error code**

  `ORDER_PRICE_CHANGED(O011)`과 `ORDER_PRODUCT_NOT_FOUND(O012)` 사이의 주문 오류 영역에 다음 항목을 추가한다.

  ```java
  SELF_PURCHASE_NOT_ALLOWED(
      HttpStatus.FORBIDDEN,
      "O015",
      "본인이 판매하는 상품은 구매할 수 없습니다."
  ),
  ```

- [ ] **Step 4: Implement the minimum policy**

  `OrderPolicyService`에 다음 메서드를 추가한다.

  ```java
  public void validateSelfPurchase(UUID buyerId, List<ProductOrderSnapshot> snapshots) {
      if (snapshots.stream().anyMatch(snapshot -> buyerId.equals(snapshot.sellerId()))) {
          throw new OrderException(ErrorCode.SELF_PURCHASE_NOT_ALLOWED);
      }
  }
  ```

  호출 전 `validateProductSnapshots`가 null seller와 null snapshot을 차단하므로 이 메서드는 이미 검증된 목록만 받는다.

- [ ] **Step 5: Run the policy tests and verify success**

  Run: `./gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderPolicyServiceTest"`

  Expected: PASS.

---

### Task 3: Invoke the policy before order side effects

**Files:**

- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderCommandHandler.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCommandHandlerTest.java`

**Interfaces:**

- Consumes: `OrderPolicyService.validateSelfPurchase(UUID, List<ProductOrderSnapshot>)` from Task 2.
- Produces: `OrderCommandHandler.createOrder` rejects before `OrderCreator.create` when any snapshot seller equals buyer.

- [ ] **Step 1: Write the failing Handler tests**

  Add tests that return a snapshot with `sellerId == BUYER_ID`, call `createOrder`, assert `ErrorCode.SELF_PURCHASE_NOT_ALLOWED`, and verify `orderCreator` has no interactions. Cover both a zero-amount snapshot and a multi-product list with one matching seller.

  The core assertion is:

  ```java
  assertThatThrownBy(() -> orderCommandHandler.createOrder(BUYER_ID, commandWithOwnProduct()))
      .isInstanceOf(OrderException.class)
      .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SELF_PURCHASE_NOT_ALLOWED);

  then(orderCreator).shouldHaveNoInteractions();
  ```

- [ ] **Step 2: Run the Handler tests and verify failure**

  Run: `./gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderCommandHandlerTest"`

  Expected: 새 검증이 호출되지 않아 the test fails because `OrderCreator` is still called.

- [ ] **Step 3: Add the policy call in the correct order**

  In `OrderCommandHandler.createOrder`, immediately after:

  ```java
  orderPolicyService.validateProductSnapshots(productIds, snapshots);
  ```

  add:

  ```java
  orderPolicyService.validateSelfPurchase(buyerId, snapshots);
  ```

  Keep this call before the `snapshotsByProductId` map, `OrderCreationItem` mapping, and `orderCreator.create` call.

- [ ] **Step 4: Run focused Handler tests**

  Run: `./gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderCommandHandlerTest"`

  Expected: existing normal, invalid snapshot, product-service failure, and new self-purchase tests all PASS.

---

### Task 4: Update the HTTP/OpenAPI error contract

**Files:**

- Modify: `src/main/java/com/prompthub/order/presentation/OrderController.java`
- Modify: `src/test/java/com/prompthub/order/presentation/OrderControllerCreateTest.java`

**Interfaces:**

- Consumes: `CreateOrderUseCase.createOrder` throwing `OrderException(ErrorCode.SELF_PURCHASE_NOT_ALLOWED)`.
- Produces: `POST /api/v2/orders` documents and returns HTTP 403 with response code `O015`.

- [ ] **Step 1: Write the failing Controller contract test**

  Stub the mocked use case to throw `new OrderException(ErrorCode.SELF_PURCHASE_NOT_ALLOWED)` and perform `POST /api/v2/orders` with a valid body and `X-User-Id`. Assert:

  ```java
  .andExpect(status().isForbidden())
  .andExpect(jsonPath("$.code").value(ErrorCode.SELF_PURCHASE_NOT_ALLOWED.getCode()))
  .andExpect(jsonPath("$.message").value(ErrorCode.SELF_PURCHASE_NOT_ALLOWED.getMessage()));
  ```

  이 테스트는 `GlobalExceptionHandler`의 공통 BusinessException 매핑을 통해 403/O015 응답이 실제 Controller 경계에서 유지되는지 검증한다. 오류 enum과 정책이 추가된 뒤에는 구현 전에도 통과할 수 있으므로, 다음 OpenAPI 테스트가 문서 변경의 실패 기준이 된다.

- [ ] **Step 2: Write the failing OpenAPI documentation test**

  `OpenApiContractTest`에 `/api/v2/orders` POST operation의 `description`과 `responses.403.description`을 읽어 다음 내용을 검증하는 테스트를 추가한다.

  ```java
  @Test
  @DisplayName("주문 생성 OpenAPI에 본인 상품 구매 제한과 O015를 문서화한다")
  void createOrderDocumentsSelfPurchaseRestriction() throws Exception {
      String document = mockMvc.perform(get("/v3/api-docs"))
          .andExpect(status().isOk())
          .andReturn()
          .getResponse()
          .getContentAsString();

      JsonNode operation = objectMapper.readTree(document)
          .path("paths").path("/api/v2/orders").path("post");

      assertThat(operation.path("description").asText())
          .contains("본인이 판매하는 상품은 주문할 수 없습니다.");
      assertThat(operation.path("responses").path("403").path("description").asText())
          .contains("O015");
  }
  ```

- [ ] **Step 3: Run the HTTP/OpenAPI tests and verify the documentation test fails**

  Run: `./gradlew :order-service:test --tests "com.prompthub.order.presentation.OrderControllerCreateTest"`

  Expected: HTTP 403/O015 response test passes.

  Run: `./gradlew :order-service:test --tests "com.prompthub.order.presentation.config.OpenApiContractTest"`

  Expected: the new documentation test fails because the current Controller annotation does not contain the self-purchase policy or `O015`.

- [ ] **Step 4: Update Swagger annotations**

  Change the create operation description to state that a buyer cannot order their own products, one matching product rejects the entire multi-product order, and no order/cart/event side effect occurs on failure. Replace the existing create operation 403 description with `O015 본인 판매 상품 구매 불가`.

- [ ] **Step 5: Run the Controller and OpenAPI tests**

  Run: `./gradlew :order-service:test --tests "com.prompthub.order.presentation.OrderControllerCreateTest" --tests "com.prompthub.order.presentation.config.OpenApiContractTest"`

  Expected: PASS.

---

### Task 5: Run regression verification

**Files:**

- No additional source files.

- [ ] **Step 1: Search for stale type references**

  Run: `rg -n "OrderItem" src/main src/test docs/superpowers/plans/2026-07-23-prevent-self-purchase.md`

  Expected: no source or test reference remains except historical design prose that names the old type as the item being renamed.

- [ ] **Step 2: Check whitespace and final diff**

  Run: `git diff --check` and `git status --short`.

  Expected: no whitespace errors and only files listed in this plan are modified. Do not stage or commit without an explicit user request.

- [ ] **Step 3: Run the complete order-service test suite**

  Run: `./gradlew :order-service:test`

  Expected: `BUILD SUCCESSFUL` with zero failed tests.

- [ ] **Step 4: Run the build if compilation or OpenAPI generation changed**

  Run: `./gradlew :order-service:build`

  Expected: `BUILD SUCCESSFUL`.
