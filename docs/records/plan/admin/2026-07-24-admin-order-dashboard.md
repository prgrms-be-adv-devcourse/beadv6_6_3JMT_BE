# 관리자 주문 대시보드 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 주문 페이지마다 구매자와 모든 주문 상품의 판매자·금액·상태를 반환하는 읽기 전용 관리자 대시보드 API를 제공한다.

**Architecture:** QueryDSL은 먼저 주문 ID를 페이지 처리하고, 해당 주문의 모든 상품 행을 조회해 주문별 projection을 만든다. 서비스는 구매자와 판매자 ID를 중복 없이 모아 사용자 프로필을 한 번에 조회한 뒤 중첩 응답으로 변환한다.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, QueryDSL, JUnit 5, Mockito, MockMvc, H2.

## Global Constraints

- `admin-service`는 주문·주문 상품·환불·정산 데이터를 변경하지 않는다.
- 주문은 생성일 내림차순으로 페이지 처리하고, 해당 페이지의 모든 주문 상품을 반환한다.
- 동일한 주문 생성일시는 `order.id` 내림차순을 추가 정렬 기준으로 사용해 페이지 경계를 결정적으로 유지한다.
- 주문 상태는 기존 `OrderStatus`를, 주문 상품 상태는 원본 DB 문자열을 그대로 사용한다.
- 사용자 정보가 없으면 이름은 `알 수 없음`, 프로필 사진 URL은 `null`이다.
- 주문 대시보드의 사용자 일괄 조회는 `User` 엔티티를 materialize하지 않는 스칼라 projection query를 사용해 EAGER 역할 컬렉션의 N+1 조회를 만들지 않는다.
- 상품명·금액은 스냅샷 컬럼을 사용한다.
- `GET /api/v2/admin/orders` 응답 계약은 새 중첩 구조로 교체한다. 환불 API는 추가하지 않는다.

---

## File Structure

| 파일 | 변경 | 책임 |
|---|---|---|
| `src/main/java/com/prompthub/admin/user/domain/model/User.java` | 수정 | 프로필 사진 URL 읽기 매핑 |
| `src/main/java/com/prompthub/admin/user/domain/model/UserProfile.java` | 생성 | 역할을 제외한 사용자 프로필 projection 값 |
| `src/main/java/com/prompthub/admin/user/domain/repository/UserRepository.java` | 수정 | 스칼라 프로필 조회 계약 |
| `src/main/java/com/prompthub/admin/user/infrastructure/persistence/UserJpaRepository.java` | 수정 | 사용자 프로필 scalar projection query |
| `src/main/java/com/prompthub/admin/user/infrastructure/persistence/UserRepositoryAdapter.java` | 수정 | JPA projection 변환 |
| `src/main/java/com/prompthub/admin/order/application/dto/OrderUserProfile.java` | 생성 | 주문 대시보드 사용자 DTO |
| `src/main/java/com/prompthub/admin/order/application/port/OrderUserProfileQueryPort.java` | 생성 | 사용자 일괄 조회 경계 |
| `src/main/java/com/prompthub/admin/order/infrastructure/user/OrderUserProfileQueryAdapter.java` | 생성 | `UserRepository` 포트 구현 |
| `src/main/java/com/prompthub/admin/order/domain/model/Order.java` | 수정 | 주문번호·구매자 ID 매핑 |
| `src/main/java/com/prompthub/admin/order/domain/model/OrderProduct.java` | 수정 | 주문 상품 상태 매핑 |
| `src/main/java/com/prompthub/admin/order/application/dto/OrderListProjection.java` | 수정 | 주문별 상품 projection |
| `src/main/java/com/prompthub/admin/order/infrastructure/persistence/OrderQueryRepositoryImpl.java` | 수정 | 주문·상품 query 조립 |
| `src/main/java/com/prompthub/admin/order/application/service/OrderService.java` | 수정 | 프로필 일괄 조회와 응답 변환 |
| `src/main/java/com/prompthub/admin/order/presentation/dto/response/OrderListResponse.java` | 수정 | 중첩 API 계약 |
| `src/test/java/com/prompthub/admin/order/infrastructure/user/OrderUserProfileQueryAdapterTest.java` | 생성 | 포트 어댑터 테스트 |
| `src/test/java/com/prompthub/admin/user/infrastructure/persistence/UserRepositoryAdapterTest.java` | 수정 | 스칼라 프로필 조회 테스트 |
| `src/test/java/com/prompthub/admin/order/application/service/OrderServiceTest.java` | 수정 | 중첩 응답 조립 테스트 |
| `src/test/java/com/prompthub/admin/order/infrastructure/persistence/OrderQueryRepositoryImplTest.java` | 수정 | query projection 테스트 |
| `src/test/java/com/prompthub/admin/order/presentation/controller/OrderControllerTest.java` | 수정 | JSON 계약 테스트 |
| `src/test/resources/sql/orders.sql` | 수정 | 새 주문·상품 컬럼 픽스처 |

## Task 1: 사용자 프로필 일괄 조회 포트

**Files:**

- Modify: `src/main/java/com/prompthub/admin/user/domain/model/User.java`
- Create: `src/main/java/com/prompthub/admin/user/domain/model/UserProfile.java`
- Modify: `src/main/java/com/prompthub/admin/user/domain/repository/UserRepository.java`
- Modify: `src/main/java/com/prompthub/admin/user/infrastructure/persistence/UserJpaRepository.java`
- Modify: `src/main/java/com/prompthub/admin/user/infrastructure/persistence/UserRepositoryAdapter.java`
- Create: `src/main/java/com/prompthub/admin/order/application/dto/OrderUserProfile.java`
- Create: `src/main/java/com/prompthub/admin/order/application/port/OrderUserProfileQueryPort.java`
- Create: `src/main/java/com/prompthub/admin/order/infrastructure/user/OrderUserProfileQueryAdapter.java`
- Test: `src/test/java/com/prompthub/admin/order/infrastructure/user/OrderUserProfileQueryAdapterTest.java`
- Test: `src/test/java/com/prompthub/admin/user/infrastructure/persistence/UserRepositoryAdapterTest.java`

**Interfaces:**

- Consumes: `UserRepository.findProfilesByIds(List<UUID>)`
- Produces: `Map<UUID, OrderUserProfile> findProfilesByUserIds(List<UUID> userIds)`

- [ ] **Step 1: 실패 테스트를 작성한다.**

```java
@Test
void 사용자ID를_중복제거해_이름과_프로필사진을_한번에_조회한다() {
    UUID userId = UUID.randomUUID();
    given(userRepository.findProfilesByIds(List.of(userId))).willReturn(List.of(
        new UserProfile(userId, "구매자A", "https://cdn.example.com/a.png")));

    Map<UUID, OrderUserProfile> result = adapter.findProfilesByUserIds(List.of(userId, userId));

    assertThat(result).containsEntry(userId,
        new OrderUserProfile(userId, "구매자A", "https://cdn.example.com/a.png"));
    then(userRepository).should().findProfilesByIds(List.of(userId));
}

@Test
void 빈_사용자ID면_저장소를_호출하지_않는다() {
    assertThat(adapter.findProfilesByUserIds(List.of())).isEmpty();
    then(userRepository).shouldHaveNoInteractions();
}
```

- [ ] **Step 2: 실패를 확인한다.**

Run: `./gradlew :admin-service:test --tests 'com.prompthub.admin.order.infrastructure.user.OrderUserProfileQueryAdapterTest'`
Expected: FAIL — `OrderUserProfileQueryAdapter`, `OrderUserProfile`, `UserProfile`, `findProfilesByIds`가 없다.

- [ ] **Step 3: 최소 구현을 작성한다.**

`User.java`에 다음을 추가한다.

```java
@Column(name = "profile_image_url", length = 500)
private String profileImageUrl;
```

`UserProfile.java`는 `UUID userId`, `String name`, `String profileImageUrl` record로 만들고, `UserRepository.findProfilesByIds`는 이를 반환한다. `UserJpaRepository`에는 `User`의 id·name·profileImageUrl만 선택하는 interface projection JPQL을 추가하며, `UserRepositoryAdapter`는 projection을 `UserProfile`로 변환한다. 이 경로에서는 `User.roles`를 조회하지 않는다.

`OrderUserProfile.java`:

```java
public record OrderUserProfile(UUID userId, String name, String profileImageUrl) {
}
```

`OrderUserProfileQueryPort.java`:

```java
public interface OrderUserProfileQueryPort {
    Map<UUID, OrderUserProfile> findProfilesByUserIds(List<UUID> userIds);
}
```

`OrderUserProfileQueryAdapter.java`:

```java
@Component
@RequiredArgsConstructor
public class OrderUserProfileQueryAdapter implements OrderUserProfileQueryPort {
    private final UserRepository userRepository;

    @Override
    public Map<UUID, OrderUserProfile> findProfilesByUserIds(List<UUID> userIds) {
        List<UUID> distinctIds = userIds.stream().distinct().toList();
        if (distinctIds.isEmpty()) return Map.of();
        return userRepository.findProfilesByIds(distinctIds).stream()
            .map(profile -> new OrderUserProfile(profile.userId(), profile.name(), profile.profileImageUrl()))
            .collect(Collectors.toUnmodifiableMap(OrderUserProfile::userId, Function.identity()));
    }
}
```

- [ ] **Step 4: 포트 테스트를 통과시킨다.**

Run: `./gradlew :admin-service:test --tests 'com.prompthub.admin.order.infrastructure.user.OrderUserProfileQueryAdapterTest'`
Expected: PASS, 2 tests successful.

- [ ] **Step 5: 커밋한다.**

```bash
git add src/main/java/com/prompthub/admin/user/domain/model/User.java src/main/java/com/prompthub/admin/user/domain/model/UserProfile.java src/main/java/com/prompthub/admin/user/domain/repository/UserRepository.java src/main/java/com/prompthub/admin/user/infrastructure/persistence/UserJpaRepository.java src/main/java/com/prompthub/admin/user/infrastructure/persistence/UserRepositoryAdapter.java src/main/java/com/prompthub/admin/order/application/dto/OrderUserProfile.java src/main/java/com/prompthub/admin/order/application/port/OrderUserProfileQueryPort.java src/main/java/com/prompthub/admin/order/infrastructure/user/OrderUserProfileQueryAdapter.java src/test/java/com/prompthub/admin/user/infrastructure/persistence/UserRepositoryAdapterTest.java src/test/java/com/prompthub/admin/order/infrastructure/user/OrderUserProfileQueryAdapterTest.java
git commit -m "feat: admin-service 주문 사용자 프로필 조회 추가"
```

## Task 2: 주문 projection과 중첩 응답 구현

**Files:**

- Modify: `src/main/java/com/prompthub/admin/order/domain/model/Order.java`
- Modify: `src/main/java/com/prompthub/admin/order/domain/model/OrderProduct.java`
- Modify: `src/main/java/com/prompthub/admin/order/application/dto/OrderListProjection.java`
- Modify: `src/main/java/com/prompthub/admin/order/infrastructure/persistence/OrderQueryRepositoryImpl.java`
- Modify: `src/main/java/com/prompthub/admin/order/application/service/OrderService.java`
- Modify: `src/main/java/com/prompthub/admin/order/presentation/dto/response/OrderListResponse.java`
- Modify: `src/test/java/com/prompthub/admin/order/infrastructure/persistence/OrderQueryRepositoryImplTest.java`
- Modify: `src/test/java/com/prompthub/admin/order/application/service/OrderServiceTest.java`
- Modify: `src/test/resources/sql/orders.sql`

**Interfaces:**

```java
public record OrderListProjection(
    UUID orderId, String orderNumber, UUID buyerId, int totalOrderAmount,
    OrderStatus orderStatus, LocalDateTime createdAt,
    List<OrderProductSummary> orderProducts
) {
    public record OrderProductSummary(
        UUID sellerId, String productTitle, int productAmount, String orderProductStatus
    ) {}
}

public record OrderListResponse(
    String orderNumber, UserSummary buyer, int totalOrderAmount,
    OrderStatus orderStatus, LocalDateTime orderedAt,
    List<OrderProductSummary> orderProducts
) {
    public record UserSummary(UUID userId, String name, String profileImageUrl) {}
    public record OrderProductSummary(
        UserSummary seller, String productTitle, int productAmount, String orderProductStatus
    ) {}
}
```

- [ ] **Step 1: 새 projection과 응답의 실패 테스트를 작성한다.**

`OrderQueryRepositoryImplTest`에 다음 assertion을 넣는다.

```java
assertThat(projection.orderNumber()).isEqualTo("ORD-20260610-0001");
assertThat(projection.buyerId())
    .isEqualTo(UUID.fromString("dddddddd-0000-0000-0000-000000000001"));
assertThat(projection.orderProducts()).containsExactly(
    new OrderListProjection.OrderProductSummary(
        UUID.fromString("cccccccc-0000-0000-0000-000000000001"), "프롬프트 상품 1", 10_000, "PAID"),
    new OrderListProjection.OrderProductSummary(
        UUID.fromString("cccccccc-0000-0000-0000-000000000002"), "프롬프트 상품 2", 20_000, "PAID")
);
```

`OrderServiceTest`에는 구매자·판매자가 한 번의 포트 호출로 조립되고, 없는 판매자는 기본값이 되는 테스트를 넣는다.

```java
given(orderUserProfileQueryPort.findProfilesByUserIds(List.of(BUYER_ID, SELLER_ID_1, SELLER_ID_2)))
    .willReturn(Map.of(
        BUYER_ID, new OrderUserProfile(BUYER_ID, "구매자A", "https://cdn/buyer.png"),
        SELLER_ID_1, new OrderUserProfile(SELLER_ID_1, "판매자A", "https://cdn/seller-a.png")
    ));

assertThat(response.getContent().getFirst().orderProducts()).containsExactly(
    new OrderListResponse.OrderProductSummary(
        new OrderListResponse.UserSummary(SELLER_ID_1, "판매자A", "https://cdn/seller-a.png"),
        "프롬프트 상품 1", 30_000, "PAID"),
    new OrderListResponse.OrderProductSummary(
        new OrderListResponse.UserSummary(SELLER_ID_2, "알 수 없음", null),
        "프롬프트 상품 2", 15_000, "REFUNDED")
);
```

- [ ] **Step 2: 실패를 확인한다.**

Run: `./gradlew :admin-service:test --tests 'com.prompthub.admin.order.infrastructure.persistence.OrderQueryRepositoryImplTest' --tests 'com.prompthub.admin.order.application.service.OrderServiceTest'`
Expected: FAIL — 주문번호·구매자·상품 상태와 새 중첩 응답이 아직 없다.

- [ ] **Step 3: 엔티티, query, 서비스, 응답을 구현한다.**

`Order.java`:

```java
@Column(name = "order_number", nullable = false, length = 30)
private String orderNumber;

@Column(name = "buyer_id", columnDefinition = "uuid", nullable = false)
private UUID buyerId;
```

`OrderProduct.java`:

```java
@Column(name = "order_product_status", nullable = false, length = 20)
private String orderProductStatus;
```

`OrderQueryRepositoryImpl.searchOrders`의 select에 `order.orderNumber`, `order.buyerId`, `orderProduct.orderProductStatus`를 추가한다. 주문 ID 페이지와 상품 행 query 모두 `order.createdAt.desc(), order.id.desc()`로 정렬하고, 상품 내부 순서는 `orderProduct.createdAt.asc(), orderProduct.id.asc()`로 유지한다. `toOrderListProjection`은 판매자별 누적을 제거하고 각 row를 `OrderProductSummary` 하나로 만든다. 동일 생성일시 주문의 page size 1 경계가 겹치지 않는 repository 회귀 테스트를 추가한다.

`OrderService`의 `SellerNicknameRepository`를 `OrderUserProfileQueryPort`로 교체한다. 각 페이지의 `buyerId`와 모든 상품의 `sellerId`를 encounter order로 중복 제거해 포트를 한 번 호출한다. 다음 fallback을 사용한다.

```java
private OrderListResponse.UserSummary toUserSummary(UUID userId, Map<UUID, OrderUserProfile> profiles) {
    OrderUserProfile profile = profiles.get(userId);
    return profile == null
        ? new OrderListResponse.UserSummary(userId, "알 수 없음", null)
        : new OrderListResponse.UserSummary(profile.userId(), profile.name(), profile.profileImageUrl());
}
```

`OrderListResponse`을 Interfaces 블록의 record로 교체하고 `orderedAt`에는 projection의 `createdAt`을 넣는다. 이전 `sellerCount`, `sellers`, `productTitle`, `totalOrderCount`, `orderId` 필드는 제거한다.

`orders.sql`에는 order 행의 `order_number`, `buyer_id`와 order_product 행의 `order_product_status`를 추가한다. 첫 주문은 `ORD-20260610-0001`, `dddddddd-0000-0000-0000-000000000001`을 사용하고, 첫 주문 상품 상태는 실제 order-service 값인 `PAID`를 사용한다.

- [ ] **Step 4: 테스트를 통과시키고 주문 전용 조회 의존성만 교체한다.**

Run: `./gradlew :admin-service:test --tests 'com.prompthub.admin.order.infrastructure.persistence.OrderQueryRepositoryImplTest' --tests 'com.prompthub.admin.order.application.service.OrderServiceTest'`
Expected: PASS, 모든 상품과 구매자·판매자 프로필 매핑 assertion이 통과한다.

`SellerNickname.java`, `SellerNicknameRepository.java`, `SellerNicknameRepositoryTest.java`는 `ProductService`가 공유하므로 유지한다. 주문 `OrderService`와 `OrderServiceTest`에서만 해당 타입의 import·mock·변환 코드를 제거하고 `OrderUserProfileQueryPort`를 사용한다. `ProductService` 및 `ProductServiceTest`의 기존 판매자 닉네임 흐름은 변경하지 않는다.

- [ ] **Step 5: 커밋한다.**

```bash
git add src/main/java/com/prompthub/admin/order src/test/java/com/prompthub/admin/order src/test/resources/sql/orders.sql
git commit -m "feat: admin-service 주문 대시보드 응답 확장"
```

## Task 3: HTTP 계약과 전체 회귀 검증

**Files:**

- Modify: `src/main/java/com/prompthub/admin/order/presentation/controller/OrderController.java`
- Modify: `src/main/java/com/prompthub/admin/order/presentation/dto/response/OrderListResponse.java`
- Modify: `src/test/java/com/prompthub/admin/order/presentation/controller/OrderControllerTest.java`

**Interfaces:**

- Consumes: `OrderUseCase.getOrders(OrderSearchCondition)`
- Produces: `GET /api/v2/admin/orders`의 `buyer`, `orderProducts[]` JSON 계약

- [ ] **Step 1: controller 실패 계약 테스트를 작성한다.**

`전체_주문_목록을_조회한다`에 새 `OrderListResponse` fixture를 넣고 다음을 검증한다.

```java
.andExpect(jsonPath("$.data[0].orderNumber").value("ORD-20260624-0001"))
.andExpect(jsonPath("$.data[0].buyer.name").value("구매자A"))
.andExpect(jsonPath("$.data[0].buyer.profileImageUrl").value("https://cdn/buyer.png"))
.andExpect(jsonPath("$.data[0].orderProducts[0].seller.name").value("판매자A"))
.andExpect(jsonPath("$.data[0].orderProducts[0].productTitle").value("프롬프트 상품 1"))
.andExpect(jsonPath("$.data[0].orderProducts[0].productAmount").value(30_000))
.andExpect(jsonPath("$.data[0].orderProducts[0].orderProductStatus").value("PAID"))
.andExpect(jsonPath("$.meta.total").value(1));
```

- [ ] **Step 2: HTTP 계약을 확인한다.**

Run: `./gradlew :admin-service:test --tests 'com.prompthub.admin.order.presentation.controller.OrderControllerTest'`
Expected: PASS — Task 2에서 만든 중첩 응답이 controller를 통해 동일한 JSON 구조로 직렬화된다.

- [ ] **Step 3: OpenAPI 설명을 갱신한다.**

`OrderListResponse`의 새 필드에 다음 `@Schema` 설명을 추가한다.

```java
@Schema(description = "주문 번호", example = "ORD-20260724-0001") String orderNumber
@Schema(description = "구매자 정보") UserSummary buyer
@Schema(description = "주문 상품 목록") List<OrderProductSummary> orderProducts
@Schema(description = "주문 상품 상태", example = "PAID", allowableValues = {"PENDING", "PAID", "FAILED", "REFUND_REQUESTED", "REFUNDED"}) String orderProductStatus
```

`OrderController.getOrders` 설명은 “주문별 구매자와 주문 상품 목록을 함께 조회합니다.”로 바꾸고, 경로와 페이지 메타데이터는 유지한다.

- [ ] **Step 4: controller와 전체 모듈을 검증한다.**

Run: `./gradlew :admin-service:test --tests 'com.prompthub.admin.order.presentation.controller.OrderControllerTest'`
Expected: PASS, 상태 필터 400 처리와 월간·주간 통계 endpoint도 통과한다.

Run: `./gradlew :admin-service:test`
Expected: PASS, `admin-service`의 모든 테스트가 성공한다.

Run: `git diff --check`
Expected: 출력 없음.

- [ ] **Step 5: 커밋한다.**

```bash
git add src/main/java/com/prompthub/admin/order/presentation src/test/java/com/prompthub/admin/order/presentation/controller/OrderControllerTest.java
git commit -m "test: admin-service 주문 대시보드 응답 검증 추가"
```

## Self-Review

- Spec coverage: 주문별 페이지와 모든 상품은 Task 2, 구매자·판매자 프로필은 Task 1과 2, HTTP·문서 계약은 Task 3이 다룬다. 직접 환불 처리와 상태 변경은 어느 task에도 포함하지 않는다.
- Placeholder scan: 각 task에 대상 파일, 정확한 record/메서드 시그니처, 실패 assertion, 실행 명령, 기대 결과와 커밋 메시지를 명시했다.
- Type consistency: `OrderUserProfileQueryPort`의 `Map<UUID, OrderUserProfile>` 계약을 adapter와 `OrderService`가 동일하게 사용하고, `OrderListResponse.UserSummary` 및 `OrderProductSummary` 이름을 Task 2와 3에서 동일하게 사용한다.
