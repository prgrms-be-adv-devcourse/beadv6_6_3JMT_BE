# Wishlist Frontend Composition Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** User 서비스의 Wishlist 응답을 소유 데이터로 축소하고, Wishlist 화면용 판매자 다건 조회 경로를 기존 Seller 조회 로직 위에 추가한다.

**Architecture:** `GET /api/v2/wishlists`는 Wishlist Repository 결과만 반환하고 Product gRPC와 판매자 Repository를 호출하지 않는다. Product 정보는 `#478 (PR)`의 `POST /api/v2/products/wishlists`, 판매자 이름은 새 `POST /api/v2/sellers/wishlists`가 제공하며 프론트가 세 응답을 조합한다. Seller 새 경로는 기존 `SellerIdsRequest`, `SellerNamesResponse`, `SellerQueryUseCase.findSellers()`를 재사용한다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring MVC, Spring Data JPA, Spring gRPC server, Jakarta Validation, JUnit 5, Mockito, AssertJ, MockMvc, Gradle

## Global Constraints

- 기준 이슈는 `#485 (이슈)`, 브랜치는 `feat/#485-split-wishlist-composition (브랜치)`다.
- 설계 원본은 `settlement-service/docs/superpowers/specs/2026-07-22-wishlist-frontend-composition-design.md`다.
- Product 계약은 `#478 (PR)`의 `POST /api/v2/products/wishlists`를 사용하며 product-service 코드는 이번 계획에서 수정하지 않는다.
- `GET /api/v2/wishlists`의 인증, `page` 기본값 `0`, `size` 기본값 `20`, `PageResponse` meta 계산은 유지한다.
- Wishlist 항목 응답은 `wishlistId`, `productId`, `addedAt`만 포함한다. 제거 필드를 fallback 값이나 deprecated 필드로 남기지 않는다.
- `POST /api/v2/sellers/wishlists`는 `SellerIdsRequest`의 빈 배열 금지와 최대 30개 검증, `SellerNamesResponse`의 중복 제거·누락 판매자 `null` 정책을 그대로 사용한다.
- 새 Seller UseCase, ApplicationService, Repository 메서드를 만들지 않는다.
- Gateway는 `/sellers/wishlists`를 whitelist에 추가하지 않는다. 로그인 Wishlist 화면 호출이므로 기본 인증 정책을 사용한다.
- User 서비스는 Seller gRPC 서버를 계속 제공하므로 protobuf plugin, gRPC server, `grpc-stub`, `grpc-protobuf`는 유지한다.
- User 전용 Product gRPC client 설정만 제거한다. 다른 서비스가 소유한 루트 `grpc/product/product_query.proto`는 수정하지 않는다.
- 루트 `config/`와 `docs/` 변경은 승인된 `#485 (이슈)` 범위에 포함된다.

---

## File Map

### Create

- 없음.

### Modify

- `user-service/src/main/java/com/prompthub/user/wishlist/application/dto/WishlistItemResult.java` — Wishlist 소유 필드만 가진 결과 DTO로 축소한다.
- `user-service/src/main/java/com/prompthub/user/wishlist/application/service/WishlistApplicationService.java` — Repository 결과만 매핑하도록 바꾼다.
- `user-service/src/main/java/com/prompthub/user/wishlist/presentation/dto/response/WishlistItemResponse.java` — 공개 응답을 세 필드로 축소한다.
- `user-service/src/test/java/com/prompthub/user/wishlist/application/service/WishlistApplicationServiceTest.java` — 외부 통신 없는 조회와 응답 필드 계약을 검증한다.
- `user-service/src/main/java/com/prompthub/user/seller/presentation/controller/SellerController.java` — Wishlist용 Seller 다건 조회 경로를 추가한다.
- `user-service/src/test/java/com/prompthub/user/seller/presentation/controller/SellerControllerTest.java` — 새 경로의 위임·응답·검증을 고정한다.
- `user-service/build.gradle` — 더 이상 필요 없는 gRPC client starter를 제거하고 서버용 의존성 설명을 정리한다.
- `user-service/src/main/resources/application-local.yml` — 로컬 Product gRPC client 주소를 제거한다.
- `config/src/main/resources/configs/user-service.yml` — 배포 Product gRPC client 주소를 제거한다.
- `docs/api-spec/user.md` — Wishlist 축소 응답과 `/sellers/wishlists` 계약을 문서화한다.
- `docs/architecture/overview.md` — `user → product` gRPC 연결을 제거한다.
- `docs/grpc-contract-ownership.md` — User Wishlist 로컬 Product 계약 제거 상태를 기록한다.

### Delete

- `user-service/src/main/java/com/prompthub/user/wishlist/application/client/ProductClient.java`
- `user-service/src/main/java/com/prompthub/user/wishlist/application/dto/ProductSummaryDto.java`
- `user-service/src/main/java/com/prompthub/user/wishlist/infrastructure/grpc/GrpcClientConfig.java`
- `user-service/src/main/java/com/prompthub/user/wishlist/infrastructure/grpc/ProductGrpcClient.java`
- `user-service/src/main/proto/product_service.proto`

---

### Task 1: Wishlist 응답을 소유 데이터 계약으로 축소

**Files:**

- Modify: `user-service/src/test/java/com/prompthub/user/wishlist/application/service/WishlistApplicationServiceTest.java`
- Modify: `user-service/src/main/java/com/prompthub/user/wishlist/application/dto/WishlistItemResult.java`
- Modify: `user-service/src/main/java/com/prompthub/user/wishlist/application/service/WishlistApplicationService.java`
- Modify: `user-service/src/main/java/com/prompthub/user/wishlist/presentation/dto/response/WishlistItemResponse.java`

**Interfaces:**

- Consumes: `WishlistRepository.findByUserId(UUID userId, int page, int size) -> List<Wishlist>`
- Produces: `WishlistItemResult(UUID wishlistId, UUID productId, LocalDateTime addedAt)`
- Produces: `WishlistItemResponse(UUID wishlistId, UUID productId, LocalDateTime addedAt)`
- Preserves: `WishlistUseCase.getWishlists(UUID, int, int) -> List<WishlistItemResult>`

- [ ] **Step 1: 응답 필드와 조회 결과를 고정하는 실패 테스트 작성**

`WishlistApplicationServiceTest`에서 `ProductClient`, `ProductSummaryDto`, `UserRepository`, `User`, `UserRole`, `SELLER_ID`, `productSummary()`, `seller()` 관련 import·mock·fixture를 제거한다. 기존 두 합성 테스트를 다음 테스트로 교체하고 record component 계약 테스트를 추가한다.

```java
import java.lang.reflect.RecordComponent;

@Test
void wishlist_item_result는_위시리스트_소유_필드만_노출한다() {
    assertThat(WishlistItemResult.class.getRecordComponents())
            .extracting(RecordComponent::getName)
            .containsExactly("wishlistId", "productId", "addedAt");
}

@Test
void getWishlists_위시리스트_식별자와_등록일만_반환한다() {
    Wishlist wishlist = Wishlist.create(USER_ID, PRODUCT_ID);
    given(wishlistRepository.findByUserId(USER_ID, 0, 20)).willReturn(List.of(wishlist));

    List<WishlistItemResult> results = wishlistApplicationService.getWishlists(USER_ID, 0, 20);

    assertThat(results).containsExactly(
            new WishlistItemResult(
                    wishlist.getWishlistId(),
                    wishlist.getProductId(),
                    wishlist.getCreatedAt()
            )
    );
}

@Test
void getWishlists_빈_목록이면_빈_결과를_반환한다() {
    given(wishlistRepository.findByUserId(USER_ID, 0, 20)).willReturn(List.of());

    List<WishlistItemResult> results = wishlistApplicationService.getWishlists(USER_ID, 0, 20);

    assertThat(results).isEmpty();
    then(wishlistRepository).should().findByUserId(USER_ID, 0, 20);
    then(wishlistRepository).shouldHaveNoMoreInteractions();
}
```

- [ ] **Step 2: 현재 10개 필드 계약 때문에 테스트가 실패하는지 확인**

Run:

```bash
./gradlew :user-service:test --tests com.prompthub.user.wishlist.application.service.WishlistApplicationServiceTest
```

Expected: FAIL. `wishlist_item_result는_위시리스트_소유_필드만_노출한다`에서 현재 `title`, `thumbnailUrl`, `price`, `sellerNickname`, `averageRating`, `salesCount`, `model`이 추가로 존재한다는 차이가 출력된다. 생성자 축소를 먼저 테스트에 반영했다면 컴파일 실패도 허용되는 Red 상태다.

- [ ] **Step 3: WishlistItemResult를 세 필드와 변환 함수로 축소**

`WishlistItemResult.java`를 다음 최종 내용으로 바꾼다.

```java
package com.prompthub.user.wishlist.application.dto;

import com.prompthub.user.wishlist.domain.model.Wishlist;
import java.time.LocalDateTime;
import java.util.UUID;

public record WishlistItemResult(
        UUID wishlistId,
        UUID productId,
        LocalDateTime addedAt
) {

    public static WishlistItemResult from(Wishlist wishlist) {
        return new WishlistItemResult(
                wishlist.getWishlistId(),
                wishlist.getProductId(),
                wishlist.getCreatedAt()
        );
    }
}
```

- [ ] **Step 4: WishlistApplicationService에서 외부 합성 제거**

다음 import를 제거한다.

```java
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.repository.UserRepository;
import com.prompthub.user.wishlist.application.client.ProductClient;
import com.prompthub.user.wishlist.application.dto.ProductSummaryDto;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
```

다음 상수와 필드를 제거한다.

```java
private static final String FALLBACK_TITLE = "상품 정보 없음";
private static final String FALLBACK_SELLER = "알 수 없음";
private final ProductClient productClient;
private final UserRepository userRepository;
```

`getWishlists`를 다음 코드로 교체하고 `toResult` 메서드 전체를 삭제한다.

```java
@Override
public List<WishlistItemResult> getWishlists(UUID userId, int page, int size) {
    return wishlistRepository.findByUserId(userId, page, size).stream()
            .map(WishlistItemResult::from)
            .toList();
}
```

- [ ] **Step 5: WishlistItemResponse를 공개 세 필드 계약으로 축소**

`WishlistItemResponse.java`를 다음 최종 내용으로 바꾼다.

```java
package com.prompthub.user.wishlist.presentation.dto.response;

import com.prompthub.user.wishlist.application.dto.WishlistItemResult;
import java.time.LocalDateTime;
import java.util.UUID;

public record WishlistItemResponse(
        UUID wishlistId,
        UUID productId,
        LocalDateTime addedAt
) {

    public static WishlistItemResponse from(WishlistItemResult result) {
        return new WishlistItemResponse(
                result.wishlistId(),
                result.productId(),
                result.addedAt()
        );
    }
}
```

- [ ] **Step 6: Wishlist 단위 테스트 통과 확인**

Run:

```bash
./gradlew :user-service:test --tests com.prompthub.user.wishlist.application.service.WishlistApplicationServiceTest
```

Expected: BUILD SUCCESSFUL. Wishlist 조회 테스트에서 Product 또는 Seller mock이 존재하지 않는다.

- [ ] **Step 7: 변경 파일 stage 및 계약 축소 커밋**

```bash
git add user-service/src/main/java/com/prompthub/user/wishlist/application/dto/WishlistItemResult.java
git add user-service/src/main/java/com/prompthub/user/wishlist/application/service/WishlistApplicationService.java
git add user-service/src/main/java/com/prompthub/user/wishlist/presentation/dto/response/WishlistItemResponse.java
git add user-service/src/test/java/com/prompthub/user/wishlist/application/service/WishlistApplicationServiceTest.java
git diff --cached --check
git commit -m "refactor: 위시리스트 응답을 소유 데이터로 축소 (#485)"
```

---

### Task 2: 사용하지 않는 Wishlist Product gRPC client 제거

**Files:**

- Delete: `user-service/src/main/java/com/prompthub/user/wishlist/application/client/ProductClient.java`
- Delete: `user-service/src/main/java/com/prompthub/user/wishlist/application/dto/ProductSummaryDto.java`
- Delete: `user-service/src/main/java/com/prompthub/user/wishlist/infrastructure/grpc/GrpcClientConfig.java`
- Delete: `user-service/src/main/java/com/prompthub/user/wishlist/infrastructure/grpc/ProductGrpcClient.java`
- Delete: `user-service/src/main/proto/product_service.proto`
- Modify: `user-service/build.gradle`
- Modify: `user-service/src/main/resources/application-local.yml`
- Modify: `config/src/main/resources/configs/user-service.yml`

**Interfaces:**

- Consumes: Task 1에서 외부 포트 의존이 제거된 `WishlistApplicationService`
- Produces: User 서비스에서 Product gRPC blocking stub과 client bean이 생성되지 않는 설정
- Preserves: User 서비스의 Seller gRPC server와 관련 proto 생성

- [ ] **Step 1: 제거 전 Product gRPC client 잔존 지점 확인**

```bash
rg -n 'ProductClient|ProductSummaryDto|ProductGrpcClient|ProductServiceBlockingStub|grpc\.client\.product-service|product-service:' \
  user-service/src/main user-service/src/test user-service/build.gradle \
  user-service/src/main/resources/application-local.yml config/src/main/resources/configs/user-service.yml
```

Expected: 제거 대상 네 Java 파일, 로컬 proto, gRPC client starter와 두 설정 파일만 결과에 남는다. Task 1에서 수정한 서비스·테스트는 결과에 없어야 한다.

- [ ] **Step 2: Wishlist Product gRPC 전용 파일 삭제**

```diff
*** Begin Patch
*** Delete File: user-service/src/main/java/com/prompthub/user/wishlist/application/client/ProductClient.java
*** Delete File: user-service/src/main/java/com/prompthub/user/wishlist/application/dto/ProductSummaryDto.java
*** Delete File: user-service/src/main/java/com/prompthub/user/wishlist/infrastructure/grpc/GrpcClientConfig.java
*** Delete File: user-service/src/main/java/com/prompthub/user/wishlist/infrastructure/grpc/ProductGrpcClient.java
*** Delete File: user-service/src/main/proto/product_service.proto
*** End Patch
```

- [ ] **Step 3: gRPC client starter만 제거하고 server 생성 의존성 유지**

`user-service/build.gradle`의 dependencies에서 다음 줄을 삭제한다.

```gradle
implementation 'org.springframework.boot:spring-boot-starter-grpc-client'
```

기존 주석과 나머지 의존성은 다음처럼 정리한다.

```gradle
// gRPC 서버 (Seller 조회 제공)
implementation 'org.springframework.boot:spring-boot-starter-grpc-server'

// protobuf로 생성되는 gRPC 서버 코드 컴파일
implementation 'io.grpc:grpc-stub'
implementation 'io.grpc:grpc-protobuf'
```

- [ ] **Step 4: 로컬과 배포 설정에서 Product gRPC client 블록 제거**

`user-service/src/main/resources/application-local.yml`과 `config/src/main/resources/configs/user-service.yml`에서 `grpc.server.port`는 유지하고 아래 `client` 블록만 삭제한다.

```yaml
  client:
    product-service:
      address: 'static://product-service:${PRODUCT_GRPC_SERVER_PORT}'
      negotiation-type: plaintext
```

로컬 파일의 실제 address가 `static://localhost:9082`여도 같은 방식으로 `client.product-service` 블록 전체를 제거한다.

- [ ] **Step 5: 잔존 참조가 없는지 확인**

```bash
rg -n 'ProductClient|ProductSummaryDto|ProductGrpcClient|ProductServiceBlockingStub|user\.product|grpc\.client\.product-service' \
  user-service/src/main user-service/src/test user-service/build.gradle \
  user-service/src/main/resources/application-local.yml config/src/main/resources/configs/user-service.yml
```

Expected: exit 1, 결과 없음.

- [ ] **Step 6: User gRPC server가 유지된 상태로 컴파일되는지 확인**

Run:

```bash
./gradlew :user-service:compileJava :user-service:compileTestJava
```

Expected: BUILD SUCCESSFUL. Seller gRPC server 구현과 생성 클래스 import가 정상 컴파일된다.

- [ ] **Step 7: 제거 변경 커밋**

```bash
git add user-service/build.gradle
git add user-service/src/main/resources/application-local.yml
git add config/src/main/resources/configs/user-service.yml
git add -u user-service/src/main/java/com/prompthub/user/wishlist user-service/src/main/proto/product_service.proto
git diff --cached --check
git commit -m "refactor: 위시리스트 Product gRPC 클라이언트 제거 (#485)"
```

---

### Task 3: Wishlist용 Seller 다건 조회 경로 추가

**Files:**

- Modify: `user-service/src/test/java/com/prompthub/user/seller/presentation/controller/SellerControllerTest.java`
- Modify: `user-service/src/main/java/com/prompthub/user/seller/presentation/controller/SellerController.java`

**Interfaces:**

- Consumes: `SellerIdsRequest(List<UUID> sellerIds)`
- Consumes: `SellerQueryUseCase.findSellers(List<String>) -> List<SellerInfoResult>`
- Produces: `POST /api/v2/sellers/wishlists -> ApiResult<SellerNamesResponse>`
- Preserves: `POST /api/v2/sellers/products`

- [ ] **Step 1: 새 경로의 위임과 응답을 고정하는 실패 테스트 작성**

`SellerControllerTest`에 다음 import와 mock을 추가한다. 중복된 `TestPropertySource` import는 함께 제거한다.

```java
import com.prompthub.user.seller.application.dto.SellerInfoResult;
import com.prompthub.user.seller.application.usecase.SellerQueryUseCase;
import java.util.List;

import static org.mockito.BDDMockito.then;

@MockitoBean
private SellerQueryUseCase sellerQueryUseCase;
```

다음 두 테스트를 추가한다.

```java
@Test
void getWishlistSellers_기존_조회_유스케이스로_판매자_이름을_반환한다() throws Exception {
    UUID foundSellerId = UUID.randomUUID();
    UUID missingSellerId = UUID.randomUUID();
    given(sellerQueryUseCase.findSellers(
            List.of(foundSellerId.toString(), missingSellerId.toString())))
            .willReturn(List.of(new SellerInfoResult(
                    foundSellerId.toString(), "김철수", "", "ACTIVE")));

    mockMvc.perform(post("/api/v2/sellers/wishlists")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                            java.util.Map.of("sellerIds", List.of(foundSellerId, missingSellerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.sellers[0].sellerId").value(foundSellerId.toString()))
            .andExpect(jsonPath("$.data.sellers[0].sellerName").value("김철수"))
            .andExpect(jsonPath("$.data.sellers[1].sellerId").value(missingSellerId.toString()))
            .andExpect(jsonPath("$.data.sellers[1].sellerName")
                    .value(org.hamcrest.Matchers.nullValue()));

    then(sellerQueryUseCase).should().findSellers(
            List.of(foundSellerId.toString(), missingSellerId.toString()));
}

@Test
void getWishlistSellers_빈_목록이면_400을_반환한다() throws Exception {
    mockMvc.perform(post("/api/v2/sellers/wishlists")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sellerIds\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("V001"));

    then(sellerQueryUseCase).shouldHaveNoInteractions();
}
```

- [ ] **Step 2: 새 경로가 없어 404로 실패하는지 확인**

Run:

```bash
./gradlew :user-service:test --tests com.prompthub.user.seller.presentation.controller.SellerControllerTest
```

Expected: FAIL. `/api/v2/sellers/wishlists`가 매핑되지 않아 정상 테스트가 `404`를 받는다.

- [ ] **Step 3: 기존 Seller DTO와 UseCase를 재사용하는 Controller 메서드 추가**

`SellerController`의 기존 `/api/v2/sellers/products` 메서드 다음에 아래 메서드를 추가한다.

```java
@Operation(summary = "Wishlist 판매자 이름 다건 조회",
        description = "Wishlist 상품 응답의 sellerId(UUID) 목록으로 판매자 이름을 조회한다. "
                + "중복은 서버가 제거하며 존재하지 않는 sellerId는 sellerName: null로 포함한다.")
@ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
                content = @Content(schema = @Schema(implementation = SellerNamesResponse.class))),
        @ApiResponse(responseCode = "400", description = "빈 배열, 30개 초과, 잘못된 UUID 형식 (V001)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
@PostMapping("/api/v2/sellers/wishlists")
public ApiResult<SellerNamesResponse> getWishlistSellers(
        @Valid @RequestBody SellerIdsRequest request) {
    List<SellerInfoResult> results = sellerQueryUseCase.findSellers(request.sellerIdStrings());
    return ApiResult.success(SellerNamesResponse.of(request.sellerIds(), results));
}
```

- [ ] **Step 4: Controller와 공유 DTO 응답 테스트 통과 확인**

Run:

```bash
./gradlew :user-service:test \
  --tests com.prompthub.user.seller.presentation.controller.SellerControllerTest \
  --tests com.prompthub.user.seller.presentation.dto.response.SellerNamesResponseTest
```

Expected: BUILD SUCCESSFUL. `/sellers/products`의 기존 코드와 UseCase는 변경되지 않는다.

- [ ] **Step 5: 새 Seller 경로 커밋**

```bash
git add user-service/src/main/java/com/prompthub/user/seller/presentation/controller/SellerController.java
git add user-service/src/test/java/com/prompthub/user/seller/presentation/controller/SellerControllerTest.java
git diff --cached --check
git commit -m "feat: Wishlist 판매자 다건 조회 API 추가 (#485)"
```

---

### Task 4: User API와 통신 문서 동기화

**Files:**

- Modify: `docs/api-spec/user.md`
- Modify: `docs/architecture/overview.md`
- Modify: `docs/grpc-contract-ownership.md`

**Interfaces:**

- Consumes: Task 1의 축소 Wishlist 응답
- Consumes: Task 3의 `/api/v2/sellers/wishlists` 계약
- Produces: 프론트 구현자가 그대로 사용할 수 있는 공개 API와 통신 문서

- [ ] **Step 1: Wishlist 응답 예시와 필드 표 축소**

`docs/api-spec/user.md`의 `GET /wishlists` 응답 항목을 다음 세 필드로 바꾼다.

```json
{
  "success": true,
  "data": [
    {
      "wishlistId": "uuid",
      "productId": "uuid",
      "addedAt": "2026-07-22T12:00:00"
    }
  ],
  "message": "success",
  "meta": {
    "page": 0,
    "size": 20,
    "total": 1,
    "hasNext": false
  }
}
```

필드 표에는 `wishlistId`, `productId`, `addedAt`과 기존 meta 네 필드만 남긴다. Product·Seller 카드 필드는 프론트가 별도 API로 조회한다고 설명한다.

- [ ] **Step 2: Seller Wishlist 다건 조회 계약 추가**

`POST /sellers/products` 절 다음에 `POST /sellers/wishlists` 절을 추가한다. 요청·응답 스키마, 최대 30개, 중복 제거, 누락 판매자 `null`은 `/sellers/products`와 같다고 명시하고, 용도와 인증만 다음처럼 구분한다.

```markdown
### POST /sellers/wishlists — Wishlist 판매자 이름 다건 조회

- 인증: 필요
- 필요 역할: BUYER / SELLER
- `POST /products/wishlists` 응답의 `sellerId` 목록을 받아 Wishlist 카드의 판매자명을 채운다.
- 요청·응답과 검증 규칙은 `POST /sellers/products`와 동일하다.
```

- [ ] **Step 3: 아키텍처 문서에서 죽은 User → Product gRPC 연결 제거**

`docs/architecture/overview.md`의 내부 통신 표에서 다음 행을 삭제한다.

```markdown
| user → product | 9082 | 찜 상품 정보 조회 | `user-service` `application.yml` `grpc.client.product-service`, `wishlist/infrastructure/grpc/GrpcClientConfig.java` |
```

REST 외부 통신 설명에 다음 흐름을 짧게 추가한다.

```markdown
- Wishlist 화면은 Client가 User `GET /wishlists`, Product `POST /products/wishlists`,
  User `POST /sellers/wishlists`를 순차 호출해 조합한다. User 서비스는 Product gRPC를 호출하지 않는다.
```

- [ ] **Step 4: gRPC 계약 소유 문서에 로컬 계약 제거 기록**

`docs/grpc-contract-ownership.md`의 제거된 계약 설명에 다음 내용을 추가한다.

```markdown
> **제거됨:** user-service Wishlist가 보유하던 로컬 `user.product.ProductService/GetProductsByIds`
> 계약과 client는 #485에서 삭제했다. Product 서버에 구현되지 않았던 계약이며,
> Wishlist 상품 조회는 Client가 Product REST `POST /api/v2/products/wishlists`를 직접 호출한다.
```

- [ ] **Step 5: 문서 정합성 검색**

```bash
rg -n 'wishlist.*gRPC|찜 상품 정보 조회|ProductGrpcClient|grpc\.client\.product-service|GET /products/by-ids|sellers/batch' \
  docs user-service/src/main/resources config/src/main/resources/configs/user-service.yml
```

Expected: 현재 동작을 설명하는 문서에 User Wishlist → Product gRPC 또는 구 Product GET 경로가 남지 않는다. 과거 이력 문장은 `제거됨`, `옛`, `#485` 같은 종료 맥락일 때만 허용한다.

- [ ] **Step 6: 문서 커밋**

```bash
git add docs/api-spec/user.md docs/architecture/overview.md docs/grpc-contract-ownership.md
git diff --cached --check
git commit -m "docs: Wishlist 프론트 조합 API 계약 반영 (#485)"
```

---

### Task 5: 백엔드 통합 검증과 Product 선행 계약 확인

**Files:**

- Verify only: `user-service/`
- Verify only: `config/src/main/resources/configs/user-service.yml`
- Verify only: `docs/`

**Interfaces:**

- Consumes: Tasks 1–4 전체 변경
- Consumes: `#478 (PR)`의 Product POST 계약
- Produces: 프론트 계획을 실행할 수 있는 검증된 User API 계약

- [ ] **Step 1: #478 병합 상태와 Product 계약 확인**

```bash
gh pr view 478 --repo prgrms-be-adv-devcourse/beadv6_6_3JMT_BE \
  --json state,mergedAt,mergeCommit,url
```

Expected: `MERGED`이며 merge commit이 확인된다. 아직 열려 있으면 Product 코드를 cherry-pick하지 말고 최종 통합 검증과 배포를 보류한다.

- [ ] **Step 2: #478이 병합됐다면 최신 develop을 반영**

```bash
git fetch origin develop
git rebase origin/develop
```

Expected: rebase 성공. Product 파일 충돌이 나면 이번 브랜치에서 Product 구현을 수정하지 않고 `#478 (PR)` 계약을 우선한다.

- [ ] **Step 3: User 전체 테스트와 정적 검증**

```bash
./gradlew :user-service:clean :user-service:test :user-service:check
```

Expected: BUILD SUCCESSFUL, 테스트 실패 0, checkstyle 위반 0.

- [ ] **Step 4: 제거 계약과 공개 경로 최종 검색**

```bash
rg -n 'ProductClient|ProductSummaryDto|ProductGrpcClient|ProductServiceBlockingStub|user\.product|grpc\.client\.product-service' \
  user-service config/src/main/resources/configs/user-service.yml
```

Expected: 결과 없음.

```bash
rg -n 'PostMapping\("/api/v2/sellers/wishlists"\)|wishlistId|productId|addedAt' \
  user-service/src/main/java/com/prompthub/user/seller/presentation/controller/SellerController.java \
  user-service/src/main/java/com/prompthub/user/wishlist
```

Expected: Seller 새 경로와 Wishlist 세 필드 계약만 확인된다.

- [ ] **Step 5: 작업 트리와 커밋 경계 확인**

```bash
git status --short
git log --oneline --decorate origin/develop..HEAD
git diff --check origin/develop...HEAD
```

Expected: 작업 트리 clean, #485 관련 코드·설정·문서 커밋만 존재, whitespace 오류 없음.
