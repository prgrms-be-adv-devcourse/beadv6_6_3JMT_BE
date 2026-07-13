# Conservative Commonization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve runtime contracts while consolidating three order-service gRPC configurations and nesting two single-owner refund event item records.

**Architecture:** Keep `common-module`'s `EventMessage` behavior unchanged. Replace three profile-identical gRPC configuration classes with one configuration in their common client package, and move each refund item record into its only owning aggregate payload without changing record components or JSON property names.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring gRPC, JUnit 5, Mockito, AssertJ, Gradle Groovy multi-project

## Global Constraints

- Modify only `common-module` and `order-service`; this implementation requires production changes only in `order-service`.
- Do not change Kafka JSON fields, gRPC channel names, API, DB, Proto, business flow, or transaction boundaries.
- Do not add strict `EventMessage` validation or alter legacy-message retry/DLT behavior.
- Do not implement the separate Factory/Appender or Processor consolidation candidate.
- Preserve all unrelated staged, modified, and unversioned user changes.
- Do not commit unless the user explicitly requests a commit.

---

### Task 1: Consolidate gRPC client configuration

**Files:**
- Create: `order-service/src/main/java/com/prompthub/order/infra/grpc/client/GrpcClientConfig.java`
- Delete: `order-service/src/main/java/com/prompthub/order/infra/grpc/client/product/ProductGrpcClientConfig.java`
- Delete: `order-service/src/main/java/com/prompthub/order/infra/grpc/client/seller/SellerGrpcClientConfig.java`
- Delete: `order-service/src/main/java/com/prompthub/order/infra/grpc/client/payment/PaymentRefundGrpcClientConfig.java`
- Modify: `order-service/src/test/java/com/prompthub/order/infra/grpc/client/GrpcClientConfigTest.java`

**Interfaces:**
- Consumes: `GrpcChannelFactory.createChannel(String)` and the generated blocking Stub factories.
- Produces: `productInternalServiceBlockingStub`, `sellerQueryServiceBlockingStub`, and `paymentRefundQueryServiceBlockingStub` Bean methods on `GrpcClientConfig`.

- [x] **Step 1: Write the failing test**

Remove imports for the three old configuration classes and instantiate `new GrpcClientConfig()` in each existing test:

```java
var stub = new GrpcClientConfig().productInternalServiceBlockingStub(channelFactory);
var stub = new GrpcClientConfig().sellerQueryServiceBlockingStub(channelFactory);
var stub = new GrpcClientConfig().paymentRefundQueryServiceBlockingStub(channelFactory);
```

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :order-service:test --tests com.prompthub.order.infra.grpc.client.GrpcClientConfigTest
```

Expected: `compileTestJava` fails because `GrpcClientConfig` does not exist.

- [x] **Step 3: Write minimal implementation**

Create the common configuration with the existing profile, channel names, method names, and return types:

```java
package com.prompthub.order.infra.grpc.client;

import com.prompthub.grpc.payment.refund.v1.PaymentRefundQueryServiceGrpc;
import com.prompthub.grpc.product.v1.ProductInternalServiceGrpc;
import com.prompthub.order.grpc.seller.SellerQueryServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
@Profile({"dev", "prod"})
public class GrpcClientConfig {

    @Bean
    public ProductInternalServiceGrpc.ProductInternalServiceBlockingStub productInternalServiceBlockingStub(
        GrpcChannelFactory channelFactory
    ) {
        return ProductInternalServiceGrpc.newBlockingStub(channelFactory.createChannel("product"));
    }

    @Bean
    public SellerQueryServiceGrpc.SellerQueryServiceBlockingStub sellerQueryServiceBlockingStub(
        GrpcChannelFactory channelFactory
    ) {
        return SellerQueryServiceGrpc.newBlockingStub(channelFactory.createChannel("seller"));
    }

    @Bean
    public PaymentRefundQueryServiceGrpc.PaymentRefundQueryServiceBlockingStub paymentRefundQueryServiceBlockingStub(
        GrpcChannelFactory channelFactory
    ) {
        return PaymentRefundQueryServiceGrpc.newBlockingStub(channelFactory.createChannel("payment-refund"));
    }
}
```

Delete the three superseded configuration files with `apply_patch`.

- [x] **Step 4: Run test to verify it passes**

Run the same focused command. Expected: three tests pass.

---

### Task 2: Nest refund event item payload records

**Files:**
- Modify: `order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/RefundRequestedPayload.java`
- Delete: `order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/RefundRequestedProductPayload.java`
- Modify: `order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderProductRefundedPayload.java`
- Delete: `order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderRefundedProductPayload.java`
- Modify: `order-service/src/test/java/com/prompthub/order/application/service/event/order/OrderEventMessageFactoryTest.java`

**Interfaces:**
- Consumes: existing parent record constructors and Jackson-compatible Java record component names.
- Produces: `RefundRequestedPayload.RefundRequestedProductPayload` and `OrderProductRefundedPayload.OrderRefundedProductPayload`.

- [x] **Step 1: Write the failing test**

Change the two existing refund-message tests to construct one product using the target nested types and assert it remains in the parent payload:

```java
var product = new RefundRequestedPayload.RefundRequestedProductPayload(ORDER_ID, PAYMENT_ID, 1000);
RefundRequestedPayload payload = new RefundRequestedPayload(
    REFUND_ID, PAYMENT_ID, ORDER_ID, BUYER_ID, 1000, null, List.of(product), EVENT_TIME
);
assertThat(message.payload().products()).containsExactly(product);
```

```java
var product = new OrderProductRefundedPayload.OrderRefundedProductPayload(
    ORDER_ID, PAYMENT_ID, BUYER_ID, 1000
);
OrderProductRefundedPayload payload = new OrderProductRefundedPayload(
    REFUND_ID, ORDER_ID, BUYER_ID, 1000, EVENT_TIME, List.of(product)
);
assertThat(message.payload().products()).containsExactly(product);
```

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :order-service:test --tests com.prompthub.order.application.service.event.order.OrderEventMessageFactoryTest
```

Expected: `compileTestJava` fails because the nested records do not exist.

- [x] **Step 3: Write minimal implementation**

Append the matching public nested record to each parent record body while preserving component names, types, and order:

```java
public record RefundRequestedProductPayload(
    UUID orderProductId,
    UUID productId,
    int refundAmount
) {
}
```

```java
public record OrderRefundedProductPayload(
    UUID orderProductId,
    UUID productId,
    UUID sellerId,
    int refundAmount
) {
}
```

The existing unqualified references inside each parent resolve to its nested type. Delete the two superseded top-level record files with `apply_patch`.

- [x] **Step 4: Run test to verify it passes**

Run the same focused command. Expected: all `OrderEventMessageFactoryTest` tests pass.

---

### Task 3: Verify behavior and scope

**Files:**
- Verify: all files changed in Tasks 1-2
- Verify unchanged behavior: `common-module/src/main/java/com/prompthub/common/event/EventMessage.java`

**Interfaces:**
- Consumes: the complete dirty worktree including user-owned changes.
- Produces: evidence that the requested refactoring builds and tests without out-of-scope edits.

- [x] **Step 1: Search for stale references**

Run:

```bash
rg "ProductGrpcClientConfig|SellerGrpcClientConfig|PaymentRefundGrpcClientConfig|new RefundRequestedProductPayload|new OrderRefundedProductPayload" order-service/src
```

Expected: no old configuration references; product constructor matches occur only within the owning parent records or use the nested qualified names in tests.

- [x] **Step 2: Run both module test suites**

```bash
./gradlew :common-module:test :order-service:test --rerun-tasks
```

Expected: `BUILD SUCCESSFUL` with zero failed tests.

- [x] **Step 3: Run the order-service build**

```bash
./gradlew :order-service:build
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 4: Validate patches and requested scope**

```bash
git diff --check
git status --short -- common-module order-service
```

Expected: no whitespace errors. Inspect only the files touched by this implementation against the pre-existing dirty state; do not alter unrelated changes.

- [x] **Step 5: Re-read the design constraints**

Confirm that `EventMessage`, Kafka fields, gRPC channel strings, API, DB, Proto, Factory/Appender, and Processor behavior were not changed by this implementation. Report verification evidence and discuss Task 2 candidate commonization separately without implementing it.
