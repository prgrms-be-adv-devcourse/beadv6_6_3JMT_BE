# Admin Home Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** admin-service가 직접 여러 DB 스키마를 집계해 GET /api/v2/admin/home 한 번으로 어드민 홈 전체 데이터를 반환하게 한다.

**Architecture:** com.prompthub.admin.home에 화면 전용 읽기 모델을 둔다. NamedParameterJdbcTemplate 어댑터는 스키마를 명시한 작은 SQL들을 실행하고, 애플리케이션 서비스는 KST 기간을 한 번 계산해 PostgreSQL REPEATABLE_READ 읽기 전용 트랜잭션에서 응답을 조립한다.

**Tech Stack:** Java 21, Spring Boot 4, Spring MVC, Spring JDBC/JPA, PostgreSQL 18, Testcontainers, JUnit 5, Mockito, AssertJ, Gradle

## Global Constraints

- 외부 경로는 GET /api/v2/admin/home이다.
- DB 조회는 admin-service 안에서 수행하고 REST, Feign, gRPC를 추가하지 않는다.
- 원본은 user_service."user", user_service.seller_settlement, order_service."order", order_service.order_product, product_service.product다.
- SQL은 스키마 이름을 명시하고 currentSchema 순서에 의존하지 않는다.
- 날짜 경계는 Asia/Seoul이며 오늘을 포함한 최근 7일을 반환한다.
- 정산 승인 대기는 WAITING + APPROVAL_ON_HOLD다.
- 검수 상품은 전체 건수와 created_at ASC, id ASC 기준 최대 4건이다.
- 기존 /admin/stats/users, /admin/orders/month, /admin/orders/weekend 및 정산·상품 API를 제거하거나 변경하지 않는다.
- User·Product 스키마의 Flyway 인덱스 마이그레이션을 추가하지 않는다.
- 모든 동작 변경은 실패 테스트를 먼저 확인한다.
- 설계 원본은 docs/superpowers/specs/2026-07-22-admin-home-dashboard-design.md다.

---

## File Map

**Create:**
- admin-service/src/main/java/com/prompthub/admin/home/domain/repository/HomeQueryRepository.java
- admin-service/src/main/java/com/prompthub/admin/home/infrastructure/persistence/HomeQueryRepositoryAdapter.java
- admin-service/src/main/java/com/prompthub/admin/home/application/dto/HomeResult.java
- admin-service/src/main/java/com/prompthub/admin/home/application/usecase/HomeUseCase.java
- admin-service/src/main/java/com/prompthub/admin/home/application/service/HomeApplicationService.java
- admin-service/src/main/java/com/prompthub/admin/home/config/HomeTimeConfig.java
- admin-service/src/main/java/com/prompthub/admin/home/presentation/dto/response/HomeResponse.java
- admin-service/src/main/java/com/prompthub/admin/home/presentation/controller/HomeController.java
- admin-service/src/test/java/com/prompthub/admin/home/infrastructure/persistence/HomeQueryRepositoryAdapterTest.java
- admin-service/src/test/java/com/prompthub/admin/home/application/service/HomeApplicationServiceTest.java
- admin-service/src/test/java/com/prompthub/admin/home/presentation/controller/HomeControllerTest.java
- docs/api-spec/admin.md

**Modify:**
- admin-service/build.gradle
- apigateway/src/main/java/com/prompthub/apigateway/route/VersionedServiceRoute.java
- apigateway/src/test/java/com/prompthub/apigateway/route/VersionedRouteDefinitionLocatorTest.java

---

### Task 0: Latest Develop Baseline

**Files:**
- Verify only: backend repository worktree

**Interfaces:**
- Produces: a clean implementation base containing the latest origin/develop

- [ ] **Step 1: Confirm the plan branch is clean**

~~~bash
git status --short
git branch --show-current
~~~

Expected: no uncommitted files and branch codex/admin-home-dashboard-design, or a dedicated implementation branch created from it.

- [ ] **Step 2: Rebase the plan commit onto current develop**

~~~bash
git fetch origin
git rebase origin/develop
~~~

Expected: rebase succeeds without dropping the design and plan documents. Resolve a conflict only by preserving both current origin/develop code and the approved docs; never reset the worktree.

- [ ] **Step 3: Verify the affected baseline modules**

~~~bash
./gradlew :admin-service:test :apigateway:test
~~~

Expected: BUILD SUCCESSFUL before feature code is added.

---

### Task 1: Home Query Port and PostgreSQL Adapter

**Files:**
- Modify: admin-service/build.gradle
- Create: admin-service/src/main/java/com/prompthub/admin/home/domain/repository/HomeQueryRepository.java
- Create: admin-service/src/main/java/com/prompthub/admin/home/infrastructure/persistence/HomeQueryRepositoryAdapter.java
- Create: admin-service/src/test/java/com/prompthub/admin/home/infrastructure/persistence/HomeQueryRepositoryAdapterTest.java

**Interfaces:**
- Consumes: NamedParameterJdbcTemplate, LocalDateTime 반개구간, preview limit
- Produces: HomeQueryRepository와 홈 전용 불변 프로젝션

- [ ] **Step 1: Add test dependencies**

~~~gradle
testImplementation 'org.springframework.boot:spring-boot-testcontainers'
testImplementation 'org.testcontainers:testcontainers'
testImplementation 'org.testcontainers:testcontainers-junit-jupiter'
testImplementation 'org.testcontainers:testcontainers-postgresql'
~~~

- [ ] **Step 2: Write a failing PostgreSQL integration fixture**

HomeQueryRepositoryAdapterTest는 postgres:18.4-alpine을 시작하고 DriverManagerDataSource와 NamedParameterJdbcTemplate로 어댑터를 직접 생성한다. @BeforeAll에서 아래 최소 테이블을 만든다.

~~~sql
CREATE SCHEMA user_service;
CREATE SCHEMA order_service;
CREATE SCHEMA product_service;

CREATE TABLE user_service."user" (
  id uuid PRIMARY KEY,
  created_at timestamp without time zone NOT NULL,
  name varchar(100) NOT NULL
);
CREATE TABLE user_service.seller_settlement (
  seller_settlement_id uuid PRIMARY KEY,
  settlement_total_amount numeric(12,2) NOT NULL,
  status varchar(30) NOT NULL
);
CREATE TABLE order_service."order" (
  id uuid PRIMARY KEY,
  total_order_amount integer NOT NULL,
  completed_at timestamp without time zone
);
CREATE TABLE order_service.order_product (
  id uuid PRIMARY KEY,
  product_amount_snapshot integer NOT NULL,
  refunded_at timestamp without time zone
);
CREATE TABLE product_service.product (
  id uuid PRIMARY KEY,
  seller_id uuid NOT NULL,
  name varchar(200) NOT NULL,
  product_type varchar(50) NOT NULL,
  model varchar(100),
  amount integer NOT NULL,
  status varchar(255) NOT NULL,
  created_at timestamp without time zone NOT NULL,
  deleted_at timestamp without time zone
);
~~~

@BeforeEach에서 다섯 테이블을 TRUNCATE한다. @AfterAll에서 컨테이너를 중지한다.

- [ ] **Step 3: Add exact failing aggregate cases**

Add these tests with JdbcTemplate inserts:

1. Four users at 2026-07-21T23:59, 2026-07-22T00:00, 2026-07-22T23:59, 2026-07-23T00:00. findUserSummary for July 22 must equal UserSummary(4, 2).
2. Settlements WAITING 1000.00, APPROVAL_ON_HOLD 2500.00, APPROVED 9000.00. Summary must equal 3500.00 and 2.
3. Completed orders July 16 30000, July 17 10000, June 30 50000; July 17 refund 4000. July monthly amount must be 36000 and daily rows must be (July 16, 1, 30000), (July 17, 1, 6000).
4. Five non-deleted PENDING_REVIEW products, one deleted pending product, one ON_SALE product. Preview limit 4 must report totalCount 5 and titles 상품1..상품4.
5. A pending product whose seller row is missing must return sellerNickname 알 수 없음.

Use these concrete helper methods so the fixture matches the production column order:

~~~java
private void insertOrder(LocalDateTime completedAt, int amount) {
	jdbc.getJdbcTemplate().update(
		"INSERT INTO order_service.\"order\" VALUES (?, ?, ?)",
		UUID.randomUUID(), amount, completedAt);
}

private void insertRefund(LocalDateTime refundedAt, int amount) {
	jdbc.getJdbcTemplate().update(
		"INSERT INTO order_service.order_product VALUES (?, ?, ?)",
		UUID.randomUUID(), amount, refundedAt);
}

private void insertProduct(
	UUID id,
	UUID sellerId,
	String name,
	String status,
	LocalDateTime createdAt,
	LocalDateTime deletedAt
) {
	jdbc.getJdbcTemplate().update("""
		INSERT INTO product_service.product
			(id, seller_id, name, product_type, model, amount, status, created_at, deleted_at)
		VALUES (?, ?, ?, 'PROMPT', 'GPT-5', 10000, ?, ?, ?)
		""", id, sellerId, name, status, createdAt, deletedAt);
}
~~~

- [ ] **Step 4: Verify RED**

~~~bash
./gradlew :admin-service:test --tests '*HomeQueryRepositoryAdapterTest'
~~~

Expected: compilation fails because the port and adapter do not exist.

- [ ] **Step 5: Create the query port**

~~~java
public interface HomeQueryRepository {
	UserSummary findUserSummary(LocalDateTime todayStartInclusive, LocalDateTime tomorrowStartExclusive);
	long findMonthlyTransactionAmount(LocalDateTime startInclusive, LocalDateTime endExclusive);
	List<DailyTransaction> findDailyTransactions(LocalDateTime startInclusive, LocalDateTime endExclusive);
	SettlementSummary findPendingApprovalSettlementSummary();
	PendingProductPreview findPendingProductPreview(int limit);

	record UserSummary(long totalUsers, long todayNewUsers) {}
	record DailyTransaction(LocalDate date, long transactionCount, long transactionAmount) {}
	record SettlementSummary(BigDecimal pendingApprovalAmount, long pendingApprovalCount) {}
	record PendingProductPreview(long totalCount, List<PendingProduct> items) {
		public PendingProductPreview {
			items = List.copyOf(items);
		}
	}
	record PendingProduct(
		UUID productId, String title, String sellerNickname, String productType,
		String model, int amount, String status, LocalDateTime createdAt
	) {}
}
~~~

- [ ] **Step 6: Implement schema-qualified SQL**

HomeQueryRepositoryAdapter uses @Repository, @RequiredArgsConstructor, NamedParameterJdbcTemplate, and MapSqlParameterSource.

~~~sql
SELECT
  (SELECT count(*) FROM user_service."user") AS total_users,
  (SELECT count(*) FROM user_service."user"
   WHERE created_at >= :startInclusive AND created_at < :endExclusive) AS today_new_users;

SELECT
  coalesce((SELECT sum(total_order_amount) FROM order_service."order"
            WHERE completed_at >= :startInclusive AND completed_at < :endExclusive), 0)
  -
  coalesce((SELECT sum(product_amount_snapshot) FROM order_service.order_product
            WHERE refunded_at >= :startInclusive AND refunded_at < :endExclusive), 0)
  AS transaction_amount;

WITH completed AS (
  SELECT cast(completed_at AS date) AS transaction_date,
         count(*) AS transaction_count,
         sum(total_order_amount) AS transaction_amount
  FROM order_service."order"
  WHERE completed_at >= :startInclusive AND completed_at < :endExclusive
  GROUP BY cast(completed_at AS date)
), refunded AS (
  SELECT cast(refunded_at AS date) AS transaction_date,
         sum(product_amount_snapshot) AS refund_amount
  FROM order_service.order_product
  WHERE refunded_at >= :startInclusive AND refunded_at < :endExclusive
  GROUP BY cast(refunded_at AS date)
)
SELECT coalesce(c.transaction_date, r.transaction_date) AS transaction_date,
       coalesce(c.transaction_count, 0) AS transaction_count,
       coalesce(c.transaction_amount, 0) - coalesce(r.refund_amount, 0) AS transaction_amount
FROM completed c
FULL OUTER JOIN refunded r ON r.transaction_date = c.transaction_date
ORDER BY transaction_date;

SELECT coalesce(sum(settlement_total_amount), 0) AS pending_amount,
       count(*) AS pending_count
FROM user_service.seller_settlement
WHERE status IN ('WAITING', 'APPROVAL_ON_HOLD');

SELECT count(*) FROM product_service.product
WHERE status = 'PENDING_REVIEW' AND deleted_at IS NULL;

SELECT p.id AS product_id, p.name AS title,
       coalesce(u.name, '알 수 없음') AS seller_nickname,
       p.product_type, p.model, p.amount, p.status, p.created_at
FROM product_service.product p
LEFT JOIN user_service."user" u ON u.id = p.seller_id
WHERE p.status = 'PENDING_REVIEW' AND p.deleted_at IS NULL
ORDER BY p.created_at ASC, p.id ASC
LIMIT :limit;
~~~

Map date with ResultSet#getObject(name, LocalDate.class), UUID with getObject(name, UUID.class), and money/count with getBigDecimal/getLong. Do not catch DataAccessException.

- [ ] **Step 7: Verify GREEN and commit**

~~~bash
./gradlew :admin-service:test --tests '*HomeQueryRepositoryAdapterTest'
git add admin-service/build.gradle \
  admin-service/src/main/java/com/prompthub/admin/home/domain/repository/HomeQueryRepository.java \
  admin-service/src/main/java/com/prompthub/admin/home/infrastructure/persistence/HomeQueryRepositoryAdapter.java \
  admin-service/src/test/java/com/prompthub/admin/home/infrastructure/persistence/HomeQueryRepositoryAdapterTest.java
git commit -m "feat: 어드민 홈 집계 조회 어댑터 추가"
~~~

Expected: tests PASS before commit.

---

### Task 2: KST Home Application Service

**Files:**
- Create: admin-service/src/main/java/com/prompthub/admin/home/application/dto/HomeResult.java
- Create: admin-service/src/main/java/com/prompthub/admin/home/application/usecase/HomeUseCase.java
- Create: admin-service/src/main/java/com/prompthub/admin/home/application/service/HomeApplicationService.java
- Create: admin-service/src/main/java/com/prompthub/admin/home/config/HomeTimeConfig.java
- Create: admin-service/src/test/java/com/prompthub/admin/home/application/service/HomeApplicationServiceTest.java

**Interfaces:**
- Consumes: Task 1 HomeQueryRepository, homeClock, homeZoneId
- Produces: HomeUseCase#getHome(): HomeResult

- [ ] **Step 1: Write failing orchestration tests**

Use Clock.fixed(Instant.parse("2026-07-22T06:30:00Z"), ZoneOffset.UTC) and Asia/Seoul. Verify exact repository arguments: today July 22..23, month July 1..August 1, recent July 16..23, preview limit 4. Return daily rows only for July 16 and July 22; assert generatedAt 2026-07-22T15:30:00+09:00, seven output rows, July 17 zeros, and weekly totals from the seven rows. A second test makes findUserSummary throw DataAccessResourceFailureException("db down") and asserts it escapes unchanged.

~~~java
@BeforeEach
void setUp() {
	Clock clock = Clock.fixed(Instant.parse("2026-07-22T06:30:00Z"), ZoneOffset.UTC);
	service = new HomeApplicationService(repository, clock, ZoneId.of("Asia/Seoul"));
}

@Test
void KST_기준으로_홈통계를_조립하고_빈날짜를_0으로_채운다() {
	when(repository.findUserSummary(
		LocalDateTime.of(2026, 7, 22, 0, 0),
		LocalDateTime.of(2026, 7, 23, 0, 0)))
		.thenReturn(new UserSummary(1250L, 18L));
	when(repository.findMonthlyTransactionAmount(
		LocalDateTime.of(2026, 7, 1, 0, 0),
		LocalDateTime.of(2026, 8, 1, 0, 0)))
		.thenReturn(32500000L);
	when(repository.findDailyTransactions(
		LocalDateTime.of(2026, 7, 16, 0, 0),
		LocalDateTime.of(2026, 7, 23, 0, 0)))
		.thenReturn(List.of(
			new DailyTransaction(LocalDate.of(2026, 7, 16), 2L, 30000L),
			new DailyTransaction(LocalDate.of(2026, 7, 22), 3L, 50000L)));
	when(repository.findPendingApprovalSettlementSummary())
		.thenReturn(new SettlementSummary(new BigDecimal("4200000.00"), 12L));
	when(repository.findPendingProductPreview(4))
		.thenReturn(new PendingProductPreview(7L, List.of()));

	HomeResult result = service.getHome();

	assertThat(result.generatedAt())
		.isEqualTo(OffsetDateTime.parse("2026-07-22T15:30:00+09:00"));
	assertThat(result.transactions().recent7Days().dailyTransactions()).hasSize(7);
	assertThat(result.transactions().recent7Days().dailyTransactions().get(1))
		.isEqualTo(new HomeResult.DailyTransaction(LocalDate.of(2026, 7, 17), 0L, 0L));
	assertThat(result.transactions().recent7Days().totalTransactionCount()).isEqualTo(5L);
	assertThat(result.transactions().recent7Days().totalTransactionAmount()).isEqualTo(80000L);
	assertThat(result.pendingProducts().totalCount()).isEqualTo(7L);
}

@Test
void 저장소오류를_정상값으로_숨기지_않는다() {
	when(repository.findUserSummary(any(), any()))
		.thenThrow(new DataAccessResourceFailureException("db down"));

	assertThatThrownBy(service::getHome)
		.isInstanceOf(DataAccessResourceFailureException.class)
		.hasMessage("db down");
}
~~~

- [ ] **Step 2: Verify RED**

~~~bash
./gradlew :admin-service:test --tests '*HomeApplicationServiceTest'
~~~

Expected: compilation fails because application types do not exist.

- [ ] **Step 3: Create HomeResult and use case**

~~~java
public record HomeResult(
	OffsetDateTime generatedAt,
	Users users,
	Transactions transactions,
	Settlements settlements,
	PendingProducts pendingProducts
) {
	public record Users(long totalUsers, long todayNewUsers) {}
	public record Transactions(long monthlyTransactionAmount, Recent7Days recent7Days) {}
	public record Recent7Days(
		long totalTransactionCount, long totalTransactionAmount,
		Period period, List<DailyTransaction> dailyTransactions
	) {
		public Recent7Days {
			dailyTransactions = List.copyOf(dailyTransactions);
		}
	}
	public record Period(LocalDate startDate, LocalDate endDate) {}
	public record DailyTransaction(LocalDate date, long transactionCount, long transactionAmount) {}
	public record Settlements(BigDecimal pendingApprovalAmount, long pendingApprovalCount) {}
	public record PendingProducts(long totalCount, List<PendingProduct> items) {
		public PendingProducts {
			items = List.copyOf(items);
		}
	}
	public record PendingProduct(
		UUID productId, String title, String sellerNickname, String productType,
		String model, int amount, String status, LocalDateTime createdAt
	) {}
}
~~~

~~~java
public interface HomeUseCase {
	HomeResult getHome();
}
~~~

- [ ] **Step 4: Implement KST assembly**

HomeApplicationService is @Service and @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ). Inject HomeQueryRepository, @Qualifier("homeClock") Clock, @Qualifier("homeZoneId") ZoneId through an explicit constructor. Constants are RECENT_DAYS = 7 and PRODUCT_PREVIEW_LIMIT = 4.

~~~java
ZonedDateTime generatedAt = clock.instant().atZone(zoneId);
LocalDate today = generatedAt.toLocalDate();
LocalDate sevenDaysStart = today.minusDays(RECENT_DAYS - 1L);

UserSummary users = repository.findUserSummary(
	today.atStartOfDay(), today.plusDays(1).atStartOfDay());
long monthlyAmount = repository.findMonthlyTransactionAmount(
	today.withDayOfMonth(1).atStartOfDay(),
	today.plusMonths(1).withDayOfMonth(1).atStartOfDay());
Map<LocalDate, HomeQueryRepository.DailyTransaction> indexedDaily = repository
	.findDailyTransactions(sevenDaysStart.atStartOfDay(), today.plusDays(1).atStartOfDay())
	.stream()
	.collect(Collectors.toMap(HomeQueryRepository.DailyTransaction::date, Function.identity()));
List<HomeResult.DailyTransaction> daily = sevenDaysStart.datesUntil(today.plusDays(1))
	.map(date -> indexedDaily.containsKey(date)
		? toResult(indexedDaily.get(date))
		: new HomeResult.DailyTransaction(date, 0L, 0L))
	.toList();
SettlementSummary settlements = repository.findPendingApprovalSettlementSummary();
PendingProductPreview products = repository.findPendingProductPreview(PRODUCT_PREVIEW_LIMIT);
~~~

Private mappers sum weekly totals from daily, set Period(sevenDaysStart, today), and copy all product fields. They add no catch/default logic.

~~~java
private HomeResult toHomeResult(
	OffsetDateTime generatedAt,
	UserSummary users,
	long monthlyAmount,
	LocalDate periodStart,
	LocalDate periodEnd,
	List<HomeResult.DailyTransaction> daily,
	SettlementSummary settlements,
	PendingProductPreview products
) {
	long totalCount = daily.stream().mapToLong(HomeResult.DailyTransaction::transactionCount).sum();
	long totalAmount = daily.stream().mapToLong(HomeResult.DailyTransaction::transactionAmount).sum();
	return new HomeResult(
		generatedAt,
		new HomeResult.Users(users.totalUsers(), users.todayNewUsers()),
		new HomeResult.Transactions(monthlyAmount,
			new HomeResult.Recent7Days(totalCount, totalAmount,
				new HomeResult.Period(periodStart, periodEnd), daily)),
		new HomeResult.Settlements(
			settlements.pendingApprovalAmount(), settlements.pendingApprovalCount()),
		new HomeResult.PendingProducts(products.totalCount(), products.items().stream()
			.map(this::toResult)
			.toList()));
}

private HomeResult.DailyTransaction toResult(HomeQueryRepository.DailyTransaction source) {
	return new HomeResult.DailyTransaction(
		source.date(), source.transactionCount(), source.transactionAmount());
}

private HomeResult.PendingProduct toResult(HomeQueryRepository.PendingProduct source) {
	return new HomeResult.PendingProduct(
		source.productId(), source.title(), source.sellerNickname(), source.productType(),
		source.model(), source.amount(), source.status(), source.createdAt());
}
~~~

- [ ] **Step 5: Add production time configuration**

~~~java
@Configuration
public class HomeTimeConfig {
	@Bean("homeClock")
	Clock homeClock() {
		return Clock.systemUTC();
	}

	@Bean("homeZoneId")
	ZoneId homeZoneId() {
		return ZoneId.of("Asia/Seoul");
	}
}
~~~

- [ ] **Step 6: Verify GREEN and commit**

~~~bash
./gradlew :admin-service:test --tests '*HomeApplicationServiceTest'
git add admin-service/src/main/java/com/prompthub/admin/home/application \
  admin-service/src/main/java/com/prompthub/admin/home/config \
  admin-service/src/test/java/com/prompthub/admin/home/application
git commit -m "feat: 어드민 홈 통계 조립 서비스 추가"
~~~

Expected: tests PASS before commit.

---

### Task 3: Home HTTP Contract

**Files:**
- Create: admin-service/src/main/java/com/prompthub/admin/home/presentation/dto/response/HomeResponse.java
- Create: admin-service/src/main/java/com/prompthub/admin/home/presentation/controller/HomeController.java
- Create: admin-service/src/test/java/com/prompthub/admin/home/presentation/controller/HomeControllerTest.java

**Interfaces:**
- Consumes: HomeUseCase#getHome()
- Produces: ApiResult<HomeResponse> at GET /api/v2/admin/home

- [ ] **Step 1: Write the failing controller test**

Use @WebMvcTest(HomeController.class), @ActiveProfiles("test"), @MockitoBean HomeUseCase, and the exact fixture below.

~~~java
private static HomeResult homeResultFixture() {
	List<HomeResult.DailyTransaction> daily = LocalDate.of(2026, 7, 16)
		.datesUntil(LocalDate.of(2026, 7, 23))
		.map(date -> new HomeResult.DailyTransaction(date, 1L, 1000L))
		.toList();
	return new HomeResult(
		OffsetDateTime.parse("2026-07-22T15:30:00+09:00"),
		new HomeResult.Users(1250L, 18L),
		new HomeResult.Transactions(32500000L,
			new HomeResult.Recent7Days(142L, 8900000L,
				new HomeResult.Period(
					LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 22)),
				daily)),
		new HomeResult.Settlements(new BigDecimal("4200000.00"), 12L),
		new HomeResult.PendingProducts(7L, List.of(
			new HomeResult.PendingProduct(
				UUID.fromString("00000000-0000-0000-0000-000000000001"),
				"상품명", "판매자", "PROMPT", "GPT-5", 10000,
				"PENDING_REVIEW", LocalDateTime.of(2026, 7, 21, 10, 20, 30)))));
}
~~~

Assert success/message, generatedAt, both user fields, monthly amount, recent period and seven rows, both settlement fields, pending totalCount, and first product status:

~~~java
when(homeUseCase.getHome()).thenReturn(homeResultFixture());

mockMvc.perform(get("/api/v2/admin/home"))
	.andExpect(status().isOk())
	.andExpect(jsonPath("$.success").value(true))
	.andExpect(jsonPath("$.message").value("success"))
	.andExpect(jsonPath("$.data.generatedAt").value("2026-07-22T15:30:00+09:00"))
	.andExpect(jsonPath("$.data.users.totalUsers").value(1250))
	.andExpect(jsonPath("$.data.users.todayNewUsers").value(18))
	.andExpect(jsonPath("$.data.transactions.monthlyTransactionAmount").value(32500000))
	.andExpect(jsonPath("$.data.transactions.recent7Days.period.startDate").value("2026-07-16"))
	.andExpect(jsonPath("$.data.transactions.recent7Days.dailyTransactions.length()").value(7))
	.andExpect(jsonPath("$.data.settlements.pendingApprovalAmount").value(4200000.00))
	.andExpect(jsonPath("$.data.settlements.pendingApprovalCount").value(12))
	.andExpect(jsonPath("$.data.pendingProducts.totalCount").value(7))
	.andExpect(jsonPath("$.data.pendingProducts.items[0].status").value("PENDING_REVIEW"));
~~~

- [ ] **Step 2: Verify RED**

~~~bash
./gradlew :admin-service:test --tests '*HomeControllerTest'
~~~

Expected: compilation fails because presentation types do not exist.

- [ ] **Step 3: Create response records**

HomeResponse is separate from HomeResult and must use this exact mapping. Define the nested records with the constructor arguments shown by the mapper.

~~~java
public record HomeResponse(
	OffsetDateTime generatedAt,
	Users users,
	Transactions transactions,
	Settlements settlements,
	PendingProducts pendingProducts
) {
	public record Users(long totalUsers, long todayNewUsers) {}
	public record Transactions(long monthlyTransactionAmount, Recent7Days recent7Days) {}
	public record Recent7Days(
		long totalTransactionCount, long totalTransactionAmount,
		Period period, List<DailyTransaction> dailyTransactions) {}
	public record Period(LocalDate startDate, LocalDate endDate) {}
	public record DailyTransaction(LocalDate date, long transactionCount, long transactionAmount) {}
	public record Settlements(BigDecimal pendingApprovalAmount, long pendingApprovalCount) {}
	public record PendingProducts(long totalCount, List<PendingProduct> items) {}
	public record PendingProduct(
		UUID productId, String title, String sellerNickname, String productType,
		String model, int amount, String status, LocalDateTime createdAt) {}
~~~

~~~java
public static HomeResponse from(HomeResult result) {
	HomeResult.Recent7Days recent = result.transactions().recent7Days();
	return new HomeResponse(
		result.generatedAt(),
		new Users(result.users().totalUsers(), result.users().todayNewUsers()),
		new Transactions(result.transactions().monthlyTransactionAmount(),
			new Recent7Days(
				recent.totalTransactionCount(),
				recent.totalTransactionAmount(),
				new Period(recent.period().startDate(), recent.period().endDate()),
				recent.dailyTransactions().stream()
					.map(item -> new DailyTransaction(
						item.date(), item.transactionCount(), item.transactionAmount()))
					.toList())),
		new Settlements(
			result.settlements().pendingApprovalAmount(),
			result.settlements().pendingApprovalCount()),
		new PendingProducts(
			result.pendingProducts().totalCount(),
			result.pendingProducts().items().stream()
				.map(item -> new PendingProduct(
					item.productId(), item.title(), item.sellerNickname(), item.productType(),
					item.model(), item.amount(), item.status(), item.createdAt()))
				.toList()));
}

}
~~~

The top-level and nested record fields must appear in the same order and with the same names as HomeResult. Controller output must not expose repository projection types.

- [ ] **Step 4: Add the controller**

~~~java
@RestController
@RequestMapping("${api.init}/admin/home")
@RequiredArgsConstructor
@Tag(name = "Admin Home", description = "어드민 홈 통합 조회 API")
@SecurityRequirement(name = "gatewayHeaders")
public class HomeController {
	private final HomeUseCase homeUseCase;

	@GetMapping
	@Operation(
		summary = "어드민 홈 조회",
		description = "홈 KPI, 최근 7일 거래, 정산 승인 대기, 검수 대기 상품을 조회합니다.")
	public ApiResult<HomeResponse> getHome() {
		return ApiResult.success(HomeResponse.from(homeUseCase.getHome()));
	}
}
~~~

- [ ] **Step 5: Verify GREEN and commit**

~~~bash
./gradlew :admin-service:test --tests '*HomeControllerTest'
git add admin-service/src/main/java/com/prompthub/admin/home/presentation \
  admin-service/src/test/java/com/prompthub/admin/home/presentation
git commit -m "feat: 어드민 홈 통합 조회 API 추가"
~~~

Expected: test PASS before commit.

---

### Task 4: Gateway Ownership

**Files:**
- Modify: apigateway/src/main/java/com/prompthub/apigateway/route/VersionedServiceRoute.java
- Modify: apigateway/src/test/java/com/prompthub/apigateway/route/VersionedRouteDefinitionLocatorTest.java

**Interfaces:**
- Consumes: /admin/home suffix
- Produces: /api/v2/admin/home to lb://ADMIN-SERVICE

- [ ] **Step 1: Write the failing ownership test**

~~~java
@Test
void 어드민_홈_경로는_admin_service만_소유한다() {
	Map<String, List<String>> config = new LinkedHashMap<>();
	config.put("admin-service", List.of("v2"));
	config.put("user-service", List.of("v2"));
	config.put("order-service", List.of("v2"));
	config.put("product-service", List.of("v2"));

	List<RouteDefinition> definitions =
		VersionedRouteDefinitionLocator.buildRouteDefinitions(propertiesOf(config));

	assertThat(pathPredicateValue(routeById(definitions, "admin-service")))
		.contains("/api/v2/admin/home");
	assertThat(pathPredicateValue(routeById(definitions, "user-service")))
		.doesNotContain("/admin/home");
	assertThat(pathPredicateValue(routeById(definitions, "order-service")))
		.doesNotContain("/admin/home");
	assertThat(pathPredicateValue(routeById(definitions, "product-service")))
		.doesNotContain("/admin/home");
}
~~~

- [ ] **Step 2: Verify RED**

~~~bash
./gradlew :apigateway:test --tests '*VersionedRouteDefinitionLocatorTest.어드민_홈_경로는_admin_service만_소유한다'
~~~

Expected: admin route lacks /api/v2/admin/home.

- [ ] **Step 3: Add only "/admin/home" to admin-service pathSuffixes**

Do not add it to other routes and do not remove legacy paths.

- [ ] **Step 4: Verify GREEN and commit**

~~~bash
./gradlew :apigateway:test --tests '*VersionedRouteDefinitionLocatorTest'
git add apigateway/src/main/java/com/prompthub/apigateway/route/VersionedServiceRoute.java \
  apigateway/src/test/java/com/prompthub/apigateway/route/VersionedRouteDefinitionLocatorTest.java
git commit -m "feat: 어드민 홈 게이트웨이 경로 추가"
~~~

Expected: route tests PASS before commit.

---

### Task 5: Documentation and Backend Verification

**Files:**
- Create: docs/api-spec/admin.md
- Verify: all planned backend files

**Interfaces:**
- Produces: current API documentation and verification evidence

- [ ] **Step 1: Write docs/api-spec/admin.md**

Include the full design JSON, ADMIN requirement, Asia/Seoul ranges, completed-minus-refund formula, WAITING + APPROVAL_ON_HOLD, four-item preview and independent totalCount, whole-request failure policy, and user_service.seller_settlement ownership.

- [ ] **Step 2: Run focused and complete tests**

~~~bash
./gradlew :admin-service:test --tests 'com.prompthub.admin.home.*'
./gradlew :admin-service:test :apigateway:test
~~~

Expected: BUILD SUCCESSFUL and zero failed tests.

- [ ] **Step 3: Build affected modules**

~~~bash
./gradlew :admin-service:build :apigateway:build
~~~

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Confirm forbidden diffs are absent**

~~~bash
git diff origin/develop...HEAD -- \
  user-service/src/main/resources/db/migration \
  product-service/src/main/resources/db/migration \
  admin-service/src/main/java/com/prompthub/admin/order \
  admin-service/src/main/java/com/prompthub/admin/user \
  admin-service/src/main/java/com/prompthub/admin/settlement \
  admin-service/src/main/java/com/prompthub/admin/product
~~~

Expected: no output.

- [ ] **Step 5: Commit documentation**

~~~bash
git add docs/api-spec/admin.md
git commit -m "docs: 어드민 홈 API 명세 추가"
~~~

- [ ] **Step 6: Review final diff**

~~~bash
git status --short
git diff --check origin/develop...HEAD
git diff --stat origin/develop...HEAD
~~~

Expected: clean worktree, no whitespace errors, and changes limited to planned home, gateway, dependency, tests, and API documentation files.
