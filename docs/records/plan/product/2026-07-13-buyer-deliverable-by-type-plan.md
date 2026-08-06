# 구매자 산출물 유형별 전달 (Spec C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** gRPC `GetProductContent`가 응답 `content`에 상품 유형별 산출물(PROMPT=본문, PPT/EXCEL=파일 presigned URL, NOTION=외부 링크)을 채워 구매자에게 전달한다.

**Architecture:** proto 계약(`GetProductContentResponse{product_id, content}`) 무변경. `ProductInternalService.getProductContent`가 productType에 따라 산출물을 해석해 단일 `content` 문자열로 반환한다. order-service 무수정.

**Tech Stack:** Java 21, Spring Boot, gRPC, JUnit5 + Mockito.

## Global Constraints

- 설계 문서: `docs/superpowers/specs/2026-07-13-buyer-deliverable-by-type-design.md` (로컬 보관 — git 미추적)
- 이슈 #309, 브랜치 `feat/#306-product-type-fields`(A·B에서 이어서)
- **proto·order-service 무변경** — 크로스서비스 아님. content(string) 하나에 유형별 값을 담는다
- content·file_url·external_url은 DB에서 모두 `text`, gRPC content도 string → 단일 필드로 충분
- 대상 row는 기존대로 family의 현재 ON_SALE(`ProductFamily::currentOnSale`)
- Build: 루트에서 `.\gradlew.bat :product-service:build --no-daemon`

---

### Task 1: getProductContent 유형별 해석 (TDD)

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/application/service/ProductInternalService.java`
- Modify: `product-service/src/main/java/com/prompthub/product/presentation/dto/response/ProductContentResponse.java`
- Test: `product-service/src/test/java/com/prompthub/product/application/service/ProductInternalServiceTest.java`

**Interfaces:**
- Consumes: `Product.getProductType()`, `Product.getContent()`, `Product.getFileUrl()`, `Product.getExternalUrl()`, `StorageClient.generatePresignedDownloadUrl(String)`
- Produces: `ProductInternalService.getProductContent(UUID)`가 유형별 산출물을 담은 `ProductContentResponse` 반환

- [ ] **Step 1: 실패 테스트 작성**

`ProductInternalServiceTest.java`에 `StorageClient` mock과 nested 테스트를 추가한다.

import 추가:
```java
import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.presentation.dto.response.ProductContentResponse;
```

`@Mock private SellerClient sellerClient;` 아래에 mock 추가:
```java
	@Mock
	private StorageClient storageClient;
```

nested 클래스 추가(`UpsertReview` 아래):
```java
	@Nested
	@DisplayName("구매자 산출물 조회 (유형별)")
	class GetProductContent {

		@Test
		@DisplayName("PROMPT는 content 본문을 반환한다")
		void getProductContent_prompt() {
			Product product = onSaleWithType(ProductType.PROMPT);
			ReflectionTestUtils.setField(product, "content", "프롬프트 본문");
			mockResolve(product);

			ProductContentResponse response = productInternalService.getProductContent(PRODUCT_ID);

			assertThat(response.content()).isEqualTo("프롬프트 본문");
			then(storageClient).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("PPT는 file_url을 presigned 다운로드 URL로 변환해 반환한다")
		void getProductContent_ppt() {
			Product product = onSaleWithType(ProductType.PPT);
			ReflectionTestUtils.setField(product, "fileUrl", "products/1/file/a.pptx");
			mockResolve(product);
			given(storageClient.generatePresignedDownloadUrl("products/1/file/a.pptx"))
				.willReturn("https://s3/presigned-file");

			ProductContentResponse response = productInternalService.getProductContent(PRODUCT_ID);

			assertThat(response.content()).isEqualTo("https://s3/presigned-file");
		}

		@Test
		@DisplayName("NOTION은 external_url 원문을 반환한다")
		void getProductContent_notion() {
			Product product = onSaleWithType(ProductType.NOTION);
			ReflectionTestUtils.setField(product, "externalUrl", "https://notion.so/t");
			mockResolve(product);

			ProductContentResponse response = productInternalService.getProductContent(PRODUCT_ID);

			assertThat(response.content()).isEqualTo("https://notion.so/t");
			then(storageClient).shouldHaveNoInteractions();
		}

		private Product onSaleWithType(ProductType type) {
			Product product = product(PRODUCT_ID, SELLER_ID, ProductStatus.ON_SALE);
			ReflectionTestUtils.setField(product, "productType", type);
			return product;
		}

		private void mockResolve(Product product) {
			given(productRepository.findAllByIdIn(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(product));
		}
	}
```

- [ ] **Step 2: 빌드로 실패 확인**

Run: `Set-Location C:\programmers_prj\beadv6_6_3JMT_BE; .\gradlew.bat :product-service:test --no-daemon --tests "com.prompthub.product.application.service.ProductInternalServiceTest"`
Expected: PPT 테스트 FAIL(현재는 content=null 반환) 또는 storageClient 미주입 관련 실패. PROMPT는 우연히 통과할 수 있음.

- [ ] **Step 3: 서비스에 StorageClient 주입 + 유형별 해석 구현**

`ProductInternalService.java`에 import 추가:
```java
import com.prompthub.product.application.client.StorageClient;
```

필드 추가(`private final SellerClient sellerClient;` 아래):
```java
	private final StorageClient storageClient;
```

`getProductContent`의 반환부를 교체:
```java
	@Override
	public ProductContentResponse getProductContent(UUID productId) {
		Map<UUID, Product> resolved = resolveFamilyRepresentatives(List.of(productId), ProductFamily::currentOnSale);
		Product product = resolved.get(productId);
		if (product == null) {
			throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
		}
		return new ProductContentResponse(productId, resolveDeliverable(product));
	}

	private String resolveDeliverable(Product product) {
		return switch (product.getProductType()) {
			case PROMPT -> product.getContent();
			case PPT, EXCEL -> presignIfPresent(product.getFileUrl());
			case NOTION -> product.getExternalUrl();
		};
	}

	private String presignIfPresent(String key) {
		if (key == null || key.isBlank()) {
			return null;
		}
		return storageClient.generatePresignedDownloadUrl(key);
	}
```

- [ ] **Step 4: 안 쓰게 된 DTO 팩토리 제거**

`ProductContentResponse.java`에서 `from(...)`가 더는 쓰이지 않으므로 제거하고, 안 쓰는 `Product` import도 지운다. 결과:
```java
package com.prompthub.product.presentation.dto.response;

import java.util.UUID;

public record ProductContentResponse(
	UUID productId,
	String content
) {
}
```

- [ ] **Step 5: 빌드로 통과 확인**

Run: `Set-Location C:\programmers_prj\beadv6_6_3JMT_BE; .\gradlew.bat :product-service:build --no-daemon`
Expected: BUILD SUCCESSFUL(신규 3개 포함 전체 PASS). checkstyle은 기존 proto WARN만.

- [ ] **Step 6: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/application/service/ProductInternalService.java product-service/src/main/java/com/prompthub/product/presentation/dto/response/ProductContentResponse.java product-service/src/test/java/com/prompthub/product/application/service/ProductInternalServiceTest.java
git commit -m "feat: 구매자 산출물 gRPC content를 상품 유형별로 해석(파일 presigned URL/외부 링크)"
```

---

### Task 2: docs 동기화

**Files:**
- Modify: `docs/api-spec/product.md`

- [ ] **Step 1: 내부 API content 의미 명시**

`docs/api-spec/product.md`의 내부 API(주문/구매 콘텐츠 관련 gRPC 또는 `/internal` content) 설명 근처에 한 줄 추가:

> `content`는 상품 유형별 산출물을 담는다 — PROMPT=본문 텍스트, PPT·EXCEL=파일 presigned 다운로드 URL, NOTION=외부 링크. gRPC 응답 구조(`GetProductContentResponse{product_id, content}`)는 변경되지 않는다.

(해당 설명 위치가 없으면, 내부 API 섹션 도입부에 한 줄로 추가한다.)

- [ ] **Step 2: 커밋**

```bash
git add docs/api-spec/product.md
git commit -m "docs: 구매자 gRPC content가 유형별 산출물임을 명시"
```

---

## Self-Review 결과

- **Spec 커버리지**: 유형별 해석→Task1 Step3; StorageClient 주입→Task1 Step3; 테스트→Task1 Step1; docs→Task2. proto·order 무변경(제약에 명시).
- **Placeholder**: 없음.
- **타입 일관성**: `resolveDeliverable`/`presignIfPresent`, `ProductContentResponse(productId, content)`가 일관. switch가 ProductType 4값(PROMPT/PPT/EXCEL/NOTION) 모두 커버(컴파일러 강제).
- **주의(구현자 확인)**: `ProductInternalServiceTest`가 `@InjectMocks`라 `@Mock StorageClient` 추가만으로 주입된다. `ProductContentResponse.from` 제거 후 다른 사용처가 없는지 확인(현재는 getProductContent만 사용).
