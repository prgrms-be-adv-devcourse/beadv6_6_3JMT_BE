# 단일 주문·다중 판매자 Checkout 상세 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 서로 다른 판매자의 상품 4개를 한 번에 주문해도 Order 1건, OrderProduct 4건, `ORDER_CREATED` Outbox 1건을 만들고, 결제 승인 시 Payment 1건과 단일 Order 상태 전이를 유지한다. 판매자 소유권은 `order_product.seller_id`로 이동하며 관리자 조회, 상품 단위 환불, 정산 gRPC, Kafka 계약까지 일관되게 변경한다.

**Architecture:** Order는 구매자·주문번호·총액·주문 상태를 소유하는 결제 aggregate root다. OrderProduct는 상품·판매자·가격 snapshot과 상품별 결제·환불 상태를 소유한다. Checkout, 결제 승인·실패, Redis 만료는 Order 단위로 처리하고, 환불·정산·판매량 이벤트는 OrderProduct 단위로 처리한다. DB 상태 변경, processed event, Outbox는 같은 transaction에 둔다.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, QueryDSL, PostgreSQL/Flyway, H2 PostgreSQL mode, Kafka/Outbox, Redis transaction event listener, gRPC/Protobuf, JUnit 5, Mockito, Embedded Kafka.

**Source spec:** `docs/superpowers/specs/2026-07-17-single-order-multi-seller-checkout-design.md`

---

## 0. 구현·검증 결과

- 구현 기준선은 `133ada88`이며 격리 branch `feat/order-service-single-order-multi-seller`에서 작업했다.
- 기능·테스트·migration 변경은 모두 `order-service/**`에만 있다. Payment, Product, Settlement Service는 source를 수정하지 않고 계약 source 읽기와 test 실행만 수행했다.
- schema 변경은 V2의 seller 소유권 이동, V3의 정산 조회 index 추가, V4의 누락된 `order_payment` baseline 복구로 확정했다. PostgreSQL 18.4-alpine 일회용 환경에서 Order Service runtime Flyway API로 sample 2 Order/3 OrderProduct를 포함한 V1 → V2 → V3 → V4 순차 적용을 검증했다.
- V2 뒤 `order.seller_id` 부재, `order_product.seller_id NOT NULL`과 seller backfill을 확인했다. V3 뒤 `idx_order_product_order_id`, `idx_order_product_refunded_at` 및 V2의 `idx_order_product_seller_created_at`을 catalog에서 확인했고, V4 뒤 `order_payment` 11개 column, PK와 unique 제약 3개를 확인했다.
- Task 10 fresh 검증 결과는 다음과 같다.

| 명령 | 결과 |
| --- | --- |
| `./gradlew :order-service:test --rerun-tasks` | `BUILD SUCCESSFUL in 1m 25s`; 428 tests, skip/failure/error 0 |
| `./gradlew :order-service:build --rerun-tasks` | 최종 HEAD에서 `BUILD SUCCESSFUL in 1m 30s`; 16 tasks executed, 기존 checkstyle warning은 non-failing |
| `./gradlew :payment-service:test :product-service:test :settlement-service:test --rerun-tasks` | `BUILD SUCCESSFUL in 49s`; 각각 66/90/87 tests, skip/failure/error 0 |

`order-service:build`의 첫 sandbox 실행은 Gradle wrapper lock 권한 오류로 exit 1이었고, 동일 명령을 승인된 권한으로 새로 실행해 위 exit 0 결과를 얻었다. Embedded Kafka 종료 시 연결 경고와 기존 deprecation/checkstyle warning은 남지만 실패한 test/task는 없다.

## 1. 구현 전 고정 계약

| 경계 | 최종 계약 |
| --- | --- |
| Checkout | 요청 1건 → Order 1건 → OrderProduct N건 |
| 판매자 소유권 | `order.seller_id` 제거, `order_product.seller_id UUID NOT NULL` |
| 생성 API | `POST /api/v2/orders`의 `data.order` 단건, 상품마다 `sellerId` |
| Order → Payment | `ORDER_CREATED(orderId, buyerId, totalAmount, createdAt)` |
| Payment → Order 승인 | `PAYMENT_APPROVED(paymentId, orderId, userId, amount, approvedAt)` |
| Payment → Order 실패 | `PAYMENT_FAILED(paymentId, orderId, userId)` |
| Payment → Order 환불 | `PAYMENT_REFUNDED(paymentId, orderId, userId, orderProductId, amount, paymentStatus, refundedAt)` |
| 하위 판매 이벤트 | `ORDER_PAID`는 Order의 전체 상품, `ORDER_REFUND`는 이번에 환불된 상품 1개 |
| 관리자 목록 | Order 1행 + `sellerCount` + 판매자별 `sellers[]` 요약 |
| 정산 | 기존 `GetSettleableLines` protobuf 유지, PAID/REFUND 상품 line을 새로 구현 |
| Redis | 생성·결제 완료 이벤트 모두 Order ID 단건 |

금액은 모두 원화 정수 snapshot을 사용한다. 승인 금액은 `order.totalOrderAmount`, 환불 금액은 대상 `orderProduct.productAmount`와 정확히 일치해야 한다.

## 2. 현재 브랜치 주의사항

- 격리 branch는 `feat/order-service-single-order-multi-seller`이며 기준선은 `133ada88`이다.
- 아래 2026-07-16 사용자 소유 문서는 이 worktree에 존재하지 않는다. 다른 worktree에서 가져오거나 수정·stage하지 않는다.
  - `docs/superpowers/plans/2026-07-16-order-v2-checkout-refund-refactoring.md`
  - `docs/superpowers/plans/2026-07-16-order-v2-payment-events.md`
  - `docs/superpowers/plans/2026-07-16-order-v2-publish.md`
- 이 계획과 기획서도 구현 완료 전까지 임의로 삭제하지 않는다.
- 기준선의 `../grpc/order/order_query.proto`에는 `GetSettleableLines`가 있었지만 Order Service 서버 메서드는 없었다. protobuf 번호와 필드는 바꾸지 않고 이번 변경에서 서버만 구현했다.
- `src/test/resources/application-test.yml`은 Flyway를 끄고 Hibernate `create-drop`을 사용한다. 따라서 JPA mapping 검증과 PostgreSQL migration rehearsal을 둘 다 수행한다.

## 3. 최종 파일 구조

### 새 파일

- `src/main/resources/db/migration/V2__move_seller_id_to_order_product.sql`
- `src/main/resources/db/migration/V3__add_settlement_query_indexes.sql`
- `src/main/resources/db/migration/V4__create_order_payment_table.sql`
- `src/main/java/com/prompthub/order/domain/enums/SettlementLineType.java`
- `src/main/java/com/prompthub/order/application/dto/SettleableLineResult.java`
- `src/main/java/com/prompthub/order/application/usecase/SettlementOrderQueryUseCase.java`
- `src/main/java/com/prompthub/order/domain/repository/SettlementOrderQueryRepository.java`
- `src/main/java/com/prompthub/order/application/service/order/SettlementOrderQueryService.java`
- `src/main/java/com/prompthub/order/infra/persistence/order/SettlementOrderQueryRepositoryImpl.java`
- `src/test/java/com/prompthub/order/infra/persistence/OrderProductSellerPersistenceTest.java`
- `src/test/java/com/prompthub/order/infra/persistence/order/SettlementOrderQueryRepositoryImplTest.java`
- `src/test/java/com/prompthub/order/application/service/order/SettlementOrderQueryServiceTest.java`

### 삭제 파일

- `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentApprovedOrderPayload.java`
- `src/test/resources/contracts/order-created-v2.json`

### 핵심 수정 파일

- Domain/DB: `domain/model/Order.java`, `domain/model/OrderProduct.java`, `global/exception/ErrorCode.java`
- 생성/API: `application/service/order/OrderCreator.java`, `application/dto/CreateOrderResult.java`, `presentation/dto/response/CreateOrderResponse.java`
- 저장소 잠금: `domain/repository/OrderRepository.java`, `infra/persistence/order/OrderAdapter.java`, `OrderPersistence.java`
- Order event: `OrderCreatedPayload.java`, `OrderEventMessageFactory.java`, `OrderCreatedEvent.java`, `OrderPaidEvent.java`
- Redis: `OrderExpirationRegistrar.java`, `OrderExpirationRemover.java`
- Payment event: `PaymentApprovedPayload.java`, `PaymentFailedPayload.java`, `PaymentRefundedPayload.java`, `PaymentEventValidator.java`, 승인·실패·환불 Processor와 Handler
- 하위 event: `OrderPaidPayload.java`, `OrderRefundPayload.java`
- 관리자: `AdminOrderListProjection.java`, `AdminOrderQueryRepositoryImpl.java`, `AdminOrderService.java`, `AdminOrderListResponse.java`
- 정산: `OrderQueryGrpcServer.java`와 위 새 Query 계층
- Fixture/계약 테스트: `OrderFixture.java`, `OrderV2Fixture.java`, `PaymentEventFixture.java`, 생성·이벤트·관리자·gRPC·Kafka 관련 테스트

---

## Task 0. 작업 기준선과 브랜치 확정

별도 안내가 없는 명령은 `order-service` 디렉터리에서 실행한다. `order-service/...` 경로를 사용하는 Git 명령과 여러 모듈 Gradle 명령은 저장소 루트에서 실행한다.

### 0.1 사용자 변경과 현재 revision 확인

- [ ] 작업 위치와 변경 상태를 확인한다.

```bash
pwd
git status --short --branch
git rev-parse HEAD
```

Expected: 격리 branch와 현재 revision이 보이고, 구현 시작 시에는 이 계획·기획서만 untracked다. 2026-07-16 사용자 문서는 이 worktree에 없어야 한다.

- [ ] 구현용 branch가 아직 없으면 저장소 루트에서 생성한다.

```bash
git checkout -b feat/order-service-single-order-multi-seller
```

Expected: `Switched to a new branch 'feat/order-service-single-order-multi-seller'`. 이미 해당 branch에서 실행 중이면 이 단계는 생략하고 현재 branch 이름을 기록한다.

### 0.2 기준선 회귀 테스트

- [ ] 코드 변경 전에 Order Service 전체 test를 실행한다.

```bash
../gradlew :order-service:test
```

Expected: `BUILD SUCCESSFUL`. 실패하면 이 계획 구현을 시작하지 않고 기존 실패와 신규 실패를 분리한다.

- [ ] 기준선 diff에 whitespace 오류가 없는지 확인한다.

```bash
git diff --check
```

Expected: 출력 없음.

---

## Task 1. 판매자 snapshot 영속화와 DB 소유권 이동

**Files:**

- Create: `src/main/resources/db/migration/V2__move_seller_id_to_order_product.sql`
- Create: `src/test/java/com/prompthub/order/infra/persistence/OrderProductSellerPersistenceTest.java`
- Modify: `src/main/java/com/prompthub/order/domain/model/Order.java`
- Modify: `src/main/java/com/prompthub/order/domain/model/OrderProduct.java`
- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderCreator.java`
- Modify: `src/main/java/com/prompthub/order/application/dto/CreateOrderResult.java`
- Modify: `src/main/java/com/prompthub/order/presentation/dto/response/CreateOrderResponse.java`
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayload.java`
- Modify: `src/main/java/com/prompthub/order/application/dto/AdminOrderListProjection.java`
- Modify: `src/main/java/com/prompthub/order/infra/persistence/order/AdminOrderQueryRepositoryImpl.java`
- Modify: `src/test/java/com/prompthub/order/domain/model/OrderTest.java`
- Modify: `src/test/java/com/prompthub/order/domain/model/OrderProductTest.java`
- Modify: `src/test/java/com/prompthub/order/fixture/OrderFixture.java`
- Modify: `src/test/java/com/prompthub/order/fixture/OrderV2Fixture.java`
- Modify: `src/test/java/com/prompthub/order/fixture/PaymentEventFixture.java`
- Modify: seller 없는 `Order.create`와 seller 없는 `OrderProduct.create`를 사용하는 모든 테스트 파일

### 1.1 실패하는 Domain/JPA 테스트 작성

- [ ] `OrderProductTest`에 생성 시 seller ID가 필수이며 그대로 반환되는 테스트를 추가한다.
- [ ] `OrderTest`에서 Order 자체에 seller getter가 없는 최종 생성 signature를 사용한다.
- [ ] `OrderProductSellerPersistenceTest`에 서로 다른 판매자 상품 두 개를 한 Order에 저장하고 clear 후 각각의 seller ID가 유지되는 테스트를 작성한다.
- [ ] 다음 테스트를 실행해 새 factory/mapping이 아직 없어 compile 또는 assertion 실패하는지 확인한다.

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.domain.model.OrderProductTest" \
  --tests "com.prompthub.order.infra.persistence.OrderProductSellerPersistenceTest"
```

Expected: `OrderProduct.create(productId, sellerId, title, amount)` 또는 `seller_id` mapping 부재로 실패한다.

### 1.2 최종 Domain mapping 구현

- [ ] `Order`에서 `sellerId` field, constructor parameter, getter, `idx_order_seller_created_at` JPA index를 제거한다.
- [ ] `Order.create`를 다음 signature로 고정한다.

```java
public static Order create(
    UUID buyerId,
    String orderNumber,
    int totalOrderAmount
)
```

- [ ] `OrderProduct`에 아래 영속 field를 추가하고 `legacySellerId`와 부모 Order fallback getter를 제거한다.

```java
@Column(name = "seller_id", columnDefinition = "uuid", nullable = false)
private UUID sellerId;
```

- [ ] 운영 생성 factory를 다음 signature로 고정한다.

```java
public static OrderProduct create(
    UUID productId,
    UUID sellerId,
    String productTitle,
    int productAmount
)
```

- [ ] 기존 테스트 호환용 product type/model overload는 seller ID를 위 factory에 전달하고 `legacyProductType`, `legacyProductModel`만 설정하게 한다.
- [ ] `OrderProduct.create(productId, title, amount)` 3-인자 overload는 제거한다. seller가 없는 OrderProduct가 만들어질 우회 경로를 남기지 않는다.

### 1.3 모든 생성 call site를 새 불변식으로 전환

- [ ] `OrderCreator`가 각 `OrderItem.sellerId()`를 OrderProduct factory에 전달하게 한다.
- [ ] 판매자별 Order grouping은 Task 2까지 유지하되 `createOrder`의 seller parameter를 제거하고 seller는 자식 생성에만 사용한다.
- [ ] `OrderFixture`, `OrderV2Fixture`, `PaymentEventFixture`에서 모든 상품에 명시적 seller ID를 넣는다.
- [ ] 아래 검색 결과가 0건이 될 때까지 테스트와 fixture를 수정한다.

```bash
rg -n "Order\.create\([^\n]*SELLER|OrderProduct\.create\([^,]+,[^,]+,[^,]+\)" src/main/java src/test/java
```

Expected: seller를 Order에 넣거나 seller 없이 OrderProduct를 생성하는 call site가 없다.

### 1.4 seller 제거로 영향을 받는 현재 계약을 compile 가능한 중간 상태로 변경

- [ ] `CreateOrderResult`와 `CreateOrderResponse`는 Task 2 전까지 `orders` 배열을 유지한다. nested Order의 `sellerId`를 제거하고 nested Product에 `sellerId`를 추가한다.
- [ ] `OrderCreatedPayload`는 Task 3 전까지 `buyerId`, `totalAmount`, `orders` 구조를 유지한다. nested Order의 `sellerId`를 제거하고 nested Product에 `sellerId`를 추가한다.
- [ ] `AdminOrderListProjection`의 단일 seller ID는 Task 6 전까지 첫 OrderProduct의 seller ID를 사용한다. Task 2 전에는 판매자별 Order grouping이 유지되므로 이 중간 동작에서 seller 손실이 없다.
- [ ] `AdminOrderQueryRepositoryImpl` select의 `order.sellerId`를 `orderProduct.sellerId`로 바꾼다.
- [ ] `OrderControllerCreateTest`, `OrderCreatedPayloadSerializationTest`, `AdminOrderQueryRepositoryImplTest`를 이 중간 shape에 맞춰 compile시킨다. 최종 단건·다중 seller assertion은 각각 Task 2, 3, 6에서 추가한다.
- [ ] 부모 seller 참조가 사라졌는지 확인한다.

```bash
rg -n "Order::getSellerId|order\.getSellerId\(\)|order\.sellerId" src/main/java src/test/java
```

Expected: 출력 없음.

### 1.5 Flyway V2 migration 작성

- [ ] 다음 순서로 migration을 작성한다.

```sql
ALTER TABLE order_product ADD COLUMN seller_id uuid;

UPDATE order_product op
SET seller_id = o.seller_id
FROM "order" o
WHERE op.order_id = o.id;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM order_product WHERE seller_id IS NULL) THEN
        RAISE EXCEPTION 'order_product seller_id backfill failed';
    END IF;
END $$;

ALTER TABLE order_product ALTER COLUMN seller_id SET NOT NULL;
CREATE INDEX idx_order_product_seller_created_at
    ON order_product USING btree (seller_id, created_at DESC);
DROP INDEX idx_order_seller_created_at;
ALTER TABLE "order" DROP COLUMN seller_id;
```

- [ ] `V1__baseline.sql`은 과거 baseline이므로 수정하지 않는다. 신규 DB도 V1 → V2 → V3 → V4 순서로 최종 schema에 도달하게 한다.
- [ ] migration에 data loss를 숨기는 임의 seller 기본값이나 nullable 유지 코드를 넣지 않는다.

### 1.6 Domain/JPA 검증 및 커밋

- [ ] targeted tests를 다시 실행한다.

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.domain.model.OrderTest" \
  --tests "com.prompthub.order.domain.model.OrderProductTest" \
  --tests "com.prompthub.order.infra.persistence.OrderProductSellerPersistenceTest"
```

Expected: `BUILD SUCCESSFUL`; reload한 두 OrderProduct가 서로 다른 seller ID를 유지한다.

- [ ] 전체 test source가 새 factory signature로 compile되는지 확인한다.

```bash
../gradlew :order-service:compileTestJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] 이 task 변경만 stage하고 커밋한다. 명령은 저장소 루트에서 실행한다.

```bash
git add order-service/src/main/resources/db/migration/V2__move_seller_id_to_order_product.sql \
  order-service/src/main/java/com/prompthub/order/domain/model/Order.java \
  order-service/src/main/java/com/prompthub/order/domain/model/OrderProduct.java \
  order-service/src/main/java/com/prompthub/order/application/service/order/OrderCreator.java \
  order-service/src/main/java/com/prompthub/order/application/dto/CreateOrderResult.java \
  order-service/src/main/java/com/prompthub/order/presentation/dto/response/CreateOrderResponse.java \
  order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayload.java \
  order-service/src/main/java/com/prompthub/order/application/dto/AdminOrderListProjection.java \
  order-service/src/main/java/com/prompthub/order/infra/persistence/order/AdminOrderQueryRepositoryImpl.java \
  order-service/src/test/java/com/prompthub/order/domain/model/OrderTest.java \
  order-service/src/test/java/com/prompthub/order/domain/model/OrderProductTest.java \
  order-service/src/test/java/com/prompthub/order/fixture/OrderFixture.java \
  order-service/src/test/java/com/prompthub/order/fixture/OrderV2Fixture.java \
  order-service/src/test/java/com/prompthub/order/fixture/PaymentEventFixture.java \
  order-service/src/test/java/com/prompthub/order/application/service/event/OrderProductEventServiceTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/order/OrderCreatorTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/order/OrderCreationTransactionIntegrationTest.java \
  order-service/src/test/java/com/prompthub/order/presentation/OrderControllerCreateTest.java \
  order-service/src/test/java/com/prompthub/order/infra/persistence/AdminOrderQueryRepositoryImplTest.java \
  order-service/src/test/java/com/prompthub/order/infra/persistence/OrderLockPersistenceTest.java \
  order-service/src/test/java/com/prompthub/order/infra/persistence/OrderPersistenceImplTest.java \
  order-service/src/test/java/com/prompthub/order/infra/persistence/OrderPaymentPersistenceImplTest.java \
  order-service/src/test/java/com/prompthub/order/infra/persistence/config/QuerydslConfigTest.java \
  order-service/src/test/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayloadSerializationTest.java \
  order-service/src/test/java/com/prompthub/order/infra/persistence/OrderProductSellerPersistenceTest.java
git commit -m "refactor: order-service 판매자 소유권을 주문상품으로 이전"
```

Expected: 민감정보와 기존 사용자 문서 없이 seller ownership 변경만 커밋된다.

---

## Task 2. Checkout을 Order 단건 생성으로 전환하고 API·Redis 내부 계약 변경

**Files:**

- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderCreator.java`
- Modify: `src/main/java/com/prompthub/order/domain/repository/OrderRepository.java`
- Modify: `src/main/java/com/prompthub/order/infra/persistence/order/OrderAdapter.java`
- Modify: `src/main/java/com/prompthub/order/application/dto/CreateOrderResult.java`
- Modify: `src/main/java/com/prompthub/order/presentation/dto/response/CreateOrderResponse.java`
- Modify: `src/main/java/com/prompthub/order/application/event/order/OrderCreatedEvent.java`
- Modify: `src/main/java/com/prompthub/order/infra/redis/OrderExpirationRegistrar.java`
- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderExpirationService.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCreatorTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderCreationTransactionIntegrationTest.java`
- Modify: `src/test/java/com/prompthub/order/presentation/OrderControllerCreateTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/redis/OrderExpirationRegistrarTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/redis/OrderExpirationAfterCommitIntegrationTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderExpirationServiceTest.java`

### 2.1 단건 생성 테스트를 먼저 고정

- [ ] `OrderCreatorTest`의 판매자 A/B/C 상품 4개 fixture를 유지하면서 아래를 검증한다.
  - Order 1건
  - OrderProduct 4건
  - 상품 입력 순서 보존
  - seller ID A/B/A/C 보존
  - 총액 11,000
  - 주문 번호 생성 1회
  - repository `save` 1회
  - Outbox append 1회
  - 내부 `OrderCreatedEvent` 발행 1회
- [ ] `OrderCreationTransactionIntegrationTest`를 Order count 1, OrderProduct count 4, Outbox count 1로 바꾼다.
- [ ] 주문 번호 충돌 테스트는 첫 단건 주문 번호 충돌 시 Order/Product/Outbox가 모두 rollback되는 시나리오로 바꾼다.
- [ ] 테스트를 실행해 현재 판매자 grouping 때문에 실패함을 확인한다.

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.application.service.order.OrderCreatorTest" \
  --tests "com.prompthub.order.application.service.order.OrderCreationTransactionIntegrationTest"
```

Expected: 현재 구현이 Order 3건을 만들어 `hasSize(1)` 또는 DB count assertion에서 실패한다.

### 2.2 OrderCreator 단건 구현

- [ ] `groupingBy`, `LinkedHashMap`, `saveAll` 경로를 제거한다.
- [ ] 전체 상품 금액을 한 번 합산하고 Order를 한 번 생성한다.
- [ ] 모든 OrderItem을 seller snapshot이 포함된 OrderProduct로 변환해 한 aggregate에 추가한다.
- [ ] repository `save` 후 Outbox와 내부 event를 각각 한 번 만든다.

```java
@Transactional
public CreateOrderResult create(UUID buyerId, List<OrderItem> items) {
    int totalAmount = items.stream().mapToInt(OrderItem::amount).sum();
    Order order = Order.create(buyerId, orderNumberGenerator.generate(), totalAmount);
    items.stream()
        .map(item -> OrderProduct.create(
            item.productId(), item.sellerId(), item.productTitle(), item.amount()))
        .forEach(order::addOrderProduct);

    Order savedOrder = orderRepository.save(order);
    OrderCreatedPayload payload = OrderCreatedPayload.from(buyerId, List.of(savedOrder));
    outboxEventAppender.append(orderEventMessageFactory.createOrderCreatedMessage(payload));
    applicationEventPublisher.publishEvent(OrderCreatedEvent.from(savedOrder));
    return CreateOrderResult.from(savedOrder);
}
```

- [ ] `OrderRepository.saveAll`과 `OrderAdapter.saveAll`을 제거한다. 주문 aggregate 생성은 단건 save만 사용한다.

### 2.3 생성 결과와 HTTP 응답을 단건으로 변경

- [ ] `CreateOrderResult`를 다음 구조로 변경한다.

```java
public record CreateOrderResult(int totalAmount, Order order) {
    public record Order(
        UUID orderId,
        String orderNumber,
        UUID buyerId,
        OrderStatus orderStatus,
        int orderAmount,
        List<Product> products,
        LocalDateTime createdAt
    ) {}

    public record Product(
        UUID orderProductId,
        UUID productId,
        UUID sellerId,
        String productTitle,
        int productAmount,
        OrderProductStatus orderProductStatus
    ) {}
}
```

- [ ] `CreateOrderResponse`도 `List<Order> orders`를 `Order order`로 바꾼다.
- [ ] Order 수준 seller ID를 제거하고 Product 수준 seller ID를 노출한다.
- [ ] Swagger 문구를 “판매자별 주문 목록”에서 “생성된 주문”과 “주문 상품별 판매자”로 변경한다.
- [ ] `OrderControllerCreateTest`에서 `$.data.order`와 상품 4개, seller ID A/B/A/C를 검증하고 `$.data.orders`가 존재하지 않음을 검증한다.

### 2.4 Redis 내부 event를 단건으로 변경

- [ ] 내부 event를 다음처럼 바꾼다.

```java
public record OrderCreatedEvent(UUID orderId, LocalDateTime createdAt) {
    public static OrderCreatedEvent from(Order order) {
        return new OrderCreatedEvent(order.getId(), order.getCreatedAt());
    }
}
```

- [ ] `OrderExpirationRegistrar`는 AFTER_COMMIT에 `registerExpiration`을 한 번 호출한다.
- [ ] 결제 완료용 `OrderPaidEvent`와 `OrderExpirationRemover`는 승인 Processor와 함께 Task 4에서 단건으로 바꾼다.
- [ ] 주문 생성 시 장바구니 상품을 제거하지 않으므로 `OrderExpirationService`의 `CartRepository` 의존성과 `restoreCart`를 제거한다.
- [ ] 만료된 CREATED Order는 Order와 PENDING OrderProduct 상태만 FAILED로 바꾸며 기존 장바구니를 수정하거나 새 장바구니를 만들지 않는다.
- [ ] `OrderExpirationServiceTest`의 복원 시나리오를 “만료되어도 장바구니 무변경”과 “장바구니가 없어도 새 장바구니 미생성”으로 바꾼다.
- [ ] Redis 저장소 key, timeout property, 실패 시 warning 정책은 바꾸지 않는다.

### 2.5 테스트 및 커밋

- [ ] 생성/API/Redis 테스트를 실행한다.

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.application.service.order.OrderCreatorTest" \
  --tests "com.prompthub.order.application.service.order.OrderCreationTransactionIntegrationTest" \
  --tests "com.prompthub.order.presentation.OrderControllerCreateTest" \
  --tests "com.prompthub.order.application.service.order.OrderExpirationServiceTest" \
  --tests "com.prompthub.order.infra.redis.OrderExpirationRegistrarTest" \
  --tests "com.prompthub.order.infra.redis.OrderExpirationAfterCommitIntegrationTest"
```

Expected: `BUILD SUCCESSFUL`; 4상품·3판매자 fixture가 Order 1건과 expiration 등록 1건을 만든다.

- [ ] task 변경만 커밋한다.

```bash
git add order-service/src/main/java/com/prompthub/order/application/service/order/OrderCreator.java \
  order-service/src/main/java/com/prompthub/order/domain/repository/OrderRepository.java \
  order-service/src/main/java/com/prompthub/order/infra/persistence/order/OrderAdapter.java \
  order-service/src/main/java/com/prompthub/order/application/dto/CreateOrderResult.java \
  order-service/src/main/java/com/prompthub/order/presentation/dto/response/CreateOrderResponse.java \
  order-service/src/main/java/com/prompthub/order/application/event/order/OrderCreatedEvent.java \
  order-service/src/main/java/com/prompthub/order/infra/redis/OrderExpirationRegistrar.java \
  order-service/src/main/java/com/prompthub/order/application/service/order/OrderExpirationService.java \
  order-service/src/test/java/com/prompthub/order/application/service/order/OrderCreatorTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/order/OrderCreationTransactionIntegrationTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/order/OrderExpirationServiceTest.java \
  order-service/src/test/java/com/prompthub/order/presentation/OrderControllerCreateTest.java \
  order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationRegistrarTest.java \
  order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationAfterCommitIntegrationTest.java
git commit -m "feat: order-service 단일 주문 생성 계약 적용"
```

---

## Task 3. `ORDER_CREATED`를 Payment Service 단건 계약으로 전환

**Files:**

- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderCreator.java`
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayload.java`
- Modify: `src/main/java/com/prompthub/order/application/service/event/OrderEventMessageFactory.java`
- Modify: `src/test/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayloadSerializationTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/event/OrderEventMessageFactoryTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/messaging/kafka/OutboxRelayIntegrationTest.java`

### 3.1 실패하는 직렬화·envelope 테스트 작성

- [ ] payload JSON의 field set이 정확히 `orderId`, `buyerId`, `totalAmount`, `createdAt`인지 검증한다.
- [ ] `orders`, `products`, `sellerId`, `orderNumber`, `orderStatus`가 payload에 없음을 검증한다.
- [ ] envelope의 `aggregateType`은 `ORDER`, `aggregateId`는 payload `orderId`인지 검증한다.
- [ ] Outbox Relay가 Kafka key로 같은 order ID를 쓰는지 검증한다.
- [ ] 테스트를 실행해 현재 `ORDER_GROUP`과 다건 payload 때문에 실패하는지 확인한다.

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.infra.messaging.kafka.event.OrderCreatedPayloadSerializationTest" \
  --tests "com.prompthub.order.application.service.event.OrderEventMessageFactoryTest" \
  --tests "com.prompthub.order.infra.messaging.kafka.OutboxRelayIntegrationTest"
```

Expected: `orders` 배열 또는 `aggregateType=ORDER_GROUP` assertion에서 실패한다.

### 3.2 최종 payload와 factory 구현

- [ ] `OrderCreatedPayload`를 다음 record로 축소한다.

```java
public record OrderCreatedPayload(
    UUID orderId,
    UUID buyerId,
    int totalAmount,
    LocalDateTime createdAt
) {
    public static OrderCreatedPayload from(Order order) {
        return new OrderCreatedPayload(
            order.getId(),
            order.getBuyerId(),
            order.getTotalOrderAmount(),
            order.getCreatedAt()
        );
    }
}
```

- [ ] `createOrderCreatedMessage`는 event ID만 새 UUID로 만들고 aggregate ID는 `payload.orderId()`를 사용한다.
- [ ] `OrderCreator`는 `OrderCreatedPayload.from(savedOrder)`를 호출해 더 이상 buyer ID와 Order list를 별도로 조립하지 않는다.

```java
return new EventMessage<>(
    UUID.randomUUID(),
    OrderEventType.ORDER_CREATED.code(),
    LocalDateTime.now(),
    "ORDER",
    payload.orderId(),
    payload
);
```

- [ ] topic, eventType, Outbox retry/relay 정책은 유지한다.

### 3.3 계약 검증 및 커밋

- [ ] 3.1의 세 테스트를 다시 실행한다.

Expected: `BUILD SUCCESSFUL`; Payment Service의 `OrderCreatedMessage`와 동일한 최소 payload가 직렬화된다.

- [ ] task 변경만 커밋한다.

```bash
git add order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayload.java \
  order-service/src/main/java/com/prompthub/order/application/service/order/OrderCreator.java \
  order-service/src/main/java/com/prompthub/order/application/service/event/OrderEventMessageFactory.java \
  order-service/src/test/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayloadSerializationTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/event/OrderEventMessageFactoryTest.java \
  order-service/src/test/java/com/prompthub/order/infra/messaging/kafka/OutboxRelayIntegrationTest.java
git commit -m "refactor: order-service 단건 주문 생성 이벤트 계약 적용"
```

---

## Task 4. 단건 비관적 잠금과 결제 승인·실패 처리

**Files:**

- Delete: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentApprovedOrderPayload.java`
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentApprovedPayload.java`
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentFailedPayload.java`
- Modify: `src/main/java/com/prompthub/order/application/service/event/PaymentEventValidator.java`
- Modify: `src/main/java/com/prompthub/order/application/service/event/PaymentApprovedProcessor.java`
- Modify: `src/main/java/com/prompthub/order/application/service/event/PaymentFailedProcessor.java`
- Modify: `src/main/java/com/prompthub/order/application/service/order/OrderExpirationService.java`
- Modify: `src/main/java/com/prompthub/order/application/event/order/OrderPaidEvent.java`
- Modify: `src/main/java/com/prompthub/order/infra/redis/OrderExpirationRemover.java`
- Modify: `src/main/java/com/prompthub/order/domain/repository/OrderRepository.java`
- Modify: `src/main/java/com/prompthub/order/infra/persistence/order/OrderAdapter.java`
- Modify: `src/main/java/com/prompthub/order/infra/persistence/order/OrderPersistence.java`
- Verify/Reuse: `src/main/java/com/prompthub/order/infra/persistence/order/OrderProductPersistence.java`
- Modify: `src/test/java/com/prompthub/order/application/service/event/PaymentEventValidatorTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/event/PaymentApprovedProcessorTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/event/PaymentFailedProcessorTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/event/PaymentApprovedEventHandlerTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/event/PaymentFailedEventHandlerTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/event/PaymentEventTransactionIntegrationTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/order/OrderExpirationServiceTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/persistence/order/OrderAdapterTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/persistence/OrderLockPersistenceTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/redis/OrderExpirationRemoverTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/messaging/kafka/consumer/PaymentEventConsumerTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/messaging/kafka/router/PaymentEventRouterTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/messaging/kafka/PaymentEventConsumerIntegrationTest.java`

### 4.1 Payment Service와 같은 DTO를 테스트로 고정

- [ ] payload record를 다음 final shape로 사용하는 validator/handler test를 먼저 작성한다.

```java
public record PaymentApprovedPayload(
    UUID paymentId,
    UUID orderId,
    UUID userId,
    int amount,
    String approvedAt
) {}

public record PaymentFailedPayload(
    UUID paymentId,
    UUID orderId,
    UUID userId
) {}
```

- [ ] 승인 validator는 null ID, `amount <= 0`, blank approvedAt을 거절한다.
- [ ] `approvedAt`은 Payment Service가 발행하는 ISO-8601 offset 문자열이므로 `OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.ofHours(9)).toLocalDateTime()`으로 검증·변환해 Processor에 반환한다.
- [ ] handler/consumer test payload는 실제 발행 형식인 `"approvedAt":"2026-07-17T10:00:05+09:00"`을 사용한다. offset 없는 문자열과 잘못된 날짜는 `INVALID_INPUT_VALUE`로 거절한다.
- [ ] 실패 validator는 null payment/order/user ID를 거절한다. 실패 시각은 envelope `occurredAt`을 사용한다.
- [ ] `PaymentEventConsumerIntegrationTest`의 raw JSON을 Payment Service 실제 field name으로 변경한다.
- [ ] validator method 계약은 `LocalDateTime validate(PaymentApprovedPayload)`와 `void validate(PaymentFailedPayload)`로 고정한다.
- [ ] tests를 실행해 현재 다건 DTO로 인해 compile 또는 mapping 실패하는지 확인한다.

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.application.service.event.PaymentEventValidatorTest" \
  --tests "com.prompthub.order.application.service.event.PaymentApprovedEventHandlerTest" \
  --tests "com.prompthub.order.application.service.event.PaymentFailedEventHandlerTest"
```

Expected: `orders`, `orderIds`, `buyerId`, `totalAmount` 기반 현재 signature 때문에 실패한다.

### 4.2 단건 aggregate 잠금 port 구현

- [ ] repository contract를 다음으로 바꾼다.

```java
Optional<Order> findByIdWithOrderProductsForUpdate(UUID orderId);
```

- [ ] Adapter는 root를 먼저 `PESSIMISTIC_WRITE`로 조회한다. 없으면 `Optional.empty()`를 반환한다.
- [ ] root lock 성공 후 해당 Order의 OrderProduct를 ID 순으로 `PESSIMISTIC_WRITE` lock한다.
- [ ] 마지막으로 fetch join aggregate를 반환한다.
- [ ] 다건 `findAllByIdsWithOrderProductsForUpdate`와 다건 fetch query를 제거한다.
- [ ] `OrderAdapterTest`와 `OrderLockPersistenceTest`에서 root → children 순 lock과 초기화된 aggregate를 검증한다.
- [ ] `OrderExpirationService`도 같은 `findByIdWithOrderProductsForUpdate` port를 사용해 결제 승인과 만료가 동일 Order에 동시에 쓰지 못하게 한다.
- [ ] 만료 서비스 test에서 lock 조회 port 호출을 검증한다.

```java
@Override
public Optional<Order> findByIdWithOrderProductsForUpdate(UUID orderId) {
    if (orderPersistence.findByIdForUpdate(orderId).isEmpty()) {
        return Optional.empty();
    }
    orderProductPersistence.findAllByOrderIdForUpdate(orderId);
    return orderPersistence.findByIdWithOrderProducts(orderId);
}
```

### 4.3 승인 Processor 구현

- [ ] 처리 순서를 아래와 같이 고정한다.
  1. envelope와 payload 검증
  2. lock 전 processed event 확인
  3. 단일 Order aggregate lock
  4. lock 후 processed event 재확인
  5. `payload.userId == order.buyerId` 검증
  6. `payload.amount == order.totalOrderAmount` 검증
  7. CREATED/FAILED일 때만 COMPLETED 전이
  8. 전이한 경우에만 `ORDER_PAID` Outbox 1건, 장바구니 상품 제거, `OrderPaidEvent` 발행
  9. processed event 저장
- [ ] buyer 불일치는 `ORDER_ACCESS_DENIED`, 금액 불일치는 `ORDER_PAYMENT_AMOUNT_MISMATCH`를 사용한다.
- [ ] COMPLETED/PARTIAL_REFUNDED/ALL_REFUNDED에 다른 event ID의 늦은 승인이 오면 processed event만 저장한다.
- [ ] 늦은 승인에서는 Outbox, cart, Redis event를 건드리지 않는다. 사용자가 다시 담은 장바구니 상품을 제거하지 않게 한다.
- [ ] 상태 전이, Outbox, cart, processed event는 한 `@Transactional` 경계에 둔다.
- [ ] `PaymentEventValidator.validate(PaymentApprovedPayload)`가 반환한 승인 시각으로 `order.markCompleted`를 호출한다.
- [ ] 내부 `OrderPaidEvent`를 `record OrderPaidEvent(UUID orderId)`로 바꾸고 전이한 Order 한 건으로 생성한다.
- [ ] `OrderExpirationRemover`는 AFTER_COMMIT에 단일 order ID의 expiration과 retry count를 제거한다.

### 4.4 실패 Processor 구현

- [ ] payload user ID와 Order buyer ID가 다르면 `ORDER_ACCESS_DENIED`로 실패시키고 상태·processed event를 저장하지 않는다.
- [ ] CREATED Order만 FAILED로 전이하고 모든 PENDING OrderProduct를 FAILED로 전이한다.
- [ ] FAILED/COMPLETED/PARTIAL_REFUNDED/ALL_REFUNDED는 정상 no-op 후 processed event만 저장한다.
- [ ] 장바구니, Outbox, Redis를 변경하지 않는다.
- [ ] processed event 저장 실패 시 Order와 OrderProduct 상태도 rollback되는 통합 테스트를 유지한다.

### 4.5 승인·실패 회귀 테스트

- [ ] 승인 정상 경로: 상품 4건 모두 PAID, Outbox 1건, cart에서 대상 4개만 제거, Redis 제거 event 1건.
- [ ] FAILED → 승인 복구 경로.
- [ ] 승인 금액 불일치 및 구매자 불일치에서 상태·Outbox·cart·processed event 무변경.
- [ ] 같은 event ID 중복과 다른 event ID 늦은 승인 모두 검증.
- [ ] 실패 정상 경로, 완료 후 늦은 실패 no-op, 실패 처리 rollback을 검증.
- [ ] `PAYMENT_CANCELED`와 `PAYMENT_REFUND_FAILED`는 handler를 호출하지 않고 ACK되는 기존 미지원 정책을 consumer/router tests로 고정한다. 두 값을 지원 enum에 추가하지 않는다.
- [ ] 다음 명령을 실행한다.

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.application.service.event.PaymentApprovedProcessorTest" \
  --tests "com.prompthub.order.application.service.event.PaymentFailedProcessorTest" \
  --tests "com.prompthub.order.application.service.event.PaymentEventTransactionIntegrationTest" \
  --tests "com.prompthub.order.application.service.order.OrderExpirationServiceTest" \
  --tests "com.prompthub.order.infra.persistence.order.OrderAdapterTest" \
  --tests "com.prompthub.order.infra.persistence.OrderLockPersistenceTest" \
  --tests "com.prompthub.order.infra.redis.OrderExpirationRemoverTest" \
  --tests "com.prompthub.order.infra.messaging.kafka.consumer.PaymentEventConsumerTest" \
  --tests "com.prompthub.order.infra.messaging.kafka.router.PaymentEventRouterTest" \
  --tests "com.prompthub.order.infra.messaging.kafka.PaymentEventConsumerIntegrationTest"
```

Expected: `BUILD SUCCESSFUL`; 승인·실패가 Order ID 1개만 lock하고 처리한다.

### 4.6 커밋

- [ ] 삭제 파일을 포함해 이 task만 명시적으로 stage한다.

```bash
git add order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentApprovedPayload.java \
  order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentApprovedOrderPayload.java \
  order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentFailedPayload.java \
  order-service/src/main/java/com/prompthub/order/application/service/event/PaymentEventValidator.java \
  order-service/src/main/java/com/prompthub/order/application/service/event/PaymentApprovedProcessor.java \
  order-service/src/main/java/com/prompthub/order/application/service/event/PaymentFailedProcessor.java \
  order-service/src/main/java/com/prompthub/order/application/service/event/PaymentApprovedEventHandler.java \
  order-service/src/main/java/com/prompthub/order/application/service/event/PaymentFailedEventHandler.java \
  order-service/src/main/java/com/prompthub/order/application/service/order/OrderExpirationService.java \
  order-service/src/main/java/com/prompthub/order/application/event/order/OrderPaidEvent.java \
  order-service/src/main/java/com/prompthub/order/infra/redis/OrderExpirationRemover.java \
  order-service/src/main/java/com/prompthub/order/domain/repository/OrderRepository.java \
  order-service/src/main/java/com/prompthub/order/infra/persistence/order/OrderAdapter.java \
  order-service/src/main/java/com/prompthub/order/infra/persistence/order/OrderPersistence.java \
  order-service/src/test/java/com/prompthub/order/application/service/event/PaymentEventValidatorTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/event/PaymentApprovedProcessorTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/event/PaymentFailedProcessorTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/event/PaymentApprovedEventHandlerTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/event/PaymentFailedEventHandlerTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/event/PaymentEventTransactionIntegrationTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/order/OrderExpirationServiceTest.java \
  order-service/src/test/java/com/prompthub/order/infra/persistence/order/OrderAdapterTest.java \
  order-service/src/test/java/com/prompthub/order/infra/persistence/OrderLockPersistenceTest.java \
  order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationRemoverTest.java \
  order-service/src/test/java/com/prompthub/order/infra/messaging/kafka/consumer/PaymentEventConsumerTest.java \
  order-service/src/test/java/com/prompthub/order/infra/messaging/kafka/router/PaymentEventRouterTest.java \
  order-service/src/test/java/com/prompthub/order/infra/messaging/kafka/PaymentEventConsumerIntegrationTest.java
git commit -m "refactor: order-service 단건 결제 승인 실패 처리 적용"
```

---

## Task 5. `PAYMENT_REFUNDED`를 OrderProduct 단위로 처리

**Files:**

- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentRefundedPayload.java`
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderRefundPayload.java`
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderPaidProductPayload.java`
- Modify: `src/main/java/com/prompthub/order/application/service/event/PaymentEventValidator.java`
- Modify: `src/main/java/com/prompthub/order/application/service/event/PaymentRefundedProcessor.java`
- Modify: `src/main/java/com/prompthub/order/domain/model/Order.java`
- Modify: `src/main/java/com/prompthub/order/global/exception/ErrorCode.java`
- Modify: `src/test/java/com/prompthub/order/domain/model/OrderTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/event/PaymentRefundedProcessorTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/event/PaymentRefundedEventHandlerTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/event/PaymentEventValidatorTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/event/PaymentEventTransactionIntegrationTest.java`
- Modify: `src/test/java/com/prompthub/order/infra/messaging/kafka/PaymentEventConsumerIntegrationTest.java`

### 5.1 환불 불변식 테스트 작성

- [ ] 두 판매자 상품을 포함한 COMPLETED Order fixture를 만든다.
- [ ] 첫 상품 환불 시 그 상품만 REFUNDED, 다른 상품은 PAID, Order는 PARTIAL_REFUNDED인지 검증한다.
- [ ] 마지막 상품 환불 시 Order가 ALL_REFUNDED이고 `refundedAt`이 설정되는지 검증한다.
- [ ] 없는 orderProduct ID, 금액 불일치, PAID가 아닌 상품 환불은 예외인지 검증한다.
- [ ] 이미 REFUNDED인 상품을 다시 요청하면 `Optional.empty()`인 멱등 결과를 검증한다.
- [ ] 테스트를 실행해 현재 Order 전체 환불 때문에 실패하는지 확인한다.

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.domain.model.OrderTest" \
  --tests "com.prompthub.order.application.service.event.PaymentRefundedProcessorTest"
```

Expected: 현재 `order.refund()`가 모든 PAID 상품을 환불해 부분 환불 assertion에서 실패한다.

### 5.2 Payment Service 환불 DTO와 validator 구현

- [ ] DTO를 다음 shape로 바꾼다.

```java
public record PaymentRefundedPayload(
    UUID paymentId,
    UUID orderId,
    UUID userId,
    UUID orderProductId,
    int amount,
    String paymentStatus,
    String refundedAt
) {}
```

- [ ] validator는 모든 ID, `amount > 0`, non-blank refundedAt, paymentStatus를 필수로 검증한다.
- [ ] `refundedAt`은 `OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.ofHours(9)).toLocalDateTime()`으로 parsing해 KST local `LocalDateTime`을 반환한다. Processor와 Domain은 이 반환값만 사용한다.
- [ ] handler/consumer test payload는 실제 발행 형식인 `"refundedAt":"2026-07-17T11:00:00+09:00"`을 사용한다.
- [ ] paymentStatus는 `PARTIAL_REFUNDED`, `ALL_REFUNDED`만 허용한다.
- [ ] validator method 계약은 `LocalDateTime validate(PaymentRefundedPayload)`로 고정한다.
- [ ] 환불 금액 불일치 전용 error를 추가한다.

```java
ORDER_REFUND_AMOUNT_MISMATCH(
    HttpStatus.BAD_REQUEST,
    "O016",
    "주문 상품 금액과 환불 금액이 일치하지 않습니다."
)
```

### 5.3 Domain 상품 단위 환불 구현

- [ ] `Order.refundOrderProduct(orderProductId, refundAmount, refundedAt)`가 `Optional<OrderProduct>`를 반환하게 추가한다.
- [ ] 대상 상품을 못 찾으면 `ORDER_PRODUCT_NOT_FOUND`, 금액이 다르면 `ORDER_REFUND_AMOUNT_MISMATCH`를 던진다.
- [ ] 이미 REFUNDED면 `Optional.empty()`를 반환하고 상태를 다시 변경하지 않는다.
- [ ] PAID가 아니면 `INVALID_ORDER_STATUS_TRANSITION`을 던진다.
- [ ] PAID면 대상 하나만 환불하고 `recalculateRefundStatus`를 호출한 뒤 `Optional.of(target)`을 반환한다.
- [ ] 전체 환불 편의 메서드 `Order.refund()`와 `Order.refund(LocalDateTime)`를 제거하고 fixture도 상품 단위 메서드를 쓰게 한다.

```java
public Optional<OrderProduct> refundOrderProduct(
    UUID orderProductId,
    int refundAmount,
    LocalDateTime refundedAt
) {
    OrderProduct target = orderProducts.stream()
        .filter(product -> product.getId().equals(orderProductId))
        .findFirst()
        .orElseThrow(() -> new OrderException(ErrorCode.ORDER_PRODUCT_NOT_FOUND));
    if (target.getProductAmount() != refundAmount) {
        throw new OrderException(ErrorCode.ORDER_REFUND_AMOUNT_MISMATCH);
    }
    if (target.getOrderStatus() == OrderProductStatus.REFUNDED) {
        return Optional.empty();
    }
    target.refund(refundedAt);
    recalculateRefundStatus(refundedAt);
    return Optional.of(target);
}
```

`OrderProduct.refund`의 상태 전이 검증이 PAID가 아닌 상품을 거절한다.

### 5.4 Refund Processor와 `ORDER_REFUND` 구현

- [ ] 승인·실패와 같은 단건 비관적 잠금 port를 사용한다.
- [ ] lock 전·후 processed event를 확인한다.
- [ ] `payload.userId == order.buyerId`를 검증하고 불일치는 `ORDER_ACCESS_DENIED`로 처리한다.
- [ ] `refundOrderProduct`가 반환한 Optional에 상품이 있을 때만 그 상품으로 Outbox를 만든다.
- [ ] `OrderRefundPayload.from`은 Order와 이번에 변경된 OrderProduct를 받아 `products`에 한 건만 넣는다.
- [ ] product payload의 seller ID는 영속 `orderProduct.sellerId`를 사용한다.
- [ ] 이미 환불된 상품을 다른 event ID로 재수신하면 processed event만 저장한다.
- [ ] 상태 변경, Outbox, processed event는 같은 transaction으로 묶는다.

```java
public static OrderRefundPayload from(
    Order order,
    OrderProduct refundedProduct,
    LocalDateTime refundedAt
) {
    return new OrderRefundPayload(
        order.getId(),
        order.getBuyerId(),
        order.getTotalOrderAmount(),
        refundedAt,
        List.of(OrderPaidProductPayload.from(refundedProduct))
    );
}
```

`OrderPaidProductPayload.from(OrderProduct)` factory를 추가하고 `OrderPaidPayload`도 같은 factory를 재사용한다.

### 5.5 환불 테스트 및 커밋

- [ ] 다음 시나리오를 모두 실행한다.
  - seller A 상품 환불이 seller B 상품에 영향 없음
  - 첫 환불 PARTIAL_REFUNDED
  - 마지막 환불 ALL_REFUNDED
  - Outbox products 1개
  - 동일 event ID 중복
  - 다른 event ID semantic duplicate
  - buyer, product, amount 불일치
  - Outbox/processed event 실패 시 rollback
  - raw Kafka JSON mapping

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.domain.model.OrderTest" \
  --tests "com.prompthub.order.application.service.event.PaymentRefundedProcessorTest" \
  --tests "com.prompthub.order.application.service.event.PaymentRefundedEventHandlerTest" \
  --tests "com.prompthub.order.application.service.event.PaymentEventValidatorTest" \
  --tests "com.prompthub.order.application.service.event.PaymentEventTransactionIntegrationTest" \
  --tests "com.prompthub.order.infra.messaging.kafka.PaymentEventConsumerIntegrationTest"
```

Expected: `BUILD SUCCESSFUL`; 환불된 OrderProduct만 상태와 `ORDER_REFUND.products`에 반영된다.

- [ ] task 변경만 커밋한다.

```bash
git add order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentRefundedPayload.java \
  order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderRefundPayload.java \
  order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderPaidProductPayload.java \
  order-service/src/main/java/com/prompthub/order/application/service/event/PaymentEventValidator.java \
  order-service/src/main/java/com/prompthub/order/application/service/event/PaymentRefundedProcessor.java \
  order-service/src/main/java/com/prompthub/order/application/service/event/PaymentRefundedEventHandler.java \
  order-service/src/main/java/com/prompthub/order/domain/model/Order.java \
  order-service/src/main/java/com/prompthub/order/global/exception/ErrorCode.java \
  order-service/src/test/java/com/prompthub/order/domain/model/OrderTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/event/PaymentRefundedProcessorTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/event/PaymentRefundedEventHandlerTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/event/PaymentEventValidatorTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/event/PaymentEventTransactionIntegrationTest.java \
  order-service/src/test/java/com/prompthub/order/infra/messaging/kafka/PaymentEventConsumerIntegrationTest.java
git commit -m "fix: order-service 주문상품 단위 환불 처리 적용"
```

---

## Task 6. 관리자 주문 목록을 다중 판매자 요약으로 변경

**Files:**

- Modify: `src/main/java/com/prompthub/order/application/dto/AdminOrderListProjection.java`
- Modify: `src/main/java/com/prompthub/order/infra/persistence/order/AdminOrderQueryRepositoryImpl.java`
- Modify: `src/main/java/com/prompthub/order/application/service/admin/AdminOrderService.java`
- Modify: `src/main/java/com/prompthub/order/presentation/dto/response/AdminOrderListResponse.java`
- Modify: `src/test/java/com/prompthub/order/infra/persistence/AdminOrderQueryRepositoryImplTest.java`
- Modify: `src/test/java/com/prompthub/order/application/service/admin/AdminOrderServiceTest.java`
- Modify: `src/test/java/com/prompthub/order/presentation/AdminOrderControllerTest.java`

### 6.1 관리자 다중 판매자 테스트 작성

- [ ] 한 Order에 seller A 상품 2개, seller B 상품 1개, seller C 상품 1개를 저장한다.
- [ ] repository projection이 seller A `(productCount=2, orderAmount=A합계)`, B와 C 각각 한 요약을 반환하는지 검증한다.
- [ ] 전체 product title은 첫 상품명 외 3건, totalOrderCount는 4, totalOrderAmount는 Order 총액인지 검증한다.
- [ ] `AdminOrderServiceTest`에서 seller ID A/B/C를 한 번의 batch client call로 조회하는지 검증한다.
- [ ] Controller JSON이 `sellerCount=3`, `sellers.length=3`이고 단일 `sellerNickname` field가 없는지 검증한다.
- [ ] 기존 구현이 첫 seller만 반환해 실패하는지 확인한다.

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.infra.persistence.AdminOrderQueryRepositoryImplTest" \
  --tests "com.prompthub.order.application.service.admin.AdminOrderServiceTest" \
  --tests "com.prompthub.order.presentation.AdminOrderControllerTest"
```

Expected: seller summary 3개 assertion에서 실패한다.

### 6.2 Projection과 QueryDSL grouping 구현

- [ ] projection을 다음 구조로 변경한다.

```java
public record AdminOrderListProjection(
    UUID orderId,
    String productTitle,
    int totalOrderCount,
    int totalOrderAmount,
    OrderStatus orderStatus,
    LocalDateTime createdAt,
    List<SellerSummary> sellers
) {
    public record SellerSummary(
        UUID sellerId,
        int productCount,
        int orderAmount
    ) {}
}
```

- [ ] QueryDSL row select에서 `order.sellerId`를 제거하고 `orderProduct.sellerId`, `orderProduct.productAmount`를 읽는다.
- [ ] pagination 대상은 계속 Order ID로 먼저 구한다. join row에 직접 offset/limit를 걸지 않는다.
- [ ] Order별 row를 모은 뒤 seller ID별 `LinkedHashMap`으로 상품 수와 금액을 합산한다.
- [ ] seller 순서는 해당 Order에서 첫 상품이 등장한 순서로 고정한다.
- [ ] 월/일 거래 통계는 기존처럼 Order completed 총액 1회 가산, 환불된 OrderProduct 금액 차감 방식을 유지한다.

### 6.3 Response와 nickname 결합 구현

- [ ] response를 다음 구조로 변경한다.

```java
public record AdminOrderListResponse(
    UUID orderId,
    int sellerCount,
    List<SellerSummary> sellers,
    String productTitle,
    int totalOrderCount,
    int totalOrderAmount,
    OrderStatus orderStatus,
    LocalDateTime createdAt
) {
    public record SellerSummary(
        UUID sellerId,
        String sellerNickname,
        int productCount,
        int orderAmount
    ) {}
}
```

- [ ] 모든 projection의 nested seller ID를 flatten해 distinct batch 조회한다.
- [ ] nickname 미조회 seller는 기존 fallback `알 수 없음`을 seller별로 적용한다.
- [ ] Swagger에 sellerCount와 판매자별 상품 수·금액을 설명한다.

### 6.4 테스트 및 커밋

- [ ] 6.1의 세 테스트를 다시 실행한다.

Expected: `BUILD SUCCESSFUL`; Order 1행에서 seller 3명을 손실 없이 반환한다.

- [ ] task 변경만 커밋한다.

```bash
git add order-service/src/main/java/com/prompthub/order/application/dto/AdminOrderListProjection.java \
  order-service/src/main/java/com/prompthub/order/infra/persistence/order/AdminOrderQueryRepositoryImpl.java \
  order-service/src/main/java/com/prompthub/order/application/service/admin/AdminOrderService.java \
  order-service/src/main/java/com/prompthub/order/presentation/dto/response/AdminOrderListResponse.java \
  order-service/src/test/java/com/prompthub/order/infra/persistence/AdminOrderQueryRepositoryImplTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/admin/AdminOrderServiceTest.java \
  order-service/src/test/java/com/prompthub/order/presentation/AdminOrderControllerTest.java
git commit -m "refactor: order-service 다중 판매자 관리자 주문 조회 적용"
```

---

## Task 7. 상품별 정산 line 조회와 `GetSettleableLines` gRPC 서버 구현

**Files:**

- Create: `src/main/java/com/prompthub/order/domain/enums/SettlementLineType.java`
- Create: `src/main/java/com/prompthub/order/application/dto/SettleableLineResult.java`
- Create: `src/main/java/com/prompthub/order/application/usecase/SettlementOrderQueryUseCase.java`
- Create: `src/main/java/com/prompthub/order/domain/repository/SettlementOrderQueryRepository.java`
- Create: `src/main/java/com/prompthub/order/application/service/order/SettlementOrderQueryService.java`
- Create: `src/main/java/com/prompthub/order/infra/persistence/order/SettlementOrderQueryRepositoryImpl.java`
- Create: `src/main/resources/db/migration/V3__add_settlement_query_indexes.sql`
- Create: `src/test/java/com/prompthub/order/infra/persistence/order/SettlementOrderQueryRepositoryImplTest.java`
- Create: `src/test/java/com/prompthub/order/application/service/order/SettlementOrderQueryServiceTest.java`
- Modify: `src/main/java/com/prompthub/order/infra/grpc/server/OrderQueryGrpcServer.java`
- Modify: `src/test/java/com/prompthub/order/infra/grpc/server/OrderQueryGrpcServerTest.java`
- Do not modify: `../grpc/order/order_query.proto`

### 7.1 Repository 계약 테스트 작성

- [ ] 다음 fixture를 JPA test에 저장한다.
  - 2026-07에 결제된 seller A/B 상품 2개
  - 그중 seller A 상품은 2026-07에 환불
  - 2026-06 결제 상품 1개
  - 2026-08 환불 상품 1개
- [ ] `2026-07-01T00:00` 이상, `2026-08-01T00:00` 미만 조회 결과가 PAID 2 line + REFUND 1 line인지 검증한다.
- [ ] 각 line의 orderProductId, 영속 sellerId, 상품 snapshot 금액, 발생 시각을 검증한다.
- [ ] 같은 orderProduct가 같은 달 결제·환불되면 PAID와 REFUND 두 line이 모두 존재하는지 검증한다.
- [ ] 결과 순서를 `occurredAt`, `orderProductId`, `lineType`으로 고정한다.

### 7.2 조회 Port와 QueryDSL 구현

- [ ] enum과 result를 다음처럼 정의한다.

```java
public enum SettlementLineType {
    PAID,
    REFUND
}

public record SettleableLineResult(
    SettlementLineType lineType,
    UUID orderId,
    UUID orderProductId,
    UUID sellerId,
    long lineAmount,
    LocalDateTime occurredAt
) {}
```

- [ ] repository port는 반개구간을 받는다.

```java
List<SettleableLineResult> findSettleableLines(
    LocalDateTime startInclusive,
    LocalDateTime endExclusive
);
```

- [ ] QueryDSL 구현은 두 query를 수행한다.
  - PAID: `order.completedAt`이 기간 안인 모든 OrderProduct
  - REFUND: `orderProduct.refundedAt`이 기간 안인 환불 상품
- [ ] 두 query 모두 seller ID와 금액을 `orderProduct.sellerId`, `orderProduct.productAmount`에서 읽는다.
- [ ] PAID와 REFUND 결과를 합친 뒤 deterministic sort한다.
- [ ] 월 경계는 `YearMonth.atDay(1).atStartOfDay()`와 `plusMonths(1)`로 만든다.
- [ ] V3에서 PAID join용 `idx_order_product_order_id(order_id)`와 REFUND 범위 조회용 `idx_order_product_refunded_at(refunded_at)`을 추가한다. 이미 적용된 V1/V2 migration은 수정하지 않는다.

### 7.3 Application use case 구현

- [ ] `SettlementOrderQueryUseCase.getSettleableLines(YearMonth period)`를 정의한다.
- [ ] `SettlementOrderQueryService`를 `@Transactional(readOnly = true)`로 구현하고 repository에 반개구간을 전달한다.
- [ ] `SettlementOrderQueryServiceTest`에서 2026-07이 정확히 7월 시작/8월 시작으로 변환되는지 검증한다.

```java
@Override
public List<SettleableLineResult> getSettleableLines(YearMonth period) {
    LocalDateTime startInclusive = period.atDay(1).atStartOfDay();
    LocalDateTime endExclusive = period.plusMonths(1).atDay(1).atStartOfDay();
    return repository.findSettleableLines(startInclusive, endExclusive);
}
```

### 7.4 gRPC 서버 구현

- [ ] `OrderQueryGrpcServer`에 `SettlementOrderQueryUseCase`를 주입한다.
- [ ] `getSettleableLines`를 override한다.
- [ ] request period는 `YearMonth.parse`로 `YYYY-MM`만 허용한다.
- [ ] 각 result를 기존 protobuf `SettleableLine`에 다음처럼 매핑한다.

```java
SettleableLine.newBuilder()
    .setLineType(result.lineType().name())
    .setOrderId(result.orderId().toString())
    .setOrderProductId(result.orderProductId().toString())
    .setSellerId(result.sellerId().toString())
    .setLineAmount(result.lineAmount())
    .setOccurredAt(result.occurredAt().toString())
    .build();
```

- [ ] 빈 결과는 정상 empty repeated response로 반환한다.
- [ ] 빈 문자열, `2026-7`, `2026-13`은 `INVALID_ARGUMENT`으로 반환한다.
- [ ] repository/application 예외는 `INTERNAL`로 반환하고 내부 예외 message를 client에 노출하지 않는다.
- [ ] 기존 `GetOrder` 동작과 wire method name 테스트를 유지한다.

### 7.5 정산 테스트 및 커밋

- [ ] repository, service, in-process gRPC tests를 실행한다.

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.infra.persistence.order.SettlementOrderQueryRepositoryImplTest" \
  --tests "com.prompthub.order.application.service.order.SettlementOrderQueryServiceTest" \
  --tests "com.prompthub.order.infra.grpc.server.OrderQueryGrpcServerTest"
```

Expected: `BUILD SUCCESSFUL`; seller A/B가 같은 Order ID를 가져도 별도 상품 line으로 반환된다.

- [ ] protobuf source에 diff가 없는지 확인한다.

```bash
git diff -- ../grpc/order/order_query.proto
```

Expected: 출력 없음.

- [ ] task 변경만 커밋한다.

```bash
git add order-service/src/main/java/com/prompthub/order/domain/enums/SettlementLineType.java \
  order-service/src/main/java/com/prompthub/order/application/dto/SettleableLineResult.java \
  order-service/src/main/java/com/prompthub/order/application/usecase/SettlementOrderQueryUseCase.java \
  order-service/src/main/java/com/prompthub/order/domain/repository/SettlementOrderQueryRepository.java \
  order-service/src/main/java/com/prompthub/order/application/service/order/SettlementOrderQueryService.java \
  order-service/src/main/java/com/prompthub/order/infra/persistence/order/SettlementOrderQueryRepositoryImpl.java \
  order-service/src/main/java/com/prompthub/order/infra/grpc/server/OrderQueryGrpcServer.java \
  order-service/src/test/java/com/prompthub/order/infra/persistence/order/SettlementOrderQueryRepositoryImplTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/order/SettlementOrderQueryServiceTest.java \
  order-service/src/test/java/com/prompthub/order/infra/grpc/server/OrderQueryGrpcServerTest.java
git commit -m "feat: order-service 상품별 정산 라인 gRPC 조회 추가"
```

---

## Task 8. 하위 이벤트·구매자 조회 seller 출처 회귀 검증

**Files:**

- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderPaidPayload.java`
- Modify: `src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderRefundPayload.java`
- Verify/Modify: `src/main/java/com/prompthub/order/application/service/order/OrderQueryService.java`
- Modify: `src/test/java/com/prompthub/order/infra/messaging/kafka/OutboxRelayIntegrationTest.java`
- Modify: `src/test/java/com/prompthub/order/presentation/OrderControllerTest.java`
- Modify: payload 관련 단위 테스트와 fixture

### 8.1 이벤트 source regression test 작성

- [ ] `ORDER_PAID.products`가 한 Order의 상품 4개를 모두 포함하고 각 seller ID가 A/B/A/C인지 검증한다.
- [ ] `ORDER_REFUND.products`는 환불 대상 1개만 포함하고 해당 상품 seller ID인지 검증한다.
- [ ] Product Service가 사용하는 `products[].productId` field name이 유지되는지 JSON assertion으로 고정한다.
- [ ] Order entity seller fallback을 사용하는 코드가 없는지 검사한다.

```bash
rg -n "order\.sellerId|Order::getSellerId|getSellerId\(\)" src/main/java/com/prompthub/order
```

Expected: seller 조회는 `OrderProduct` 또는 외부 seller DTO에만 존재하고 Order seller 참조는 없다.

### 8.2 구매자 조회 회귀 검증

- [ ] 주문 상세 응답의 각 상품 seller ID가 영속 OrderProduct seller ID에서 나오는지 검증한다.
- [ ] Order 목록·결제 준비 조회는 단일 Order의 total amount와 상태를 그대로 사용한다.
- [ ] 다운로드/환불 가능 여부가 상품별 status와 downloaded flag를 계속 사용하는지 검증한다.

### 8.3 테스트 및 커밋

```bash
../gradlew :order-service:test \
  --tests "com.prompthub.order.infra.messaging.kafka.OutboxRelayIntegrationTest" \
  --tests "com.prompthub.order.presentation.OrderControllerTest"
```

Expected: `BUILD SUCCESSFUL`; 하위 consumer가 기대하는 product field shape와 구매자 상세 seller ID가 유지된다.

- [ ] 실제 코드 변경이 있을 때만 이 task를 별도 커밋한다.

```bash
git add order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderPaidPayload.java \
  order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderRefundPayload.java \
  order-service/src/main/java/com/prompthub/order/application/service/order/OrderQueryService.java \
  order-service/src/test/java/com/prompthub/order/infra/messaging/kafka/OutboxRelayIntegrationTest.java \
  order-service/src/test/java/com/prompthub/order/presentation/OrderControllerTest.java
git commit -m "test: order-service 주문상품 판매자 이벤트 조회 회귀 검증"
```

Expected: production 변경이 없고 테스트만 바뀌면 `test:` 커밋으로 유지한다.

---

## Task 9. PostgreSQL migration rehearsal과 서비스 간 계약 검증

**Files:**

- Verify: `src/main/resources/db/migration/V1__baseline.sql`
- Verify: `src/main/resources/db/migration/V2__move_seller_id_to_order_product.sql`
- Verify: `src/main/resources/db/migration/V3__add_settlement_query_indexes.sql`
- Verify: `src/main/resources/db/migration/V4__create_order_payment_table.sql`
- Verify: sibling Payment/Product/Settlement test suites; 기능 코드는 수정하지 않는다.

이 task의 Docker·Gradle 명령은 저장소 루트 `beadv6_6_3JMT_BE`에서 실행한다.

### 9.1 Disposable PostgreSQL에서 runtime Flyway V1 → V4와 Hibernate validate 검증

- [x] 같은 이름의 container가 없음을 확인한 뒤 volume 없는 PostgreSQL 18.4-alpine container를 생성했다.
- [x] 임시 JUnit harness에서 Order Service가 사용하는 Flyway API와 `classpath:db/migration` 위치로 target V1을 적용했다.
- [x] 서로 다른 기존 Order 2건과 OrderProduct 3건을 JDBC로 넣은 뒤 target V4까지 Flyway migrate/validate를 실행했다. migration SQL을 `psql`로 직접 적용하지 않았다.
- [x] seller 역채움 3건, 부모 seller column 제거, child seller NOT NULL, 최종 index 3개를 catalog에서 확인했다.
- [x] V4가 `order_payment` 11개 column, PK, named unique 제약 3개를 만들었음을 확인했다. `CREATE TABLE IF NOT EXISTS`이므로 과거 baseline에 해당 table이 존재하는 환경에서도 안전하게 no-op한다.
- [x] 같은 DB에 실제 `OrderServiceApplication` context를 띄워 Flyway `validate-on-migrate=true`와 Hibernate `ddl-auto=validate`를 모두 통과시키고 context를 정상 close했다. Config/Eureka/discovery, Kafka listener, Redis repository, scheduler, Outbox Relay, expiration worker는 비활성화했다.
- [x] 검증 뒤 임시 harness를 제거하고 전용 container가 없음을 다시 확인했다.

실행 명령은 다음과 같았고 exit 0, `BUILD SUCCESSFUL in 13s`였다.

```bash
ORDER_MIGRATION_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55433/order_test \
ORDER_MIGRATION_TEST_DB_USERNAME=postgres \
ORDER_MIGRATION_TEST_DB_PASSWORD=order_test \
./gradlew :order-service:test \
  --tests "com.prompthub.order.migration.FlywayRuntimeValidationHarnessTest" \
  --rerun-tasks
```

검증된 `flyway_schema_history`는 다음과 같다.

| version | description | checksum | success |
| --- | --- | ---: | --- |
| 1 | baseline | 1943769770 | true |
| 2 | move seller id to order product | 731148972 | true |
| 3 | add settlement query indexes | -2109978892 | true |
| 4 | create order payment table | -844890900 | true |

첫 진단 실행에서 V1 → V3 Flyway migrate/validate는 성공했지만 Hibernate가 active `OrderPayment` mapping의 `order_payment` table 누락을 검출했다. 기존 migration checksum을 바꾸지 않고 전진형 V4로 복구한 뒤 위 최종 검증이 통과했다.

### 9.2 Payment Service 계약 검증

- [ ] Order Service exact `ORDER_CREATED` JSON과 Payment Service `OrderCreatedMessage` field가 같은지 최종 diff review한다.
- [ ] Payment Service의 snapshot consumer와 단건 결제 생성/중복 방지 tests를 실행한다.

```bash
./gradlew :payment-service:test \
  --tests "com.prompthub.paymentservice.OrderEventConsumerIntegrationTest" \
  --tests "com.prompthub.paymentservice.ConfirmPaymentIntegrationTest" \
  --tests "com.prompthub.paymentservice.application.service.ConfirmPaymentServiceTest"
```

Expected: `BUILD SUCCESSFUL`; Order ID 1개를 기준으로 snapshot과 Payment가 각각 1건이고 중복 결제가 거절된다.

### 9.3 Product/Settlement Service 계약 검증

- [ ] Product Service가 `ORDER_PAID`와 `ORDER_REFUND.products[].productId`를 계속 소비하는지 확인한다.

```bash
./gradlew :product-service:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] Settlement Service client가 기존 protobuf line을 역직렬화하는지 확인한다.

```bash
./gradlew :settlement-service:test \
  --tests "com.prompthub.settlement.infrastructure.client.order.OrderSettlementQueryClientTest" \
  --tests "com.prompthub.settlement.application.service.SettlementSourceLoadServiceTest"
```

Expected: `BUILD SUCCESSFUL`; protobuf 변경 없이 seller별 source line을 매핑한다.

### 9.4 실제 Task 9 결과

- PostgreSQL 18.4-alpine에서 runtime Flyway V1 → sample → V2 → V3 → V4와 실제 Spring/Hibernate schema validate가 모두 exit 0이었다.
- seller backfill은 3개 자식 모두 원래 parent seller ID와 일치했고, 최종 column 조회는 `order_product|seller_id|NO` 한 줄이었다.
- 최종 catalog에서 `idx_order_product_order_id`, `idx_order_product_refunded_at`, `idx_order_product_seller_created_at`을 확인했다.
- 지정 Payment tests는 29초, Product 전체 tests는 19초, 지정 Settlement tests는 7초에 각각 `BUILD SUCCESSFUL`이었다.
- 세 sibling module의 source/test/설정 파일은 수정하지 않았다.

---

## Task 10. 전체 회귀, 문서, 최종 품질 검증

**Files:**

- Verify: `docs/superpowers/specs/2026-07-17-single-order-multi-seller-checkout-design.md`
- Modify: Swagger annotations in changed response DTOs
- Verify: all changed files and migration

Task 10의 명령은 저장소 루트에서 실행한다.

### 10.1 계약 잔재 검색

- [ ] 다건 Order/payment 계약과 부모 seller 참조가 남지 않았는지 확인한다.

```bash
rg -n "ORDER_GROUP|List<Order> orders|orderIds\(|PaymentApprovedOrderPayload|order\.sellerId|Order::getSellerId|idx_order_seller_created_at" \
  order-service/src/main order-service/src/test
```

Expected: migration의 `DROP INDEX idx_order_seller_created_at` 외 production 잔재가 없다. 과거 계약의 부재를 검증하는 test assertion만 허용한다.

- [ ] API test에서 과거 `data.orders`와 단일 seller response가 남지 않았는지 확인한다.

```bash
rg -n "data\.orders|\$\.data\[0\]\.sellerNickname" order-service/src/test/java
```

Expected: 과거 field 부재 assertion 외 top-level 사용 없음. nested `sellers[*].sellerNickname`은 정상 계약이다.

### 10.2 Order Service 전체 검증

- [ ] 전체 tests를 실행한다.

```bash
./gradlew :order-service:test
```

Expected: `BUILD SUCCESSFUL`; 실패·skip된 신규 핵심 test 없음.

- [ ] packaging과 generated QueryDSL/gRPC compile을 확인한다.

```bash
./gradlew :order-service:build
```

Expected: `BUILD SUCCESSFUL`.

### 10.3 저장소 전체 영향 검증

- [ ] 관련 모듈 tests를 실행한다.

```bash
./gradlew :payment-service:test :product-service:test :settlement-service:test
```

Expected: `BUILD SUCCESSFUL`. Docker/Testcontainers가 필요한 환경이면 Docker daemon이 실행 중이어야 한다.

### 10.4 Diff와 보안 검토

- [ ] whitespace 오류를 확인한다.

```bash
git diff --check
```

Expected: 출력 없음.

- [ ] 변경 범위와 통계를 확인한다.

```bash
git status --short
git diff --stat
git diff -- order-service/src/main order-service/src/test order-service/docs
```

Expected: `133ada88..HEAD` 범위에는 요청된 `order-service/**` 코드·테스트·migration·문서만 포함하며 `order-service/**` 밖 변경은 없다.

- [ ] 새 diff에 production secret이 없는지 확인한다.
  - 허용되는 새 password 문자열은 이 계획의 disposable container용 `POSTGRES_PASSWORD=order_test`뿐이다.
  - API key, token, 실제 DB credential, 인증서가 추가됐으면 commit하지 않는다.

### 10.5 최종 문서 커밋

- [ ] 실제 구현과 기획서의 API, event, migration, 정산 설명이 일치하는지 대조한다.
- [ ] 검증 명령과 결과를 PR 본문의 Test Plan에 기록한다.
- [ ] 문서 변경만 별도 커밋한다.

```bash
git add order-service/docs/superpowers/specs/2026-07-17-single-order-multi-seller-checkout-design.md \
  order-service/docs/superpowers/plans/2026-07-17-single-order-multi-seller-checkout.md
git commit -m "docs: order-service 배포 검증 절차 보완"
```

Expected: 설계·구현 계획 문서만 포함된 `docs:` 커밋.

---

## 4. 배포 Gate와 순서

이 변경은 `order.seller_id`를 제거하는 비호환 migration이므로 일반 rolling deploy로 처리하지 않는다.

1. Order/Payment Kafka exact JSON fixture와 관련 모듈 tests를 모두 통과시킨다.
2. 운영 `flyway_schema_history`의 version, description, checksum, success를 승인된 값과 대조한다. checksum mismatch나 실패 이력이 하나라도 있으면 배포를 즉시 중단하며, 원인 규명과 별도 검토 없이 `repair`하지 않는다.
3. 주문 생성 ingress를 중지한다.
4. 수신 완료된 in-flight 주문이 모두 종료되었는지 확인한다.
5. 구 Order Service writer instance를 모두 중지하고 DB writer가 남아 있지 않은지 확인한다.
6. 이 시점의 cutover snapshot과 PITR 복구 위치를 생성·기록한다.
7. Flyway V2를 적용하고 null count 0, child NOT NULL, parent column 제거를 확인한다.
8. Flyway V3를 적용하고 정산 조회 index 2개를 catalog에서 확인한다.
9. Flyway V4를 적용하고 `order_payment` mapping의 column, PK, unique 제약을 확인한다.
10. V1–V4 schema history/checksum/success와 Flyway validate, Hibernate `ddl-auto=validate`를 확인한다.
11. 새 Order Service를 배포한다.
12. 4상품·3판매자 Checkout smoke test로 Order 1, OrderProduct 4, Outbox 1을 확인한다.
13. `ORDER_CREATED`가 Payment snapshot 1건을 만들고 결제 승인 요청이 Payment 1건만 만드는지 확인한다.
14. 승인 후 Order COMPLETED, 상품 4건 PAID, `ORDER_PAID` 1건, Redis 만료 제거를 확인한다.
15. seller A 상품 1개를 환불해 PARTIAL_REFUNDED와 `ORDER_REFUND.products=1`을 확인하고, 마지막 상품까지 환불해 ALL_REFUNDED를 확인한다.
16. 관리자 응답 seller summary 3개와 대상 월 정산 PAID/REFUND line seller ID를 확인한다.
17. 이상이 없으면 주문 생성 트래픽을 재개한다.

주문 생성 트래픽 재개 전 rollback은 cutover snapshot/PITR 복원과 구 application 재배포를 반드시 동시에 수행한다. application만 이전 버전으로 내리거나 seller column 역 migration만 적용하지 않는다.

다중 seller 쓰기가 시작된 뒤에는 새 데이터를 구 단일 seller schema로 무손실 표현할 수 없으므로 reverse migration을 금지한다. forward-fix를 우선하며, snapshot rollback도 운영 DB에 직접 수행하지 않는다. snapshot이 필요하면 별도 복구 환경에서 변경분을 식별·reconcile하고 명시적인 데이터 손실 승인까지 받은 복구 계획으로만 진행한다.

## 5. 완료 조건

- [x] 판매자 수와 무관하게 Checkout 1건이 Order 1건을 만든다.
- [x] 상품 N개가 OrderProduct N개로 저장되고 각 seller ID가 NOT NULL로 유지된다.
- [x] 주문번호, `ORDER_CREATED` Outbox, Redis expiration이 각각 1번만 생성된다.
- [x] `/api/v2/orders`가 `data.order` 단건과 상품별 seller ID를 반환한다.
- [x] Payment Service는 Order ID 하나로 snapshot 1건과 Payment 1건을 유지한다.
- [x] 승인·실패는 단건 Order aggregate와 모든 자식에 원자적으로 반영된다.
- [x] 늦은 승인 duplicate가 Outbox, cart, Redis를 다시 변경하지 않는다.
- [x] 환불은 지정 OrderProduct 하나만 바꾸고 Order 상태를 재계산한다.
- [x] `ORDER_REFUND`에는 실제 환불된 상품만 포함된다.
- [x] 관리자 목록은 한 Order에서 모든 판매자와 판매자별 상품 수·금액을 반환한다.
- [x] `GetSettleableLines`는 상품별 seller ID로 PAID/REFUND line을 반환한다.
- [x] protobuf field와 Payment/Product/Settlement 소비 계약이 유지된다.
- [x] PostgreSQL runtime Flyway V1 → V2 → V3 → V4와 Hibernate schema validation이 성공한다.
- [x] Order, Payment, Product, Settlement 관련 tests와 Order build가 모두 성공한다.
- [x] `git diff --check`가 깨끗하고 민감정보·무관한 사용자 변경이 포함되지 않는다.
