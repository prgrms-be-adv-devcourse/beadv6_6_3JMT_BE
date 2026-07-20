# 주문 상품 단건 부분 환불 구현 계획

> **구현 기준:** `docs/superpowers/specs/2026-07-15-order-partial-refund-design.md`
>
> **범위:** `order-service`만 수정한다. Frontend와 payment-service의 현재 단건 계약은 변경하지 않는다.

**목표:** `POST /api/v2/orders/{orderId}/refund`에서 `paymentId`와 `orderProductId` 한 건을 받아 비동기 환불을 접수하고, Kafka 결과 또는 gRPC 정합성 조회로 주문·주문 상품 상태를 안전하게 반영한다.

**구조:** Presentation은 `OrderRefundUseCase`만 호출하고, `OrderRefundService`가 로컬 결제 검증·상태 전이·Outbox 저장을 조율한다. 환불 요청은 `order_refund`와 `order_refund_product`에 1:1로 저장한다. Payment Service 결과는 `paymentId + orderProductId`로 상관관계를 확인하고, Kafka 결과 유실은 기존 `GetRefund(paymentId, orderProductId)` gRPC로 보완한다.

**기술:** Java 21, Spring Boot, Spring MVC, JPA, QueryDSL, PostgreSQL/H2, Kafka Outbox, gRPC, JUnit 5, Mockito, Embedded Kafka

---

## 1. 확정 계약

### 1.1 HTTP

```http
POST /api/v2/orders/{orderId}/refund
X-User-Id: {buyerId}
X-User-Role: BUYER
Content-Type: application/json
```

```json
{
  "paymentId": "550e8400-e29b-41d4-a716-446655440000",
  "orderProductId": "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa"
}
```

- 성공: `202 Accepted`, 빈 ResponseBody
- 한 요청은 주문 상품 한 건만 처리한다.
- 같은 주문은 동시에 하나의 환불 요청만 진행한다.
- 완료 후 `PARTIAL_REFUNDED` 주문의 남은 `PAID` 상품은 다시 단건 환불할 수 있다.

### 1.2 Order Service -> Payment Service

- topic: `order-events`
- eventType: `ORDER_REFUND_REQUESTED`
- 현재 payment-service 단건 payload를 그대로 사용한다.

```json
{
  "orderId": "UUID",
  "orderProductId": "UUID",
  "buyerId": "UUID",
  "refundAmount": 10000,
  "requestedAt": "2026-07-15T12:00:00"
}
```

`paymentId`와 Order Service 내부 `refundRequestId`는 이벤트에 추가하지 않는다.

### 1.3 Payment Service -> Order Service

- 성공: `PAYMENT_REFUNDED`
- 실패: `PAYMENT_REFUND_FAILED`
- 상관관계 키: `paymentId + orderProductId`
- 현재 payload 필드와 이벤트 이름을 변경하지 않는다.

### 1.4 gRPC

기존 `PaymentQueryService.GetRefund`를 사용한다.

```protobuf
message GetRefundRequest {
  string payment_id = 1;
  string order_product_id = 2;
}
```

`refund_status`는 `REQUESTED`, `COMPLETED`, `FAILED`만 처리한다. 신규 RPC나 Protobuf 변경은 없다.

---

## 2. 이슈 분할과 의존성

| 순서 | 이슈 | 결과물 | 의존성 |
|---:|---|---|---|
| O1 | #346 환불 도메인과 단건 저장 모델 | 상태 전이, 1:1 Entity, Repository, 스키마 | 없음 |
| O2 | #347 단건 환불 요청 API | 검증, 상태 변경, 요청 Outbox, `202` API | O1 |
| O3 | #348 콘텐츠 접근과 낙관적 잠금 | 상품 단위 접근, 다운로드 경합, 동시 환불 | O1, O2 |
| O4 | #349 단건 환불 결과 이벤트 | 성공·실패 Consumer, 멱등성, 후속 Outbox | O1, O2 |
| O5 | #350 단건 환불 정합성 Worker | 기존 gRPC Adapter, due 선점, 최대 6회 | O1, O4 |
| O6 | #351 조회·통계·통합 검증 | DTO·검색·통계, 문서, 회귀·통합 테스트 | O2~O5 |

Payment Service 선행 개발 이슈는 없다. 현재 단건 Kafka와 gRPC 계약 배포 여부만 통합 전에 확인한다.

---

## 3. Task O1: 환불 도메인과 단건 저장 모델

**이슈:** #346
**권장 커밋:** `feat: order-service 부분 환불 도메인과 단건 저장 모델 추가`

### 변경 파일

- Modify: `src/main/java/com/prompthub/order/domain/enums/OrderStatus.java`
- Modify: `src/main/java/com/prompthub/order/domain/model/Order.java`
- Modify: `src/main/java/com/prompthub/order/domain/model/OrderProduct.java`
- Create: `src/main/java/com/prompthub/order/domain/enums/OrderRefundStatus.java`
- Create: `src/main/java/com/prompthub/order/domain/model/OrderRefund.java`
- Create: `src/main/java/com/prompthub/order/domain/model/OrderRefundProduct.java`
- Create: `src/main/java/com/prompthub/order/domain/repository/OrderRefundRepository.java`
- Create: `src/main/java/com/prompthub/order/infra/persistence/refund/OrderRefundPersistence.java`
- Create: `src/main/java/com/prompthub/order/infra/persistence/refund/OrderRefundPersistenceCustom.java`
- Create: `src/main/java/com/prompthub/order/infra/persistence/refund/OrderRefundPersistenceImpl.java`
- Create: `src/main/java/com/prompthub/order/infra/persistence/refund/OrderRefundAdapter.java`
- Create: 스키마 migration 파일
- Modify/Create: 대응 Domain·Persistence 테스트

### 구현 순서

1. 다음 실패 테스트를 먼저 작성한다.

   - 주문 `PAID/PARTIAL_REFUNDED -> REFUND_REQUESTED`
   - 상품 `PAID -> REFUND_REQUESTED -> REFUNDED`
   - 일부 상품 완료 시 주문 `PARTIAL_REFUNDED`
   - 모든 상품 완료 시 주문 `REFUNDED`
   - 환불 실패 시 주문·상품 `REFUND_REQUESTED` 유지
   - 요청당 상세 1건, 상품당 환불 이력 1건 제약

2. `OrderStatus`에 `REFUND_REQUESTED`, `PARTIAL_REFUNDED`, `REFUNDED`를 확정된 철자로 반영한다.

3. `OrderRefundStatus`는 다음만 둔다.

   ```text
   REQUESTED -> COMPLETED | FAILED | DLQ
   DLQ       -> COMPLETED | FAILED
   ```

   payment-service에 없는 `PROCESSING` 상태를 추가하지 않는다.

4. `order_refund`를 요청 집계로 구현한다.

   - `id`, `orderId`, `paymentId`, `buyerId`
   - `status`, `totalRefundAmount`
   - `checkCount`, `nextCheckAt`
   - `requestedAt`, `completedAt`, `failedAt`
   - `failureCode`, `failureReason`
   - `@Version version`, 생성·수정 시각

5. `order_refund_product`를 단건 상세로 구현한다.

   - `orderRefundId` unique
   - `orderProductId` unique
   - `refundAmount > 0`
   - 집계 금액과 상세 금액이 동일하도록 생성 메서드에서 검증

6. `Order`, `OrderProduct`, `OrderRefund`에 낙관적 잠금용 `@Version`을 적용한다.

7. Repository에 최소 포트를 정의한다.

   - ID와 상세 동시 조회
   - `paymentId + orderProductId` 조회
   - 주문의 진행 중 `REQUESTED` 존재 여부
   - `status=REQUESTED and nextCheckAt<=now` due 조회·선점

8. 스키마에는 FK, unique, 금액 check와 다음 인덱스를 반영한다.

   - `order_refund(status, next_check_at)`
   - `order_refund(order_id, status)`
   - `order_refund(payment_id)`
   - `order_refund_product(order_product_id)`

9. H2 PostgreSQL mode에서 매핑·제약·낙관적 잠금 테스트를 통과시킨다.

### 검증

```bash
../gradlew :order-service:test --tests "com.prompthub.order.domain.model.OrderRefundTest"
../gradlew :order-service:test --tests "com.prompthub.order.infra.persistence.refund.OrderRefundPersistenceTest"
```

---

## 4. Task O2: 단건 부분 환불 요청 API와 Outbox

**이슈:** #347
**권장 커밋:** `feat: order-service 단건 부분 환불 요청 API 추가`

### 변경 파일

- Create: `src/main/java/com/prompthub/order/application/usecase/OrderRefundUseCase.java`
- Create: `src/main/java/com/prompthub/order/application/service/refund/OrderRefundService.java`
- Create: `src/main/java/com/prompthub/order/presentation/OrderRefundController.java`
- Create: `src/main/java/com/prompthub/order/presentation/dto/request/RefundOrderRequest.java`
- Create: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderRefundRequestedPayload.java`
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderEventType.java`
- Modify: `src/main/java/com/prompthub/order/application/service/event/OrderEventMessageFactory.java`
- Modify: `src/main/java/com/prompthub/order/global/exception/ErrorCode.java`
- Modify: `src/main/java/com/prompthub/order/global/web/WebConfig.java` 또는 기존 v2 경로 설정 파일
- Create/Modify: 대응 Service·Controller·Outbox 테스트

### 구현 순서

1. Service 테스트로 다음 정책을 고정한다.

   - 본인 주문의 미다운로드 `PAID` 상품 단건 요청 성공
   - `PARTIAL_REFUNDED` 주문의 남은 `PAID` 상품 요청 성공
   - 로컬 `order_payment`의 `paymentId/orderId/buyerId/approvedAmount` 불일치 거절
   - 다른 주문의 상품, 이미 다운로드한 상품, `PAID`가 아닌 상품 거절
   - 같은 주문의 진행 중 요청, 동일 상품의 기존 요청 거절
   - 실패 시 주문·상품·환불 이력·Outbox 모두 롤백

2. Use Case 계약은 목록 없이 단건으로 정의한다.

   ```java
   void requestRefund(
       UUID buyerId,
       UUID orderId,
       UUID paymentId,
       UUID orderProductId
   );
   ```

3. 요청 DTO는 Frontend 계약과 정확히 맞춘다.

   ```java
   public record RefundOrderRequest(
       @NotNull UUID paymentId,
       @NotNull UUID orderProductId
   ) {}
   ```

4. `OrderRefundService.requestRefund`를 한 트랜잭션으로 구현한다.

   - 주문 소유권 확인
   - 로컬 결제 검증
   - 상품 소속·상태·다운로드 검증
   - 진행 중 요청과 과거 상품 요청 검증
   - 스냅샷 금액으로 환불 금액 계산
   - `OrderRefund`와 상세 한 건 저장
   - 주문과 대상 상품만 `REFUND_REQUESTED`로 전이
   - `nextCheckAt=requestedAt+10분`
   - 요청 Outbox 저장

5. 요청 이벤트 payload는 payment-service 현재 DTO와 정확히 맞춘다.

   ```java
   public record OrderRefundRequestedPayload(
       UUID orderId,
       UUID orderProductId,
       UUID buyerId,
       int refundAmount,
       LocalDateTime requestedAt
   ) {}
   ```

6. Kafka key와 envelope aggregate ID는 `orderId`를 사용한다. Entity나 API DTO를 payload로 직접 사용하지 않는다.

7. Controller는 `X-User-Id`, `orderId`, DTO를 Use Case에 전달하고 `202 Accepted`와 빈 body를 반환한다.

8. Swagger와 예외 응답을 실제 계약에 맞춘다.

   - `O016 ORDER_PAYMENT_NOT_FOUND`
   - `O017 ORDER_REFUND_NOT_ALLOWED`
   - `O018 ORDER_REFUND_IN_PROGRESS`
   - `O019 ORDER_CONCURRENT_MODIFICATION`
   - `O020 REFUND_EVENT_MISMATCH`

### 검증

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.refund.OrderRefundServiceTest"
../gradlew :order-service:test --tests "com.prompthub.order.presentation.OrderRefundControllerTest"
../gradlew :order-service:test --tests "com.prompthub.order.application.service.event.outbox.OutboxEventAppenderTest"
```

---

## 5. Task O3: 상품 단위 콘텐츠 접근과 낙관적 잠금

**이슈:** #348
**권장 커밋:** `feat: order-service 환불 상품 콘텐츠 차단과 동시성 제어 추가`

### 변경 파일

- Modify: `src/main/java/com/prompthub/order/application/service/order/ConfirmDownloadCommandHandler.java`
- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderQueryService.java`
- Modify: `src/main/java/com/prompthub/order/domain/model/Order.java`
- Modify: `src/main/java/com/prompthub/order/domain/model/OrderProduct.java`
- Modify: `src/main/java/com/prompthub/order/global/exception/GlobalExceptionHandler.java`
- Modify: 대응 Domain·Application·Controller 테스트

### 구현 순서

1. 다음 경쟁·접근 테스트를 먼저 작성한다.

   - 환불 대상 `REFUND_REQUESTED/REFUNDED` 상품은 열람·다운로드 불가
   - 같은 주문의 환불 비대상 `PAID` 상품은 계속 이용 가능
   - 다운로드가 먼저 커밋되면 환불 거절
   - 환불이 먼저 커밋되면 같은 상품 다운로드 거절
   - 서로 다른 상품이면 환불 중에도 `PAID` 상품 다운로드 허용
   - 같은 주문의 두 환불 요청은 하나만 성공

2. 콘텐츠 접근과 다운로드 정책을 주문 전체 상태가 아니라 대상 `OrderProduct.status` 기준으로 바꾼다.

3. 다운로드와 환불 양쪽이 `OrderProduct.version` 변경을 유발하도록 도메인 메서드를 사용한다.

4. 같은 주문의 환불 동시성은 `Order.version`과 진행 중 요청 검증을 함께 사용한다.

5. `ObjectOptimisticLockingFailureException` 계열을 `O019`, HTTP `409`로 변환한다.

6. 비즈니스 검증 실패와 낙관적 잠금 충돌 모두 부분 저장 없이 롤백되는지 확인한다.

### 검증

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.ConfirmDownloadCommandHandlerTest"
../gradlew :order-service:test --tests "com.prompthub.order.application.service.refund.OrderRefundConcurrencyTest"
../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderQueryServiceTest"
```

---

## 6. Task O4: 단건 환불 성공·실패 Kafka 처리

**이슈:** #349
**권장 커밋:** `feat: order-service 단건 환불 결과 이벤트 처리 추가`

### 변경 파일

- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentRefundedPayload.java`
- Create: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentRefundFailedPayload.java`
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/consumer/payment/PaymentEventType.java`
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/router/PaymentEventRouter.java`
- Modify: `src/main/java/com/prompthub/order/application/service/event/PaymentRefundedEventHandler.java`
- Modify: `src/main/java/com/prompthub/order/application/service/event/PaymentRefundedProcessor.java`
- Create: `src/main/java/com/prompthub/order/application/service/event/PaymentRefundFailedEventHandler.java`
- Create: `src/main/java/com/prompthub/order/application/service/event/PaymentRefundFailedProcessor.java`
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderRefundPayload.java`
- Modify/Create: 대응 Processor·Router·Embedded Kafka 테스트

### 구현 순서

1. 현재 payment-service payload를 fixture로 고정한다.

   성공:

   ```text
   paymentId, orderId, userId, orderProductId,
   amount, paymentStatus, refundedAt
   ```

   실패:

   ```text
   paymentId, orderId, userId, orderProductId,
   refundAmount, paymentStatus, failureReason, failedAt
   ```

2. Router가 `PAYMENT_REFUNDED`, `PAYMENT_REFUND_FAILED`를 typed Handler로 전달하도록 한다.

3. Processor는 `paymentId + orderProductId`로 저장 요청과 상세를 찾고 다음 필드를 전부 비교한다.

   - payment ID
   - order ID
   - `userId == buyerId`
   - order product ID
   - 성공 `amount` 또는 실패 `refundAmount`

4. 불일치는 `O020`을 던져 기존 재시도와 DLT로 보내며 상태를 바꾸지 않는다.

5. 성공 처리는 한 트랜잭션으로 묶는다.

   - processed event 중복 확인
   - 대상 상품 `REFUNDED`
   - 남은 `PAID` 상품이 있으면 주문 `PARTIAL_REFUNDED`
   - 모든 상품이 `REFUNDED`면 주문 `REFUNDED`
   - 환불 요청 `COMPLETED`
   - 원소 한 건의 기존 `ORDER_REFUND.products[]` Outbox
   - processed event 저장

6. 실패 처리는 환불 요청만 `FAILED`로 바꾸고 주문·상품은 `REFUND_REQUESTED`로 유지한다. `failureCode=PAYMENT_REFUND_FAILED`와 실패 사유·시각을 저장한다.

7. 두 단계 멱등성을 검증한다.

   - 동일 `eventId + consumerGroup` 중복
   - 서로 다른 eventId지만 동일한 `paymentId + orderProductId` 성공 결과 중복

8. gRPC가 먼저 완료를 반영한 뒤 늦은 Kafka 이벤트가 도착해도 상태와 Outbox는 한 번만 반영하고 processed event만 기록한다.

### 검증

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.event.PaymentRefundedProcessorTest"
../gradlew :order-service:test --tests "com.prompthub.order.application.service.event.PaymentRefundFailedProcessorTest"
../gradlew :order-service:test --tests "com.prompthub.order.infra.messaging.kafka.PaymentEventConsumerIntegrationTest"
```

---

## 7. Task O5: 기존 gRPC를 이용한 단건 환불 정합성 Worker

**이슈:** #350
**권장 커밋:** `feat: order-service 단건 환불 상태 정합성 조회 Worker 추가`

### 변경 파일

- Create: `src/main/java/com/prompthub/order/application/client/PaymentRefundClient.java`
- Create: `src/main/java/com/prompthub/order/application/dto/PaymentRefundStatusResult.java`
- Create: `src/main/java/com/prompthub/order/infra/grpc/client/payment/PaymentRefundGrpcClientConfig.java`
- Create: `src/main/java/com/prompthub/order/infra/grpc/client/payment/PaymentRefundGrpcClientAdapter.java`
- Create: `src/main/java/com/prompthub/order/infra/redis/OrderRefundReconciliationProperties.java` 또는 현재 Scheduler 패키지에 맞는 설정 클래스
- Create: `src/main/java/com/prompthub/order/infra/redis/OrderRefundReconciliationWorker.java` 또는 현재 Scheduler 패키지에 맞는 Worker
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application-test.yml`
- Create: Adapter·Worker·선점 테스트

> 구현 시 환불 일정은 Redis ZSet이나 TTL key가 아니라 `order_refund.next_check_at`을 source of truth로 사용한다. 패키지는 구현 시 현재 코드 책임에 맞춰 `infra/scheduler`로 둘 수 있다.

### 구현 순서

1. Client 포트는 단건 계약으로 정의한다.

   ```java
   PaymentRefundStatusResult getRefund(
       UUID paymentId,
       UUID orderProductId
   );
   ```

2. Adapter는 기존 `GetRefundRequest.payment_id/order_product_id`만 사용한다. `.proto`와 생성 코드는 수정하지 않는다.

3. 결과를 다음처럼 매핑한다.

   - `REQUESTED`: 다음 조회 유지
   - `COMPLETED`: Kafka 성공과 같은 application 메서드 호출
   - `FAILED`: Kafka 실패와 같은 application 메서드 호출
   - `NOT_FOUND`, `UNAVAILABLE`, `DEADLINE_EXCEEDED`: 다음 조회 유지

4. gRPC `FAILED` 응답에는 실패 시각·사유가 없으므로 조회 시각을 `failedAt`으로 사용하고 `failureCode=PAYMENT_REFUND_FAILED_CONFIRMED_BY_GRPC`를 저장한다.

5. 설정을 하드코딩하지 않는다.

   ```yaml
   prompthub:
     order:
       refund-reconciliation:
         enabled: true
         initial-delay-minutes: 10
         retry-interval-minutes: 10
         max-checks: 6
         fixed-delay-ms: 5000
         batch-size: 100
   ```

6. Worker는 짧은 DB 트랜잭션에서 due 요청을 선점하고 `checkCount`, `nextCheckAt`을 먼저 갱신한다.

7. 다중 인스턴스는 `FOR UPDATE SKIP LOCKED` 또는 동등한 원자 선점으로 같은 요청을 동시에 가져가지 않게 한다.

8. gRPC 호출은 선점 트랜잭션 밖에서 수행한다. deadline을 적용하고 로그에는 `refundRequestId`, `paymentId`, `orderProductId`, gRPC status만 남긴다.

9. 최대 6회 후에도 결과가 확정되지 않으면 `DLQ`로 바꾸고 자동 조회를 중단한다. 늦은 Kafka 성공·실패는 `DLQ`에서도 반영 가능해야 한다.

10. 테스트 프로파일에서는 Worker를 비활성화하고 Worker 단위 테스트가 직접 실행하도록 한다.

### 검증

```bash
../gradlew :order-service:test --tests "com.prompthub.order.infra.grpc.client.payment.PaymentRefundGrpcClientAdapterTest"
../gradlew :order-service:test --tests "com.prompthub.order.infra.redis.OrderRefundReconciliationWorkerTest"
../gradlew :order-service:test --tests "com.prompthub.order.infra.persistence.refund.OrderRefundClaimTest"
```

---

## 8. Task O6: 조회·통계·문서와 통합 검증

**이슈:** #351
**권장 커밋:** `feat: order-service 부분 환불 조회와 통계 반영`

### 변경 파일

- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderQueryService.java`
- Modify: 주문 상세·목록 response DTO와 projection
- Modify: `src/main/java/com/prompthub/order/application/service/admin/AdminOrderQueryService.java`
- Modify: `src/main/java/com/prompthub/order/infra/persistence/order/AdminOrderQueryRepositoryImpl.java`
- Modify: 관련 Swagger 설명
- Modify: `docs/superpowers/specs/2026-07-15-order-partial-refund-design.md`
- Modify: `docs/integration/payment-service-single-refund-contract.md`
- Verify: `docs/trade-off/2026-07-15-single-order-product-refund.md`
- Modify/Create: 조회·통계·통합 테스트

### 구현 순서

1. 조회 DTO와 검색 조건에 `REFUND_REQUESTED`, `PARTIAL_REFUNDED`, `REFUNDED`를 반영한다.

2. 콘텐츠 이용 가능 여부와 환불 가능 여부를 상품 단위로 계산한다.

   - 이용 가능: 대상 상품 `PAID`
   - 환불 가능: 주문 `PAID/PARTIAL_REFUNDED`, 상품 `PAID`, 미다운로드, 진행 중 환불 없음

3. 순거래액과 환불 통계는 `order_refund.status=COMPLETED` 금액 합계로 계산한다. 같은 주문의 순차 단건 환불이 각각 정확히 한 번 차감되는지 검증한다.

4. Controller부터 Outbox까지 단건 접수 통합 테스트를 작성한다.

5. Embedded Kafka로 성공·실패·중복·payload 불일치 DLT 경로를 검증한다.

6. in-process gRPC로 `REQUESTED/COMPLETED/FAILED/NOT_FOUND`와 Kafka 유실 복구를 검증한다.

7. API, Kafka, DB, gRPC 문서가 실제 구현과 일치하는지 점검한다.

### 검증

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderQueryServiceTest"
../gradlew :order-service:test --tests "com.prompthub.order.infra.persistence.AdminOrderQueryRepositoryImplTest"
../gradlew :order-service:test --tests "com.prompthub.order.infra.messaging.kafka.PaymentEventConsumerIntegrationTest"
```

---

## 9. 전체 완료 조건

- Frontend의 `paymentId + orderProductId` 단건 body가 변경 없이 동작한다.
- 요청당 `order_refund_product`는 정확히 한 건이다.
- 로컬 결제·소유권·금액·상품·다운로드 정책을 모두 검증한다.
- 요청 상태 변경과 `ORDER_REFUND_REQUESTED` Outbox가 원자적으로 저장된다.
- 환불 대상 상품만 이용을 차단하고 다른 `PAID` 상품은 계속 이용할 수 있다.
- payment-service의 현재 단건 성공·실패 이벤트를 처리한다.
- Kafka 결과가 누락되면 기존 `GetRefund(paymentId, orderProductId)`로 확인한다.
- 최종 실패 시 주문과 상품은 `REFUND_REQUESTED`를 유지한다.
- `PARTIAL_REFUNDED` 주문의 남은 상품을 완료 후 순차 환불할 수 있다.
- payment-service, Frontend, 다른 서비스의 코드는 수정하지 않는다.

## 10. 최종 회귀 검증

```bash
../gradlew :order-service:test
../gradlew :order-service:build
git diff --check
git status --short
```

추가 확인:

```bash
rg -n "orderProductIds|totalRefundAmount.*products|GetRefundRequestStatus|PROCESSING" docs src/main src/test
rg -n "PARTICAL_REFUNDED|TODO|TBD|API_KEY|SECRET|PASSWORD" docs src/main src/test
```

첫 번째 검색은 단건 전환 후 남아 있는 과거 다건 계약을 찾기 위한 것이다. 기존 `ORDER_REFUND`의 단일 원소 `products[]`와 통계용 `totalRefundAmount`는 예외다.
