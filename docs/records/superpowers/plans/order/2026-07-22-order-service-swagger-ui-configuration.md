# Order Service Swagger UI Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the API Gateway Swagger UI describe and invoke order-service APIs with the same Bearer JWT model used by user-, product-, and payment-service, without exposing Gateway-injected identity headers as client inputs.

**Architecture:** Keep order-service on `springdoc-openapi-starter-webmvc-api` so it publishes only `/v3/api-docs`, while API Gateway remains the only Swagger UI host. Add presentation-level OpenAPI metadata and a Bearer security scheme to order-service, then align both order controllers with that scheme and hide `X-User-Id` because Gateway derives and injects it after JWT validation.

**Tech Stack:** Java 21, Spring Boot 4.1.0, springdoc-openapi 3.0.0, Spring Web MVC, JUnit 5, MockMvc, AssertJ.

## Global Constraints

- Keep `implementation "org.springdoc:springdoc-openapi-starter-webmvc-api:${springdocVersion}"` in order-service; do not add `springdoc-openapi-starter-webmvc-ui`.
- Keep API Gateway as the sole Swagger UI host at `/swagger-ui/index.html`.
- Do not modify the existing Gateway route `/order-service/v3/api-docs` or its `springdoc.swagger-ui.urls` entry; both are already present.
- Use the security-scheme name `Bearer`, HTTP scheme `bearer`, and bearer format `JWT`, matching user-, product-, and payment-service.
- Swagger UI callers provide `Authorization: Bearer {accessToken}`; they must not provide `X-User-Id` directly.
- Preserve the runtime `@RequestHeader(X-User-Id)` contract and the existing missing-header `401 A003` behavior. Only its OpenAPI visibility changes.
- Keep Swagger annotations in the presentation layer. Do not add Swagger dependencies or annotations to application, domain, or infrastructure code.
- Make no REST path, response body, database, Kafka, Redis, or gRPC contract changes.
- Preserve unrelated working-tree changes. Do not stage or commit unless the user separately authorizes it.

---

## File Map

- Create `src/main/java/com/prompthub/order/presentation/config/SwaggerConfig.java`: define order-service OpenAPI metadata, root server URL, and Bearer JWT security scheme.
- Create `src/test/java/com/prompthub/order/presentation/config/OpenApiContractTest.java`: verify the generated `/v3/api-docs` document, not just Java annotation presence.
- Modify `src/main/java/com/prompthub/order/presentation/OrderController.java`: use the Bearer scheme and hide all eight Gateway-injected buyer ID parameters.
- Modify `src/main/java/com/prompthub/order/presentation/CartController.java`: use the Bearer scheme and hide all three Gateway-injected buyer ID parameters.
- Do not modify `build.gradle` or `../apigateway/src/main/resources/application.yml`.

### Task 1: Define the order-service OpenAPI document and Bearer scheme

**Files:**

- Create: `src/main/java/com/prompthub/order/presentation/config/SwaggerConfig.java`
- Create: `src/test/java/com/prompthub/order/presentation/config/OpenApiContractTest.java`

**Interfaces:**

- Consumes: springdoc's existing `GET /v3/api-docs` endpoint and API Gateway's existing `/order-service/v3/api-docs` rewrite.
- Produces: OpenAPI `info`, `servers[0].url=/`, `components.securitySchemes.Bearer`, and global `security.Bearer` metadata.

- [ ] **Step 1: Write the failing generated-document contract test.**

Create `src/test/java/com/prompthub/order/presentation/config/OpenApiContractTest.java` with this content:

```java
package com.prompthub.order.presentation.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("OpenAPI 문서는 주문 서비스 정보와 Bearer 인증 스킴을 제공한다")
    void openApiDefinesOrderServiceBearerAuthentication() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.info.title").value("PromptHub Order Service API"))
            .andExpect(jsonPath("$.info.version").value("v1"))
            .andExpect(jsonPath("$.servers[0].url").value("/"))
            .andExpect(jsonPath("$.components.securitySchemes.Bearer.type").value("http"))
            .andExpect(jsonPath("$.components.securitySchemes.Bearer.scheme").value("bearer"))
            .andExpect(jsonPath("$.components.securitySchemes.Bearer.bearerFormat").value("JWT"))
            .andExpect(jsonPath("$.security[0].Bearer").isArray());
    }
}
```

- [ ] **Step 2: Run the focused test and confirm the RED state.**

Run from `order-service`:

```bash
../gradlew :order-service:test --tests "com.prompthub.order.presentation.config.OpenApiContractTest"
```

Expected: the test starts the application and fails at the first order-service-specific assertion because the generated document still has springdoc defaults and no `Bearer` security scheme.

- [ ] **Step 3: Add the minimal presentation-level Swagger configuration.**

Create `src/main/java/com/prompthub/order/presentation/config/SwaggerConfig.java`:

```java
package com.prompthub.order.presentation.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
    info = @Info(
        title = "PromptHub Order Service API",
        version = "v1",
        description = "주문·장바구니·구매 콘텐츠·환불 요청 기능을 제공하는 Order Service API"
    ),
    security = @SecurityRequirement(name = "Bearer"),
    servers = @Server(url = "/", description = "order-service")
)
@SecurityScheme(
    name = "Bearer",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description = "API Gateway가 검증할 JWT. Authorization 헤더에 'Bearer {accessToken}' 형식으로 입력"
)
@Configuration
public class SwaggerConfig {
}
```

The OpenAPI document version remains `v1` because user-, product-, and payment-service all use `v1` for `Info.version`, even while serving `/api/v2` routes. Do not redefine that repository-wide convention in this order-service-only change.

- [ ] **Step 4: Rerun the focused test and confirm the GREEN state.**

```bash
../gradlew :order-service:test --tests "com.prompthub.order.presentation.config.OpenApiContractTest"
```

Expected: `OpenApiContractTest` passes and `/v3/api-docs` contains the order-service title, root server, and Bearer security scheme.

- [ ] **Step 5: Review the Task 1 diff.**

```bash
sed -n '1,240p' src/main/java/com/prompthub/order/presentation/config/SwaggerConfig.java
sed -n '1,240p' src/test/java/com/prompthub/order/presentation/config/OpenApiContractTest.java
git diff --check
git status --short
```

Expected: both files contain only the planned OpenAPI configuration and contract test, `git diff --check` prints nothing, and `git status --short` lists both as untracked unless they were already staged by explicit user direction.

- [ ] **Step 6: Commit only when separately authorized.**

Suggested commit:

```bash
git add src/main/java/com/prompthub/order/presentation/config/SwaggerConfig.java src/test/java/com/prompthub/order/presentation/config/OpenApiContractTest.java
git commit -m "feat: order-service OpenAPI Bearer 인증 설정 추가"
```

Expected: one focused feature commit. Skip this step when commit authorization has not been given.

### Task 2: Align controller security and hide Gateway-injected headers

**Files:**

- Modify: `src/test/java/com/prompthub/order/presentation/config/OpenApiContractTest.java`
- Modify: `src/main/java/com/prompthub/order/presentation/OrderController.java`
- Modify: `src/main/java/com/prompthub/order/presentation/CartController.java`

**Interfaces:**

- Consumes: the `Bearer` security scheme produced by Task 1 and the existing runtime `AuthHeaders.USER_ID` header contract.
- Produces: Bearer-secured order/cart operations whose generated OpenAPI parameter lists omit `X-User-Id`.

- [ ] **Step 1: Extend the contract test with failing operation-level assertions.**

Add these imports to `OpenApiContractTest`:

```java
import com.prompthub.order.global.web.AuthHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
```

Add the injected mapper below the existing `MockMvc` field:

```java
@Autowired
private ObjectMapper objectMapper;
```

Add this test and helper methods to the class:

```java
@Test
@DisplayName("주문과 장바구니 API는 Bearer 인증을 사용하고 Gateway 사용자 헤더를 숨긴다")
void orderAndCartOperationsUseBearerAndHideGatewayUserId() throws Exception {
    String document = mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();

    JsonNode openApi = objectMapper.readTree(document);

    assertUsesBearerAndHidesUserId(openApi, "/api/v2/orders", "post");
    assertUsesBearerAndHidesUserId(openApi, "/api/v2/cart/products", "get");
}

private void assertUsesBearerAndHidesUserId(JsonNode openApi, String path, String method) {
    JsonNode operation = openApi.path("paths").path(path).path(method);

    assertThat(operation.isMissingNode()).isFalse();
    assertThat(operation.path("security").get(0).has("Bearer")).isTrue();

    List<String> parameterNames = new ArrayList<>();
    operation.path("parameters")
        .forEach(parameter -> parameterNames.add(parameter.path("name").asText()));

    assertThat(parameterNames).doesNotContain(AuthHeaders.USER_ID);
}
```

The two representative operations protect both controllers. The source edit in Step 3 must still update all eleven `X-User-Id` parameters so no operation regresses.

- [ ] **Step 2: Run the focused test and confirm the RED state.**

```bash
../gradlew :order-service:test --tests "com.prompthub.order.presentation.config.OpenApiContractTest"
```

Expected: `orderAndCartOperationsUseBearerAndHideGatewayUserId` fails because operations currently reference `gatewayHeaders` and include `X-User-Id` as a required header parameter.

- [ ] **Step 3: Replace the undefined controller security requirement.**

In both `OrderController` and `CartController`, replace:

```java
@SecurityRequirement(name = "gatewayHeaders")
```

with:

```java
@SecurityRequirement(name = "Bearer")
```

Do not introduce a `gatewayHeaders` API-key scheme. External clients authenticate to Gateway with JWT; the Gateway headers are an internal downstream contract.

- [ ] **Step 4: Hide every Gateway-injected buyer ID parameter.**

In all eight `OrderController` methods that receive `@RequestHeader(USER_ID)`, replace this pair:

```java
@Parameter(in = ParameterIn.HEADER, name = USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
@RequestHeader(USER_ID) UUID buyerId
```

with:

```java
@Parameter(hidden = true)
@RequestHeader(USER_ID) UUID buyerId
```

Preserve the existing trailing comma on parameters that are not last in their method signature.

In all three `CartController` methods that receive `@RequestHeader(AuthHeaders.USER_ID)`, replace this pair:

```java
@Parameter(in = ParameterIn.HEADER, name = AuthHeaders.USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
@RequestHeader(AuthHeaders.USER_ID) UUID buyerId
```

with:

```java
@Parameter(hidden = true)
@RequestHeader(AuthHeaders.USER_ID) UUID buyerId
```

After all replacements, remove the unused import from both controllers:

```java
import io.swagger.v3.oas.annotations.enums.ParameterIn;
```

Do not change `@RequestHeader`, `AuthHeaders.USER_ID`, parameter types, controller paths, or handler bodies.

- [ ] **Step 5: Rerun the focused OpenAPI contract test.**

```bash
../gradlew :order-service:test --tests "com.prompthub.order.presentation.config.OpenApiContractTest"
```

Expected: both tests pass. The representative order and cart operations require `Bearer`, and neither exposes `X-User-Id`.

- [ ] **Step 6: Run existing controller and web-contract regressions.**

```bash
../gradlew :order-service:test --tests "com.prompthub.order.presentation.OrderControllerTest" --tests "com.prompthub.order.presentation.CartControllerTest" --tests "com.prompthub.order.global.web.OrderWebContractTest"
```

Expected: all three test classes pass. This confirms that hiding a Swagger parameter did not change runtime request-header validation or the existing `401 A003` behavior.

- [ ] **Step 7: Run the complete order-service regression suite.**

```bash
../gradlew :order-service:test
```

Expected: `BUILD SUCCESSFUL` with all order-service tests passing. If an unrelated pre-existing working-tree change causes a failure, record the failing class and error without modifying or reverting that work.

- [ ] **Step 8: Review the complete diff and configuration boundaries.**

```bash
git diff --check
git diff -- src/main/java/com/prompthub/order/presentation/OrderController.java src/main/java/com/prompthub/order/presentation/CartController.java
sed -n '1,240p' src/main/java/com/prompthub/order/presentation/config/SwaggerConfig.java
sed -n '1,280p' src/test/java/com/prompthub/order/presentation/config/OpenApiContractTest.java
rg -n '@SecurityRequirement\(name = "gatewayHeaders"\)' src/main/java/com/prompthub/order
rg -c '@Parameter\(hidden = true\)' src/main/java/com/prompthub/order/presentation/OrderController.java src/main/java/com/prompthub/order/presentation/CartController.java
git status --short
```

Expected:

- No whitespace errors.
- No `build.gradle` change.
- No API Gateway configuration change.
- The first `rg` prints nothing because `gatewayHeaders` is no longer referenced under order-service.
- The count command prints `8` for `OrderController` and `3` for `CartController`, proving that all eleven Gateway-injected buyer ID parameters use `@Parameter(hidden = true)`.
- Existing unrelated user changes remain untouched and unstaged.

- [ ] **Step 9: Commit only when separately authorized.**

Suggested commit when Task 1 was committed separately:

```bash
git add src/main/java/com/prompthub/order/presentation/OrderController.java src/main/java/com/prompthub/order/presentation/CartController.java src/test/java/com/prompthub/order/presentation/config/OpenApiContractTest.java
git commit -m "fix: order-service Swagger Gateway 인증 헤더 노출 수정"
```

If Task 1 was not committed separately, stage all four implementation files and use one focused commit instead:

```bash
git add src/main/java/com/prompthub/order/presentation/config/SwaggerConfig.java src/main/java/com/prompthub/order/presentation/OrderController.java src/main/java/com/prompthub/order/presentation/CartController.java src/test/java/com/prompthub/order/presentation/config/OpenApiContractTest.java
git commit -m "fix: order-service Swagger Bearer 인증 설정 정리"
```

Expected: only Swagger/OpenAPI configuration, controller documentation annotations, and their contract test are committed. Skip this step when commit authorization has not been given.

## Runtime Smoke Test

Perform this only when discovery, order-service, and API Gateway are already available in the target environment. It is not a substitute for the automated tests above.

- [ ] Open `/swagger-ui/index.html` on API Gateway and select `order-service`.
- [ ] Confirm the document title is `PromptHub Order Service API`.
- [ ] Confirm the `Authorize` dialog offers a Bearer JWT field.
- [ ] Enter a valid access token without the literal `Bearer ` prefix if Swagger UI adds the scheme prefix automatically.
- [ ] Execute an order or cart endpoint and confirm the request contains `Authorization: Bearer ...` but the form does not ask for `X-User-Id`.
- [ ] Confirm Gateway injects `X-User-Id` and the order-service endpoint returns its normal application response.

## Completion Criteria

- `/v3/api-docs` identifies order-service and declares the `Bearer` HTTP JWT scheme.
- API Gateway continues to aggregate order-service through `/order-service/v3/api-docs` without configuration changes.
- Order and cart operations reference `Bearer`, not the undefined `gatewayHeaders` scheme.
- All eleven Gateway-injected `X-User-Id` parameters are hidden from the OpenAPI document but remain required at runtime downstream of Gateway.
- Focused OpenAPI, controller, and web-contract tests pass.
- `../gradlew :order-service:test` and `git diff --check` pass.
- No unrelated file, external contract, dependency, or sensitive value is changed.
