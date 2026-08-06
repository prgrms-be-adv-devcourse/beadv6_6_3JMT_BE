# order-service 어드민 주문 API → admin-service 이관 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** order-service의 어드민 주문 API 3개(`GET /admin/orders`, `/admin/orders/month`, `/admin/orders/weekend`)를 admin-service로 이관하고, order-service의 대응 코드를 삭제한다.

**Architecture:** admin-service가 order_service 스키마(order, order_product)와 user_service 스키마(user)를 read-only JPA/QueryDSL 엔티티로 직접 읽는다(settlement 이관 전례와 동일, 네트워크 클라이언트 없음). 게이트웨이 라우트 소유권을 order-service에서 admin-service로 옮기고, order-service의 컨트롤러/서비스/리포지토리/Seller 클라이언트를 삭제하는 hard cutover.

**Tech Stack:** Spring Boot, Spring Data JPA, QueryDSL 5.1.0(jakarta), H2(테스트), JUnit5 + Mockito + AssertJ.

## Global Constraints

- 새 코드는 `com.prompthub.admin.order` 패키지, 계층 구조는 기존 `com.prompthub.admin.settlement`와 동일(`presentation/application/domain/infrastructure`).
- 클래스명에서 "Admin" 접두사 제거(`OrderController`, `OrderService`, `OrderQueryService`, `OrderUseCase`, `OrderQueryRepository`, `OrderSearchCondition` 등). 메서드명도 동일 원칙: `getAdminOrders` → `getOrders`, `searchAdminOrders` → `searchOrders`.
- `OrderController` 경로는 `${api.init}/admin/orders`(설계 결정 6, admin-service 기준 `/api/v2/admin/orders`로 해석됨) — order-service처럼 하드코딩하지 않는다.
- admin-service는 order_service·user_service 스키마를 **읽기 전용**으로만 접근한다. 신규 엔티티에 `@Builder`/`create()`/setter 등 쓰기용 API를 두지 않는다 — `@Getter` + `@NoArgsConstructor(access = PROTECTED)`만 사용하고, 테스트 픽스처는 SQL(`@Sql`)로만 넣는다(admin-service의 기존 `SettlementQueryRepositoryAdapterTest` 컨벤션과 동일).
- 읽기 전용 미러 엔티티는 **이번 3개 쿼리가 실제로 참조하는 컬럼만** 매핑한다(예: `Order`에 `buyer_id`/`order_number`, `OrderProduct`에 `product_id`/`order_product_status`/`updated_at`/`downloaded` 매핑 안 함) — 안 쓰는 컬럼까지 미러링하지 않는다.
- 신규 클래스의 Swagger(`@Schema`/`@Operation`/`@ApiResponses`)는 order-service 원본 문구를 그대로 유지한다(문서 톤 변경 없음), 단 `OrderSearchCondition`의 `orderStatus` 필드 `@Schema` 설명은 실제 `OrderStatus.valueOf`가 허용하는 값(`CREATED, COMPLETED, FAILED, PARTIAL_REFUNDED, ALL_REFUNDED`)으로 고친다 — 원본 문서는 `PENDING/PAID/CANCELED/REFUNDED` 별칭을 예시로 들었지만 `valueOf`는 실제 enum 상수만 인식하므로 그 별칭들은 애초에 400이 난다(원본의 문서 버그, 이번에 같이 고친다).
- 에러는 admin-service 자체 `AdminException`/`AdminErrorCode.INVALID_INPUT_VALUE`(코드 `A-001`)를 사용한다. `GlobalExceptionHandler`는 이미 `BusinessException`을 공통 처리하므로 신규 예외 핸들러 추가 불필요.
- 커밋 메시지는 `<타입>: <내용>` 컨벤션(`feat`, `test`, `chore`, `refactor`)을 따른다.
- 모든 `./gradlew` 명령은 저장소 루트(`/Users/anjinpyo/developments/dev-course/projects/beadv6_6_3JMT_BE`)에서 실행한다.

---

### Task 1: 읽기 전용 Order/OrderProduct 엔티티 + OrderQueryRepository(QueryDSL)

**Files:**
- Modify: `admin-service/build.gradle`
- Create: `admin-service/src/main/java/com/prompthub/admin/order/domain/enums/OrderStatus.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/order/domain/model/Order.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/order/domain/model/OrderProduct.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/order/application/dto/OrderListProjection.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/order/application/dto/DailyTransactionProjection.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/order/presentation/dto/request/OrderSearchCondition.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/order/domain/repository/OrderQueryRepository.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/order/infrastructure/persistence/config/QuerydslConfig.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/order/infrastructure/persistence/OrderQueryRepositoryImpl.java`
- Test: `admin-service/src/test/java/com/prompthub/admin/order/infrastructure/persistence/OrderQueryRepositoryImplTest.java`
- Create: `admin-service/src/test/resources/sql/orders.sql`

**Interfaces:**
- Produces: `OrderQueryRepository.searchOrders(OrderSearchCondition, Pageable): Page<OrderListProjection>`, `.sumMonthlyTransactionAmount(LocalDateTime, LocalDateTime): long`, `.findDailyTransactions(LocalDateTime, LocalDateTime): List<DailyTransactionProjection>` — Task 3(application 레이어)이 이 포트를 그대로 소비한다.
- Produces: `OrderSearchCondition` record(`orderStatus: String, page: Integer, size: Integer`, `resolve()`, `resolvedOrderStatus()`) — Task 3·4가 그대로 재사용한다.
- Produces: `OrderListProjection(orderId: UUID, productTitle: String, totalOrderCount: int, totalOrderAmount: int, orderStatus: OrderStatus, createdAt: LocalDateTime, sellers: List<SellerSummary>)`, `SellerSummary(sellerId: UUID, productCount: int, orderAmount: int)`.
- Produces: `DailyTransactionProjection(date: LocalDate, transactionCount: long, transactionAmount: long)`.

- [ ] **Step 1: admin-service build.gradle에 QueryDSL 의존성 추가**

`admin-service/build.gradle`을 다음으로 교체한다:

```gradle
dependencies {
    // swagger (api-docs JSON 엔드포인트만 노출 — UI는 Gateway에서 집계)
    implementation "org.springdoc:springdoc-openapi-starter-webmvc-api:${springdocVersion}"

    // QueryDSL — order-service 어드민 주문 쿼리 이관용
    implementation 'com.querydsl:querydsl-jpa:5.1.0:jakarta'
    annotationProcessor 'com.querydsl:querydsl-apt:5.1.0:jakarta'
    annotationProcessor 'jakarta.annotation:jakarta.annotation-api'
    annotationProcessor 'jakarta.persistence:jakarta.persistence-api'

    testImplementation 'org.springframework.boot:spring-boot-starter-data-jpa-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'

    // 테스트용 인메모리 DB — @SpringBootTest 가 외부 실 PostgreSQL 에 의존하지 않게 한다
    testRuntimeOnly 'com.h2database:h2'
}
```

- [ ] **Step 2: OrderStatus enum 작성**

`admin-service/src/main/java/com/prompthub/admin/order/domain/enums/OrderStatus.java`:

```java
package com.prompthub.admin.order.domain.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주문 상태: CREATED(생성), COMPLETED(결제 완료), FAILED(결제 실패), PARTIAL_REFUNDED(부분 환불), ALL_REFUNDED(전체 환불)")
public enum OrderStatus {
	CREATED,
	COMPLETED,
	FAILED,
	PARTIAL_REFUNDED,
	ALL_REFUNDED
}
```

- [ ] **Step 3: Order/OrderProduct 읽기 전용 엔티티 작성**

`admin-service/src/main/java/com/prompthub/admin/order/domain/model/Order.java`:

```java
package com.prompthub.admin.order.domain.model;

import com.prompthub.admin.order.domain.enums.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * order-service 소유 "order" 테이블의 읽기 전용 재매핑.
 * admin-service는 이 테이블에 쓰기 작업을 하지 않는다 — 컬럼 정의가 바뀌면
 * order-service의 Order 엔티티에 맞춰 같이 수정한다. 이 3개 어드민 쿼리가
 * 실제로 참조하는 컬럼만 매핑했다(buyer_id·order_number 등은 매핑하지 않음).
 */
@Entity
@Table(name = "\"order\"")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

	@Id
	@Column(name = "id", columnDefinition = "uuid")
	private UUID id;

	@Column(name = "total_order_amount", nullable = false)
	private int totalOrderAmount;

	@Enumerated(EnumType.STRING)
	@Column(name = "order_status", length = 20, nullable = false)
	private OrderStatus orderStatus;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@Column(name = "created_at", updatable = false)
	private LocalDateTime createdAt;

	@OneToMany(mappedBy = "order")
	private final List<OrderProduct> orderProducts = new ArrayList<>();
}
```

`admin-service/src/main/java/com/prompthub/admin/order/domain/model/OrderProduct.java`:

```java
package com.prompthub.admin.order.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * order-service 소유 "order_product" 테이블의 읽기 전용 재매핑.
 * 이 3개 어드민 쿼리가 실제로 참조하는 컬럼만 매핑했다(product_id·
 * order_product_status·updated_at·downloaded 등은 매핑하지 않음).
 */
@Entity
@Table(name = "\"order_product\"")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderProduct {

	@Id
	@Column(name = "id", columnDefinition = "uuid")
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "order_id", nullable = false)
	private Order order;

	@Column(name = "seller_id", columnDefinition = "uuid", nullable = false)
	private UUID sellerId;

	@Column(name = "product_title_snapshot", length = 200, nullable = false)
	private String productTitle;

	@Column(name = "product_amount_snapshot", nullable = false)
	private int productAmount;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "refunded_at")
	private LocalDateTime refundedAt;
}
```

- [ ] **Step 4: 프로젝션 DTO + 검색 조건 작성**

`admin-service/src/main/java/com/prompthub/admin/order/application/dto/OrderListProjection.java`:

```java
package com.prompthub.admin.order.application.dto;

import com.prompthub.admin.order.domain.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderListProjection(
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
	) {
	}
}
```

`admin-service/src/main/java/com/prompthub/admin/order/application/dto/DailyTransactionProjection.java`:

```java
package com.prompthub.admin.order.application.dto;

import java.time.LocalDate;

public record DailyTransactionProjection(
	LocalDate date,
	long transactionCount,
	long transactionAmount
) {
}
```

`admin-service/src/main/java/com/prompthub/admin/order/presentation/dto/request/OrderSearchCondition.java`:

```java
package com.prompthub.admin.order.presentation.dto.request;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.order.domain.enums.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 주문 목록 조회 조건")
public record OrderSearchCondition(
	@Schema(description = "주문 상태 필터. ALL, CREATED, COMPLETED, FAILED, PARTIAL_REFUNDED, ALL_REFUNDED", example = "ALL", defaultValue = "ALL")
	String orderStatus,
	@Schema(description = "페이지 번호. 1부터 시작하며 생략 시 1", example = "1", defaultValue = "1")
	Integer page,
	@Schema(description = "페이지 크기. 1 이상 100 이하이며 생략 시 20", example = "20", defaultValue = "20")
	Integer size
) {

	private static final String ALL = "ALL";
	private static final int DEFAULT_PAGE = 1;
	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 100;

	public OrderSearchCondition resolve() {
		OrderStatus resolvedOrderStatus = resolvedOrderStatus();
		return new OrderSearchCondition(
			resolvedOrderStatus == null ? ALL : resolvedOrderStatus.name(),
			resolvePage(),
			resolveSize()
		);
	}

	public OrderStatus resolvedOrderStatus() {
		String status = resolveOrderStatusText();
		if (ALL.equals(status)) {
			return null;
		}

		try {
			return OrderStatus.valueOf(status);
		} catch (IllegalArgumentException exception) {
			throw new AdminException(AdminErrorCode.INVALID_INPUT_VALUE);
		}
	}

	private String resolveOrderStatusText() {
		if (orderStatus == null || orderStatus.isBlank()) {
			return ALL;
		}
		return orderStatus.trim().toUpperCase();
	}

	private int resolvePage() {
		if (page == null) {
			return DEFAULT_PAGE;
		}
		if (page < 1) {
			throw new AdminException(AdminErrorCode.INVALID_INPUT_VALUE);
		}
		return page;
	}

	private int resolveSize() {
		if (size == null) {
			return DEFAULT_SIZE;
		}
		if (size < 1 || size > MAX_SIZE) {
			throw new AdminException(AdminErrorCode.INVALID_INPUT_VALUE);
		}
		return size;
	}
}
```

- [ ] **Step 5: OrderQueryRepository 포트 작성**

`admin-service/src/main/java/com/prompthub/admin/order/domain/repository/OrderQueryRepository.java`:

```java
package com.prompthub.admin.order.domain.repository;

import com.prompthub.admin.order.application.dto.DailyTransactionProjection;
import com.prompthub.admin.order.application.dto.OrderListProjection;
import com.prompthub.admin.order.presentation.dto.request.OrderSearchCondition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderQueryRepository {

	Page<OrderListProjection> searchOrders(OrderSearchCondition condition, Pageable pageable);

	long sumMonthlyTransactionAmount(LocalDateTime startInclusive, LocalDateTime endExclusive);

	List<DailyTransactionProjection> findDailyTransactions(LocalDateTime startInclusive, LocalDateTime endExclusive);
}
```

- [ ] **Step 6: 실패하는 리포지토리 테스트 + SQL 픽스처 작성**

`admin-service/src/test/resources/sql/orders.sql`:

```sql
-- 어드민 주문 목록/월간·주간 통계 쿼리 검증용 픽스처.
-- H2 스키마는 application-test.yml 안내대로 admin 재매핑 엔티티(Order/OrderProduct) 기준으로 생성된다(ddl-auto: create-drop).
INSERT INTO "order" (id, total_order_amount, order_status, completed_at, created_at) VALUES
('aaaaaaaa-0000-0000-0000-000000000001', 30000, 'COMPLETED', '2026-06-10 10:01:00', '2026-06-10 10:00:00'),
('aaaaaaaa-0000-0000-0000-000000000002', 10000, 'ALL_REFUNDED', '2026-06-11 09:00:00', '2026-06-11 08:59:00'),
('aaaaaaaa-0000-0000-0000-000000000003', 5000, 'CREATED', NULL, '2026-06-12 10:00:00');

INSERT INTO "order_product" (id, order_id, seller_id, product_title_snapshot, product_amount_snapshot, created_at, refunded_at) VALUES
('bbbbbbbb-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001', 'cccccccc-0000-0000-0000-000000000001', '프롬프트 상품 1', 10000, '2026-06-10 10:00:01', NULL),
('bbbbbbbb-0000-0000-0000-000000000002', 'aaaaaaaa-0000-0000-0000-000000000001', 'cccccccc-0000-0000-0000-000000000002', '프롬프트 상품 2', 20000, '2026-06-10 10:00:02', NULL),
('bbbbbbbb-0000-0000-0000-000000000003', 'aaaaaaaa-0000-0000-0000-000000000002', 'cccccccc-0000-0000-0000-000000000001', '프롬프트 상품 3', 10000, '2026-06-11 08:59:01', '2026-06-12 09:00:00'),
('bbbbbbbb-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000003', 'cccccccc-0000-0000-0000-000000000002', '프롬프트 상품 4', 5000, '2026-06-12 10:00:01', NULL);
```

`admin-service/src/test/java/com/prompthub/admin/order/infrastructure/persistence/OrderQueryRepositoryImplTest.java`:

```java
package com.prompthub.admin.order.infrastructure.persistence;

import com.prompthub.admin.order.application.dto.DailyTransactionProjection;
import com.prompthub.admin.order.application.dto.OrderListProjection;
import com.prompthub.admin.order.domain.enums.OrderStatus;
import com.prompthub.admin.order.infrastructure.persistence.config.QuerydslConfig;
import com.prompthub.admin.order.presentation.dto.request.OrderSearchCondition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({QuerydslConfig.class, OrderQueryRepositoryImpl.class})
@ActiveProfiles("test")
@Sql("/sql/orders.sql")
class OrderQueryRepositoryImplTest {

	@Autowired
	private OrderQueryRepositoryImpl repository;

	@Test
	void COMPLETED_상태로_필터링하면_해당_주문만_판매자별로_묶어서_반환한다() {
		Page<OrderListProjection> result = repository.searchOrders(
			new OrderSearchCondition("COMPLETED", 1, 20).resolve(),
			PageRequest.of(0, 20)
		);

		assertThat(result.getTotalElements()).isEqualTo(1);
		OrderListProjection projection = result.getContent().getFirst();
		assertThat(projection.orderId()).isEqualTo(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"));
		assertThat(projection.productTitle()).isEqualTo("프롬프트 상품 1 외 1건");
		assertThat(projection.totalOrderCount()).isEqualTo(2);
		assertThat(projection.totalOrderAmount()).isEqualTo(30000);
		assertThat(projection.orderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(projection.sellers()).containsExactly(
			new OrderListProjection.SellerSummary(UUID.fromString("cccccccc-0000-0000-0000-000000000001"), 1, 10000),
			new OrderListProjection.SellerSummary(UUID.fromString("cccccccc-0000-0000-0000-000000000002"), 1, 20000)
		);
	}

	@Test
	void 월간_실거래액은_완료금액에서_환불금액을_뺀다() {
		long result = repository.sumMonthlyTransactionAmount(
			LocalDateTime.of(2026, 6, 1, 0, 0),
			LocalDateTime.of(2026, 7, 1, 0, 0)
		);

		// completedAt 기준 6월 합계(30000+10000) - 환불된 상품 금액(10000) = 30000
		assertThat(result).isEqualTo(30000L);
	}

	@Test
	void 일별_거래는_완료일과_환불일을_기준으로_집계한다() {
		List<DailyTransactionProjection> result = repository.findDailyTransactions(
			LocalDateTime.of(2026, 6, 10, 0, 0),
			LocalDateTime.of(2026, 6, 13, 0, 0)
		);

		assertThat(result).hasSize(3);
		assertThat(result.get(0).date()).isEqualTo(java.time.LocalDate.of(2026, 6, 10));
		assertThat(result.get(0).transactionCount()).isEqualTo(1L);
		assertThat(result.get(0).transactionAmount()).isEqualTo(30000L);
		assertThat(result.get(1).date()).isEqualTo(java.time.LocalDate.of(2026, 6, 11));
		assertThat(result.get(1).transactionCount()).isEqualTo(1L);
		assertThat(result.get(1).transactionAmount()).isEqualTo(10000L);
		assertThat(result.get(2).date()).isEqualTo(java.time.LocalDate.of(2026, 6, 12));
		assertThat(result.get(2).transactionCount()).isZero();
		assertThat(result.get(2).transactionAmount()).isEqualTo(-10000L);
	}
}
```

- [ ] **Step 7: 테스트 실행해서 컴파일 실패 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.order.infrastructure.persistence.OrderQueryRepositoryImplTest"`
Expected: FAIL — `OrderQueryRepositoryImpl`, `QuerydslConfig` 클래스가 없어 컴파일 에러.

- [ ] **Step 8: QuerydslConfig + OrderQueryRepositoryImpl 구현**

`admin-service/src/main/java/com/prompthub/admin/order/infrastructure/persistence/config/QuerydslConfig.java`:

```java
package com.prompthub.admin.order.infrastructure.persistence.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QuerydslConfig {

	@PersistenceContext
	private EntityManager entityManager;

	@Bean
	public JPAQueryFactory jpaQueryFactory() {
		return new JPAQueryFactory(entityManager);
	}
}
```

`admin-service/src/main/java/com/prompthub/admin/order/infrastructure/persistence/OrderQueryRepositoryImpl.java`:

```java
package com.prompthub.admin.order.infrastructure.persistence;

import com.prompthub.admin.order.application.dto.DailyTransactionProjection;
import com.prompthub.admin.order.application.dto.OrderListProjection;
import com.prompthub.admin.order.domain.enums.OrderStatus;
import com.prompthub.admin.order.domain.repository.OrderQueryRepository;
import com.prompthub.admin.order.presentation.dto.request.OrderSearchCondition;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.DateExpression;
import com.querydsl.core.types.dsl.DateTimePath;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.prompthub.admin.order.domain.model.QOrder.order;
import static com.prompthub.admin.order.domain.model.QOrderProduct.orderProduct;

@Repository
@RequiredArgsConstructor
public class OrderQueryRepositoryImpl implements OrderQueryRepository {

	private final JPAQueryFactory queryFactory;

	@Override
	public Page<OrderListProjection> searchOrders(OrderSearchCondition condition, Pageable pageable) {
		List<UUID> orderIds = queryFactory
			.select(order.id)
			.from(order)
			.where(orderStatusEq(condition.resolvedOrderStatus()))
			.orderBy(order.createdAt.desc())
			.offset(pageable.getOffset())
			.limit(pageable.getPageSize())
			.fetch();

		JPAQuery<Long> countQuery = queryFactory
			.select(order.count())
			.from(order)
			.where(orderStatusEq(condition.resolvedOrderStatus()));

		if (orderIds.isEmpty()) {
			return new PageImpl<>(List.of(), pageable, valueOrZero(countQuery.fetchOne()));
		}

		List<Tuple> rows = queryFactory
			.select(
				order.id,
				orderProduct.sellerId,
				orderProduct.productTitle,
				orderProduct.productAmount,
				order.totalOrderAmount,
				order.orderStatus,
				order.createdAt,
				orderProduct.createdAt,
				orderProduct.id
			)
			.from(order)
			.join(order.orderProducts, orderProduct)
			.where(order.id.in(orderIds))
			.orderBy(order.createdAt.desc(), orderProduct.createdAt.asc(), orderProduct.id.asc())
			.fetch();

		Map<UUID, List<Tuple>> rowsByOrderId = new LinkedHashMap<>();
		rows.forEach(row -> rowsByOrderId
			.computeIfAbsent(row.get(order.id), ignored -> new ArrayList<>())
			.add(row));

		List<OrderListProjection> content = orderIds.stream()
			.map(rowsByOrderId::get)
			.filter(orderRows -> orderRows != null && !orderRows.isEmpty())
			.map(this::toOrderListProjection)
			.toList();

		return new PageImpl<>(content, pageable, valueOrZero(countQuery.fetchOne()));
	}

	@Override
	public long sumMonthlyTransactionAmount(LocalDateTime startInclusive, LocalDateTime endExclusive) {
		return sumCompletedOrderAmount(startInclusive, endExclusive)
			- sumRefundedProductAmount(startInclusive, endExclusive);
	}

	@Override
	public List<DailyTransactionProjection> findDailyTransactions(
		LocalDateTime startInclusive,
		LocalDateTime endExclusive
	) {
		Map<LocalDate, DailyTransactionAccumulator> dailyTransactions = new LinkedHashMap<>();

		DateExpression<java.sql.Date> completedDate = toDate(order.completedAt);
		NumberExpression<Long> completedCount = order.count();
		NumberExpression<Integer> completedAmount = order.totalOrderAmount.sum();
		queryFactory
			.select(completedDate, completedCount, completedAmount)
			.from(order)
			.where(
				dateTimeGoe(order.completedAt, startInclusive),
				dateTimeLt(order.completedAt, endExclusive)
			)
			.groupBy(completedDate)
			.fetch()
			.forEach(row -> dailyTransactions
				.computeIfAbsent(toLocalDate(row.get(completedDate)), ignored -> new DailyTransactionAccumulator())
				.addCompleted(valueOrZero(row.get(completedCount)), valueOrZero(row.get(completedAmount))));

		DateExpression<java.sql.Date> refundedDate = toDate(orderProduct.refundedAt);
		NumberExpression<Integer> refundedAmount = orderProduct.productAmount.sum();
		queryFactory
			.select(refundedDate, refundedAmount)
			.from(orderProduct)
			.where(
				dateTimeGoe(orderProduct.refundedAt, startInclusive),
				dateTimeLt(orderProduct.refundedAt, endExclusive)
			)
			.groupBy(refundedDate)
			.fetch()
			.forEach(row -> dailyTransactions
				.computeIfAbsent(toLocalDate(row.get(refundedDate)), ignored -> new DailyTransactionAccumulator())
				.subtractRefund(valueOrZero(row.get(refundedAmount))));

		return dailyTransactions.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.map(entry -> new DailyTransactionProjection(
				entry.getKey(),
				entry.getValue().transactionCount,
				entry.getValue().transactionAmount
			))
			.toList();
	}

	private OrderListProjection toOrderListProjection(List<Tuple> rows) {
		Tuple first = rows.getFirst();
		int productCount = rows.size();
		Map<UUID, SellerSummaryAccumulator> sellerSummaries = new LinkedHashMap<>();
		rows.forEach(row -> sellerSummaries
			.computeIfAbsent(row.get(orderProduct.sellerId), ignored -> new SellerSummaryAccumulator())
			.addProduct(valueOrZero(row.get(orderProduct.productAmount))));

		return new OrderListProjection(
			first.get(order.id),
			formatProductTitle(first.get(orderProduct.productTitle), productCount),
			productCount,
			valueOrZero(first.get(order.totalOrderAmount)),
			first.get(order.orderStatus),
			first.get(order.createdAt),
			sellerSummaries.entrySet().stream()
				.map(entry -> new OrderListProjection.SellerSummary(
					entry.getKey(),
					entry.getValue().productCount,
					entry.getValue().orderAmount
				))
				.toList()
		);
	}

	private long sumCompletedOrderAmount(LocalDateTime startInclusive, LocalDateTime endExclusive) {
		Integer amount = queryFactory
			.select(order.totalOrderAmount.sum())
			.from(order)
			.where(
				dateTimeGoe(order.completedAt, startInclusive),
				dateTimeLt(order.completedAt, endExclusive)
			)
			.fetchOne();
		return valueOrZero(amount);
	}

	private long sumRefundedProductAmount(LocalDateTime startInclusive, LocalDateTime endExclusive) {
		Integer amount = queryFactory
			.select(orderProduct.productAmount.sum())
			.from(orderProduct)
			.where(
				dateTimeGoe(orderProduct.refundedAt, startInclusive),
				dateTimeLt(orderProduct.refundedAt, endExclusive)
			)
			.fetchOne();
		return valueOrZero(amount);
	}

	private BooleanExpression orderStatusEq(OrderStatus orderStatus) {
		return orderStatus == null ? null : order.orderStatus.eq(orderStatus);
	}

	private BooleanExpression dateTimeGoe(DateTimePath<LocalDateTime> path, LocalDateTime value) {
		return value == null ? null : path.goe(value);
	}

	private BooleanExpression dateTimeLt(DateTimePath<LocalDateTime> path, LocalDateTime value) {
		return value == null ? null : path.lt(value);
	}

	private DateExpression<java.sql.Date> toDate(DateTimePath<LocalDateTime> path) {
		return Expressions.dateTemplate(java.sql.Date.class, "cast({0} as date)", path);
	}

	private LocalDate toLocalDate(java.sql.Date date) {
		return date == null ? null : date.toLocalDate();
	}

	private long valueOrZero(Long value) {
		return value == null ? 0L : value;
	}

	private int valueOrZero(Integer value) {
		return value == null ? 0 : value;
	}

	private String formatProductTitle(String firstProductTitle, int totalProductCount) {
		if (totalProductCount <= 1) {
			return firstProductTitle;
		}
		return firstProductTitle + " 외 " + (totalProductCount - 1) + "건";
	}

	private static class DailyTransactionAccumulator {
		private long transactionCount;
		private long transactionAmount;

		private void addCompleted(long count, long amount) {
			this.transactionCount += count;
			this.transactionAmount += amount;
		}

		private void subtractRefund(long amount) {
			this.transactionAmount -= amount;
		}
	}

	private static class SellerSummaryAccumulator {
		private int productCount;
		private int orderAmount;

		private void addProduct(int productAmount) {
			this.productCount++;
			this.orderAmount += productAmount;
		}
	}
}
```

- [ ] **Step 9: 테스트 실행해서 통과 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.order.infrastructure.persistence.OrderQueryRepositoryImplTest"`
Expected: PASS (3 tests, QueryDSL이 `QOrder`/`QOrderProduct`를 어노테이션 프로세싱 단계에서 생성한다)

- [ ] **Step 10: 커밋**

```bash
git add admin-service/build.gradle \
  admin-service/src/main/java/com/prompthub/admin/order \
  admin-service/src/test/java/com/prompthub/admin/order/infrastructure/persistence/OrderQueryRepositoryImplTest.java \
  admin-service/src/test/resources/sql/orders.sql
git commit -m "$(cat <<'EOF'
feat: 어드민 주문 조회 QueryDSL 리포지토리 admin-service로 이관

- order-service AdminOrderQueryRepositoryImpl의 QueryDSL 로직(페이징+상태
  필터, 월간 순거래액, 일별 완료/환불 집계)을 admin-service로 1:1 이식
- order_service 소유 "order"/"order_product" 테이블을 admin-service 전용
  read-only 엔티티(Order, OrderProduct)로 재매핑 — 이번 3개 쿼리가 실제로
  참조하는 컬럼만 매핑하고 쓰기용 API(빌더/setter/create())는 두지 않음
- admin-service에 QueryDSL 의존성 신규 추가(order-service와 동일 버전)
- 테스트 픽스처는 엔티티 생성자 호출이 아니라 SQL(@Sql)로만 주입 —
  admin-service의 기존 SettlementQueryRepositoryAdapterTest 컨벤션과 통일
EOF
)"
```

---

### Task 2: SellerNickname 읽기 전용 엔티티 + SellerNicknameRepository

**Files:**
- Create: `admin-service/src/main/java/com/prompthub/admin/order/domain/model/SellerNickname.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/order/infrastructure/persistence/SellerNicknameRepository.java`
- Test: `admin-service/src/test/java/com/prompthub/admin/order/infrastructure/persistence/SellerNicknameRepositoryTest.java`
- Create: `admin-service/src/test/resources/sql/seller_nicknames.sql`

**Interfaces:**
- Consumes: 없음(독립 컴포넌트).
- Produces: `SellerNicknameRepository extends JpaRepository<SellerNickname, UUID>` — `findAllById(Iterable<UUID>): List<SellerNickname>`(Spring Data 기본 제공). `SellerNickname.getSellerId(): UUID`, `.getNickname(): String` — Task 3의 `OrderService`가 이 둘을 그대로 소비한다.

- [ ] **Step 1: 실패하는 테스트 + SQL 픽스처 작성**

`admin-service/src/test/resources/sql/seller_nicknames.sql`:

```sql
-- user-service 소유 "user" 테이블 재매핑 검증용 픽스처(판매자 닉네임 = user.name).
INSERT INTO "user" (user_id, name) VALUES
('cccccccc-0000-0000-0000-000000000001', '판매자A'),
('cccccccc-0000-0000-0000-000000000002', '판매자B');
```

`admin-service/src/test/java/com/prompthub/admin/order/infrastructure/persistence/SellerNicknameRepositoryTest.java`:

```java
package com.prompthub.admin.order.infrastructure.persistence;

import com.prompthub.admin.order.domain.model.SellerNickname;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Sql("/sql/seller_nicknames.sql")
class SellerNicknameRepositoryTest {

	@Autowired
	private SellerNicknameRepository repository;

	@Test
	void 존재하는_id만_닉네임과_함께_반환하고_없는_id는_조용히_빠진다() {
		List<SellerNickname> result = repository.findAllById(List.of(
			UUID.fromString("cccccccc-0000-0000-0000-000000000001"),
			UUID.fromString("cccccccc-0000-0000-0000-000000000002"),
			UUID.fromString("cccccccc-0000-0000-0000-000000000999")
		));

		assertThat(result).hasSize(2);
		assertThat(result).extracting(SellerNickname::getNickname)
			.containsExactlyInAnyOrder("판매자A", "판매자B");
	}
}
```

- [ ] **Step 2: 테스트 실행해서 컴파일 실패 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.order.infrastructure.persistence.SellerNicknameRepositoryTest"`
Expected: FAIL — `SellerNickname`, `SellerNicknameRepository` 클래스가 없어 컴파일 에러.

- [ ] **Step 3: SellerNickname 엔티티 + Repository 구현**

`admin-service/src/main/java/com/prompthub/admin/order/domain/model/SellerNickname.java`:

```java
package com.prompthub.admin.order.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * user-service 소유 "user" 테이블의 읽기 전용 재매핑. 판매자 닉네임(user.name)만 조회한다.
 * 판매자 식별자는 user-service 도메인 컨벤션에 따라 user.user_id 를 그대로 쓴다
 * (별도 seller 테이블 없음 — docs/domain-glossary/user.md 참고).
 */
@Entity
@Table(name = "\"user\"")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerNickname {

	@Id
	@Column(name = "user_id", columnDefinition = "uuid")
	private UUID sellerId;

	@Column(name = "name", length = 100, nullable = false)
	private String nickname;
}
```

`admin-service/src/main/java/com/prompthub/admin/order/infrastructure/persistence/SellerNicknameRepository.java`:

```java
package com.prompthub.admin.order.infrastructure.persistence;

import com.prompthub.admin.order.domain.model.SellerNickname;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SellerNicknameRepository extends JpaRepository<SellerNickname, UUID> {
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.order.infrastructure.persistence.SellerNicknameRepositoryTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add admin-service/src/main/java/com/prompthub/admin/order/domain/model/SellerNickname.java \
  admin-service/src/main/java/com/prompthub/admin/order/infrastructure/persistence/SellerNicknameRepository.java \
  admin-service/src/test/java/com/prompthub/admin/order/infrastructure/persistence/SellerNicknameRepositoryTest.java \
  admin-service/src/test/resources/sql/seller_nicknames.sql
git commit -m "$(cat <<'EOF'
feat: 판매자 닉네임 조회를 user 테이블 직접 읽기로 구현

- order-service의 SellerClient(gRPC 기본 + REST 서킷브레이커 fallback)를
  대체 — admin-service는 별도 인터서비스 클라이언트 없이 user_service
  스키마의 "user" 테이블을 own read-only 엔티티(SellerNickname)로 직접
  읽는다(settlement 이관 전례와 동일 패턴)
- 판매자 식별자·닉네임은 user.user_id / user.name — user-service 도메인
  컨벤션상 별도 seller 테이블이 없다는 점을 그대로 반영
- SellerNicknameRepository는 커스텀 쿼리 메서드 없이 Spring Data JPA
  기본 제공 findAllById만 사용(PK가 sellerId이므로 별도 메서드 불필요)
- 네트워크 호출·서킷브레이커가 없어져 실패 시나리오가 DB 조회 하나로 단순화됨
EOF
)"
```

---

### Task 3: Application 레이어 (OrderUseCase, OrderQueryService, OrderService)

**Files:**
- Create: `admin-service/src/main/java/com/prompthub/admin/order/application/usecase/OrderUseCase.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/order/application/service/OrderQueryService.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/order/application/service/OrderService.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/order/presentation/dto/response/OrderListResponse.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/order/presentation/dto/response/MonthlyTradeAmountResponse.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/order/presentation/dto/response/DailyTransactionResponse.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/order/presentation/dto/response/WeeklyTransactionResponse.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/order/presentation/dto/response/TransactionPeriodResponse.java`
- Test: `admin-service/src/test/java/com/prompthub/admin/order/application/service/OrderServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `OrderQueryService`(신규, 이 태스크에서 작성)가 감싸는 `OrderQueryRepository` 포트, Task 2의 `SellerNicknameRepository.findAllById(Iterable<UUID>): List<SellerNickname>`.
- Produces: `OrderUseCase.getOrders(OrderSearchCondition): Page<OrderListResponse>`, `.getMonthlyTransactionAmount(): MonthlyTradeAmountResponse`, `.getWeeklyTransactions(): WeeklyTransactionResponse` — Task 4의 `OrderController`가 그대로 주입받아 호출한다.

- [ ] **Step 1: 응답 DTO 작성**

`admin-service/src/main/java/com/prompthub/admin/order/presentation/dto/response/OrderListResponse.java`:

```java
package com.prompthub.admin.order.presentation.dto.response;

import com.prompthub.admin.order.domain.enums.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "관리자 주문 목록 항목 응답")
public record OrderListResponse(
	@Schema(description = "주문 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111")
	UUID orderId,
	@Schema(description = "주문에 포함된 판매자 수", example = "2")
	int sellerCount,
	@Schema(description = "판매자별 주문 상품 수와 금액 요약")
	List<SellerSummary> sellers,
	@Schema(description = "상품명. 다건 주문이면 첫 상품명 외 N건 형식", example = "면접 답변 프롬프트 외 2건")
	String productTitle,
	@Schema(description = "주문 상품 수", example = "3")
	int totalOrderCount,
	@Schema(description = "총 주문 금액", example = "15000")
	int totalOrderAmount,
	@Schema(description = "주문 상태", example = "PAID")
	OrderStatus orderStatus,
	@Schema(description = "주문 생성 일시", example = "2026-06-24T10:00:00")
	LocalDateTime createdAt
) {
	@Schema(description = "판매자별 주문 상품 요약")
	public record SellerSummary(
		@Schema(description = "판매자 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a2222")
		UUID sellerId,
		@Schema(description = "판매자 닉네임", example = "prompt-seller")
		String sellerNickname,
		@Schema(description = "해당 판매자의 주문 상품 수", example = "2")
		int productCount,
		@Schema(description = "해당 판매자의 주문 상품 금액", example = "30000")
		int orderAmount
	) {
	}
}
```

`admin-service/src/main/java/com/prompthub/admin/order/presentation/dto/response/MonthlyTradeAmountResponse.java`:

```java
package com.prompthub.admin.order.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 월간 실제 거래액 응답")
public record MonthlyTradeAmountResponse(
	@Schema(description = "이번 달 실제 거래액", example = "1250000")
	long monthlyTransactionAmount
) {
}
```

`admin-service/src/main/java/com/prompthub/admin/order/presentation/dto/response/DailyTransactionResponse.java`:

```java
package com.prompthub.admin.order.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "관리자 일자별 거래 통계 응답")
public record DailyTransactionResponse(
	@Schema(description = "거래 일자", example = "2026-06-24")
	LocalDate date,
	@Schema(description = "결제 승인 완료 주문 수", example = "5")
	long transactionCount,
	@Schema(description = "실제 거래액", example = "120000")
	long transactionAmount
) {
}
```

`admin-service/src/main/java/com/prompthub/admin/order/presentation/dto/response/TransactionPeriodResponse.java`:

```java
package com.prompthub.admin.order.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "관리자 거래 통계 조회 기간 응답")
public record TransactionPeriodResponse(
	@Schema(description = "조회 시작일", example = "2026-06-18")
	LocalDate startDate,
	@Schema(description = "조회 종료일", example = "2026-06-24")
	LocalDate endDate
) {
}
```

`admin-service/src/main/java/com/prompthub/admin/order/presentation/dto/response/WeeklyTransactionResponse.java`:

```java
package com.prompthub.admin.order.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "관리자 최근 7일 거래량 응답")
public record WeeklyTransactionResponse(
	@Schema(description = "최근 7일 결제 승인 완료 주문 수", example = "42")
	long totalTransactionCount,
	@Schema(description = "최근 7일 실제 거래액", example = "980000")
	long totalTransactionAmount,
	TransactionPeriodResponse period,
	List<DailyTransactionResponse> dailyTransactions
) {
}
```

- [ ] **Step 2: OrderUseCase 포트 작성**

`admin-service/src/main/java/com/prompthub/admin/order/application/usecase/OrderUseCase.java`:

```java
package com.prompthub.admin.order.application.usecase;

import com.prompthub.admin.order.presentation.dto.request.OrderSearchCondition;
import com.prompthub.admin.order.presentation.dto.response.MonthlyTradeAmountResponse;
import com.prompthub.admin.order.presentation.dto.response.OrderListResponse;
import com.prompthub.admin.order.presentation.dto.response.WeeklyTransactionResponse;
import org.springframework.data.domain.Page;

public interface OrderUseCase {

	Page<OrderListResponse> getOrders(OrderSearchCondition condition);

	MonthlyTradeAmountResponse getMonthlyTransactionAmount();

	WeeklyTransactionResponse getWeeklyTransactions();
}
```

- [ ] **Step 3: 실패하는 OrderServiceTest 작성**

`admin-service/src/test/java/com/prompthub/admin/order/application/service/OrderServiceTest.java`:

```java
package com.prompthub.admin.order.application.service;

import com.prompthub.admin.order.application.dto.DailyTransactionProjection;
import com.prompthub.admin.order.application.dto.OrderListProjection;
import com.prompthub.admin.order.domain.enums.OrderStatus;
import com.prompthub.admin.order.domain.model.SellerNickname;
import com.prompthub.admin.order.infrastructure.persistence.SellerNicknameRepository;
import com.prompthub.admin.order.presentation.dto.request.OrderSearchCondition;
import com.prompthub.admin.order.presentation.dto.response.MonthlyTradeAmountResponse;
import com.prompthub.admin.order.presentation.dto.response.OrderListResponse;
import com.prompthub.admin.order.presentation.dto.response.WeeklyTransactionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

	private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
	private static final UUID SELLER_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000201");
	private static final UUID SELLER_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000202");
	private static final String PRODUCT_TITLE_1 = "프롬프트 상품 1";

	@Mock
	private OrderQueryService orderQueryService;

	@Mock
	private SellerNicknameRepository sellerNicknameRepository;

	@InjectMocks
	private OrderService orderService;

	@Nested
	@DisplayName("관리자 주문 목록 조회")
	class GetOrders {

		@Test
		@DisplayName("한 주문의 모든 판매자를 한 번의 bulk 조회로 닉네임과 함께 매핑한다")
		void getOrders_mapsAllSellersWithSingleBulkLookup() {
			OrderSearchCondition condition = new OrderSearchCondition("ALL", 1, 20);
			OrderListProjection projection = orderProjection(
				ORDER_ID,
				List.of(
					new OrderListProjection.SellerSummary(SELLER_ID_1, 2, 30_000),
					new OrderListProjection.SellerSummary(SELLER_ID_2, 1, 15_000)
				)
			);
			given(orderQueryService.searchOrders(any(), any()))
				.willReturn(new PageImpl<>(List.of(projection), PageRequest.of(0, 20), 1));
			given(sellerNicknameRepository.findAllById(List.of(SELLER_ID_1, SELLER_ID_2)))
				.willReturn(List.of(nickname(SELLER_ID_1, "판매자A")));

			Page<OrderListResponse> response = orderService.getOrders(condition.resolve());

			assertThat(response.getContent()).hasSize(1);
			assertThat(response.getContent().getFirst().sellers()).containsExactly(
				new OrderListResponse.SellerSummary(SELLER_ID_1, "판매자A", 2, 30_000),
				new OrderListResponse.SellerSummary(SELLER_ID_2, "알 수 없음", 1, 15_000)
			);
		}

		@Test
		@DisplayName("주문 목록이 비어 있으면 판매자 조회를 생략한다")
		void getOrders_emptyOrders_skipsSellerLookup() {
			OrderSearchCondition condition = new OrderSearchCondition("ALL", 1, 20);
			given(orderQueryService.searchOrders(any(), any()))
				.willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

			Page<OrderListResponse> response = orderService.getOrders(condition.resolve());

			assertThat(response.getContent()).isEmpty();
			then(sellerNicknameRepository).should(never()).findAllById(any());
		}
	}

	@Test
	@DisplayName("이번 달 실제 거래액을 조회한다")
	void getMonthlyTransactionAmount_success() {
		given(orderQueryService.sumMonthlyTransactionAmount(any(), any()))
			.willReturn(25_000L);

		MonthlyTradeAmountResponse response = orderService.getMonthlyTransactionAmount();

		assertThat(response.monthlyTransactionAmount()).isEqualTo(25_000L);
	}

	@Test
	@DisplayName("최근 7일 거래량은 누락된 날짜를 0으로 채우고 합계를 계산한다")
	void getWeeklyTransactions_success() {
		LocalDate today = LocalDate.now();
		given(orderQueryService.findDailyTransactions(any(), any()))
			.willReturn(List.of(new DailyTransactionProjection(today, 2L, 30_000L)));

		WeeklyTransactionResponse response = orderService.getWeeklyTransactions();

		assertThat(response.dailyTransactions()).hasSize(7);
		assertThat(response.totalTransactionCount()).isEqualTo(2L);
		assertThat(response.totalTransactionAmount()).isEqualTo(30_000L);
	}

	private SellerNickname nickname(UUID sellerId, String nickname) {
		// SellerNickname 은 protected 기본 생성자만 가진 읽기 전용 엔티티라
		// 다른 패키지의 테스트에서는 리플렉션으로만 인스턴스를 만들 수 있다.
		try {
			var constructor = SellerNickname.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			SellerNickname entity = constructor.newInstance();
			org.springframework.test.util.ReflectionTestUtils.setField(entity, "sellerId", sellerId);
			org.springframework.test.util.ReflectionTestUtils.setField(entity, "nickname", nickname);
			return entity;
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private OrderListProjection orderProjection(UUID orderId, List<OrderListProjection.SellerSummary> sellers) {
		return new OrderListProjection(
			orderId,
			PRODUCT_TITLE_1,
			2,
			45_000,
			OrderStatus.COMPLETED,
			LocalDateTime.of(2026, 6, 24, 10, 0),
			sellers
		);
	}
}
```

- [ ] **Step 4: 테스트 실행해서 컴파일 실패 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.order.application.service.OrderServiceTest"`
Expected: FAIL — `OrderQueryService`, `OrderService` 클래스가 없어 컴파일 에러.

- [ ] **Step 5: OrderQueryService + OrderService 구현**

`admin-service/src/main/java/com/prompthub/admin/order/application/service/OrderQueryService.java`:

```java
package com.prompthub.admin.order.application.service;

import com.prompthub.admin.order.application.dto.DailyTransactionProjection;
import com.prompthub.admin.order.application.dto.OrderListProjection;
import com.prompthub.admin.order.domain.repository.OrderQueryRepository;
import com.prompthub.admin.order.presentation.dto.request.OrderSearchCondition;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderQueryService {

	private final OrderQueryRepository orderQueryRepository;

	public Page<OrderListProjection> searchOrders(OrderSearchCondition condition, PageRequest pageable) {
		return orderQueryRepository.searchOrders(condition, pageable);
	}

	public long sumMonthlyTransactionAmount(LocalDateTime startInclusive, LocalDateTime endExclusive) {
		return orderQueryRepository.sumMonthlyTransactionAmount(startInclusive, endExclusive);
	}

	public List<DailyTransactionProjection> findDailyTransactions(
		LocalDateTime startInclusive,
		LocalDateTime endExclusive
	) {
		return orderQueryRepository.findDailyTransactions(startInclusive, endExclusive);
	}
}
```

`admin-service/src/main/java/com/prompthub/admin/order/application/service/OrderService.java`:

```java
package com.prompthub.admin.order.application.service;

import com.prompthub.admin.order.application.dto.DailyTransactionProjection;
import com.prompthub.admin.order.application.dto.OrderListProjection;
import com.prompthub.admin.order.application.usecase.OrderUseCase;
import com.prompthub.admin.order.domain.model.SellerNickname;
import com.prompthub.admin.order.infrastructure.persistence.SellerNicknameRepository;
import com.prompthub.admin.order.presentation.dto.request.OrderSearchCondition;
import com.prompthub.admin.order.presentation.dto.response.DailyTransactionResponse;
import com.prompthub.admin.order.presentation.dto.response.MonthlyTradeAmountResponse;
import com.prompthub.admin.order.presentation.dto.response.OrderListResponse;
import com.prompthub.admin.order.presentation.dto.response.TransactionPeriodResponse;
import com.prompthub.admin.order.presentation.dto.response.WeeklyTransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService implements OrderUseCase {

	private static final int RECENT_DAYS = 7;
	private static final String UNKNOWN_SELLER_NICKNAME = "알 수 없음";

	private final OrderQueryService orderQueryService;
	private final SellerNicknameRepository sellerNicknameRepository;

	@Override
	public Page<OrderListResponse> getOrders(OrderSearchCondition condition) {
		PageRequest pageable = PageRequest.of(
			condition.page() - 1,
			condition.size(),
			Sort.by(Sort.Direction.DESC, "createdAt")
		);
		Page<OrderListProjection> orders = orderQueryService.searchOrders(condition, pageable);
		Set<UUID> sellerIds = collectSellerIds(orders.getContent());
		Map<UUID, String> sellerNicknames = sellerIds.isEmpty()
			? Map.of()
			: sellerNicknameRepository.findAllById(sellerIds).stream()
				.collect(Collectors.toMap(
					SellerNickname::getSellerId,
					SellerNickname::getNickname,
					(existing, ignored) -> existing
				));

		return orders.map(projection -> toOrderListResponse(projection, sellerNicknames));
	}

	@Override
	public MonthlyTradeAmountResponse getMonthlyTransactionAmount() {
		LocalDate today = LocalDate.now();
		LocalDateTime start = today.withDayOfMonth(1).atStartOfDay();
		LocalDateTime endExclusive = today.plusMonths(1).withDayOfMonth(1).atStartOfDay();

		return new MonthlyTradeAmountResponse(
			orderQueryService.sumMonthlyTransactionAmount(start, endExclusive)
		);
	}

	@Override
	public WeeklyTransactionResponse getWeeklyTransactions() {
		LocalDate endDate = LocalDate.now();
		LocalDate startDate = endDate.minusDays(RECENT_DAYS - 1L);
		LocalDateTime start = startDate.atStartOfDay();
		LocalDateTime endExclusive = endDate.plusDays(1).atStartOfDay();

		Map<LocalDate, DailyTransactionProjection> dailyTransactions = new LinkedHashMap<>();
		orderQueryService.findDailyTransactions(start, endExclusive)
			.forEach(dailyTransaction -> dailyTransactions.put(dailyTransaction.date(), dailyTransaction));

		List<DailyTransactionResponse> responses = startDate.datesUntil(endDate.plusDays(1))
			.map(date -> toDailyTransactionResponse(date, dailyTransactions.get(date)))
			.toList();

		long totalTransactionCount = responses.stream()
			.mapToLong(DailyTransactionResponse::transactionCount)
			.sum();
		long totalTransactionAmount = responses.stream()
			.mapToLong(DailyTransactionResponse::transactionAmount)
			.sum();

		return new WeeklyTransactionResponse(
			totalTransactionCount,
			totalTransactionAmount,
			new TransactionPeriodResponse(startDate, endDate),
			responses
		);
	}

	private OrderListResponse toOrderListResponse(
		OrderListProjection projection,
		Map<UUID, String> sellerNicknames
	) {
		List<OrderListResponse.SellerSummary> sellers = projection.sellers().stream()
			.map(seller -> new OrderListResponse.SellerSummary(
				seller.sellerId(),
				sellerNicknames.getOrDefault(seller.sellerId(), UNKNOWN_SELLER_NICKNAME),
				seller.productCount(),
				seller.orderAmount()
			))
			.toList();

		return new OrderListResponse(
			projection.orderId(),
			sellers.size(),
			sellers,
			projection.productTitle(),
			projection.totalOrderCount(),
			projection.totalOrderAmount(),
			projection.orderStatus(),
			projection.createdAt()
		);
	}

	private Set<UUID> collectSellerIds(List<OrderListProjection> orders) {
		if (orders.isEmpty()) {
			return Set.of();
		}
		return orders.stream()
			.flatMap(order -> order.sellers().stream())
			.map(OrderListProjection.SellerSummary::sellerId)
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private DailyTransactionResponse toDailyTransactionResponse(
		LocalDate date,
		DailyTransactionProjection projection
	) {
		if (projection == null) {
			return new DailyTransactionResponse(date, 0L, 0L);
		}
		return new DailyTransactionResponse(
			date,
			projection.transactionCount(),
			projection.transactionAmount()
		);
	}
}
```

- [ ] **Step 6: 테스트 실행해서 통과 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.order.application.service.OrderServiceTest"`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add admin-service/src/main/java/com/prompthub/admin/order/application \
  admin-service/src/main/java/com/prompthub/admin/order/presentation/dto/response \
  admin-service/src/test/java/com/prompthub/admin/order/application/service/OrderServiceTest.java
git commit -m "$(cat <<'EOF'
feat: 어드민 주문 애플리케이션 서비스 admin-service로 이관

- order-service AdminOrderService/AdminOrderQueryService 로직을
  OrderService/OrderQueryService로 이관("Admin" 접두사 제거, settlement
  컨벤션과 통일). 월간·주간 집계, seller 닉네임 bulk 조회·매핑 로직은
  동일하게 유지
- seller 닉네임 조회만 SellerClient.getSellerNicknames(List) 호출에서
  SellerNicknameRepository.findAllById(Set) 호출로 교체 — Set을 List로
  변환하던 원본 코드는 findAllById가 Iterable을 받아 불필요해져 제거
- 응답 DTO 5종(OrderListResponse 등)을 admin-service 패키지로 이관,
  Swagger 문서 문구는 원본 그대로 유지
- OrderService는 클래스 레벨 @Transactional을 두지 않고, 조회 전용
  OrderQueryService만 @Transactional(readOnly = true)를 갖는 구조를
  원본과 동일하게 유지(seller 닉네임 조회가 트랜잭션 밖에서 일어나게 함).
  단 이 구조 자체를 리플렉션으로 검증하던 원본의 2개 테스트
  (adminOrderService_hasNoClassLevelTransaction 등)는 이번 테스트
  파일에 옮기지 않았다 — 필요하면 별도로 추가할 것
EOF
)"
```

---

### Task 4: OrderController (프레젠테이션 레이어)

**Files:**
- Create: `admin-service/src/main/java/com/prompthub/admin/order/presentation/controller/OrderController.java`
- Test: `admin-service/src/test/java/com/prompthub/admin/order/presentation/controller/OrderControllerTest.java`

**Interfaces:**
- Consumes: Task 3의 `OrderUseCase`(`getOrders`, `getMonthlyTransactionAmount`, `getWeeklyTransactions`), 공통 모듈의 `com.prompthub.presentation.dto.ApiResult`, `com.prompthub.presentation.dto.PageResponse`.
- Produces: `GET ${api.init}/admin/orders`, `GET ${api.init}/admin/orders/month`, `GET ${api.init}/admin/orders/weekend` — 이 태스크가 마지막 신규 코드 레이어이며, 이후 태스크는 게이트웨이 라우팅과 order-service 삭제만 다룬다.

- [ ] **Step 1: 실패하는 OrderControllerTest 작성**

`admin-service/src/test/java/com/prompthub/admin/order/presentation/controller/OrderControllerTest.java`:

```java
package com.prompthub.admin.order.presentation.controller;

import com.prompthub.admin.order.application.usecase.OrderUseCase;
import com.prompthub.admin.order.domain.enums.OrderStatus;
import com.prompthub.admin.order.presentation.dto.response.DailyTransactionResponse;
import com.prompthub.admin.order.presentation.dto.response.MonthlyTradeAmountResponse;
import com.prompthub.admin.order.presentation.dto.response.OrderListResponse;
import com.prompthub.admin.order.presentation.dto.response.TransactionPeriodResponse;
import com.prompthub.admin.order.presentation.dto.response.WeeklyTransactionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@ActiveProfiles("test")
class OrderControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OrderUseCase orderUseCase;

	@Test
	void 전체_주문_목록을_조회한다() throws Exception {
		OrderListResponse order = new OrderListResponse(
			UUID.fromString("00000000-0000-0000-0000-000000000501"),
			1,
			List.of(new OrderListResponse.SellerSummary(
				UUID.fromString("00000000-0000-0000-0000-000000000201"), "판매자A", 2, 30_000
			)),
			"프롬프트 상품 1",
			2,
			30_000,
			OrderStatus.COMPLETED,
			LocalDateTime.of(2026, 6, 24, 10, 0)
		);
		when(orderUseCase.getOrders(eq(new com.prompthub.admin.order.presentation.dto.request.OrderSearchCondition("ALL", 1, 20).resolve())))
			.thenReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 20), 1));

		mockMvc.perform(get("/api/v2/admin/orders")
				.param("orderStatus", "ALL")
				.param("page", "1")
				.param("size", "20"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].sellerCount").value(1))
			.andExpect(jsonPath("$.data[0].sellers[0].sellerNickname").value("판매자A"))
			.andExpect(jsonPath("$.meta.total").value(1));
	}

	@Test
	void 존재하지_않는_주문_상태는_400을_내려준다() throws Exception {
		mockMvc.perform(get("/api/v2/admin/orders").param("orderStatus", "UNKNOWN"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("A-001"));
	}

	@Test
	void 이번_달_실제_거래액을_조회한다() throws Exception {
		when(orderUseCase.getMonthlyTransactionAmount())
			.thenReturn(new MonthlyTradeAmountResponse(25_000L));

		mockMvc.perform(get("/api/v2/admin/orders/month"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.monthlyTransactionAmount").value(25_000L));
	}

	@Test
	void 최근_7일_거래량을_조회한다() throws Exception {
		when(orderUseCase.getWeeklyTransactions())
			.thenReturn(new WeeklyTransactionResponse(
				2L,
				30_000L,
				new TransactionPeriodResponse(LocalDate.of(2026, 6, 18), LocalDate.of(2026, 6, 24)),
				List.of(new DailyTransactionResponse(LocalDate.of(2026, 6, 24), 2L, 30_000L))
			));

		mockMvc.perform(get("/api/v2/admin/orders/weekend"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalTransactionCount").value(2))
			.andExpect(jsonPath("$.data.period.startDate").value("2026-06-18"));
	}
}
```

- [ ] **Step 2: 테스트 실행해서 컴파일 실패 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.order.presentation.controller.OrderControllerTest"`
Expected: FAIL — `OrderController` 클래스가 없어 컴파일 에러.

- [ ] **Step 3: OrderController 구현**

`admin-service/src/main/java/com/prompthub/admin/order/presentation/controller/OrderController.java`:

```java
package com.prompthub.admin.order.presentation.controller;

import com.prompthub.admin.order.application.usecase.OrderUseCase;
import com.prompthub.admin.order.presentation.dto.request.OrderSearchCondition;
import com.prompthub.admin.order.presentation.dto.response.MonthlyTradeAmountResponse;
import com.prompthub.admin.order.presentation.dto.response.OrderListResponse;
import com.prompthub.admin.order.presentation.dto.response.WeeklyTransactionResponse;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.presentation.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${api.init}/admin/orders")
@RequiredArgsConstructor
@Tag(name = "Admin Order", description = "관리자 주문 관리 API (order-service 에서 이관)")
@SecurityRequirement(name = "gatewayHeaders")
public class OrderController {

	private final OrderUseCase orderUseCase;

	@GetMapping
	@Operation(summary = "관리자 전체 주문 목록 조회", description = "관리자가 전체 주문을 상태 조건과 페이지 조건으로 조회합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "관리자 주문 목록 조회 성공"),
		@ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음"),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
	})
	public PageResponse<OrderListResponse> getOrders(@ModelAttribute OrderSearchCondition condition) {
		OrderSearchCondition resolvedCondition = condition.resolve();
		Page<OrderListResponse> orders = orderUseCase.getOrders(resolvedCondition);

		return PageResponse.success(
			orders.getContent(),
			resolvedCondition.page(),
			resolvedCondition.size(),
			orders.getTotalElements(),
			orders.hasNext()
		);
	}

	@GetMapping("/month")
	@Operation(summary = "이번 달 실제 거래액 조회", description = "이번 달 승인 금액에서 취소/환불 금액을 차감한 실제 거래액을 조회합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "월간 실제 거래액 조회 성공"),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음"),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
	})
	public ApiResult<MonthlyTradeAmountResponse> getMonthlyTransactionAmount() {
		return ApiResult.success(orderUseCase.getMonthlyTransactionAmount());
	}

	@GetMapping("/weekend")
	@Operation(summary = "최근 7일 거래량 조회", description = "최근 7일의 일자별 결제 승인 건수와 실제 거래액을 조회합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "최근 7일 거래량 조회 성공"),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음"),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
	})
	public ApiResult<WeeklyTransactionResponse> getWeeklyTransactions() {
		return ApiResult.success(orderUseCase.getWeeklyTransactions());
	}
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.order.presentation.controller.OrderControllerTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add admin-service/src/main/java/com/prompthub/admin/order/presentation/controller/OrderController.java \
  admin-service/src/test/java/com/prompthub/admin/order/presentation/controller/OrderControllerTest.java
git commit -m "$(cat <<'EOF'
feat: 어드민 주문 컨트롤러 admin-service로 이관(api/v2)

- order-service AdminOrderController의 엔드포인트 3개(목록/월간/주간)를
  OrderController로 이관, "Admin" 접두사 제거(settlement 컨벤션과 통일)
- 경로는 order-service처럼 "/api/v1/admin/orders"를 하드코딩하지 않고
  settlement와 동일하게 "${api.init}/admin/orders"로 매핑 — admin-service
  기준 api.init=/api/v2 이므로 실제 경로는 "/api/v2/admin/orders"가 됨
  (design.md 결정 6 — v1 → v2 전환은 프로젝트 전반의 기존 방향과 일치)
- 이 변경으로 기존에 v1로 이 API를 호출하던 클라이언트는 v2로 바꿔야
  한다 — 이 커밋만으로는 아직 영향 없음(order-service 쪽 v1 경로는
  Task 5에서 게이트웨이 라우트를 옮기기 전까지 계속 살아있음)
- 컨트롤러 테스트는 @WebMvcTest + @MockitoBean 조합(admin-service
  SettlementControllerTest와 동일한 테스트 컨벤션)으로 작성해 ${api.init}
  프로퍼티가 실제로 해석되는 것까지 검증
EOF
)"
```

---

### Task 5: 게이트웨이 라우트 소유권 이전

**Files:**
- Modify: `apigateway/src/main/java/com/prompthub/apigateway/route/VersionedServiceRoute.java`
- Modify: `apigateway/src/test/java/com/prompthub/apigateway/route/VersionedRouteDefinitionLocatorTest.java`

**Interfaces:**
- Consumes: 없음(순수 라우팅 설정 변경, admin-service/order-service 코드와 컴파일 의존성 없음).
- Produces: 없음(최종 라우팅 동작 변경).

- [ ] **Step 1: 실패하는 라우트 테스트 추가**

`VersionedRouteDefinitionLocatorTest.java`의 마지막 테스트 뒤에 아래 테스트를 추가한다(클래스 닫는 `}` 바로 앞):

```java
	@Test
	void 어드민_주문_경로는_admin_service가_소유하고_order_service는_소유하지_않는다() {
		Map<String, List<String>> config = new LinkedHashMap<>();
		config.put("admin-service", List.of("v2"));
		config.put("order-service", List.of("v1", "v2"));

		List<RouteDefinition> definitions = VersionedRouteDefinitionLocator.buildRouteDefinitions(propertiesOf(config));

		String adminPattern = pathPredicateValue(routeById(definitions, "admin-service"));
		String orderPattern = pathPredicateValue(routeById(definitions, "order-service"));
		assertThat(adminPattern).contains("/api/v2/admin/orders");
		assertThat(orderPattern).doesNotContain("/admin/orders");
	}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew :apigateway:test --tests "com.prompthub.apigateway.route.VersionedRouteDefinitionLocatorTest"`
Expected: FAIL — `admin-service` 패턴에 아직 `/admin/orders`가 없고, `order-service` 패턴에 여전히 `/admin/orders`가 남아있어 두 assertion 모두 실패.

- [ ] **Step 3: VersionedServiceRoute.java에서 경로 소유권 이전**

`apigateway/src/main/java/com/prompthub/apigateway/route/VersionedServiceRoute.java`에서 `admin-service`와 `order-service` 항목을 다음으로 교체한다:

```java
        new VersionedServiceRoute(
            "admin-service",
            "lb://ADMIN-SERVICE",
            List.of("/admin/settlements/**", "/admin/orders", "/admin/orders/**"),
            1
        ),
        new VersionedServiceRoute(
            "order-service",
            "lb://ORDER-SERVICE",
            List.of(
                "/orders", "/orders/**",
                "/cart", "/cart/**"
            ),
            2
        ),
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew :apigateway:test --tests "com.prompthub.apigateway.route.VersionedRouteDefinitionLocatorTest"`
Expected: PASS(전체 6개 테스트)

- [ ] **Step 5: 커밋**

```bash
git add apigateway/src/main/java/com/prompthub/apigateway/route/VersionedServiceRoute.java \
  apigateway/src/test/java/com/prompthub/apigateway/route/VersionedRouteDefinitionLocatorTest.java
git commit -m "$(cat <<'EOF'
feat: 어드민 주문 경로 라우팅을 order-service에서 admin-service로 이전

- VersionedServiceRoute.ALL에서 "/admin/orders", "/admin/orders/**"를
  order-service 항목의 pathSuffixes에서 제거하고 admin-service 항목으로
  이동 — settlement-service → admin-service 이관 때와 동일한 방식으로
  경로 소유권만 코드 레벨에서 옮긴다(런타임 설정 변경 아님)
- admin-service 항목의 order 값(1)은 order-service(2)보다 여전히 앞서고,
  admin-service 항목이 settlement-service 배치 경로(order 0)보다는 뒤라
  기존 매칭 우선순위(주석에 명시된 settlement 배치 > admin 상위 경로)에
  영향 없음
- 이 커밋 이후 게이트웨이는 "/api/v1/admin/orders/**"와
  "/api/v2/admin/orders/**" 요청을 모두 admin-service로 넘긴다. admin-
  service 컨트롤러는 v2만 매핑하므로(Task 4) v1 요청은 404가 된다 —
  design.md 결정 6에서 이미 합의된 의도된 동작이며, 이 시점부터 실제로
  적용된다(따라서 이 태스크와 Task 6은 같은 배포 단위로 함께 나가야 함)
- 회귀 방지 테스트 1개 추가: admin-service 패턴에 "/admin/orders"가
  있고 order-service 패턴에는 없음을 확인
EOF
)"
```

---

### Task 6: order-service 어드민 주문 코드 삭제

**Files:**
- Delete: `order-service/src/main/java/com/prompthub/order/presentation/AdminOrderController.java`
- Delete: `order-service/src/main/java/com/prompthub/order/application/usecase/AdminOrderUseCase.java`
- Delete: `order-service/src/main/java/com/prompthub/order/application/service/admin/AdminOrderService.java`
- Delete: `order-service/src/main/java/com/prompthub/order/application/service/admin/AdminOrderQueryService.java`
- Delete: `order-service/src/main/java/com/prompthub/order/application/dto/AdminOrderListProjection.java`
- Delete: `order-service/src/main/java/com/prompthub/order/application/dto/AdminDailyTransactionProjection.java`
- Delete: `order-service/src/main/java/com/prompthub/order/presentation/dto/request/AdminOrderSearchCondition.java`
- Delete: `order-service/src/main/java/com/prompthub/order/presentation/dto/response/AdminOrderListResponse.java`
- Delete: `order-service/src/main/java/com/prompthub/order/presentation/dto/response/AdminMonthlyTradeAmountResponse.java`
- Delete: `order-service/src/main/java/com/prompthub/order/presentation/dto/response/AdminWeeklyTransactionResponse.java`
- Delete: `order-service/src/main/java/com/prompthub/order/presentation/dto/response/AdminDailyTransactionResponse.java`
- Delete: `order-service/src/main/java/com/prompthub/order/presentation/dto/response/AdminTransactionPeriodResponse.java`
- Delete: `order-service/src/main/java/com/prompthub/order/domain/repository/AdminOrderQueryRepository.java`
- Delete: `order-service/src/main/java/com/prompthub/order/infra/persistence/order/AdminOrderQueryRepositoryImpl.java`
- Delete: `order-service/src/main/java/com/prompthub/order/application/client/SellerClient.java`
- Delete: `order-service/src/main/java/com/prompthub/order/infra/grpc/client/seller/SellerGrpcClientAdapter.java`
- Delete: `order-service/src/main/java/com/prompthub/order/infra/grpc/client/seller/SellerGrpcClientConfig.java`
- Delete: `order-service/src/main/java/com/prompthub/order/infra/rest/client/SellerRestFallbackClient.java`
- Delete: `order-service/src/test/java/com/prompthub/order/presentation/AdminOrderControllerTest.java`
- Delete: `order-service/src/test/java/com/prompthub/order/application/service/admin/AdminOrderServiceTest.java`
- Delete: `order-service/src/test/java/com/prompthub/order/infra/persistence/AdminOrderQueryRepositoryImplTest.java`
- Modify: `order-service/build.gradle`

**Interfaces:**
- Consumes: 없음(삭제 전용 태스크, Task 1~5가 admin-service에 동등 기능을 이미 제공한 뒤에만 실행).
- Produces: 없음.

- [ ] **Step 1: order-service 어드민 주문 소스 삭제**

```bash
rm order-service/src/main/java/com/prompthub/order/presentation/AdminOrderController.java
rm order-service/src/main/java/com/prompthub/order/application/usecase/AdminOrderUseCase.java
rm order-service/src/main/java/com/prompthub/order/application/service/admin/AdminOrderService.java
rm order-service/src/main/java/com/prompthub/order/application/service/admin/AdminOrderQueryService.java
rm order-service/src/main/java/com/prompthub/order/application/dto/AdminOrderListProjection.java
rm order-service/src/main/java/com/prompthub/order/application/dto/AdminDailyTransactionProjection.java
rm order-service/src/main/java/com/prompthub/order/presentation/dto/request/AdminOrderSearchCondition.java
rm order-service/src/main/java/com/prompthub/order/presentation/dto/response/AdminOrderListResponse.java
rm order-service/src/main/java/com/prompthub/order/presentation/dto/response/AdminMonthlyTradeAmountResponse.java
rm order-service/src/main/java/com/prompthub/order/presentation/dto/response/AdminWeeklyTransactionResponse.java
rm order-service/src/main/java/com/prompthub/order/presentation/dto/response/AdminDailyTransactionResponse.java
rm order-service/src/main/java/com/prompthub/order/presentation/dto/response/AdminTransactionPeriodResponse.java
rm order-service/src/main/java/com/prompthub/order/domain/repository/AdminOrderQueryRepository.java
rm order-service/src/main/java/com/prompthub/order/infra/persistence/order/AdminOrderQueryRepositoryImpl.java
rm order-service/src/main/java/com/prompthub/order/application/client/SellerClient.java
rm order-service/src/main/java/com/prompthub/order/infra/grpc/client/seller/SellerGrpcClientAdapter.java
rm order-service/src/main/java/com/prompthub/order/infra/grpc/client/seller/SellerGrpcClientConfig.java
rm order-service/src/main/java/com/prompthub/order/infra/rest/client/SellerRestFallbackClient.java
rmdir order-service/src/main/java/com/prompthub/order/application/service/admin 2>/dev/null || true
rmdir order-service/src/main/java/com/prompthub/order/infra/grpc/client/seller 2>/dev/null || true
rmdir order-service/src/main/java/com/prompthub/order/infra/rest/client 2>/dev/null || true
```

- [ ] **Step 2: 대응 테스트 삭제**

```bash
rm order-service/src/test/java/com/prompthub/order/presentation/AdminOrderControllerTest.java
rm order-service/src/test/java/com/prompthub/order/application/service/admin/AdminOrderServiceTest.java
rm order-service/src/test/java/com/prompthub/order/infra/persistence/AdminOrderQueryRepositoryImplTest.java
rmdir order-service/src/test/java/com/prompthub/order/application/service/admin 2>/dev/null || true
```

- [ ] **Step 3: order-service build.gradle에서 grpc/user proto srcDir 제거**

`order-service/build.gradle`의 `sourceSets` 블록을 다음으로 교체한다(`grpc/user` 줄만 제거):

```gradle
sourceSets {
    main {
        proto {
            srcDir "${rootProject.projectDir}/grpc/order"
            srcDir "${rootProject.projectDir}/grpc/product"
        }
    }
}
```

- [ ] **Step 4: order-service 전체 빌드로 삭제 이후 컴파일·테스트 확인**

Run: `./gradlew :order-service:build`
Expected: BUILD SUCCESSFUL — 삭제한 클래스를 참조하는 곳이 남아있지 않고, 남은 일반 주문 API 테스트가 전부 통과한다. 실패하면 에러가 가리키는 참조를 찾아 정리한다(단, 일반 주문 도메인 코드는 변경하지 않는다).

- [ ] **Step 5: 커밋**

```bash
git add -A order-service
git commit -m "$(cat <<'EOF'
refactor: order-service 어드민 주문 API 및 seller 클라이언트 제거(admin-service로 이관 완료)

- 어드민 주문 컨트롤러/유스케이스/서비스/리포지토리/DTO 12개 파일과
  대응 테스트 3개를 삭제 — admin-service가 Task 1~5에서 동등 기능을
  이미 제공하므로 order-service 쪽 구현은 더 이상 필요 없음
- SellerClient 인터페이스와 두 구현체(SellerGrpcClientAdapter gRPC
  기본, SellerRestFallbackClient 서킷브레이커 fallback), 관련 gRPC 설정
  (SellerGrpcClientConfig)까지 함께 제거 — AdminOrderService 외에는
  아무 곳에서도 참조하지 않는 것을 사전에 grep으로 확인했다(design.md
  "현재 상태(order-service)" 참고)
- order-service build.gradle의 proto sourceSets에서 "grpc/user" srcDir
  제거 — SellerGrpcClientAdapter가 유일한 소비자였으므로 더 이상 이
  프로토를 컴파일할 이유가 없음. resilience4j 의존성 자체는 product
  gRPC client가 별도로 쓰므로 그대로 둔다
- 일반 주문 API(/orders/**, /cart/**)와 Order/OrderProduct 도메인
  엔티티는 변경하지 않음 — 이 커밋은 어드민 전용 코드만 제거한다
- 이 커밋은 Task 5(게이트웨이 라우트 이전)와 같은 배포 단위여야 한다 —
  둘 중 하나만 나가면 어드민 주문 API가 완전히 끊긴다
EOF
)"
```

---

## 최종 확인

모든 태스크 완료 후:

- [ ] `./gradlew :admin-service:test :apigateway:test :order-service:test` 전체 통과 확인
- [ ] `admin-service/works/order/design.md`의 결정 사항 6개(이관 방식, seller 조회, 쿼리 구현, 컷오버, 클래스명, API 버전)가 실제 구현과 일치하는지 재확인
- [ ] 프론트/어드민 클라이언트 쪽에 `/api/v1/admin/orders` → `/api/v2/admin/orders` 전환을 알려야 한다(별도 커뮤니케이션, 이 플랜 범위 밖)
