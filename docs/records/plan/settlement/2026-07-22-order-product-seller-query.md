# Order Product Seller Query Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** User 서비스에 구매한 상품 화면 전용 판매자 이름 다건 조회 API를 추가한다.

**Architecture:** 기존 `SellerController`에 `POST /api/v2/users/order-products`를 추가하고, 공개 계약은 전용 Request/Response DTO로 분리한다. Controller는 기존 `SellerQueryUseCase.findSellers()`에만 위임하며 Application, Domain, Repository 계층은 변경하지 않는다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring MVC, Jakarta Validation, springdoc-openapi, JUnit 5, Mockito, AssertJ, MockMvc, Gradle

## Global Constraints

- 기준 이슈는 `#487 (이슈)`, 브랜치는 `feat/#487-order-product-sellers (브랜치)`다.
- 설계 원본은 `settlement-service/docs/superpowers/specs/2026-07-22-order-product-seller-query-design.md`다.
- 공개 경로는 정확히 `POST /api/v2/users/order-products`다.
- 요청과 응답은 구매한 상품 화면 전용 DTO를 새로 만든다. 기존 `SellerIdsRequest`, `SellerNamesResponse`를 재사용하지 않는다.
- 요청 필드는 `sellerIds`이고 빈 배열을 허용하지 않으며 최대 30개 UUID를 받는다.
- 응답은 `data.sellers[].sellerId`, `data.sellers[].sellerName`만 제공한다.
- 중복 ID는 첫 등장 순서대로 한 번만 반환하고, 조회되지 않은 ID는 `sellerName: null`로 반환한다.
- 조회는 기존 `SellerQueryUseCase.findSellers(List<String>)`만 재사용한다. 새 UseCase, ApplicationService, Repository 메서드를 만들지 않는다.
- 기존 `POST /api/v2/sellers/products`의 코드, DTO, 계약과 테스트 동작을 변경하지 않는다.
- Gateway whitelist를 수정하지 않는다. `/users/order-products`는 기본 인증 정책을 사용한다.
- Order, Product, Frontend 저장소는 이번 백엔드 계획에서 수정하지 않는다.
- 루트 `docs/api-spec/user.md` 변경은 `#487 (이슈)`와 승인된 설계 범위에 포함된다.

---

## Execution Preflight

```bash
cd /Users/taetaetae/orca/workspaces/beadv6_6_3JMT_BE/구매한-상품-페이지-변경
git status --short
git branch --show-current
git log -1 --oneline
sed -n '1,220p' settlement-service/AGENTS.md
sed -n '1,240p' user-service/CLAUDE.md
sed -n '1,220p' user-service/.claude/rules/clean-architecture.md
sed -n '1,220p' user-service/.claude/rules/controller-exception.md
sed -n '1,220p' user-service/.claude/rules/swagger.md
sed -n '1,180p' user-service/.claude/rules/code-style.md
sed -n '1,180p' user-service/.claude/rules/git-convention.md
```

Expected: 작업 트리가 clean이고 현재 브랜치가 `feat/#487-order-product-sellers`이며, `2a4236c6 (커밋)` 이후에서 작업한다. 다른 변경이 있으면 임의로 stash, reset, commit하지 않고 사용자 변경과 분리한다.

---

## File Map

### Create

- `user-service/src/main/java/com/prompthub/user/seller/presentation/dto/request/OrderProductSellerIdsRequest.java` — 구매 상품 화면의 판매자 ID 목록 검증과 UseCase 입력 변환.
- `user-service/src/main/java/com/prompthub/user/seller/presentation/dto/response/OrderProductSellerNamesResponse.java` — 요청 순서, 중복 제거, 누락 판매자 `null`을 보장하는 공개 응답.
- `user-service/src/test/java/com/prompthub/user/seller/presentation/dto/response/OrderProductSellerNamesResponseTest.java` — 전용 응답 필드와 조립 정책 검증.
- `user-service/src/test/java/com/prompthub/user/seller/presentation/controller/SellerControllerOrderProductTest.java` — 새 HTTP 경로의 위임, 응답, 검증 계약 확인.

### Modify

- `user-service/src/main/java/com/prompthub/user/seller/presentation/controller/SellerController.java` — `/api/v2/users/order-products` 메서드와 Swagger 계약 추가.
- `user-service/docs/기획문서.md` — 구현 대상과 완료 상태를 User 서비스 현황에 반영.
- `docs/api-spec/user.md` — 인증, 요청·응답, 검증 오류와 누락 판매자 정책 문서화.

### Explicitly Unchanged

- `user-service/src/main/java/com/prompthub/user/seller/application/usecase/SellerQueryUseCase.java`
- `user-service/src/main/java/com/prompthub/user/seller/application/service/SellerQueryApplicationService.java`
- `user-service/src/main/java/com/prompthub/user/seller/presentation/dto/request/SellerIdsRequest.java`
- `user-service/src/main/java/com/prompthub/user/seller/presentation/dto/response/SellerNamesResponse.java`
- `apigateway/`
- `order-service/`
- `product-service/`
- `/Users/taetaetae/IdeaProjects/beadv6_6_3JMT_FE`

---

### Task 1: 구매 상품 전용 판매자 응답 DTO

**Files:**

- Create: `user-service/src/test/java/com/prompthub/user/seller/presentation/dto/response/OrderProductSellerNamesResponseTest.java`
- Create: `user-service/src/main/java/com/prompthub/user/seller/presentation/dto/response/OrderProductSellerNamesResponse.java`

**Interfaces:**

- Consumes: `List<UUID> requestedSellerIds`
- Consumes: `List<SellerInfoResult> results`
- Produces: `OrderProductSellerNamesResponse.of(List<UUID>, List<SellerInfoResult>)`
- Produces: `OrderProductSellerNamesResponse.Item(UUID sellerId, String sellerName)`
- Preserves: 기존 `SellerNamesResponse`와 `/sellers/products` 응답 계약

- [ ] **Step 1: 전용 응답 계약의 실패 테스트 작성**

`OrderProductSellerNamesResponseTest.java`를 생성한다.

```java
package com.prompthub.user.seller.presentation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.user.seller.application.dto.SellerInfoResult;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderProductSellerNamesResponseTest {

    @Test
    void of_요청_순서대로_이름을_매핑하고_누락된_판매자는_null로_반환한다() {
        UUID found = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        SellerInfoResult result = new SellerInfoResult(
                found.toString(), "김철수", "https://cdn.example.com/profile.png", "ACTIVE");

        OrderProductSellerNamesResponse response = OrderProductSellerNamesResponse.of(
                List.of(found, missing), List.of(result));

        assertThat(response.sellers()).containsExactly(
                new OrderProductSellerNamesResponse.Item(found, "김철수"),
                new OrderProductSellerNamesResponse.Item(missing, null)
        );
    }

    @Test
    void of_중복된_sellerId는_첫_등장_한_건만_반환한다() {
        UUID sellerId = UUID.randomUUID();
        SellerInfoResult result = new SellerInfoResult(
                sellerId.toString(), "김철수", "", "ACTIVE");

        OrderProductSellerNamesResponse response = OrderProductSellerNamesResponse.of(
                List.of(sellerId, sellerId, sellerId), List.of(result));

        assertThat(response.sellers()).containsExactly(
                new OrderProductSellerNamesResponse.Item(sellerId, "김철수")
        );
    }

    @Test
    void item은_sellerId와_sellerName만_노출한다() {
        assertThat(OrderProductSellerNamesResponse.Item.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("sellerId", "sellerName");
    }
}
```

- [ ] **Step 2: 응답 DTO가 없어 테스트가 실패하는지 확인**

Run:

```bash
./gradlew :user-service:test \
  --tests com.prompthub.user.seller.presentation.dto.response.OrderProductSellerNamesResponseTest
```

Expected: FAIL. `OrderProductSellerNamesResponse` 타입을 찾을 수 없어 test compilation이 실패한다.

- [ ] **Step 3: 전용 응답 DTO 최소 구현**

`OrderProductSellerNamesResponse.java`를 생성한다.

```java
package com.prompthub.user.seller.presentation.dto.response;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.prompthub.user.seller.application.dto.SellerInfoResult;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "구매한 상품 판매자 이름 다건 조회 응답")
public record OrderProductSellerNamesResponse(
        @Schema(description = "구매한 상품의 판매자 이름 목록")
        List<Item> sellers
) {

    public static OrderProductSellerNamesResponse of(
            List<UUID> requestedSellerIds,
            List<SellerInfoResult> results
    ) {
        Map<String, String> nameById = results.stream()
                .collect(Collectors.toMap(SellerInfoResult::sellerId, SellerInfoResult::sellerName));

        List<Item> items = requestedSellerIds.stream()
                .distinct()
                .map(sellerId -> new Item(sellerId, nameById.get(sellerId.toString())))
                .toList();

        return new OrderProductSellerNamesResponse(items);
    }

    @Schema(description = "구매한 상품 판매자 이름 항목")
    public record Item(
            @Schema(description = "판매자 ID(UUID)", example = "3f1b1b0e-1111-2222-3333-444444444444")
            UUID sellerId,

            @Schema(description = "판매자 이름, 조회되지 않은 sellerId면 null", nullable = true)
            String sellerName
    ) {
    }
}
```

- [ ] **Step 4: 응답 DTO 테스트 통과 확인**

Run:

```bash
./gradlew :user-service:test \
  --tests com.prompthub.user.seller.presentation.dto.response.OrderProductSellerNamesResponseTest
```

Expected: BUILD SUCCESSFUL. 3개 테스트가 모두 통과한다.

- [ ] **Step 5: 응답 DTO 커밋**

```bash
git add user-service/src/main/java/com/prompthub/user/seller/presentation/dto/response/OrderProductSellerNamesResponse.java
git add user-service/src/test/java/com/prompthub/user/seller/presentation/dto/response/OrderProductSellerNamesResponseTest.java
git diff --cached --check
git commit -m "feat: 구매 상품 판매자 응답 DTO 추가 (#487)"
```

---

### Task 2: 구매 상품 판매자 조회 Controller 경로

**Files:**

- Create: `user-service/src/test/java/com/prompthub/user/seller/presentation/controller/SellerControllerOrderProductTest.java`
- Create: `user-service/src/main/java/com/prompthub/user/seller/presentation/dto/request/OrderProductSellerIdsRequest.java`
- Modify: `user-service/src/main/java/com/prompthub/user/seller/presentation/controller/SellerController.java`
- Modify: `user-service/docs/기획문서.md`

**Interfaces:**

- Consumes: `POST /api/v2/users/order-products` body `{ "sellerIds": UUID[] }`
- Consumes: `SellerQueryUseCase.findSellers(List<String>) -> List<SellerInfoResult>`
- Produces: `ApiResult<OrderProductSellerNamesResponse>`
- Produces: `OrderProductSellerIdsRequest.sellerIdStrings() -> List<String>`
- Preserves: `POST /api/v2/sellers/products`, `GET /api/v2/sellers/product`, `POST /api/v2/seller/register`

- [ ] **Step 1: 구현 현황에 미구현 API 등록**

`user-service/docs/기획문서.md`의 판매자 표에 다음 행을 추가해 구현 전 대상임을 기록한다.

```markdown
| - [ ] | POST | `/users/order-products` |
```

이 단계에서는 진행 현황 요약 숫자를 바꾸지 않는다. Task 완료 직전에 구현 완료 값으로 함께 정리한다.

- [ ] **Step 2: 새 경로의 위임·응답·검증 실패 테스트 작성**

`SellerControllerOrderProductTest.java`를 생성한다.

```java
package com.prompthub.user.seller.presentation.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prompthub.user.seller.application.dto.SellerInfoResult;
import com.prompthub.user.seller.application.usecase.SellerQueryUseCase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SellerControllerOrderProductTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SellerQueryUseCase sellerQueryUseCase;

    @Test
    void getOrderProductSellers_기존_조회_유스케이스로_판매자_이름을_반환한다() throws Exception {
        UUID foundSellerId = UUID.randomUUID();
        UUID missingSellerId = UUID.randomUUID();
        List<String> sellerIds = List.of(foundSellerId.toString(), missingSellerId.toString());
        given(sellerQueryUseCase.findSellers(sellerIds))
                .willReturn(List.of(new SellerInfoResult(
                        foundSellerId.toString(), "김철수", "https://cdn.example.com/profile.png", "ACTIVE")));

        mockMvc.perform(post("/api/v2/users/order-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("sellerIds", sellerIds))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sellers[0].sellerId").value(foundSellerId.toString()))
                .andExpect(jsonPath("$.data.sellers[0].sellerName").value("김철수"))
                .andExpect(jsonPath("$.data.sellers[0].profileImageUrl").doesNotExist())
                .andExpect(jsonPath("$.data.sellers[0].status").doesNotExist())
                .andExpect(jsonPath("$.data.sellers[1].sellerId").value(missingSellerId.toString()))
                .andExpect(jsonPath("$.data.sellers[1].sellerName").value(Matchers.nullValue()));

        then(sellerQueryUseCase).should().findSellers(sellerIds);
    }

    @Test
    void getOrderProductSellers_빈_목록이면_400_V001을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v2/users/order-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V001"));

        then(sellerQueryUseCase).shouldHaveNoInteractions();
    }

    @Test
    void getOrderProductSellers_31개_요청이면_400_V001을_반환한다() throws Exception {
        List<String> sellerIds = IntStream.range(0, 31)
                .mapToObj(index -> UUID.randomUUID().toString())
                .toList();

        mockMvc.perform(post("/api/v2/users/order-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("sellerIds", sellerIds))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V001"));

        then(sellerQueryUseCase).shouldHaveNoInteractions();
    }
}
```

- [ ] **Step 3: 새 경로가 없어 정상 테스트가 실패하는지 확인**

Run:

```bash
./gradlew :user-service:test \
  --tests com.prompthub.user.seller.presentation.controller.SellerControllerOrderProductTest
```

Expected: FAIL. 정상 테스트가 매핑되지 않은 `/api/v2/users/order-products`에서 `404`를 받는다. 검증 테스트도 현재 경로가 없어 기대한 `400 V001`과 일치하지 않는다.

- [ ] **Step 4: 전용 Request DTO 구현**

`OrderProductSellerIdsRequest.java`를 생성한다.

```java
package com.prompthub.user.seller.presentation.dto.request;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

@Schema(description = "구매한 상품 판매자 이름 다건 조회 요청")
public record OrderProductSellerIdsRequest(
        @Schema(
                description = "구매한 상품의 판매자 ID(UUID) 목록, 최대 30개",
                example = "[\"3f1b1b0e-1111-2222-3333-444444444444\"]"
        )
        @NotEmpty @Size(max = 30) List<UUID> sellerIds
) {

    public List<String> sellerIdStrings() {
        return sellerIds.stream().map(UUID::toString).toList();
    }
}
```

- [ ] **Step 5: SellerController에 인증된 구매 상품 경로 추가**

`SellerController.java`에 다음 import를 추가한다.

```java
import com.prompthub.user.seller.presentation.dto.request.OrderProductSellerIdsRequest;
import com.prompthub.user.seller.presentation.dto.response.OrderProductSellerNamesResponse;
```

기존 `getSellers()` 다음에 아래 메서드를 추가한다.

```java
@Operation(summary = "구매 상품 판매자 이름 다건 조회",
        description = "구매한 상품 응답의 sellerId(UUID) 목록으로 판매자 이름을 조회한다. "
                + "중복은 제거하며 조회되지 않은 sellerId는 sellerName: null로 포함한다.")
@SecurityRequirement(name = "Bearer")
@ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
                content = @Content(schema = @Schema(
                        implementation = OrderProductSellerNamesResponse.class))),
        @ApiResponse(responseCode = "400",
                description = "빈 배열, 30개 초과, 잘못된 UUID 형식 (V001)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
@PostMapping("/api/v2/users/order-products")
public ApiResult<OrderProductSellerNamesResponse> getOrderProductSellers(
        @Valid @RequestBody OrderProductSellerIdsRequest request
) {
    List<SellerInfoResult> results = sellerQueryUseCase.findSellers(request.sellerIdStrings());
    return ApiResult.success(OrderProductSellerNamesResponse.of(request.sellerIds(), results));
}
```

Gateway는 whitelist에 없는 경로를 `authenticated()`로 처리하므로 `apigateway/` 파일은 수정하지 않는다. User 서비스의 MockMvc 테스트는 Gateway를 통과하지 않으므로 인증 토큰 여부가 아닌 Controller 계약만 검증한다.

- [ ] **Step 6: 새 경로와 기존 Seller 경로 회귀 테스트 통과 확인**

Run:

```bash
./gradlew :user-service:test \
  --tests com.prompthub.user.seller.presentation.controller.SellerControllerOrderProductTest \
  --tests com.prompthub.user.seller.presentation.controller.SellerControllerGetSellerTest \
  --tests com.prompthub.user.seller.presentation.controller.SellerControllerTest \
  --tests com.prompthub.user.seller.presentation.dto.response.OrderProductSellerNamesResponseTest \
  --tests com.prompthub.user.seller.presentation.dto.response.SellerNamesResponseTest
```

Expected: BUILD SUCCESSFUL. 새 테스트 6개와 기존 Seller Controller/DTO 테스트가 모두 통과한다.

- [ ] **Step 7: User 구현 현황을 완료 상태로 갱신**

`user-service/docs/기획문서.md`의 판매자 표에서 새 행을 완료 처리한다.

```markdown
| - [x] | POST | `/users/order-products` |
```

진행 현황 요약을 다음 값으로 바꾼다.

```markdown
- 전체: 18개
- 구현 완료: 15개
- 미구현: 3개
```

- [ ] **Step 8: Controller와 전용 Request DTO 커밋**

```bash
git add user-service/src/main/java/com/prompthub/user/seller/presentation/controller/SellerController.java
git add user-service/src/main/java/com/prompthub/user/seller/presentation/dto/request/OrderProductSellerIdsRequest.java
git add user-service/src/test/java/com/prompthub/user/seller/presentation/controller/SellerControllerOrderProductTest.java
git add user-service/docs/기획문서.md
git diff --cached --check
git commit -m "feat: 구매 상품 판매자 다건 조회 API 추가 (#487)"
```

---

### Task 3: 공개 API 명세와 User 모듈 전체 검증

**Files:**

- Modify: `docs/api-spec/user.md`

**Interfaces:**

- Documents: `POST /api/v2/users/order-products`
- Documents: `{ sellerIds: UUID[] } -> { sellers: { sellerId, sellerName }[] }`
- Verifies: User 서비스 전체 테스트, Checkstyle, 공개 경로와 DTO 필드 일치

- [ ] **Step 1: User 공개 API 명세에 구매 상품 판매자 조회 계약 추가**

`docs/api-spec/user.md`의 `POST /sellers/products` 섹션 다음에 아래 내용을 추가한다.

````markdown
### POST /users/order-products — 구매 상품 판매자 이름 다건 조회

- 인증: 필요
- 필요 역할: BUYER / SELLER
- `/mypage?tab=purchased`에서 Order와 Product 응답을 조합한 뒤 Product의 `sellerId` 목록으로 판매자 이름을 조회한다.
- 기존 `/sellers/products`와 조회 UseCase는 같지만 요청·응답 DTO는 구매 상품 화면 전용 계약으로 분리한다.

#### Request

**Body**

```json
{
  "sellerIds": [
    "3f1b1b0e-1111-2222-3333-444444444444",
    "9a2c2c1f-5555-6666-7777-888888888888"
  ]
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| sellerIds | string(UUID)[] | Y | 조회할 판매자 ID 목록. 최대 30개, 빈 배열 금지, 중복은 첫 등장 기준으로 제거 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "sellers": [
      {
        "sellerId": "3f1b1b0e-1111-2222-3333-444444444444",
        "sellerName": "김철수"
      },
      {
        "sellerId": "9a2c2c1f-5555-6666-7777-888888888888",
        "sellerName": null
      }
    ]
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| sellers[].sellerId | string(UUID) | 요청한 판매자 ID |
| sellers[].sellerName | string \| null | 판매자 이름. 조회되지 않은 판매자는 null이며 전체 요청은 성공 처리 |

**400 Bad Request** — 빈 배열, 잘못된 UUID 형식, 30개 초과 (`VALIDATION_FAILED`, V001)

---
````

- [ ] **Step 2: 경로, DTO, UseCase 재사용과 변경 금지 범위 정적 확인**

Run:

```bash
rg -n 'users/order-products|OrderProductSellerIdsRequest|OrderProductSellerNamesResponse' \
  user-service/src/main/java user-service/src/test/java docs/api-spec/user.md
rg -n 'findSellers' \
  user-service/src/main/java/com/prompthub/user/seller/application \
  user-service/src/main/java/com/prompthub/user/seller/presentation
git diff origin/develop...HEAD -- \
  user-service/src/main/java/com/prompthub/user/seller/application \
  user-service/src/main/java/com/prompthub/user/seller/domain \
  user-service/src/main/java/com/prompthub/user/seller/infrastructure \
  apigateway order-service product-service
```

Expected: 새 경로와 전용 DTO가 Controller, 테스트, 문서에서 같은 이름으로 확인된다. `findSellers`는 기존 UseCase를 호출하며 Application, Domain, Infrastructure, Gateway, Order, Product에는 #487 구현 diff가 없다.

- [ ] **Step 3: User 서비스 전체 테스트와 Checkstyle 실행**

Run:

```bash
./gradlew :user-service:test :user-service:checkstyleMain
```

Expected: BUILD SUCCESSFUL. User 서비스 전체 테스트가 통과하고 Checkstyle 신규 위반이 없다. 루트 설정상 `checkstyleTest`는 비활성화되어 별도로 실행하지 않는다.

- [ ] **Step 4: 변경 파일과 공백 오류 최종 확인**

Run:

```bash
git status --short
git diff --check
git diff --stat origin/develop...HEAD
```

Expected: 계획에 명시한 User 소스·테스트·문서 파일만 변경되어 있고 whitespace 오류가 없다. `settlement-service/AGENTS.md`와 설계·계획 문서는 #487 문서 커밋으로만 존재한다.

- [ ] **Step 5: 공개 API 명세 커밋**

```bash
git add docs/api-spec/user.md
git diff --cached --check
git commit -m "docs: 구매 상품 판매자 조회 API 명세 추가 (#487)"
```

- [ ] **Step 6: 구현 종료점 확인**

Run:

```bash
git status --short
git log --oneline origin/develop..HEAD
```

Expected: 작업 트리가 clean이고 #487의 설계·담당 범위, 응답 DTO, Controller API, 공개 API 문서 커밋만 존재한다. push와 PR 생성은 별도 요청 전에는 수행하지 않는다.
