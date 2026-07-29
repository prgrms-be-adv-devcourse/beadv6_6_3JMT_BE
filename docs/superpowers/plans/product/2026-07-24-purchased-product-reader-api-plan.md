# 구매자용 상품 데이터 조회 API + 리뷰 연동 (#550) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** FE 구매 프롬프트 reader 페이지가 한 번의 호출로 상품 데이터·유형별 콘텐츠·평균/내 별점을 받도록 `GET /api/v2/products/{productId}/orders`를 추가하고, FE reader 페이지를 이 API 기준으로 전환한다.

**Architecture:** product-service에 새 유스케이스(`PurchasedProductQueryUseCase`)를 추가한다. 유형별 콘텐츠(PROMPT=본문, PPT·EXCEL=presigned 파일 URL, NOTION=외부 링크) 처리는 새 서비스가 자체적으로 수행하며 **기존 gRPC 경로(`ProductGrpcService`)는 수정하지 않는다** (사용자 결정). 구매 여부 검증은 이번 이슈에서 생략하되 유스케이스 진입부에 검증 지점을 한 곳으로 모아둔다. FE는 reader 페이지의 상품 데이터·콘텐츠·별점을 새 API로 전환하고 다운로드/환불 플로우는 order-service에 남긴다.

**Tech Stack:** Spring Boot(product-service), JUnit5 + Mockito + standalone MockMvc, Next.js FE(`C:\programmers_prj\beadv6_6_3JMT_FE`), node:test

**Spec:** `docs/superpowers/specs/2026-07-24-purchased-product-reader-api-design.md` (저장소 루트, gitignore)

## Global Constraints

- 작업 위치(BE): worktree `C:\programmers_prj\worktrees\purchased-product-detail`, 브랜치 `feat/#550-purchased-product-reader-api`
- 작업 위치(FE): `C:\programmers_prj\beadv6_6_3JMT_FE` (별도 저장소)
- **기존 gRPC 코드(`ProductGrpcService` 등)는 수정하지 않는다** — 새 API는 독립적으로 구현
- 계층 규칙: presentation → application → domain, Controller는 위임만 (`.claude/rules/architecture.md`)
- 외부 API 응답은 `ApiResult<T>` wrapper, 인증은 Gateway 주입 `X-User-Id` 헤더만 읽는다 (`.claude/rules/product-api.md`)
- 코드 스타일: 탭 들여쓰기, wildcard import 금지, checkstyle 통과 필수
- 커밋: **반드시 commit 스킬 게이트를 거쳐 사용자 승인 후 실행** (임의 git add/commit 금지)
- BE 검증 명령: `cd C:\programmers_prj\worktrees\purchased-product-detail\product-service` 후 `.\gradlew.bat test --tests "<클래스>"`, 최종은 `clean build`
- 유형별 산출물 계약: PROMPT→`content`, PPT·EXCEL→`fileUrl`(presigned URL 변환 필수 — DB 값은 S3 키), NOTION→`externalUrl`
- 별점 anchoring: 리뷰 row는 family root 상품에 붙는다. `averageRating`/`myRating` 조회는 `familyRootId` 기준 (upsert와 동일)

---

### Task 1: PurchasedProductQueryService + DTO (BE)

**Files:**
- Create: `product-service/src/main/java/com/prompthub/product/presentation/dto/response/PurchasedProductDetailResponse.java`
- Create: `product-service/src/main/java/com/prompthub/product/application/usecase/PurchasedProductQueryUseCase.java`
- Create: `product-service/src/main/java/com/prompthub/product/application/service/PurchasedProductQueryService.java`
- Test: `product-service/src/test/java/com/prompthub/product/application/service/PurchasedProductQueryServiceTest.java`

**Interfaces:**
- Consumes: `ProductFamilyResolver.resolveFamilyRepresentatives(List<UUID>, Function<ProductFamily, Optional<Product>>)`, `StorageClient.generatePresignedDownloadUrl(String)`, `ProductRepository.getAverageRating(UUID)`, `ReviewRepository.findByUserIdAndProductId(UUID, UUID)` (전부 기존 코드)
- Produces: `PurchasedProductQueryUseCase.getPurchasedProduct(UUID userId, UUID productId): PurchasedProductDetailResponse` — Task 2가 사용. Response 필드: `productId(UUID), title, productType, model, content, fileUrl, externalUrl, thumbnailUrl(String), sellerId(UUID), averageRating(double), myRating(Integer)`

- [ ] **Step 1: 실패하는 테스트 작성**

`PurchasedProductQueryServiceTest.java`:

```java
package com.prompthub.product.application.service;

import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.Review;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.domain.repository.ReviewRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.presentation.dto.response.PurchasedProductDetailResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PurchasedProductQueryServiceTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ReviewRepository reviewRepository;

	@Mock
	private StorageClient storageClient;

	private PurchasedProductQueryService purchasedProductQueryService;

	@BeforeEach
	void setUp() {
		purchasedProductQueryService = new PurchasedProductQueryService(
			new ProductFamilyResolver(productRepository),
			productRepository,
			reviewRepository,
			storageClient
		);
	}

	@Test
	@DisplayName("PROMPT 상품은 content만 채워서 반환하고 평균/내 별점을 포함한다")
	void getPurchasedProduct_prompt_success() {
		Product product = onSaleProduct(ProductType.PROMPT);
		ReflectionTestUtils.setField(product, "content", "프롬프트 본문");
		stubFamily(product);
		given(productRepository.getAverageRating(PRODUCT_ID)).willReturn(4.5);
		Review review = review(product, (short) 5);
		given(reviewRepository.findByUserIdAndProductId(USER_ID, PRODUCT_ID)).willReturn(Optional.of(review));

		PurchasedProductDetailResponse result =
			purchasedProductQueryService.getPurchasedProduct(USER_ID, PRODUCT_ID);

		assertThat(result.productId()).isEqualTo(PRODUCT_ID);
		assertThat(result.title()).isEqualTo("면접 답변 프롬프트");
		assertThat(result.productType()).isEqualTo("PROMPT");
		assertThat(result.model()).isEqualTo("GPT-4o");
		assertThat(result.content()).isEqualTo("프롬프트 본문");
		assertThat(result.fileUrl()).isNull();
		assertThat(result.externalUrl()).isNull();
		assertThat(result.sellerId()).isEqualTo(SELLER_ID);
		assertThat(result.averageRating()).isEqualTo(4.5);
		assertThat(result.myRating()).isEqualTo(5);
		verifyNoInteractions(storageClient);
	}

	@Test
	@DisplayName("PPT 상품은 fileUrl을 presigned URL로 채워서 반환한다")
	void getPurchasedProduct_ppt_presignedFileUrl() {
		Product product = onSaleProduct(ProductType.PPT);
		ReflectionTestUtils.setField(product, "fileUrl", "files/deck.pptx");
		stubFamily(product);
		given(productRepository.getAverageRating(PRODUCT_ID)).willReturn(0.0);
		given(reviewRepository.findByUserIdAndProductId(USER_ID, PRODUCT_ID)).willReturn(Optional.empty());
		given(storageClient.generatePresignedDownloadUrl("files/deck.pptx")).willReturn("https://s3/presigned");

		PurchasedProductDetailResponse result =
			purchasedProductQueryService.getPurchasedProduct(USER_ID, PRODUCT_ID);

		assertThat(result.content()).isNull();
		assertThat(result.fileUrl()).isEqualTo("https://s3/presigned");
		assertThat(result.externalUrl()).isNull();
		assertThat(result.myRating()).isNull();
	}

	@Test
	@DisplayName("fileUrl이 비어 있으면 presign 없이 null을 반환한다")
	void getPurchasedProduct_blankFileUrl_returnsNull() {
		Product product = onSaleProduct(ProductType.EXCEL);
		ReflectionTestUtils.setField(product, "fileUrl", " ");
		stubFamily(product);
		given(productRepository.getAverageRating(PRODUCT_ID)).willReturn(0.0);
		given(reviewRepository.findByUserIdAndProductId(USER_ID, PRODUCT_ID)).willReturn(Optional.empty());

		PurchasedProductDetailResponse result =
			purchasedProductQueryService.getPurchasedProduct(USER_ID, PRODUCT_ID);

		assertThat(result.fileUrl()).isNull();
		verifyNoInteractions(storageClient);
	}

	@Test
	@DisplayName("NOTION 상품은 externalUrl만 채워서 반환한다")
	void getPurchasedProduct_notion_externalUrl() {
		Product product = onSaleProduct(ProductType.NOTION);
		ReflectionTestUtils.setField(product, "externalUrl", "https://notion.so/x");
		stubFamily(product);
		given(productRepository.getAverageRating(PRODUCT_ID)).willReturn(0.0);
		given(reviewRepository.findByUserIdAndProductId(USER_ID, PRODUCT_ID)).willReturn(Optional.empty());

		PurchasedProductDetailResponse result =
			purchasedProductQueryService.getPurchasedProduct(USER_ID, PRODUCT_ID);

		assertThat(result.content()).isNull();
		assertThat(result.fileUrl()).isNull();
		assertThat(result.externalUrl()).isEqualTo("https://notion.so/x");
	}

	@Test
	@DisplayName("family에 ON_SALE 대표가 없으면 PRODUCT_NOT_FOUND")
	void getPurchasedProduct_noOnSale_throwsNotFound() {
		Product product = product(ProductType.PROMPT, ProductStatus.STOPPED);
		stubFamily(product);

		assertThatThrownBy(() -> purchasedProductQueryService.getPurchasedProduct(USER_ID, PRODUCT_ID))
			.isInstanceOf(ProductException.class);
	}

	@Test
	@DisplayName("별점 조회는 요청 id가 아니라 family root id 기준이다")
	void getPurchasedProduct_ratingAnchorsToFamilyRoot() {
		UUID rootId = UUID.fromString("44444444-4444-4444-4444-444444444444");
		Product product = onSaleProduct(ProductType.PROMPT);
		ReflectionTestUtils.setField(product, "parentId", rootId);
		ReflectionTestUtils.setField(product, "content", "본문");
		given(productRepository.findAllByIdIn(List.of(PRODUCT_ID))).willReturn(List.of(product));
		given(productRepository.findAllByFamilyRootIds(List.of(rootId))).willReturn(List.of(product));
		given(productRepository.getAverageRating(rootId)).willReturn(3.0);
		given(reviewRepository.findByUserIdAndProductId(USER_ID, rootId)).willReturn(Optional.empty());

		PurchasedProductDetailResponse result =
			purchasedProductQueryService.getPurchasedProduct(USER_ID, PRODUCT_ID);

		assertThat(result.averageRating()).isEqualTo(3.0);
	}

	private void stubFamily(Product product) {
		given(productRepository.findAllByIdIn(List.of(PRODUCT_ID))).willReturn(List.of(product));
		given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(product));
	}

	private Product onSaleProduct(ProductType productType) {
		return product(productType, ProductStatus.ON_SALE);
	}

	private Product product(ProductType productType, ProductStatus status) {
		Product product = instantiate(Product.class);
		ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
		ReflectionTestUtils.setField(product, "sellerId", SELLER_ID);
		ReflectionTestUtils.setField(product, "name", "면접 답변 프롬프트");
		ReflectionTestUtils.setField(product, "model", "GPT-4o");
		ReflectionTestUtils.setField(product, "productType", productType);
		ReflectionTestUtils.setField(product, "status", status);
		return product;
	}

	private Review review(Product product, short rating) {
		Review review = instantiate(Review.class);
		ReflectionTestUtils.setField(review, "userId", USER_ID);
		ReflectionTestUtils.setField(review, "product", product);
		ReflectionTestUtils.setField(review, "rating", rating);
		return review;
	}

	private <T> T instantiate(Class<T> type) {
		try {
			java.lang.reflect.Constructor<T> constructor = type.getDeclaredConstructor();
			constructor.setAccessible(true);
			return constructor.newInstance();
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("테스트 fixture 생성에 실패했습니다.", exception);
		}
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `.\gradlew.bat test --tests "com.prompthub.product.application.service.PurchasedProductQueryServiceTest"`
Expected: 컴파일 실패 — `PurchasedProductQueryService`, `PurchasedProductDetailResponse` 심볼 없음

- [ ] **Step 3: 구현**

`PurchasedProductDetailResponse.java`:

```java
package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.domain.model.entity.Product;
import java.util.UUID;

public record PurchasedProductDetailResponse(
	UUID productId,
	String title,
	String productType,
	String model,
	String content,
	String fileUrl,
	String externalUrl,
	String thumbnailUrl,
	UUID sellerId,
	double averageRating,
	Integer myRating
) {

	public static PurchasedProductDetailResponse of(
		UUID requestedId, Product product,
		String content, String fileUrl, String externalUrl,
		double averageRating, Integer myRating
	) {
		return new PurchasedProductDetailResponse(
			requestedId,
			product.getName(),
			product.getProductType().name(),
			product.getModel(),
			content,
			fileUrl,
			externalUrl,
			product.getThumbnailUrl(),
			product.getSellerId(),
			averageRating,
			myRating
		);
	}
}
```

`PurchasedProductQueryUseCase.java`:

```java
package com.prompthub.product.application.usecase;

import com.prompthub.product.presentation.dto.response.PurchasedProductDetailResponse;
import java.util.UUID;

public interface PurchasedProductQueryUseCase {

	PurchasedProductDetailResponse getPurchasedProduct(UUID userId, UUID productId);
}
```

`PurchasedProductQueryService.java` — 유형별 분기와 presign을 이 서비스가 직접 처리한다 (기존 gRPC 코드는 건드리지 않는다):

```java
package com.prompthub.product.application.service;

import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.application.usecase.PurchasedProductQueryUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.domain.repository.ReviewRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.response.PurchasedProductDetailResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchasedProductQueryService implements PurchasedProductQueryUseCase {

	private final ProductFamilyResolver productFamilyResolver;
	private final ProductRepository productRepository;
	private final ReviewRepository reviewRepository;
	private final StorageClient storageClient;

	@Override
	public PurchasedProductDetailResponse getPurchasedProduct(UUID userId, UUID productId) {
		verifyPurchase(userId, productId);
		Map<UUID, Product> resolved =
			productFamilyResolver.resolveFamilyRepresentatives(List.of(productId), ProductFamily::currentOnSale);
		Product product = resolved.get(productId);
		if (product == null) {
			throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
		}
		UUID familyRootId = product.familyRootId();
		double averageRating = productRepository.getAverageRating(familyRootId);
		Integer myRating = reviewRepository.findByUserIdAndProductId(userId, familyRootId)
			.map(review -> (int) review.getRating())
			.orElse(null);
		return buildResponse(productId, product, averageRating, myRating);
	}

	// 유형별 콘텐츠: PROMPT=본문, PPT·EXCEL=presigned 다운로드 URL(DB 값은 S3 키), NOTION=외부 링크
	private PurchasedProductDetailResponse buildResponse(
		UUID requestedId, Product product, double averageRating, Integer myRating
	) {
		String content = null;
		String fileUrl = null;
		String externalUrl = null;
		switch (product.getProductType()) {
			case PROMPT -> content = product.getContent();
			case PPT, EXCEL -> fileUrl = presignIfPresent(product.getFileUrl());
			case NOTION -> externalUrl = product.getExternalUrl();
		}
		return PurchasedProductDetailResponse.of(
			requestedId, product, content, fileUrl, externalUrl, averageRating, myRating);
	}

	private String presignIfPresent(String key) {
		if (key == null || key.isBlank()) {
			return null;
		}
		return storageClient.generatePresignedDownloadUrl(key);
	}

	// 구매 여부 검증 지점 — 현재는 검증하지 않는다(#550 설계 결정). 후속 이슈에서 order-service gRPC 검증으로 대체한다.
	private void verifyPurchase(UUID userId, UUID productId) {
	}
}
```

- [ ] **Step 4: 통과 확인**

Run: `.\gradlew.bat test --tests "com.prompthub.product.application.service.PurchasedProductQueryServiceTest"`
Expected: 6개 테스트 전부 PASS

- [ ] **Step 5: 커밋 (commit 스킬 게이트, 사용자 승인 후)**

메시지: `feat: 구매자용 상품 데이터 조회 유스케이스 추가 (#550)`

---

### Task 2: Controller 엔드포인트 (BE)

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/presentation/controller/ProductController.java`
- Modify: `product-service/src/test/java/com/prompthub/product/presentation/controller/ProductControllerTest.java`

**Interfaces:**
- Consumes: `PurchasedProductQueryUseCase.getPurchasedProduct(UUID userId, UUID productId)` (Task 1)
- Produces: `GET /api/v2/products/{productId}/orders` — `X-User-Id` 헤더 필수, `ApiResult<PurchasedProductDetailResponse>` 응답

- [ ] **Step 1: 실패하는 테스트 작성**

`ProductControllerTest.java`에 추가. 먼저 setUp의 컨트롤러 생성자에 `@Mock PurchasedProductQueryUseCase purchasedProductQueryUseCase` 필드를 추가하고 `new ProductController(productQueryUseCase, productSellerUseCase, purchasedProductQueryUseCase)`로 바꾼다 (기존 setUp의 인자 순서는 실제 파일 기준으로 맞춘다). 그 다음 `@Nested` 클래스 추가:

```java
	@Nested
	@DisplayName("GET /api/v2/products/{productId}/orders")
	class GetPurchasedProduct {

		@Test
		@DisplayName("구매한 상품 reader 데이터를 반환한다")
		void getPurchasedProduct_success() throws Exception {
			PurchasedProductDetailResponse response = new PurchasedProductDetailResponse(
				PRODUCT_ID, "면접 답변 프롬프트", "PROMPT", "GPT-4o",
				"프롬프트 본문", null, null, "https://cdn/thumb.png",
				SELLER_ID, 4.5, 5
			);
			given(purchasedProductQueryUseCase.getPurchasedProduct(USER_ID, PRODUCT_ID)).willReturn(response);

			mockMvc.perform(get("/api/v2/products/{productId}/orders", PRODUCT_ID)
					.header("X-User-Id", USER_ID.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.productId").value(PRODUCT_ID.toString()))
				.andExpect(jsonPath("$.data.title").value("면접 답변 프롬프트"))
				.andExpect(jsonPath("$.data.productType").value("PROMPT"))
				.andExpect(jsonPath("$.data.model").value("GPT-4o"))
				.andExpect(jsonPath("$.data.content").value("프롬프트 본문"))
				.andExpect(jsonPath("$.data.fileUrl").isEmpty())
				.andExpect(jsonPath("$.data.externalUrl").isEmpty())
				.andExpect(jsonPath("$.data.thumbnailUrl").value("https://cdn/thumb.png"))
				.andExpect(jsonPath("$.data.sellerId").value(SELLER_ID.toString()))
				.andExpect(jsonPath("$.data.averageRating").value(4.5))
				.andExpect(jsonPath("$.data.myRating").value(5));
		}

		@Test
		@DisplayName("없는 상품이면 404 P001을 반환한다")
		void getPurchasedProduct_notFound() throws Exception {
			given(purchasedProductQueryUseCase.getPurchasedProduct(USER_ID, PRODUCT_ID))
				.willThrow(new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

			mockMvc.perform(get("/api/v2/products/{productId}/orders", PRODUCT_ID)
					.header("X-User-Id", USER_ID.toString()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("P001"));
		}

		@Test
		@DisplayName("X-User-Id 헤더가 없으면 500(SYS001)으로 처리된다 — 기존 인증 필요 API와 동일")
		void getPurchasedProduct_missingUserIdHeader() throws Exception {
			mockMvc.perform(get("/api/v2/products/{productId}/orders", PRODUCT_ID))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("SYS001"));

			verifyNoInteractions(purchasedProductQueryUseCase);
		}
	}
```

`USER_ID`/`SELLER_ID` 상수가 클래스에 없으면 기존 상수 명명 규칙대로 추가한다. 필요한 import: `PurchasedProductDetailResponse`, `ProductException`, `ProductErrorCode`, `verifyNoInteractions`.

- [ ] **Step 2: 실패 확인**

Run: `.\gradlew.bat test --tests "com.prompthub.product.presentation.controller.ProductControllerTest"`
Expected: 컴파일 실패 — 컨트롤러 생성자 인자 및 엔드포인트 없음

- [ ] **Step 3: 구현**

`ProductController.java`:
- 필드 추가: `private final PurchasedProductQueryUseCase purchasedProductQueryUseCase;`
- import 추가: `PurchasedProductQueryUseCase`, `PurchasedProductDetailResponse`
- 메서드 추가 (`getRelatedProducts` 근처, `/products/{productId}/...` 블록):

```java
	@GetMapping("/products/{productId}/orders")
	public ApiResult<PurchasedProductDetailResponse> getPurchasedProduct(
		@RequestHeader("X-User-Id") UUID userId,
		@PathVariable UUID productId
	) {
		return ApiResult.success(purchasedProductQueryUseCase.getPurchasedProduct(userId, productId));
	}
```

- [ ] **Step 4: 통과 확인**

Run: `.\gradlew.bat test --tests "com.prompthub.product.presentation.controller.ProductControllerTest"`
Expected: 전부 PASS

- [ ] **Step 5: 커밋 (commit 스킬 게이트, 사용자 승인 후)**

메시지: `feat: GET /products/{productId}/orders 구매자용 상품 조회 API 추가 (#550)`

---

### Task 3: api-spec 문서 + BE 전체 빌드

**Files:**
- Modify: `docs/api-spec/product.md` (저장소 루트 — 공통 docs이므로 변경 사실을 PR 본문에 명시)

**Interfaces:**
- Consumes: Task 2의 엔드포인트 계약
- Produces: 문서화된 API 계약 (sync-product-docs 단계에서 재검증)

- [ ] **Step 1: 문서 추가**

`docs/api-spec/product.md`의 기존 상세 조회 API 섹션 뒤에 추가 (형식은 인접 섹션의 표 형식을 그대로 따른다):

```markdown
### 구매 상품 reader 조회

`GET /api/v2/products/{productId}/orders`

구매한 프롬프트 reader 페이지용 상품 데이터 조회. 인증 필요(`X-User-Id`).
구매 여부 검증은 현재 하지 않는다(#550 결정, 후속 이슈에서 order-service 연동 예정).

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| productId | string(UUID) | 요청한 상품 ID |
| title | string | 상품명 |
| productType | string | PROMPT / NOTION / PPT / EXCEL |
| model | string \| null | 대상 모델 |
| content | string \| null | 프롬프트 본문 (PROMPT만) |
| fileUrl | string \| null | presigned 다운로드 URL (PPT·EXCEL만) |
| externalUrl | string \| null | 외부 노션 링크 (NOTION만) |
| thumbnailUrl | string \| null | 썸네일 URL |
| sellerId | string(UUID) | 판매자 ID |
| averageRating | number | family 평균 별점 |
| myRating | number \| null | 요청 유저의 별점 (없으면 null) |
```

- [ ] **Step 2: 전체 빌드**

Run (PowerShell, `product-service` 디렉터리):

```powershell
$env:DB_HOST="localhost"; $env:DB_PORT="5432"; $env:DB_NAME="prompthub_test"; $env:DB_USERNAME="test"; $env:DB_PASSWORD="test"
.\gradlew.bat clean build --no-daemon
```

Expected: BUILD SUCCESSFUL (checkstyle 포함)

- [ ] **Step 3: 커밋 (commit 스킬 게이트, 사용자 승인 후)**

메시지: `docs: 구매 상품 reader 조회 API 명세 추가 (#550)`

---

### Task 4: FE lib — getPurchasedProduct + resolveDeliverable (FE 저장소)

**Files:**
- Create: `C:\programmers_prj\beadv6_6_3JMT_FE\lib\purchasedProducts.ts`
- Test: `C:\programmers_prj\beadv6_6_3JMT_FE\lib\purchasedProducts.test.ts`

**Interfaces:**
- Consumes: `api`(`@/lib/auth`), `API_BASE`(`@/lib/apiBase`) — 기존 FE 패턴
- Produces: `getPurchasedProduct(productId: string): Promise<PurchasedProductDetail>`, `resolveDeliverable(detail): Deliverable` — Task 5가 사용

- [ ] **Step 1: 실패하는 테스트 작성**

`lib/purchasedProducts.test.ts` (기존 `lib/orderAdapters.test.ts`와 동일하게 node:test 사용):

```ts
import test from 'node:test'
import assert from 'node:assert/strict'

import { resolveDeliverable } from './purchasedProducts'

test('resolveDeliverable returns text for PROMPT', () => {
  const result = resolveDeliverable({
    productType: 'PROMPT', content: '본문', fileUrl: null, externalUrl: null,
  })
  assert.deepEqual(result, { kind: 'text', value: '본문' })
})

test('resolveDeliverable returns file for PPT/EXCEL', () => {
  const result = resolveDeliverable({
    productType: 'PPT', content: null, fileUrl: 'https://s3/presigned', externalUrl: null,
  })
  assert.deepEqual(result, { kind: 'file', value: 'https://s3/presigned' })
})

test('resolveDeliverable returns link for NOTION', () => {
  const result = resolveDeliverable({
    productType: 'NOTION', content: null, fileUrl: null, externalUrl: 'https://notion.so/x',
  })
  assert.deepEqual(result, { kind: 'link', value: 'https://notion.so/x' })
})

test('resolveDeliverable returns null when the deliverable field is empty', () => {
  const result = resolveDeliverable({
    productType: 'EXCEL', content: null, fileUrl: null, externalUrl: null,
  })
  assert.equal(result, null)
})
```

- [ ] **Step 2: 실패 확인**

Run (FE 저장소 루트): `npx tsx --test lib/purchasedProducts.test.ts`
Expected: FAIL — `./purchasedProducts` 모듈 없음

- [ ] **Step 3: 구현**

`lib/purchasedProducts.ts`:

```ts
import api from '@/lib/auth'
import { API_BASE } from '@/lib/apiBase'

export interface PurchasedProductDetail {
  productId: string
  title: string
  productType: string
  model: string | null
  content: string | null
  fileUrl: string | null
  externalUrl: string | null
  thumbnailUrl: string | null
  sellerId: string
  averageRating: number
  myRating: number | null
}

// GET /api/v2/products/{productId}/orders — 구매한 상품 reader 데이터 조회 (#550)
export async function getPurchasedProduct(productId: string): Promise<PurchasedProductDetail> {
  const res = await api.get<{ success: boolean; data: PurchasedProductDetail; message: string }>(
    `${API_BASE}/products/${productId}/orders`,
  )
  return res.data.data
}

export type Deliverable =
  | { kind: 'text'; value: string }
  | { kind: 'file'; value: string }
  | { kind: 'link'; value: string }
  | null

// 유형별 산출물 해석: PROMPT=본문, PPT·EXCEL=presigned 파일 URL, NOTION=외부 링크
export function resolveDeliverable(
  detail: Pick<PurchasedProductDetail, 'productType' | 'content' | 'fileUrl' | 'externalUrl'>,
): Deliverable {
  if (detail.productType === 'PPT' || detail.productType === 'EXCEL') {
    return detail.fileUrl ? { kind: 'file', value: detail.fileUrl } : null
  }
  if (detail.productType === 'NOTION') {
    return detail.externalUrl ? { kind: 'link', value: detail.externalUrl } : null
  }
  return detail.content ? { kind: 'text', value: detail.content } : null
}
```

- [ ] **Step 4: 통과 확인**

Run: `npx tsx --test lib/purchasedProducts.test.ts`
Expected: 4 tests PASS

- [ ] **Step 5: 커밋 (FE 저장소, 사용자 승인 후)**

메시지: `feat: 구매 상품 reader 조회 lib 추가 (#550 BE 연동)`

---

### Task 5: FE reader 페이지 전환 (FE 저장소)

**Files:**
- Modify: `C:\programmers_prj\beadv6_6_3JMT_FE\app\reader\[id]\page.tsx`

**Interfaces:**
- Consumes: `getPurchasedProduct`, `resolveDeliverable`, `PurchasedProductDetail` (Task 4), `getOrderProductSellerNames`(`@/lib/sellers`, 기존), `getOrders`/`downloadOrderProduct`(`@/lib/orders`, 기존)
- Produces: reader 페이지가 새 API 기준으로 동작 (상품 데이터·콘텐츠·별점 서버 기준, localStorage 별점 제거)

- [ ] **Step 1: import·state 수정**

- import에서 `getOrderContent` 제거, 추가:

```ts
import { downloadOrderProduct, getOrders } from '@/lib/orders'
import { getPurchasedProduct, resolveDeliverable, type PurchasedProductDetail } from '@/lib/purchasedProducts'
import { getOrderProductSellerNames } from '@/lib/sellers'
```

- state 추가:

```ts
const [detail, setDetail] = useState<PurchasedProductDetail | null>(null)
const [sellerName, setSellerName] = useState<string | null>(null)
```

- [ ] **Step 2: 데이터 로딩 useEffect 교체**

기존 `getOrders()` 단독 useEffect를 아래로 교체 (localStorage `ph_downloaded_` 과도기 분기 삭제):

```ts
useEffect(() => {
  if (!id) { router.push('/mypage'); return }
  Promise.all([getOrders(), getPurchasedProduct(String(id))])
    .then(async ([orders, fetched]) => {
      const products = orders.map(mapOrderToPrompt).filter(Boolean) as Prompt[]
      const found = products.find((product) => String(product.id) === String(id))
      if (!found) {
        router.push('/mypage')
        return
      }
      setP({
        ...found,
        title: fetched.title,
        productType: fetched.productType,
        model: fetched.model || 'Prompt',
        rating: fetched.averageRating,
        thumbnail_url: fetched.thumbnailUrl,
      })
      setDetail(fetched)
      setMyRating(fetched.myRating ?? 0)
      if (found.downloaded) {
        setDownloaded(true)
        const deliverable = resolveDeliverable(fetched)
        if (deliverable) setContent(deliverable.value)
      }
      const names = await getOrderProductSellerNames([fetched.sellerId])
      setSellerName(names[fetched.sellerId] ?? null)
    })
    .catch(() => router.push('/mypage'))
    .finally(() => setLoading(false))
}, [id, router])
```

- [ ] **Step 3: 별점 localStorage 제거**

- `ph_rating_` 복원 useEffect 전체 삭제.
- `handleRate`에서 `localStorage.setItem(...)` 줄 삭제 (POST 후 `setMyRating(n)`과 toast만 유지).

- [ ] **Step 4: confirmDownload를 detail 기준으로 교체**

```ts
const confirmDownload = async () => {
  if (!p || !detail || !p.orderId || !p.orderProductId) {
    setConfirmOpen(false)
    setDownloaded(true)
    return
  }
  try {
    const deliverable = resolveDeliverable(detail)
    if (deliverable) setContent(deliverable.value)
    await downloadOrderProduct(p.orderId, p.orderProductId)
    setConfirmOpen(false)
    setDownloaded(true)
    setP((prev) => prev ? { ...prev, downloaded: true, isRefundable: false } : prev)
    if (deliverable?.kind === 'file') openFile(deliverable.value)
    else if (deliverable?.kind === 'link') openLink(deliverable.value)
  } catch {
    showToast('콘텐츠를 불러오지 못했어요. 다시 시도해 주세요')
  }
}
```

`reopen`은 기존 로직 유지 (content state 기반). `confirmDownload` 안의 `localStorage.setItem('ph_downloaded_...')` 줄 삭제.

- [ ] **Step 5: 판매자 표시를 sellerName 우선으로**

Seller info card와 Avatar에서 `p.seller` → `sellerName ?? p.seller`:

```tsx
<Avatar name={sellerName ?? p.seller} size={48} />
...
<div style={{ fontSize: 16, fontWeight: 700 }}>{sellerName ?? p.seller}</div>
```

- [ ] **Step 6: 검증**

Run (FE 저장소 루트):

```bash
npx tsc --noEmit
```

Expected: 에러 없음

```bash
npm run lint
```

Expected: 에러 없음 (기존 워닝 제외)

가능하면 `npm run dev`로 reader 페이지 수동 확인: 구매한 상품 진입 → 제목/모델/썸네일/판매자 이름 표시, 별점 등록 후 새로고침 시 내 별점 유지(localStorage 아닌 서버 기준).

- [ ] **Step 7: 커밋 (FE 저장소, 사용자 승인 후)**

메시지: `feat: reader 페이지를 구매 상품 조회 API 기준으로 전환 (#550 BE 연동)`

---

### Task 6: 마무리 — 규칙 검증·PR 준비 (BE)

**Files:** 없음 (검증만)

- [ ] **Step 1: BE 전체 빌드 재확인**

Run (`product-service` 디렉터리): `.\gradlew.bat clean build --no-daemon` (Task 3의 DB env 설정 포함)
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 스킬 절차 실행**

순서대로: `sync-product-docs` → `verify-rules` → (사용자 승인 후) `create-github-pr`
PR 본문에 명시할 것: 루트 `docs/api-spec/product.md` 변경 이유, 구매 검증 미적용(#550 결정)과 후속 이슈 계획, FE 저장소 변경 사항 링크.
