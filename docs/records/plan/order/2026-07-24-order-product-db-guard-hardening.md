# Order Product DB Guard Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redis 예약, PostgreSQL `PENDING` 부분 유니크 인덱스, DB timeout reconciliation을 결합해 동일 구매자·상품의 복수 결제 대기 주문을 차단하고 결제 결과가 유실된 주문을 복구한다.

**Architecture:** 비트랜잭션 `OrderCreator`가 Redis 예약을 획득한 뒤 별도 `OrderCreationTransactionService`를 호출한다. 내부 트랜잭션은 DB 차단 상태를 다시 확인하고 `saveAndFlush`로 PostgreSQL 부분 유니크 인덱스를 검증하며, 실패하면 외부 조정 계층이 Redis 예약을 보상 해제한다. Micrometer 구현은 인프라 계층에 두고 애플리케이션에는 저카디널리티 지표 포트만 노출한다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Data JPA, PostgreSQL 18 Testcontainers, Spring Data Redis, Flyway, Micrometer, JUnit 5, Mockito

## Global Constraints

- 구현 기준 브랜치는 `fix/#533-order-product-idempotency`이며 설계 기준 커밋은 `0b782653`이다.
- 실행 시작 시 `superpowers:using-git-worktrees`를 적용해 기본 checkout을 `develop`로 전환하고 대상 브랜치를 `/private/tmp/beadv6_6_3JMT_BE-fix-533`에 연결한다.
- 기존 미추적 파일 `order-service/docs/superpowers/plans/2026-07-23-digital-product-idempotency.md`, `order-service/docs/superpowers/plans/2026-07-23-pr-535-conflict-resolution.md`, `order-service/docs/superpowers/specs/2026-07-23-digital-product-idempotency-design.md`는 수정·스테이징하지 않는다.
- 사용자 요청에 따라 TDD 순서를 사용하지 않는다. 각 기능을 구현한 뒤 해당 회귀 테스트를 추가하고 실행한다.
- 새 예약 테이블을 만들지 않고 기존 `order_product`를 사용한다.
- 현재 배포에서는 `buyer_id`를 nullable로 확장하고 non-null `PENDING` 행에만 부분 유니크 인덱스를 적용한다. `NOT NULL`은 후속 배포로 남긴다.
- 유니크 인덱스 조건은 `order_product_status = 'PENDING'`으로 제한한다. `PAID`, 무료 주문, `FAILED -> COMPLETED` 지연 승인 정책을 변경하지 않는다.
- Redis 예약은 DB 트랜잭션 밖에서 획득한다.
- Redis 장애는 fail-closed로 처리한다. 충돌은 `O018`, 획득·조회 장애는 `SYS003`을 유지한다.
- Redis 자동 재시도, Circuit Breaker, Bulkhead를 추가하지 않는다.
- `PAYMENT_CANCELED` 이벤트, Kafka topic·payload, REST 계약, gRPC·Protobuf 계약을 변경하지 않는다.
- 지표 태그에 `buyerId`, `productId`, `orderId`, `eventId`를 넣지 않는다.
- 커밋은 기능별로 분리하고 `feat`, `fix`, `refactor`, `test`, `docs`, `chore`만 사용한다. 제목에 소괄호를 사용하지 않는다.
- Task 0의 준비 명령만 기본 저장소 루트 `/Users/chan/Desktop/gongbu/programmers/beadv6_6_3JMT_BE`에서 실행한다. 이후 명령은 격리 저장소 루트 `/private/tmp/beadv6_6_3JMT_BE-fix-533`에서 실행한다.

---

## File Map

### 신규 운영 파일

- `order-service/src/main/resources/db/migration/V7__add_order_product_pending_uniqueness.sql`
  - `buyer_id` 확장·백필·타입 및 중복 검증·`PENDING` 부분 유니크 인덱스 생성
- `order-service/src/main/java/com/prompthub/order/application/service/order/OrderCreationTransactionService.java`
  - DB 재검증, 주문 flush, 장바구니 변경, Outbox·이벤트 발행의 트랜잭션 경계
- `order-service/src/main/java/com/prompthub/order/application/service/order/OrderProductReservationMetrics.java`
  - 예약 결과와 Redis 작업 시간 기록 포트
- `order-service/src/main/java/com/prompthub/order/application/service/order/OrderExpirationMetrics.java`
  - 만료 후보와 보상 결과 기록 포트
- `order-service/src/main/java/com/prompthub/order/infra/metrics/MicrometerOrderProductReservationMetrics.java`
  - 예약·Redis Micrometer 지표 구현
- `order-service/src/main/java/com/prompthub/order/infra/metrics/MicrometerOrderExpirationMetrics.java`
  - 만료 reconciliation Micrometer 지표 구현

### 수정 운영 파일

- `order-service/src/main/java/com/prompthub/order/domain/model/Order.java`
  - 주문 상품에 부모 구매자 ID 할당
- `order-service/src/main/java/com/prompthub/order/domain/model/OrderProduct.java`
  - nullable `buyer_id` 영속 필드 추가
- `order-service/src/main/java/com/prompthub/order/domain/repository/OrderRepository.java`
  - `saveAndFlush`를 추가하고 호출처 이동 후 기존 `save` 제거
- `order-service/src/main/java/com/prompthub/order/infra/persistence/order/OrderAdapter.java`
  - 명시적 flush와 정확한 인덱스 충돌의 `O018` 변환
- `order-service/src/main/java/com/prompthub/order/application/service/order/OrderCreator.java`
  - 비트랜잭션 Redis 예약 조정 계층으로 축소
- `order-service/src/main/java/com/prompthub/order/application/service/order/OrderProductReservationService.java`
  - 트랜잭션 동기화 제거, 명시적 실패 보상과 예약 결과 지표 추가
- `order-service/src/main/java/com/prompthub/order/infra/redis/RedisOrderProductIdempotencyStore.java`
  - Redis 작업별 latency·outcome 기록
- `order-service/src/main/java/com/prompthub/order/infra/redis/OrderExpirationProperties.java`
  - 중복 TTL 보정 생성자 제거
- `order-service/src/main/java/com/prompthub/order/infra/redis/OrderExpirationWorker.java`
  - DB·Redis 후보 수와 보상 결과 지표 기록
- `order-service/src/main/resources/application.yml`
  - Redis 연결 1초·read 2초 timeout 외부화

### 신규 테스트 파일

- `order-service/src/test/java/com/prompthub/order/infra/persistence/OrderProductIdempotencyMigrationTest.java`
- `order-service/src/test/java/com/prompthub/order/application/service/order/OrderCreationTransactionServiceTest.java`
- `order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationPropertiesTest.java`
- `order-service/src/test/java/com/prompthub/order/infra/redis/RedisTimeoutConfigurationTest.java`
- `order-service/src/test/java/com/prompthub/order/infra/metrics/MicrometerOrderProductReservationMetricsTest.java`
- `order-service/src/test/java/com/prompthub/order/infra/metrics/MicrometerOrderExpirationMetricsTest.java`
- `order-service/src/test/java/com/prompthub/order/application/service/order/OrderProductPendingUniquenessConcurrencyTest.java`
- `order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationReconciliationIntegrationTest.java`

### 수정 테스트 파일

- `order-service/src/test/java/com/prompthub/order/domain/model/OrderTest.java`
- `order-service/src/test/java/com/prompthub/order/infra/persistence/order/OrderAdapterTest.java`
- `order-service/src/test/java/com/prompthub/order/application/service/order/OrderCreatorTest.java`
- `order-service/src/test/java/com/prompthub/order/application/service/order/OrderProductReservationServiceTest.java`
- `order-service/src/test/java/com/prompthub/order/application/service/order/OrderCreationTransactionIntegrationTest.java`
- `order-service/src/test/java/com/prompthub/order/infra/redis/RedisOrderProductIdempotencyStoreTest.java`
- `order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationWorkerTest.java`
- `order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationRegistrarTest.java`

---

### Task 0: 대상 브랜치의 격리 worktree 복구

**Files:**

- Preserve: 기본 checkout의 기존 세 미추적 설계·계획 문서
- Create worktree: `/private/tmp/beadv6_6_3JMT_BE-fix-533`

**Interfaces:**

- Consumes: branch `fix/#533-order-product-idempotency`
- Produces: 동일 브랜치를 checkout한 독립 구현 디렉터리
- Preserves: 기본 checkout의 사용자 미추적 파일

- [ ] **Step 1: worktree 절차를 적용한다**

`superpowers:using-git-worktrees`를 읽고 안전 확인 절차를 적용한다. 이 계획의 고정 경로와 사용자 파일 보존 조건을 우선한다.

- [ ] **Step 2: 기본 checkout과 대상 경로를 사전 확인한다**

기본 저장소 루트에서 실행한다.

```bash
git status --short --branch
git ls-tree -r --name-only develop -- \
  order-service/docs/superpowers/plans/2026-07-23-digital-product-idempotency.md \
  order-service/docs/superpowers/plans/2026-07-23-pr-535-conflict-resolution.md \
  order-service/docs/superpowers/specs/2026-07-23-digital-product-idempotency-design.md
test ! -e /private/tmp/beadv6_6_3JMT_BE-fix-533
```

Expected:

- 현재 브랜치는 `fix/#533-order-product-idempotency`
- 세 문서 외에 의도하지 않은 변경 없음
- `develop`은 세 미추적 경로를 추적하지 않음
- 고정 worktree 경로가 아직 존재하지 않음

조건이 다르면 branch 전환이나 경로 삭제를 임의로 수행하지 말고 상태를 다시 검토한다.

- [ ] **Step 3: 기본 checkout에서 브랜치를 비우고 격리 worktree를 만든다**

```bash
git switch develop
git worktree add \
  /private/tmp/beadv6_6_3JMT_BE-fix-533 \
  'fix/#533-order-product-idempotency'
git -C /private/tmp/beadv6_6_3JMT_BE-fix-533 status --short --branch
git -C /private/tmp/beadv6_6_3JMT_BE-fix-533 rev-parse --show-toplevel
```

Expected:

- 기본 checkout의 세 미추적 파일은 그대로 남음
- 격리 worktree 브랜치는 `fix/#533-order-product-idempotency`
- 격리 worktree는 깨끗함
- 이후 작업 루트는 `/private/tmp/beadv6_6_3JMT_BE-fix-533`

- [ ] **Step 4: 변경 전 기준 테스트를 실행한다**

격리 worktree 루트로 이동해 실행한다.

```bash
./gradlew :order-service:test
```

Expected: `BUILD SUCCESSFUL`

기준 테스트가 실패하면 구현을 시작하지 않고 실패가 기존 상태인지 먼저 보고한다.

---

### Task 1: `order_product` 구매자 매핑과 `PENDING` 부분 유니크 인덱스

**Files:**

- Create: `order-service/src/main/resources/db/migration/V7__add_order_product_pending_uniqueness.sql`
- Modify: `order-service/src/main/java/com/prompthub/order/domain/model/Order.java:109-112`
- Modify: `order-service/src/main/java/com/prompthub/order/domain/model/OrderProduct.java:43-48,135-137`
- Modify: `order-service/src/test/java/com/prompthub/order/domain/model/OrderTest.java`
- Create: `order-service/src/test/java/com/prompthub/order/infra/persistence/OrderProductIdempotencyMigrationTest.java`

**Interfaces:**

- Produces: `UUID OrderProduct#getBuyerId()`
- Produces: `OrderProduct#assignOrder(Order)`가 `order`와 `buyerId`를 원자적으로 할당
- Produces: PostgreSQL index `uk_order_product_buyer_product_pending`
- Consumes: 기존 `"order".buyer_id`, `order_product.order_id`, `order_product.product_id`, `order_product.order_product_status`

- [ ] **Step 1: 도메인에 부모 주문 기반 구매자 ID를 매핑한다**

`OrderProduct`의 `order` 다음에 nullable 영속 필드를 추가한다.

```java
@Column(name = "buyer_id", columnDefinition = "uuid")
private UUID buyerId;
```

`assignOrder`는 null 주문을 거절하고 부모 구매자를 함께 복사한다.

```java
protected void assignOrder(Order order) {
    this.order = Objects.requireNonNull(order, "order must not be null");
    this.buyerId = order.getBuyerId();
}
```

`Order#addOrderProduct`는 null 상품이 컬렉션에 먼저 추가되지 않게 순서를 바꾼다.

```java
public void addOrderProduct(OrderProduct orderProduct) {
    Objects.requireNonNull(orderProduct, "orderProduct must not be null");
    orderProduct.assignOrder(this);
    this.orderProducts.add(orderProduct);
}
```

`Order.java`에 `java.util.Objects` import를 추가한다. `OrderProduct.create`에는 `buyerId` 인자를 추가하지 않는다.

- [ ] **Step 2: nullable 확장·백필·중복 검증·부분 인덱스 마이그레이션을 추가한다**

`V7__add_order_product_pending_uniqueness.sql` 전체 내용:

```sql
ALTER TABLE order_product
    ADD COLUMN IF NOT EXISTS buyer_id uuid;

DO $$
DECLARE
    actual_type text;
BEGIN
    SELECT data_type
    INTO actual_type
    FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND table_name = 'order_product'
      AND column_name = 'buyer_id';

    IF actual_type IS DISTINCT FROM 'uuid' THEN
        RAISE EXCEPTION 'order_product.buyer_id must be uuid, actual type: %', actual_type;
    END IF;
END
$$;

UPDATE order_product op
SET buyer_id = o.buyer_id
FROM "order" o
WHERE op.order_id = o.id
  AND op.buyer_id IS NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM order_product
        WHERE buyer_id IS NOT NULL
          AND order_product_status = 'PENDING'
        GROUP BY buyer_id, product_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'duplicate PENDING order products exist for buyer_id and product_id';
    END IF;
END
$$;

CREATE UNIQUE INDEX uk_order_product_buyer_product_pending
    ON order_product (buyer_id, product_id)
    WHERE buyer_id IS NOT NULL
      AND order_product_status = 'PENDING';
```

마이그레이션에서 중복 데이터를 삭제하거나 상태 변경하지 않는다. `NOT NULL`도 이번 버전에는 추가하지 않는다.

- [ ] **Step 3: 도메인 구매자 전파 회귀 테스트를 추가한다**

`OrderTest`에 다음 테스트를 추가한다.

```java
@Test
void addOrderProduct_assignsParentOrderAndBuyerId() {
    Order order = Order.create(BUYER_ID, "ORD-BUYER", 10_000);
    OrderProduct product = createOrderProduct1();

    order.addOrderProduct(product);

    assertThat(product.getOrder()).isSameAs(order);
    assertThat(product.getBuyerId()).isEqualTo(BUYER_ID);
}
```

기존 fixture의 `BUYER_ID`와 `createOrderProduct1`을 사용한다.

- [ ] **Step 4: 실제 PostgreSQL 마이그레이션 테스트를 추가한다**

`OrderProductIdempotencyMigrationTest`는 `PostgreSqlIntegrationTestSupport`를 상속하고 `JdbcTemplate`을 사용한다. 다음 네 테스트를 구현한다.

외부 연동 bean은 실제 네트워크를 사용하지 않도록 교체한다.

```java
@MockitoBean
private ProductClient productClient;

@MockitoBean
private OrderExpirationStore orderExpirationStore;
```

테스트 식별자와 DB 접근 필드는 다음처럼 고정한다.

```java
private static final UUID BUYER_A = uuid(1);
private static final UUID BUYER_B = uuid(2);
private static final UUID PRODUCT_A = uuid(101);
private static final UUID PRODUCT_B = uuid(102);
private static final UUID SELLER_A = uuid(201);
private static final UUID ORDER_A = uuid(301);
private static final UUID ORDER_B = uuid(302);
private static final UUID ORDER_PRODUCT_A = uuid(401);
private static final UUID ORDER_PRODUCT_B = uuid(402);
private static final UUID ORDER_PRODUCT_C = uuid(403);

@Autowired
private JdbcTemplate jdbcTemplate;

private static UUID uuid(long suffix) {
    return UUID.fromString(
        "00000000-0000-0000-0000-%012d".formatted(suffix)
    );
}
```

```java
@Test
void migrationRejectsSecondPendingProductForSameBuyerAndProduct() {
    insertOrder(ORDER_A, BUYER_A, "ORD-A");
    insertOrder(ORDER_B, BUYER_A, "ORD-B");
    insertProduct(ORDER_PRODUCT_A, ORDER_A, BUYER_A, PRODUCT_A, "PENDING");

    assertThatThrownBy(() ->
        insertProduct(ORDER_PRODUCT_B, ORDER_B, BUYER_A, PRODUCT_A, "PENDING")
    )
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(exception -> assertThat(rootCause(exception).getMessage())
            .contains("uk_order_product_buyer_product_pending"));
}

@Test
void migrationAllowsDifferentBuyerOrProduct() {
    insertOrder(ORDER_A, BUYER_A, "ORD-A");
    insertOrder(ORDER_B, BUYER_B, "ORD-B");
    insertProduct(ORDER_PRODUCT_A, ORDER_A, BUYER_A, PRODUCT_A, "PENDING");
    insertProduct(ORDER_PRODUCT_B, ORDER_B, BUYER_B, PRODUCT_A, "PENDING");
    insertProduct(ORDER_PRODUCT_C, ORDER_A, BUYER_A, PRODUCT_B, "PENDING");

    assertThat(countOrderProducts()).isEqualTo(3);
}

@Test
void failedProductReleasesPendingUniqueness() {
    insertOrder(ORDER_A, BUYER_A, "ORD-A");
    insertOrder(ORDER_B, BUYER_A, "ORD-B");
    insertProduct(ORDER_PRODUCT_A, ORDER_A, BUYER_A, PRODUCT_A, "PENDING");
    jdbcTemplate.update(
        "update order_product set order_product_status = 'FAILED' where id = ?",
        ORDER_PRODUCT_A
    );

    insertProduct(ORDER_PRODUCT_B, ORDER_B, BUYER_A, PRODUCT_A, "PENDING");

    assertThat(countOrderProducts()).isEqualTo(2);
}

@Test
void migrationBackfillsBuyerIdFromParentOrder() {
    String schema = "backfill_" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.execute("create schema " + schema);
    try {
        DriverManagerDataSource dataSource = schemaDataSource(schema);
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("6"))
            .load()
            .migrate();
        JdbcTemplate schemaJdbc = new JdbcTemplate(dataSource);
        insertLegacyOrderAndProduct(schemaJdbc);

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();

        assertThat(schemaJdbc.queryForObject(
            "select buyer_id from order_product where id = ?",
            UUID.class,
            ORDER_PRODUCT_A
        )).isEqualTo(BUYER_A);
    } finally {
        jdbcTemplate.execute("drop schema " + schema + " cascade");
    }
}
```

도우미 SQL은 현재 V6 스키마의 필수 컬럼을 모두 명시한다.

```java
private void insertOrder(UUID orderId, UUID buyerId, String orderNumber) {
    jdbcTemplate.update("""
        insert into "order"
            (id, buyer_id, order_number, total_order_amount, order_status,
             created_at, updated_at)
        values (?, ?, ?, 10000, 'CREATED', current_timestamp, current_timestamp)
        """, orderId, buyerId, orderNumber);
}

private void insertProduct(
    UUID orderProductId,
    UUID orderId,
    UUID buyerId,
    UUID productId,
    String status
) {
    jdbcTemplate.update("""
        insert into order_product
            (id, order_id, buyer_id, product_id, seller_id,
             product_title_snapshot, product_amount_snapshot,
             order_product_status, downloaded, created_at, updated_at)
        values (?, ?, ?, ?, ?, '상품', 10000, ?, false,
                current_timestamp, current_timestamp)
        """, orderProductId, orderId, buyerId, productId, SELLER_A, status);
}
```

`schemaDataSource`는 `POSTGRES.getJdbcUrl() + "?currentSchema=" + schema`와 container username/password를 사용한다. `insertLegacyOrderAndProduct`는 같은 SQL에서 `order_product.buyer_id`만 제외한다.

```java
private DriverManagerDataSource schemaDataSource(String schema) {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl() + "?currentSchema=" + schema,
        POSTGRES.getUsername(),
        POSTGRES.getPassword()
    );
}

private void insertLegacyOrderAndProduct(JdbcTemplate schemaJdbc) {
    schemaJdbc.update("""
        insert into "order"
            (id, buyer_id, order_number, total_order_amount, order_status,
             created_at, updated_at)
        values (?, ?, 'ORD-BACKFILL', 10000, 'CREATED',
                current_timestamp, current_timestamp)
        """, ORDER_A, BUYER_A);
    schemaJdbc.update("""
        insert into order_product
            (id, order_id, product_id, seller_id,
             product_title_snapshot, product_amount_snapshot,
             order_product_status, downloaded, created_at, updated_at)
        values (?, ?, ?, ?, '상품', 10000, 'PENDING', false,
                current_timestamp, current_timestamp)
        """, ORDER_PRODUCT_A, ORDER_A, PRODUCT_A, SELLER_A);
}

private long countOrderProducts() {
    Long count = jdbcTemplate.queryForObject(
        "select count(*) from order_product",
        Long.class
    );
    return count == null ? 0L : count;
}

private Throwable rootCause(Throwable exception) {
    Throwable cause = exception;
    while (cause.getCause() != null) {
        cause = cause.getCause();
    }
    return cause;
}
```

- [ ] **Step 5: Task 1 테스트를 실행한다**

Run:

```bash
./gradlew :order-service:test \
  --tests "com.prompthub.order.domain.model.OrderTest" \
  --tests "com.prompthub.order.infra.persistence.OrderProductIdempotencyMigrationTest"
```

Expected:

- PostgreSQL 18.4 Testcontainer가 시작된다.
- 네 테스트 모두 통과한다.
- `BUILD SUCCESSFUL`

- [ ] **Step 6: 스키마·도메인 변경을 커밋한다**

```bash
git add \
  order-service/src/main/resources/db/migration/V7__add_order_product_pending_uniqueness.sql \
  order-service/src/main/java/com/prompthub/order/domain/model/Order.java \
  order-service/src/main/java/com/prompthub/order/domain/model/OrderProduct.java \
  order-service/src/test/java/com/prompthub/order/domain/model/OrderTest.java \
  order-service/src/test/java/com/prompthub/order/infra/persistence/OrderProductIdempotencyMigrationTest.java
git commit \
  -m "feat: order-service 주문 상품 구매자 DB 멱등 제약 추가" \
  -m "- OrderProduct에 부모 주문에서 파생한 buyerId 영속 필드를 추가
- 기존 order_product 구매자를 백필하고 중복 PENDING 데이터를 사전 검증
- buyer_id와 product_id 기준 PENDING 부분 유니크 인덱스를 추가
- PostgreSQL 마이그레이션과 FAILED 이후 재주문 경로를 검증"
```

---

### Task 2: DB 부분 유니크 충돌의 정확한 `O018` 변환

**Files:**

- Modify: `order-service/src/main/java/com/prompthub/order/domain/repository/OrderRepository.java:15-18`
- Modify: `order-service/src/main/java/com/prompthub/order/infra/persistence/order/OrderAdapter.java:21-31`
- Modify: `order-service/src/test/java/com/prompthub/order/infra/persistence/order/OrderAdapterTest.java`
- Modify: `order-service/src/test/java/com/prompthub/order/infra/persistence/OrderProductIdempotencyMigrationTest.java`

**Interfaces:**

- Produces: `Order OrderRepository#saveAndFlush(Order order)`
- Consumes: Hibernate `ConstraintViolationException#getConstraintName()`
- Consumes: index name `uk_order_product_buyer_product_pending`
- Produces: 해당 인덱스 충돌에만 `OrderException(ErrorCode.ORDER_PRODUCT_ALREADY_OWNED)`

- [ ] **Step 1: 기존 호출을 깨지 않고 명시적 flush 저장 포트를 추가한다**

Task 3 전까지 기존 `OrderCreator`가 컴파일되도록 `save`를 유지하고 다음 메서드를 추가한다.

```java
Order save(Order order);

Order saveAndFlush(Order order);
```

`OrderAdapter`의 기존 `save` 위임은 유지하고 제약 이름 상수, 신규 flush 메서드, 원인 체인 판별을 추가한다.

```java
private static final String PENDING_PRODUCT_UNIQUENESS =
    "uk_order_product_buyer_product_pending";

@Override
public Order saveAndFlush(Order order) {
    try {
        return orderPersistence.saveAndFlush(order);
    } catch (DataIntegrityViolationException exception) {
        if (hasConstraint(exception, PENDING_PRODUCT_UNIQUENESS)) {
            throw new OrderException(ErrorCode.ORDER_PRODUCT_ALREADY_OWNED);
        }
        throw exception;
    }
}

private boolean hasConstraint(Throwable failure, String expectedName) {
    Throwable cause = failure;
    while (cause != null) {
        if (cause instanceof ConstraintViolationException violation
            && expectedName.equalsIgnoreCase(violation.getConstraintName())) {
            return true;
        }
        cause = cause.getCause();
    }
    return false;
}
```

필요한 import:

```java
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
```

- [ ] **Step 2: 어댑터가 지정된 제약만 변환하는 단위 테스트를 추가한다**

`OrderAdapterTest`에 다음 두 테스트와 helper를 추가한다.

```java
@Test
void saveAndFlush_pendingProductConstraint_mapsToO018() {
    Order order = createdOrder();
    given(orderPersistence.saveAndFlush(order))
        .willThrow(integrityFailure("uk_order_product_buyer_product_pending"));

    assertThatThrownBy(() -> orderAdapter.saveAndFlush(order))
        .isInstanceOf(OrderException.class)
        .hasFieldOrPropertyWithValue(
            "errorCode",
            ErrorCode.ORDER_PRODUCT_ALREADY_OWNED
        );
}

@Test
void saveAndFlush_unrelatedConstraint_preservesOriginalFailure() {
    Order order = createdOrder();
    DataIntegrityViolationException failure = integrityFailure("uk_order_number");
    given(orderPersistence.saveAndFlush(order)).willThrow(failure);

    assertThatThrownBy(() -> orderAdapter.saveAndFlush(order))
        .isSameAs(failure);
}

private DataIntegrityViolationException integrityFailure(String constraintName) {
    ConstraintViolationException cause = new ConstraintViolationException(
        "constraint violation",
        new SQLException("duplicate"),
        "insert into order_product",
        ConstraintViolationException.ConstraintKind.UNIQUE,
        constraintName
    );
    return new DataIntegrityViolationException("constraint violation", cause);
}
```

- [ ] **Step 3: 실제 PostgreSQL 제약 이름이 `O018`로 변환되는 통합 테스트를 추가한다**

`OrderProductIdempotencyMigrationTest`에 `OrderRepository`를 주입하고 다음 테스트를 추가한다.

```java
@Test
void persistenceAdapterMapsRealPendingConstraintToO018() {
    Order first = order("ORD-FIRST", BUYER_A, PRODUCT_A);
    Order second = order("ORD-SECOND", BUYER_A, PRODUCT_A);
    orderRepository.saveAndFlush(first);

    assertThatThrownBy(() -> orderRepository.saveAndFlush(second))
        .isInstanceOf(OrderException.class)
        .hasFieldOrPropertyWithValue(
            "errorCode",
            ErrorCode.ORDER_PRODUCT_ALREADY_OWNED
        );
}

@Test
void multiProductConflictRollsBackEveryProductInNewOrder() {
    Order existing = order("ORD-EXISTING", BUYER_A, PRODUCT_A);
    orderRepository.saveAndFlush(existing);
    Order conflicting = Order.create(BUYER_A, "ORD-CONFLICTING", 20_000);
    conflicting.addOrderProduct(
        OrderProduct.create(PRODUCT_A, SELLER_A, "상품 A", 10_000)
    );
    conflicting.addOrderProduct(
        OrderProduct.create(PRODUCT_B, SELLER_A, "상품 B", 10_000)
    );

    assertThatThrownBy(() -> orderRepository.saveAndFlush(conflicting))
        .isInstanceOf(OrderException.class)
        .hasFieldOrPropertyWithValue(
            "errorCode",
            ErrorCode.ORDER_PRODUCT_ALREADY_OWNED
        );
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from order_product where order_id = ?",
        Long.class,
        conflicting.getId()
    )).isZero();
}

private Order order(String number, UUID buyerId, UUID productId) {
    Order order = Order.create(buyerId, number, 10_000);
    order.addOrderProduct(
        OrderProduct.create(productId, SELLER_A, "상품", 10_000)
    );
    return order;
}
```

- [ ] **Step 4: Task 2 테스트를 실행한다**

Run:

```bash
./gradlew :order-service:test \
  --tests "com.prompthub.order.infra.persistence.order.OrderAdapterTest" \
  --tests "com.prompthub.order.infra.persistence.OrderProductIdempotencyMigrationTest"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: DB 오류 변환 변경을 커밋한다**

```bash
git add \
  order-service/src/main/java/com/prompthub/order/domain/repository/OrderRepository.java \
  order-service/src/main/java/com/prompthub/order/infra/persistence/order/OrderAdapter.java \
  order-service/src/test/java/com/prompthub/order/infra/persistence/order/OrderAdapterTest.java \
  order-service/src/test/java/com/prompthub/order/infra/persistence/OrderProductIdempotencyMigrationTest.java
git commit \
  -m "fix: order-service 주문 상품 DB 멱등 충돌 O018 변환" \
  -m "- 기존 저장 호출과 병행 가능한 saveAndFlush 포트를 추가
- PENDING 부분 유니크 인덱스 이름이 일치하는 경우에만 O018로 변환
- 주문 번호와 외래키 등 다른 무결성 오류는 원래 예외를 유지
- Mockito와 실제 PostgreSQL 제약 위반 경로를 함께 검증"
```

---

### Task 3: Redis 예약과 DB 주문 생성 트랜잭션 분리

**Files:**

- Create: `order-service/src/main/java/com/prompthub/order/application/service/order/OrderCreationTransactionService.java`
- Modify: `order-service/src/main/java/com/prompthub/order/application/service/order/OrderCreator.java:1-98`
- Modify: `order-service/src/main/java/com/prompthub/order/application/service/order/OrderProductReservationService.java:1-91`
- Modify: `order-service/src/main/java/com/prompthub/order/domain/repository/OrderRepository.java:15-19`
- Modify: `order-service/src/main/java/com/prompthub/order/infra/persistence/order/OrderAdapter.java:25-34`
- Rewrite: `order-service/src/test/java/com/prompthub/order/application/service/order/OrderCreatorTest.java`
- Create: `order-service/src/test/java/com/prompthub/order/application/service/order/OrderCreationTransactionServiceTest.java`
- Modify: `order-service/src/test/java/com/prompthub/order/application/service/order/OrderProductReservationServiceTest.java`
- Modify: `order-service/src/test/java/com/prompthub/order/application/service/order/OrderCreationTransactionIntegrationTest.java`

**Interfaces:**

- Produces: `CreateOrderResult OrderCreationTransactionService#create(Order order)`
- Produces: `void OrderProductReservationService#releaseAfterFailure(Order order)`
- Consumes: `OrderRepository#saveAndFlush(Order)`
- Preserves: `CreateOrderResult OrderCreator#create(UUID buyerId, List<OrderCreationItem> items)`

- [ ] **Step 1: DB 변경을 소유하는 내부 트랜잭션 서비스를 추가한다**

`OrderCreationTransactionService.java` 전체 구현:

```java
package com.prompthub.order.application.service.order;

import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.application.event.order.OrderCreatedEvent;
import com.prompthub.order.application.event.order.OrderProductReservationCleanupEvent;
import com.prompthub.order.application.service.event.OrderPaidOutboxAppender;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderCreationTransactionService {

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final OrderPaidOutboxAppender orderPaidOutboxAppender;
    private final OrderProductPurchasePolicy purchasePolicy;

    @Transactional
    public CreateOrderResult create(Order order) {
        List<UUID> productIds = order.getOrderProducts().stream()
            .map(OrderProduct::getProductId)
            .distinct()
            .sorted()
            .toList();
        purchasePolicy.validateOrderable(order.getBuyerId(), productIds);

        Order savedOrder = orderRepository.saveAndFlush(order);
        removeOrderedProductsFromCart(savedOrder);
        if (savedOrder.isFree()) {
            orderPaidOutboxAppender.append(savedOrder);
            applicationEventPublisher.publishEvent(
                OrderProductReservationCleanupEvent.from(savedOrder)
            );
        } else {
            applicationEventPublisher.publishEvent(OrderCreatedEvent.from(savedOrder));
        }
        return CreateOrderResult.from(savedOrder);
    }

    private void removeOrderedProductsFromCart(Order order) {
        List<UUID> productIds = order.getOrderProducts().stream()
            .map(OrderProduct::getProductId)
            .distinct()
            .sorted()
            .toList();
        cartRepository.findByBuyerIdForUpdateWithCartProducts(order.getBuyerId())
            .ifPresent(cart -> {
                cart.removeProductsByProductIds(productIds);
                cartRepository.save(cart);
            });
    }
}
```

- [ ] **Step 2: `OrderCreator`를 비트랜잭션 조정 계층으로 축소한다**

`OrderCreator`의 dependency는 다음 세 개만 유지한다.

```java
private final OrderNumberGenerator orderNumberGenerator;
private final OrderProductReservationService reservationService;
private final OrderCreationTransactionService transactionService;
```

`@Transactional`, DB·Cart·Outbox·event dependency, 무료 상품 별도 DB 조회를 제거한다. `create`는 다음 구현을 사용한다.

```java
public CreateOrderResult create(UUID buyerId, List<OrderCreationItem> items) {
    int totalAmount = OrderAmountCalculator.sum(items, OrderCreationItem::amount);
    Order order = Order.create(
        buyerId,
        orderNumberGenerator.generate(),
        totalAmount
    );
    items.stream()
        .map(item -> OrderProduct.create(
            item.productId(),
            item.sellerId(),
            item.productTitle(),
            item.amount()
        ))
        .forEach(order::addOrderProduct);
    if (order.isFree()) {
        order.completeFreeOrder();
    }

    reservationService.reserve(order);
    try {
        return transactionService.create(order);
    } catch (RuntimeException exception) {
        reservationService.releaseAfterFailure(order);
        throw exception;
    }
}
```

`validateNoAccessibleFreeProduct`는 `OrderProductPurchasePolicy`의 더 넓은 DB 재검증으로 대체되므로 삭제한다.

`OrderCreator`의 기존 호출이 모두 사라진 뒤 `OrderRepository#save`와 `OrderAdapter#save`를 제거한다. 최종 저장 포트는 `saveAndFlush` 하나만 유지한다.

- [ ] **Step 3: 예약 서비스에서 트랜잭션 동기화를 제거하고 명시적 보상을 추가한다**

`TransactionSynchronization`과 `TransactionSynchronizationManager` import 및 `registerRollbackCleanup`을 삭제한다. `reserve`는 획득 성공 후 바로 반환한다.

```java
public void reserve(Order order) {
    List<UUID> productIds = productIds(order);
    try {
        boolean acquired = store.acquire(
            order.getBuyerId(),
            productIds,
            order.getId(),
            policy.ttl()
        );
        if (!acquired) {
            throw new OrderException(ErrorCode.ORDER_PRODUCT_ALREADY_OWNED);
        }
    } catch (OrderException exception) {
        throw exception;
    } catch (RuntimeException exception) {
        log.warn(
            "주문 상품 예약 저장소를 사용할 수 없습니다. orderId={}",
            order.getId(),
            exception
        );
        throw new OrderException(ErrorCode.ORDER_IDEMPOTENCY_STORE_UNAVAILABLE);
    }
}

public void releaseAfterFailure(Order order) {
    try {
        store.release(
            order.getBuyerId(),
            productIds(order),
            order.getId()
        );
    } catch (RuntimeException exception) {
        log.warn(
            "주문 생성 실패 후 상품 예약 정리에 실패했습니다. orderId={}",
            order.getId(),
            exception
        );
    }
}
```

`releaseAfterFailure`는 Redis 정리 오류를 삼켜 원래 DB·비즈니스 예외를 보존한다.

- [ ] **Step 4: 외부 조정 계층과 내부 트랜잭션 단위 테스트를 재구성한다**

`OrderCreatorTest`는 다음 경계를 검증하도록 축소한다.

```java
@Test
void create_reservesBeforeTransactionalCreation() {
    given(orderNumberGenerator.generate()).willReturn("ORD-A");
    CreateOrderResult expected = mock(CreateOrderResult.class);
    given(transactionService.create(any(Order.class))).willReturn(expected);

    CreateOrderResult actual = orderCreator.create(BUYER_ID, orderItems());

    assertThat(actual).isSameAs(expected);
    InOrder ordered = inOrder(reservationService, transactionService);
    ordered.verify(reservationService).reserve(any(Order.class));
    ordered.verify(transactionService).create(any(Order.class));
}

@Test
void create_transactionFailureReleasesReservationAndPreservesFailure() {
    RuntimeException failure = new RuntimeException("database failure");
    given(orderNumberGenerator.generate()).willReturn("ORD-A");
    given(transactionService.create(any(Order.class))).willThrow(failure);

    assertThatThrownBy(() -> orderCreator.create(BUYER_ID, orderItems()))
        .isSameAs(failure);

    then(reservationService).should().releaseAfterFailure(any(Order.class));
}

@Test
void create_reservationFailureDoesNotStartDatabaseTransaction() {
    given(orderNumberGenerator.generate()).willReturn("ORD-A");
    willThrow(new OrderException(ErrorCode.ORDER_PRODUCT_ALREADY_OWNED))
        .given(reservationService).reserve(any(Order.class));

    assertThatThrownBy(() -> orderCreator.create(BUYER_ID, orderItems()))
        .isInstanceOf(OrderException.class);

    then(transactionService).shouldHaveNoInteractions();
}
```

추가로 현재 테스트의 다음 조정 계층 계약을 유지한다.

- 여러 판매자의 상품으로 단일 주문 aggregate 생성
- `Integer.MAX_VALUE` 합계 허용
- 금액 overflow 시 주문 번호·Redis·트랜잭션 서비스 부수효과 없음
- 무료 주문을 `COMPLETED/PAID` 상태로 만든 뒤 예약·내부 트랜잭션 호출

기존 주문 저장·장바구니·이벤트 테스트는 신규 `OrderCreationTransactionServiceTest`로 이동한다. 이 테스트는 다음을 각각 검증한다.

- `purchasePolicy.validateOrderable` 이후 `saveAndFlush`
- 주문 상품만 장바구니에서 제거
- 유료 주문은 `OrderCreatedEvent`
- 무료 주문은 `ORDER_PAID` Outbox와 `OrderProductReservationCleanupEvent`
- Cart 저장 실패가 원래 예외로 전파
- DB 차단 상태에서 저장·Cart·event 부수효과 없음

- [ ] **Step 5: 예약 서비스 단위 테스트를 명시적 보상 계약으로 변경한다**

기존 transaction synchronization 초기화와 `@AfterEach` 정리를 삭제한다. 다음 테스트를 사용한다.

```java
@Test
void reserve_successDoesNotRequireTransactionSynchronization() {
    Order order = createdOrder();
    given(policy.ttl()).willReturn(TTL);
    given(store.acquire(
        eq(BUYER_ID), anyCollection(), eq(order.getId()), eq(TTL)
    )).willReturn(true);

    assertThatCode(() -> service.reserve(order)).doesNotThrowAnyException();
}

@Test
void releaseAfterFailure_releasesWithOrderToken() {
    Order order = createdOrder();

    service.releaseAfterFailure(order);

    then(store).should().release(
        eq(BUYER_ID), anyCollection(), eq(order.getId())
    );
}

@Test
void releaseAfterFailure_storeFailureIsSwallowed() {
    Order order = createdOrder();
    willThrow(new IllegalStateException("redis down"))
        .given(store).release(
            eq(BUYER_ID), anyCollection(), eq(order.getId())
        );

    assertThatCode(() -> service.releaseAfterFailure(order))
        .doesNotThrowAnyException();
}
```

- [ ] **Step 6: Spring 통합 테스트에서 실제 트랜잭션 위치를 검증한다**

`OrderCreationTransactionIntegrationTest#setUp`에서 store 획득과 repository flush를 각각 검사한다.

```java
willAnswer(invocation -> {
    assertThat(
        TransactionSynchronizationManager.isActualTransactionActive()
    ).isFalse();
    return true;
}).given(orderProductIdempotencyStore).acquire(
    any(UUID.class),
    anyCollection(),
    any(UUID.class),
    any(Duration.class)
);

willAnswer(invocation -> {
    assertThat(
        TransactionSynchronizationManager.isActualTransactionActive()
    ).isTrue();
    return invocation.callRealMethod();
}).given(orderRepository).saveAndFlush(any(Order.class));
```

Cart 저장 실패 테스트에는 다음 검증을 추가한다.

```java
then(orderProductIdempotencyStore).should().release(
    eq(BUYER_ID),
    anyCollection(),
    any(UUID.class)
);
```

- [ ] **Step 7: Task 3 테스트를 실행한다**

Run:

```bash
./gradlew :order-service:test \
  --tests "com.prompthub.order.application.service.order.OrderCreatorTest" \
  --tests "com.prompthub.order.application.service.order.OrderCreationTransactionServiceTest" \
  --tests "com.prompthub.order.application.service.order.OrderProductReservationServiceTest" \
  --tests "com.prompthub.order.application.service.order.OrderCreationTransactionIntegrationTest" \
  --tests "com.prompthub.order.infra.redis.OrderExpirationAfterCommitIntegrationTest"
```

Expected:

- Redis 획득 assertion은 트랜잭션 비활성 상태다.
- repository flush assertion은 트랜잭션 활성 상태다.
- `BUILD SUCCESSFUL`

- [ ] **Step 8: 트랜잭션 경계 변경을 커밋한다**

```bash
git add \
  order-service/src/main/java/com/prompthub/order/application/service/order/OrderCreator.java \
  order-service/src/main/java/com/prompthub/order/application/service/order/OrderCreationTransactionService.java \
  order-service/src/main/java/com/prompthub/order/application/service/order/OrderProductReservationService.java \
  order-service/src/main/java/com/prompthub/order/domain/repository/OrderRepository.java \
  order-service/src/main/java/com/prompthub/order/infra/persistence/order/OrderAdapter.java \
  order-service/src/test/java/com/prompthub/order/application/service/order/OrderCreatorTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/order/OrderCreationTransactionServiceTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/order/OrderProductReservationServiceTest.java \
  order-service/src/test/java/com/prompthub/order/application/service/order/OrderCreationTransactionIntegrationTest.java
git commit \
  -m "refactor: order-service Redis 예약과 주문 생성 트랜잭션 경계 분리" \
  -m "- OrderCreator를 Redis 예약과 실패 보상을 담당하는 비트랜잭션 조정 계층으로 축소
- DB 재검증과 주문·장바구니·이벤트 변경을 별도 트랜잭션 서비스로 이동
- 트랜잭션 동기화 기반 예약 해제를 명시적 보상 호출로 교체
- Redis 획득 중 DB 트랜잭션이 비활성 상태인지 통합 테스트로 검증"
```

---

### Task 4: Redis timeout과 TTL 기본값 단일화

**Files:**

- Modify: `order-service/src/main/resources/application.yml:1-9,66-70`
- Modify: `order-service/src/main/java/com/prompthub/order/infra/redis/OrderExpirationProperties.java:11-53`
- Create: `order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationPropertiesTest.java`
- Create: `order-service/src/test/java/com/prompthub/order/infra/redis/RedisTimeoutConfigurationTest.java`
- Modify: `order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationWorkerTest.java`
- Modify: `order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationRegistrarTest.java`

**Interfaces:**

- Consumes: Spring Boot 4.1 metadata `spring.data.redis.connect-timeout`
- Consumes: Spring Boot 4.1 metadata `spring.data.redis.timeout`
- Preserves: `Duration OrderProductIdempotencyPolicy#ttl()`
- Produces: 연결 timeout 기본값 1초, read timeout 기본값 2초

- [ ] **Step 1: 모든 profile에 Redis timeout 기본값을 추가한다**

`application.yml` 첫 번째 `spring` 블록에 다음을 추가한다.

```yaml
spring:
  application:
    name: order-service
  data:
    redis:
      connect-timeout: ${REDIS_CONNECT_TIMEOUT:1s}
      timeout: ${REDIS_COMMAND_TIMEOUT:2s}
```

local profile의 host·port 설정은 유지한다. 자동 재시도와 Circuit Breaker 설정은 추가하지 않는다.

- [ ] **Step 2: 중복 TTL 보정 생성자를 제거한다**

`OrderExpirationProperties`의 5개 인자 convenience constructor와 `Math.max(paymentTimeoutMinutes + 1, 30)`을 삭제한다. canonical constructor의 다음 검증은 유지한다.

```java
if (productIdempotencyTtlMinutes <= paymentTimeoutMinutes) {
    throw new IllegalArgumentException(
        "productIdempotencyTtlMinutes must be greater than paymentTimeoutMinutes"
    );
}
```

테스트에서 직접 생성하는 세 위치는 6번째 인자로 `30`을 전달한다.

```java
new OrderExpirationProperties(true, 20, 5_000L, 100, 3, 30)
```

- [ ] **Step 3: 설정 바인딩과 경계값 테스트를 추가한다**

`OrderExpirationPropertiesTest`는 `ApplicationContextRunner`와 중첩 설정을 사용한다.

```java
private final ApplicationContextRunner contextRunner =
    new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class);

@Test
void defaultsProductIdempotencyTtlToThirtyMinutes() {
    contextRunner.run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBean(OrderExpirationProperties.class).ttl())
            .isEqualTo(Duration.ofMinutes(30));
    });
}

@Test
void rejectsTtlNotGreaterThanPaymentTimeout() {
    contextRunner
        .withPropertyValues(
            "prompthub.order.payment-timeout-minutes=20",
            "prompthub.order.product-idempotency-ttl-minutes=20"
        )
        .run(context -> assertThat(context).hasFailed());
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderExpirationProperties.class)
static class PropertiesConfiguration {
}
```

`RedisTimeoutConfigurationTest`는 application YAML의 두 timeout을 독립적으로 검증한다.

```java
class RedisTimeoutConfigurationTest {

    @Test
    void applicationYamlDefinesBoundedRedisTimeouts() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));

        Properties properties = yaml.getObject();

        assertThat(properties)
            .containsEntry(
                "spring.data.redis.connect-timeout",
                "${REDIS_CONNECT_TIMEOUT:1s}"
            )
            .containsEntry(
                "spring.data.redis.timeout",
                "${REDIS_COMMAND_TIMEOUT:2s}"
            );
    }
}
```

- [ ] **Step 4: Task 4 테스트를 실행한다**

Run:

```bash
./gradlew :order-service:test \
  --tests "com.prompthub.order.infra.redis.OrderExpirationPropertiesTest" \
  --tests "com.prompthub.order.infra.redis.RedisTimeoutConfigurationTest" \
  --tests "com.prompthub.order.infra.redis.OrderExpirationWorkerTest" \
  --tests "com.prompthub.order.infra.redis.OrderExpirationRegistrarTest"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Redis 설정 변경을 커밋한다**

```bash
git add \
  order-service/src/main/resources/application.yml \
  order-service/src/main/java/com/prompthub/order/infra/redis/OrderExpirationProperties.java \
  order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationPropertiesTest.java \
  order-service/src/test/java/com/prompthub/order/infra/redis/RedisTimeoutConfigurationTest.java \
  order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationWorkerTest.java \
  order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationRegistrarTest.java
git commit \
  -m "fix: order-service Redis 멱등 예약 timeout과 TTL 설정 정리" \
  -m "- Redis 연결 timeout을 1초, read timeout을 2초 기본값으로 외부화
- 요청 경로 재시도 없이 기존 fail-closed 정책을 유지
- 테스트 편의 생성자의 중복 30분 보정을 제거하고 설정 바인딩을 단일 출처로 사용
- TTL이 결제 제한 시간보다 길어야 하는 시작 시 검증을 보강"
```

---

### Task 5: 주문 멱등성과 만료 reconciliation 관측 지표

**Files:**

- Create: `order-service/src/main/java/com/prompthub/order/application/service/order/OrderProductReservationMetrics.java`
- Create: `order-service/src/main/java/com/prompthub/order/application/service/order/OrderExpirationMetrics.java`
- Create: `order-service/src/main/java/com/prompthub/order/infra/metrics/MicrometerOrderProductReservationMetrics.java`
- Create: `order-service/src/main/java/com/prompthub/order/infra/metrics/MicrometerOrderExpirationMetrics.java`
- Modify: `order-service/src/main/java/com/prompthub/order/application/service/order/OrderProductReservationService.java`
- Modify: `order-service/src/main/java/com/prompthub/order/infra/redis/RedisOrderProductIdempotencyStore.java`
- Modify: `order-service/src/main/java/com/prompthub/order/infra/redis/OrderExpirationWorker.java`
- Modify: `order-service/src/test/java/com/prompthub/order/application/service/order/OrderProductReservationServiceTest.java`
- Modify: `order-service/src/test/java/com/prompthub/order/infra/redis/RedisOrderProductIdempotencyStoreTest.java`
- Modify: `order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationWorkerTest.java`
- Create: `order-service/src/test/java/com/prompthub/order/infra/metrics/MicrometerOrderProductReservationMetricsTest.java`
- Create: `order-service/src/test/java/com/prompthub/order/infra/metrics/MicrometerOrderExpirationMetricsTest.java`

**Interfaces:**

- Produces: `OrderProductReservationMetrics#recordAttempt(ReservationOutcome)`
- Produces: `OrderProductReservationMetrics#recordRedis(RedisOperation, RedisOutcome, Duration)`
- Produces: `OrderExpirationMetrics#recordCandidates(CandidateSource, int)`
- Produces: `OrderExpirationMetrics#recordCompensation(CompensationOutcome)`
- Produces meter: `order.product.reservation.attempts`
- Produces meter: `order.product.reservation.redis.duration`
- Produces meter: `order.expiration.candidates`
- Produces meter: `order.expiration.compensation`

- [ ] **Step 1: 저카디널리티 애플리케이션 지표 포트를 추가한다**

`OrderProductReservationMetrics.java`:

```java
package com.prompthub.order.application.service.order;

import java.time.Duration;

public interface OrderProductReservationMetrics {

    void recordAttempt(ReservationOutcome outcome);

    void recordRedis(
        RedisOperation operation,
        RedisOutcome outcome,
        Duration duration
    );

    enum ReservationOutcome {
        SUCCESS,
        CONFLICT,
        ERROR
    }

    enum RedisOperation {
        ACQUIRE,
        EXISTS,
        RELEASE
    }

    enum RedisOutcome {
        SUCCESS,
        ERROR
    }
}
```

`OrderExpirationMetrics.java`:

```java
package com.prompthub.order.application.service.order;

public interface OrderExpirationMetrics {

    void recordCandidates(CandidateSource source, int count);

    void recordCompensation(CompensationOutcome outcome);

    enum CandidateSource {
        DB,
        REDIS
    }

    enum CompensationOutcome {
        SUCCESS,
        SKIPPED,
        FAILURE,
        DLQ
    }
}
```

- [ ] **Step 2: Micrometer 구현을 추가한다**

`MicrometerOrderProductReservationMetrics`는 `@Component`, `@RequiredArgsConstructor`로 `MeterRegistry`를 주입한다.

```java
@Override
public void recordAttempt(ReservationOutcome outcome) {
    meterRegistry.counter(
        "order.product.reservation.attempts",
        "outcome",
        tag(outcome)
    ).increment();
}

@Override
public void recordRedis(
    RedisOperation operation,
    RedisOutcome outcome,
    Duration duration
) {
    Timer.builder("order.product.reservation.redis.duration")
        .tag("operation", tag(operation))
        .tag("outcome", tag(outcome))
        .register(meterRegistry)
        .record(duration);
}

private String tag(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
}
```

`MicrometerOrderExpirationMetrics`는 다음 구현을 사용한다.

```java
@Override
public void recordCandidates(CandidateSource source, int count) {
    if (count <= 0) {
        return;
    }
    meterRegistry.counter(
        "order.expiration.candidates",
        "source",
        tag(source)
    ).increment(count);
}

@Override
public void recordCompensation(CompensationOutcome outcome) {
    meterRegistry.counter(
        "order.expiration.compensation",
        "outcome",
        tag(outcome)
    ).increment();
}

private String tag(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
}
```

- [ ] **Step 3: 예약 결과와 Redis latency를 기록한다**

`OrderProductReservationService`에 `OrderProductReservationMetrics`를 주입한다.

```java
if (!acquired) {
    metrics.recordAttempt(ReservationOutcome.CONFLICT);
    throw new OrderException(ErrorCode.ORDER_PRODUCT_ALREADY_OWNED);
}
metrics.recordAttempt(ReservationOutcome.SUCCESS);
```

store RuntimeException catch에는 다음을 먼저 기록한다.

```java
metrics.recordAttempt(ReservationOutcome.ERROR);
```

`RedisOrderProductIdempotencyStore`에 metrics를 주입하고 각 public 연산을 `System.nanoTime()`으로 측정한다. 다음 helper를 사용한다.

```java
private <T> T observe(RedisOperation operation, Supplier<T> action) {
    long startedAt = System.nanoTime();
    try {
        T result = action.get();
        metrics.recordRedis(
            operation,
            RedisOutcome.SUCCESS,
            Duration.ofNanos(System.nanoTime() - startedAt)
        );
        return result;
    } catch (RuntimeException exception) {
        metrics.recordRedis(
            operation,
            RedisOutcome.ERROR,
            Duration.ofNanos(System.nanoTime() - startedAt)
        );
        throw exception;
    }
}
```

적용:

```java
return observe(RedisOperation.ACQUIRE, () ->
    acquireKeys(buyerId, productIds, reservationId, ttl)
);

return observe(
    RedisOperation.EXISTS,
    () -> Boolean.TRUE.equals(redisTemplate.hasKey(key(buyerId, productId)))
);

observe(RedisOperation.RELEASE, () -> {
    releaseKeys(keys, reservationId);
    return null;
});
```

기존 acquire 본문은 private `acquireKeys`로 옮긴다.

- [ ] **Step 4: 만료 후보와 단일 보상 결과를 기록한다**

`OrderExpirationWorker`에 `OrderExpirationMetrics`를 주입한다.

DB와 Redis 조회 성공 시 원본 조회 수를 기록한 뒤 set에 합친다.

```java
List<UUID> databaseCandidates =
    orderRepository.findExpiredCreatedOrderIds(cutoff, properties.batchSize());
metrics.recordCandidates(CandidateSource.DB, databaseCandidates.size());
candidates.addAll(databaseCandidates);
```

```java
Set<UUID> redisCandidates =
    orderExpirationStore.findExpiredOrderIds(now, properties.batchSize());
metrics.recordCandidates(CandidateSource.REDIS, redisCandidates.size());
candidates.addAll(redisCandidates);
```

보상 결과는 처리 시도당 하나만 기록한다.

```java
boolean completed = compensationService.compensateTimeout(orderId, now);
if (completed) {
    cleanupRedisState(orderId);
    metrics.recordCompensation(CompensationOutcome.SUCCESS);
} else {
    metrics.recordCompensation(CompensationOutcome.SKIPPED);
}
```

`handleFailure`가 `CompensationOutcome`을 반환하게 변경한다.

```java
private CompensationOutcome handleFailure(UUID orderId, Exception exception) {
    long retryCount;
    try {
        retryCount = orderExpirationStore.incrementRetryCount(orderId);
    } catch (RuntimeException retryException) {
        log.warn(
            "주문 만료 재시도 상태 저장에 실패했습니다. orderId={}",
            orderId,
            retryException
        );
        return CompensationOutcome.FAILURE;
    }

    boolean movedToDeadLetter = false;
    if (retryCount == properties.maxRetryCount() + 1L) {
        try {
            orderExpirationStore.moveToDeadLetter(orderId);
            movedToDeadLetter = true;
        } catch (RuntimeException dlqException) {
            log.warn(
                "주문 만료 DLQ 기록에 실패했습니다. orderId={}",
                orderId,
                dlqException
            );
        }
    }
    if (retryCount > properties.maxRetryCount()) {
        removeExpirationQuietly(orderId);
    }
    log.warn(
        "주문 만료 처리에 실패했습니다. orderId={}, retryCount={}",
        orderId,
        retryCount,
        exception
    );
    return movedToDeadLetter
        ? CompensationOutcome.DLQ
        : CompensationOutcome.FAILURE;
}
```

catch에서는 반환 결과를 한 번 기록한다.

```java
metrics.recordCompensation(handleFailure(orderId, exception));
```

기존 Redis 대상 제거 catch는 다음 helper로 이동한다.

```java
private void removeExpirationQuietly(UUID orderId) {
    try {
        orderExpirationStore.removeExpiration(orderId);
    } catch (RuntimeException removeException) {
        log.warn(
            "주문 만료 Redis 대상 제거에 실패했습니다. orderId={}",
            orderId,
            removeException
        );
    }
}
```

- [ ] **Step 5: 지표 포트 호출과 Micrometer meter를 테스트한다**

기존 Mockito 테스트에 다음 검증을 추가한다.

```java
then(metrics).should().recordAttempt(ReservationOutcome.SUCCESS);
then(metrics).should().recordRedis(
    eq(RedisOperation.ACQUIRE),
    eq(RedisOutcome.SUCCESS),
    any(Duration.class)
);
then(expirationMetrics).should()
    .recordCandidates(CandidateSource.DB, 1);
then(expirationMetrics).should()
    .recordCompensation(CompensationOutcome.SUCCESS);
```

생성자 변경은 다음과 같이 명시적으로 반영한다.

```java
service = new OrderProductReservationService(store, policy, metrics);
store = new RedisOrderProductIdempotencyStore(redisTemplate, metrics);
return new OrderExpirationWorker(
    orderExpirationStore,
    compensationService,
    orderRepository,
    new OrderExpirationProperties(true, 20, 5_000L, 100, 3, 30),
    CLOCK,
    expirationMetrics
);
```

`MicrometerOrderProductReservationMetricsTest`는 `SimpleMeterRegistry`로 다음을 검증한다.

```java
@Test
void recordsLowCardinalityReservationAndRedisMeters() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerOrderProductReservationMetrics metrics =
        new MicrometerOrderProductReservationMetrics(registry);

    metrics.recordAttempt(ReservationOutcome.CONFLICT);
    metrics.recordRedis(
        RedisOperation.ACQUIRE,
        RedisOutcome.SUCCESS,
        Duration.ofMillis(15)
    );

    assertThat(registry.get(
        "order.product.reservation.attempts"
    ).tag("outcome", "conflict").counter().count()).isEqualTo(1);
    assertThat(registry.get(
        "order.product.reservation.redis.duration"
    ).tag("operation", "acquire").tag("outcome", "success")
        .timer().count()).isEqualTo(1);
    assertNoIdentifierTags(registry);
}
```

`assertNoIdentifierTags`는 모든 meter tag key가 `buyerId`, `productId`, `orderId`, `eventId`가 아님을 확인한다.

```java
private void assertNoIdentifierTags(SimpleMeterRegistry registry) {
    assertThat(registry.getMeters())
        .flatExtracting(meter -> meter.getId().getTags())
        .extracting(Tag::getKey)
        .doesNotContain("buyerId", "productId", "orderId", "eventId");
}
```

`MicrometerOrderExpirationMetricsTest`는 다음 값을 검증한다.

```java
@Test
void recordsDatabaseCandidatesAndDlqWithoutIdentifierTags() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerOrderExpirationMetrics metrics =
        new MicrometerOrderExpirationMetrics(registry);

    metrics.recordCandidates(CandidateSource.DB, 3);
    metrics.recordCompensation(CompensationOutcome.DLQ);

    assertThat(registry.get("order.expiration.candidates")
        .tag("source", "db").counter().count()).isEqualTo(3);
    assertThat(registry.get("order.expiration.compensation")
        .tag("outcome", "dlq").counter().count()).isEqualTo(1);
    assertNoIdentifierTags(registry);
}

private void assertNoIdentifierTags(SimpleMeterRegistry registry) {
    assertThat(registry.getMeters())
        .flatExtracting(meter -> meter.getId().getTags())
        .extracting(Tag::getKey)
        .doesNotContain("buyerId", "productId", "orderId", "eventId");
}
```

- [ ] **Step 6: Task 5 테스트를 실행한다**

Run:

```bash
./gradlew :order-service:test \
  --tests "com.prompthub.order.application.service.order.OrderProductReservationServiceTest" \
  --tests "com.prompthub.order.infra.redis.RedisOrderProductIdempotencyStoreTest" \
  --tests "com.prompthub.order.infra.redis.OrderExpirationWorkerTest" \
  --tests "com.prompthub.order.infra.metrics.MicrometerOrderProductReservationMetricsTest" \
  --tests "com.prompthub.order.infra.metrics.MicrometerOrderExpirationMetricsTest"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 관측 지표 변경을 커밋한다**

```bash
git add \
  order-service/src/main/java/com/prompthub/order/application/service/order/OrderProductReservationMetrics.java \
  order-service/src/main/java/com/prompthub/order/application/service/order/OrderExpirationMetrics.java \
  order-service/src/main/java/com/prompthub/order/infra/metrics/MicrometerOrderProductReservationMetrics.java \
  order-service/src/main/java/com/prompthub/order/infra/metrics/MicrometerOrderExpirationMetrics.java \
  order-service/src/main/java/com/prompthub/order/application/service/order/OrderProductReservationService.java \
  order-service/src/main/java/com/prompthub/order/infra/redis/RedisOrderProductIdempotencyStore.java \
  order-service/src/main/java/com/prompthub/order/infra/redis/OrderExpirationWorker.java \
  order-service/src/test/java/com/prompthub/order/application/service/order/OrderProductReservationServiceTest.java \
  order-service/src/test/java/com/prompthub/order/infra/redis/RedisOrderProductIdempotencyStoreTest.java \
  order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationWorkerTest.java \
  order-service/src/test/java/com/prompthub/order/infra/metrics/MicrometerOrderProductReservationMetricsTest.java \
  order-service/src/test/java/com/prompthub/order/infra/metrics/MicrometerOrderExpirationMetricsTest.java
git commit \
  -m "feat: order-service 주문 멱등성과 만료 보상 관측 지표 추가" \
  -m "- Redis 예약 성공·충돌·오류와 acquire·exists·release latency를 기록
- DB·Redis 만료 후보 수와 보상 success·skipped·failure·dead letter 결과를 기록
- Micrometer 구현을 인프라 계층에 두고 애플리케이션에는 지표 포트만 노출
- 사용자·상품·주문 식별자가 meter tag에 포함되지 않는지 검증"
```

---

### Task 6: PostgreSQL 동시성 및 결제 이벤트 없는 timeout 복구 통합 검증

**Files:**

- Create: `order-service/src/test/java/com/prompthub/order/application/service/order/OrderProductPendingUniquenessConcurrencyTest.java`
- Create: `order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationReconciliationIntegrationTest.java`

**Interfaces:**

- Consumes: `OrderRepository#saveAndFlush(Order)`
- Consumes: `ConcurrentScenarioRunner`
- Consumes: `OrderExpirationWorker#processExpiredOrders()`
- Verifies: 동시 저장 하나만 성공, 다른 하나는 `O018`
- Verifies: Redis 조회 실패와 결제 이벤트 부재에도 DB 후보가 `FAILED` 처리

- [ ] **Step 1: 실제 PostgreSQL 동시 저장 테스트를 추가한다**

`OrderProductPendingUniquenessConcurrencyTest`는 `PostgreSqlIntegrationTestSupport`를 상속하고 `OrderRepository`, `PlatformTransactionManager`를 주입한다.

Spring context의 외부 연동을 격리한다.

```java
@MockitoBean
private ProductClient productClient;

@MockitoBean
private OrderExpirationStore orderExpirationStore;
```

```java
@RepeatedTest(5)
void concurrentPendingOrdersPersistOnlyOneProduct() {
    Order first = order("ORD-CONCURRENT-A");
    Order second = order("ORD-CONCURRENT-B");

    try (ConcurrentScenarioRunner runner =
             new ConcurrentScenarioRunner(transactionManager, 10)) {
        ConcurrentScenarioRunner.Results results = runner.run(
            () -> orderRepository.saveAndFlush(first),
            () -> orderRepository.saveAndFlush(second)
        );

        List<Throwable> failures = Stream.of(
                results.firstFailure(),
                results.secondFailure()
            )
            .filter(Objects::nonNull)
            .toList();
        assertThat(failures)
            .singleElement()
            .isInstanceOf(OrderException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.ORDER_PRODUCT_ALREADY_OWNED
            );
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from order_product",
            Long.class
        )).isEqualTo(1L);
    }
}
```

`order` helper는 `OrderV2Fixture`의 동일 `BUYER_ID`, 동일 `PRODUCT_A1`, `SELLER_A`와 서로 다른 주문 번호를 사용한다.

```java
private Order order(String orderNumber) {
    Order order = Order.create(BUYER_ID, orderNumber, 10_000);
    order.addOrderProduct(
        OrderProduct.create(PRODUCT_A1, SELLER_A, "상품", 10_000)
    );
    return order;
}
```

테스트에는 `BUYER_ID`, `PRODUCT_A1`, `SELLER_A`를 static import하고 `java.util.List`, `java.util.Objects`, `java.util.stream.Stream`을 import한다.

- [ ] **Step 2: 결제 이벤트 없이 DB reconciliation이 주문을 복구하는 통합 테스트를 추가한다**

`OrderExpirationReconciliationIntegrationTest`는 `PostgreSqlIntegrationTestSupport`를 상속한다. 외부 연동과 지표는 다음 mock으로 교체한다.

```java
@MockitoBean
private ProductClient productClient;

@MockitoBean
private OrderExpirationStore orderExpirationStore;

@MockitoBean
private OrderProductIdempotencyStore orderProductIdempotencyStore;

@MockitoBean
private OrderExpirationMetrics expirationMetrics;
```

```java
@Test
void databaseReconciliationFailsExpiredOrderWithoutPaymentEvent() {
    Order order = orderRepository.saveAndFlush(createdOrder());
    LocalDateTime timedOutAt = order.getCreatedAt().plusMinutes(20);
    Clock clock = Clock.fixed(
        timedOutAt.atZone(ZoneId.of("Asia/Seoul")).toInstant(),
        ZoneId.of("Asia/Seoul")
    );
    OrderExpirationWorker worker = new OrderExpirationWorker(
        orderExpirationStore,
        compensationService,
        orderRepository,
        new OrderExpirationProperties(true, 20, 5_000L, 100, 3, 30),
        clock,
        expirationMetrics
    );
    willThrow(new IllegalStateException("redis down"))
        .given(orderExpirationStore)
        .findExpiredOrderIds(any(Instant.class), eq(100));

    worker.processExpiredOrders();

    Order restored = orderRepository.findByIdWithOrderProducts(order.getId())
        .orElseThrow();
    assertThat(restored.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
    assertThat(restored.getOrderProducts())
        .extracting(OrderProduct::getOrderStatus)
        .containsOnly(OrderProductStatus.FAILED);
    assertThat(cartRepository.findByBuyerIdWithCartProducts(BUYER_ID))
        .hasValueSatisfying(cart -> assertThat(cart.getCartProducts())
            .extracting(CartProduct::getProductId)
            .containsExactlyInAnyOrderElementsOf(productIds()));
    then(orderProductIdempotencyStore).should().release(
        eq(BUYER_ID),
        eq(productIds().stream().sorted().toList()),
        eq(order.getId())
    );
}
```

주문 fixture와 기대 상품 목록은 `OrderV2Fixture` 상수로 고정한다.

```java
private Order createdOrder() {
    Order order = Order.create(BUYER_ID, "ORD-RECONCILE", AMOUNT_A1 + AMOUNT_A2);
    order.addOrderProduct(
        OrderProduct.create(PRODUCT_A1, SELLER_A, "상품 A1", AMOUNT_A1)
    );
    order.addOrderProduct(
        OrderProduct.create(PRODUCT_A2, SELLER_A, "상품 A2", AMOUNT_A2)
    );
    return order;
}

private List<UUID> productIds() {
    return List.of(PRODUCT_A1, PRODUCT_A2);
}
```

`BUYER_ID`, `PRODUCT_A1`, `PRODUCT_A2`, `SELLER_A`, `AMOUNT_A1`, `AMOUNT_A2`를 static import한다.

이 테스트에서는 `PaymentEventRouter`를 호출하지 않는다. 복구 근거는 오직 DB의 만료 `CREATED` 행이다.

- [ ] **Step 3: `PAYMENT_CANCELED` 계약을 추가하지 않았는지 정적 확인한다**

Run:

```bash
rg -n "PAYMENT_CANCELED" order-service/src/main
```

Expected: 출력 없음, exit code 1

`PaymentEventType`은 `PAYMENT_APPROVED`, `PAYMENT_REFUNDED`, `PAYMENT_REFUND_FAILED`, `PAYMENT_FAILED`만 유지한다. 기존 테스트의 `PAYMENT_CANCELED` 미지원·ACK 검증은 삭제하지 않는다.

- [ ] **Step 4: Task 6 테스트를 실행한다**

Run:

```bash
./gradlew :order-service:test \
  --tests "com.prompthub.order.application.service.order.OrderProductPendingUniquenessConcurrencyTest" \
  --tests "com.prompthub.order.infra.redis.OrderExpirationReconciliationIntegrationTest" \
  --tests "com.prompthub.order.application.service.order.OrderFailureCompensationJpaTest"
```

Expected:

- 동시성 반복 5회 모두 주문 상품 한 행만 저장된다.
- 결제 이벤트 없이 만료 주문이 `FAILED`가 되고 장바구니가 복원된다.
- `BUILD SUCCESSFUL`

- [ ] **Step 5: 통합 검증을 별도 커밋한다**

```bash
git add \
  order-service/src/test/java/com/prompthub/order/application/service/order/OrderProductPendingUniquenessConcurrencyTest.java \
  order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationReconciliationIntegrationTest.java
git commit \
  -m "test: order-service 주문 상품 동시성과 timeout 복구 검증 추가" \
  -m "- PostgreSQL에서 동일 구매자·상품 PENDING 동시 저장 시 하나만 성공하는지 반복 검증
- DB 부분 유니크 충돌이 O018로 변환되는 실제 경쟁 경로를 확인
- Redis 조회 실패와 결제 이벤트 부재에도 DB reconciliation이 주문을 FAILED 처리하는지 검증
- timeout 보상 시 장바구니 복원과 상품 예약 정리를 함께 확인"
```

---

### Task 7: 전체 회귀 검증과 PR `#535` 갱신

**Files:**

- Verify: `order-service/**`
- Preserve: 기존 세 미추적 설계·계획 문서
- External update: GitHub PR `#535`

**Interfaces:**

- Consumes: Tasks 1-6의 여섯 기능별 커밋
- Produces: 통과한 전체 test·build 결과
- Produces: 스키마·Redis·Kafka 영향과 실제 테스트 수가 반영된 PR 본문

- [ ] **Step 1: 변경 범위와 커밋 분리를 검토한다**

Run:

```bash
git status --short --branch
git log --oneline --decorate origin/fix/#533-order-product-idempotency..HEAD
git diff --stat origin/fix/#533-order-product-idempotency...HEAD
git diff --check origin/fix/#533-order-product-idempotency...HEAD
```

Expected:

- 구현 커밋은 Task 1-6의 목적별 커밋으로 나뉜다.
- 기존 세 미추적 문서 외에 의도하지 않은 파일이 없다.
- `git diff --check` 출력 없음

- [ ] **Step 2: 민감정보와 계약 변경 여부를 확인한다**

Run:

```bash
git diff origin/fix/#533-order-product-idempotency...HEAD -- \
  order-service/src/main \
  order-service/src/test \
  order-service/docs
rg -n "API_KEY|SECRET_KEY|PASSWORD=|BEGIN PRIVATE KEY" \
  order-service/src order-service/docs \
  --glob '!**/superpowers/**'
rg -n "PAYMENT_CANCELED" order-service/src/main
git diff --name-only origin/fix/#533-order-product-idempotency...HEAD | \
  rg 'grpc/|\\.proto$|infra/messaging/kafka/event'
```

Expected:

- 비밀값 없음
- 운영 코드에 `PAYMENT_CANCELED` 없음
- `.proto`, Kafka payload·event type 변경 없음

- [ ] **Step 3: 전체 order-service 테스트를 강제 재실행한다**

Run:

```bash
./gradlew :order-service:test --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`, failed test 0

실제 테스트 수:

```bash
rg -o 'tests="[0-9]+"' order-service/build/test-results/test/*.xml |
  cut -d'"' -f2 |
  awk '{ total += $1 } END { print total }'
```

- [ ] **Step 4: 패키징 포함 build를 실행한다**

Run:

```bash
./gradlew :order-service:build
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: PR 본문을 템플릿에 맞게 갱신한다**

PR `#535`에 다음 내용을 반영한다.

- `Related: #533`
- 새 테이블 없음
- `order_product.buyer_id` nullable 확장·백필
- `uk_order_product_buyer_product_pending` 부분 유니크 인덱스
- 후속 배포의 `buyer_id NOT NULL`
- Redis 연결 1초·read 2초 timeout과 fail-closed `SYS003`
- Redis 밖·DB 안 트랜잭션 경계
- Micrometer 네 개 지표와 고카디널리티 태그 제외
- `PAYMENT_CANCELED`, Kafka payload·topic, gRPC 계약 변경 없음
- 실행한 전체 test·build 명령과 Step 3에서 계산한 실제 테스트 수
- 무료 주문과 `FAILED -> COMPLETED` 지연 승인 한계

PR 제목은 기존 단일 목적과 맞으면 유지하고, 바꿔야 할 경우 다음 형식을 사용한다.

```text
[FIX] order-service - 디지털 상품 중복 주문과 만료 복구 보강
```

- [ ] **Step 6: 원격 상태를 다시 확인하고 안전하게 push한다**

Run:

```bash
git fetch origin 'fix/#533-order-product-idempotency' develop
git rev-list --left-right --count \
  HEAD...origin/fix/#533-order-product-idempotency
```

Expected: 원격 브랜치 기준 local ahead, remote ahead 0

원격이 앞서 있으면 push하지 말고 변경 내용을 비교해 fast-forward 또는 충돌 해결 계획을 다시 세운다. 원격이 앞서 있지 않으면:

```bash
git push origin 'fix/#533-order-product-idempotency'
```

- [ ] **Step 7: PR과 CI 상태를 확인한다**

Run:

```bash
gh pr view 535 --json url,state,mergeStateStatus,reviewDecision,statusCheckRollup
gh pr checks 535
```

Expected:

- PR URL은 `https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/pull/535`
- 새 커밋이 PR에 포함됨
- CI가 통과하거나 실행 중
- 실패 시 로그를 확인하고 실패를 숨기지 않음
