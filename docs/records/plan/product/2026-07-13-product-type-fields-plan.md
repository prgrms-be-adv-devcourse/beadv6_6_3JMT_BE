# 상품유형별 필드 (Spec A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** product 상품 유형(PROMPT/NOTION/PPT/EXCEL)별로 서로 다른 산출물 필드(`file_url`, `external_url`)를 추가하고, 유형에 맞지 않는 필드 조합을 도메인에서 거부한다.

**Architecture:** `product` 테이블에 nullable 컬럼 2개를 두는 sparse-column 방식. 유형별 정합성은 `Product` 도메인 팩토리에서 검증한다. 서비스는 유형 분기 없이 `file_url`을 스토리지 키로(기존 thumbnail 흐름 재사용), `external_url`을 외부 링크 원문으로 처리하고, 도메인 검증이 상호배타성을 보장한다.

**Tech Stack:** Java 21, Spring Boot, JPA(Hibernate `ddl-auto`), JUnit5 + Mockito + AssertJ.

## Global Constraints

- 설계 문서: `docs/superpowers/specs/2026-07-13-product-type-fields-design.md` (근거, 로컬 보관 — git 미추적)
- 유형별 필드 매트릭스: PROMPT→`content` / PPT·EXCEL→`file_url` / NOTION→`external_url` (각 1개, 나머지 null)
- 불일치/누락 = `ProductException(PRODUCT_TYPE_FIELD_MISMATCH)` → 400
- 컬럼 순서: 팩토리 시그니처는 `content` 바로 다음에 `fileUrl`, `externalUrl` 삽입
- 코드 스타일: `style/checkstyle/prompthub-checkstyle-rules.xml` (wildcard/unused import 금지, 빈 catch 금지)
- Build 검증: `product-service` 디렉터리에서 `.\gradlew.bat clean build --no-daemon`
- 스키마: product-service는 JPA `ddl-auto` 사용(별도 SQL 파일 없음). 배포 DB DDL은 사용자가 직접 적용 → Task 4가 `ALTER TABLE` 문 산출
- 이 spec은 **판매자 상세 응답에만** 필드를 노출한다. 공개 상세(`ProductDetailResponse`)·구매자 gRPC는 범위 밖(각각 미노출/ C spec)

---

### Task 1: 요청 DTO + 엔티티 필드 + 서비스 배선 (구조 플러밍, 검증 없음)

`file_url`/`external_url`이 요청→엔티티→저장까지 흐르게 만든다. 유형 검증은 Task 2. 이 태스크 종료 시 모듈이 컴파일되고 기존 테스트가 통과해야 한다.

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/presentation/dto/request/ProductCreateRequest.java`
- Modify: `product-service/src/main/java/com/prompthub/product/presentation/dto/request/ProductUpdateRequest.java`
- Modify: `product-service/src/main/java/com/prompthub/product/domain/model/entity/Product.java` (create/update/nextVersion 시그니처 + 필드)
- Modify: `product-service/src/main/java/com/prompthub/product/application/service/ProductSellerService.java:59` (create 호출), `:105` (update 호출), `:119`, `:125` (nextVersion 호출)
- Test: `product-service/src/test/java/com/prompthub/product/domain/model/entity/ProductTest.java` (기존 8개 create / 2개 update / 2개 nextVersion 호출부 인자 추가)
- Test: `product-service/src/test/java/com/prompthub/product/application/service/ProductSellerServiceTest.java` (`request()`, `product()` 헬퍼 인자 추가 + 신규 createProduct 테스트)

**Interfaces:**
- Produces:
  - `ProductCreateRequest(String title, String productType, String model, String desc, Integer amount, String content, String fileUrl, String externalUrl, String thumbnailUrl, List<String> imageUrls, List<String> tags)` — `content`에서 `@NotBlank` 제거
  - `ProductUpdateRequest(String title, String productType, String model, String desc, Integer amount, String content, String fileUrl, String externalUrl, String thumbnailUrl, List<String> imageUrls, List<String> tags, String changeReason, String versionType)`
  - `Product.create(UUID id, UUID sellerId, ProductType productType, String name, String description, String model, AmountType amountType, int amount, String thumbnailUrl, List<String> imageUrls, String content, String fileUrl, String externalUrl, List<String> tags)`
  - `Product.update(ProductType productType, String name, String description, String model, AmountType amountType, int amount, String thumbnailUrl, List<String> imageUrls, String content, String fileUrl, String externalUrl, List<String> tags, String changeReason, boolean isMajor)`
  - `Product.nextVersion(boolean isMajor, ProductType productType, String name, String description, String model, AmountType amountType, int amount, String thumbnailUrl, List<String> imageUrls, String content, String fileUrl, String externalUrl, List<String> tags, String changeReason)`
  - `Product.getFileUrl()`, `Product.getExternalUrl()` (Lombok `@Getter`)

- [ ] **Step 1: 요청 DTO 두 개에 필드 추가 + content @NotBlank 제거**

`ProductCreateRequest.java` 전체를 아래로 교체:

```java
package com.prompthub.product.presentation.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ProductCreateRequest(
	@NotBlank String title,
	String productType,
	@NotBlank String model,
	@NotBlank String desc,
	@NotNull @Min(0) Integer amount,
	String content,
	String fileUrl,
	String externalUrl,
	String thumbnailUrl,
	List<String> imageUrls,
	List<String> tags
) {
}
```

`ProductUpdateRequest.java` 전체를 아래로 교체:

```java
package com.prompthub.product.presentation.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ProductUpdateRequest(
	@NotBlank String title,
	String productType,
	@NotBlank String model,
	@NotBlank String desc,
	@NotNull @Min(0) Integer amount,
	String content,
	String fileUrl,
	String externalUrl,
	String thumbnailUrl,
	List<String> imageUrls,
	List<String> tags,
	String changeReason,
	String versionType
) {
}
```

- [ ] **Step 2: `Product` 엔티티에 필드 2개 + 3개 팩토리 시그니처 확장**

`Product.java`에서 `content` 필드 선언 바로 아래(현재 74-75행 근처)에 추가:

```java
	@Column(name = "file_url", columnDefinition = "TEXT")
	private String fileUrl;

	@Column(name = "external_url", columnDefinition = "TEXT")
	private String externalUrl;
```

`create(...)` 시그니처를 `String content, List<String> tags` → `String content, String fileUrl, String externalUrl, List<String> tags`로 바꾸고, 본문에서 `product.content = content;` 아래에 대입 추가:

```java
		product.content = content;
		product.fileUrl = fileUrl;
		product.externalUrl = externalUrl;
```

`update(...)` 시그니처를 `String content, List<String> tags, String changeReason, boolean isMajor` → `String content, String fileUrl, String externalUrl, List<String> tags, String changeReason, boolean isMajor`로 바꾸고 `this.content = content;` 아래에 추가:

```java
		this.content = content;
		this.fileUrl = fileUrl;
		this.externalUrl = externalUrl;
```

`nextVersion(...)` 시그니처를 `String content, List<String> tags, String changeReason` → `String content, String fileUrl, String externalUrl, List<String> tags, String changeReason`로 바꾸고 `next.content = content;` 아래에 추가:

```java
		next.content = content;
		next.fileUrl = fileUrl;
		next.externalUrl = externalUrl;
```

- [ ] **Step 3: 서비스 3개 호출부 배선 (fileUrl→키, externalUrl→raw)**

`ProductSellerService.createProduct`에서 `imageKeys` 계산 뒤에 파일 키 추출을 추가하고, `Product.create(...)` 호출에 두 인자를 넣는다. 59행의 create 호출을 아래로 교체:

```java
		String fileKey = moveToProductPath(extractKey(request.fileUrl()), productId);
		AmountType amountType = request.amount() == 0 ? AmountType.FREE : AmountType.PAID;
		Product product = Product.create(
			productId,
			sellerId,
			productType,
			request.title(),
			request.desc(),
			request.model(),
			amountType,
			request.amount(),
			thumbnailKey,
			imageKeys,
			request.content(),
			fileKey,
			request.externalUrl(),
			request.tags()
		);
```

`updateProduct`에서 `newImageKeys` 계산 뒤에 추가:

```java
		String newFileKey = moveToProductPath(extractKey(request.fileUrl()), productId);
```

그리고 `updateProduct` 안의 `anchor.update(...)` 호출(105행)과 두 `onSale.nextVersion(...)` 호출(119, 125행)에서 `request.content()` 뒤에 `newFileKey, request.externalUrl()`를 삽입한다. 예: in-place update:

```java
			anchor.update(
				productType, request.title(), request.desc(), request.model(), amountType, request.amount(),
				newThumbnailKey, newImageKeys, request.content(), newFileKey, request.externalUrl(),
				request.tags(), request.changeReason(), isMajor
			);
```

major nextVersion:

```java
				Product next = onSale.nextVersion(
					true, productType, request.title(), request.desc(), request.model(), amountType, request.amount(),
					newThumbnailKey, newImageKeys, request.content(), newFileKey, request.externalUrl(),
					request.tags(), request.changeReason()
				);
```

patch nextVersion도 동일하게 `request.content(), newFileKey, request.externalUrl(),` 삽입(첫 인자 `false`).

- [ ] **Step 4: 기존 테스트 호출부 인자 추가**

`ProductTest.java`의 모든 `Product.create(...)` 호출(8곳)에서 `"content"` 뒤에 `null, null,`를 넣어 `..., "content", null, null, List.of(...))` 형태로 만든다. `update(...)` 호출(45행)은 `"content2"` 뒤에 `null, null,` 삽입. `nextVersion(...)` 호출(78, 105행)은 `"content2"`/`"content"` 뒤에 `null, null,` 삽입.

`ProductSellerServiceTest.java`의 `request()` 헬퍼를 교체:

```java
	private ProductUpdateRequest request(String versionType) {
		return new ProductUpdateRequest(
			"새 제목", "PROMPT", "model2", "새 설명", 2000, "content2",
			null, null, null, List.of(), List.of(), "변경 사유", versionType
		);
	}
```

`product(...)` 헬퍼의 `Product.create(...)`에서 `"content"` 뒤에 `null, null,` 삽입:

```java
		Product product = Product.create(
			id, SELLER_ID, ProductType.PROMPT,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", null, null, List.of()
		);
```

- [ ] **Step 5: 신규 createProduct 서비스 테스트 작성 (실패 확인)**

`ProductSellerServiceTest.java`에 `@Nested class CreateProduct` 추가:

```java
	@Nested
	@DisplayName("상품 생성 - 유형별 필드")
	class CreateProduct {

		@Test
		@DisplayName("NOTION 생성 시 external_url을 외부 링크 원문 그대로 저장한다")
		void createProduct_notion_savesExternalUrlRaw() {
			given(sellerClient.getSellerInfo(SELLER_ID))
				.willReturn(new com.prompthub.product.application.client.SellerInfo(
					SELLER_ID, "판매자", null, "ACTIVE"));
			given(productRepository.save(org.mockito.ArgumentMatchers.any(Product.class)))
				.willAnswer(inv -> inv.getArgument(0));

			productSellerService.createProduct(SELLER_ID, new com.prompthub.product.presentation.dto.request.ProductCreateRequest(
				"노션 상품", "NOTION", "model", "설명", 1000,
				null, null, "https://notion.so/my-template", null, List.of(), List.of()
			));

			ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
			then(productRepository).should().save(captor.capture());
			assertThat(captor.getValue().getExternalUrl()).isEqualTo("https://notion.so/my-template");
			assertThat(captor.getValue().getFileUrl()).isNull();
			then(storageClient).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("PPT 생성 시 file_url 임시 키를 상품 경로로 이동해 키로 저장한다")
		void createProduct_ppt_movesFileKey() {
			given(sellerClient.getSellerInfo(SELLER_ID))
				.willReturn(new com.prompthub.product.application.client.SellerInfo(
					SELLER_ID, "판매자", null, "ACTIVE"));
			given(productRepository.save(org.mockito.ArgumentMatchers.any(Product.class)))
				.willAnswer(inv -> inv.getArgument(0));

			productSellerService.createProduct(SELLER_ID, new com.prompthub.product.presentation.dto.request.ProductCreateRequest(
				"PPT 상품", "PPT", "model", "설명", 1000,
				null, "products/temp/file/abc.pptx", null, null, List.of(), List.of()
			));

			ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
			then(productRepository).should().save(captor.capture());
			assertThat(captor.getValue().getFileUrl()).startsWith("products/");
			assertThat(captor.getValue().getFileUrl()).endsWith("/file/abc.pptx");
			assertThat(captor.getValue().getExternalUrl()).isNull();
			then(storageClient).should().copyObject(
				org.mockito.ArgumentMatchers.eq("products/temp/file/abc.pptx"),
				org.mockito.ArgumentMatchers.anyString());
		}
	}
```

> 참고: `SellerInfo`의 실제 필드 순서는 `product-service/.../application/client/SellerInfo.java`를 열어 확인하고 생성자 인자를 맞춘다. `status()`가 `"ACTIVE"`여야 통과한다.

- [ ] **Step 6: 빌드 실행 (실패 → 구현 → 통과)**

Run: `cd C:\programmers_prj\beadv6_6_3JMT_BE\product-service; .\gradlew.bat clean build --no-daemon`
Expected: Step 1-5 반영 후 컴파일 성공, 신규 2개 포함 전체 테스트 PASS. (Step 5를 먼저 넣고 미구현 상태면 컴파일/RED, Step 1-4 반영 후 GREEN)

- [ ] **Step 7: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/presentation/dto/request/ProductCreateRequest.java product-service/src/main/java/com/prompthub/product/presentation/dto/request/ProductUpdateRequest.java product-service/src/main/java/com/prompthub/product/domain/model/entity/Product.java product-service/src/main/java/com/prompthub/product/application/service/ProductSellerService.java product-service/src/test/java/com/prompthub/product/domain/model/entity/ProductTest.java product-service/src/test/java/com/prompthub/product/application/service/ProductSellerServiceTest.java
git commit -m "feat: 상품 유형별 필드(file_url, external_url) 요청·엔티티·저장 배선"
```

---

### Task 2: 유형별 정합성 도메인 검증 (TDD)

유형에 맞지 않는 필드 조합을 `Product` 팩토리에서 거부한다.

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/exception/enums/ProductErrorCode.java`
- Modify: `product-service/src/main/java/com/prompthub/product/domain/model/entity/Product.java` (검증 메서드 + create/update/nextVersion에서 호출)
- Test: `product-service/src/test/java/com/prompthub/product/domain/model/entity/ProductTest.java`

**Interfaces:**
- Consumes: Task 1의 확장된 create/update/nextVersion 시그니처, `Product.getFileUrl()`/`getExternalUrl()`
- Produces: `ProductErrorCode.PRODUCT_TYPE_FIELD_MISMATCH`; `Product` 생성/변경 시 유형 불일치면 `ProductException` throw

- [ ] **Step 1: 실패 테스트 작성**

`ProductTest.java`에 추가(import: `com.prompthub.product.exception.ProductException`):

```java
	@Test
	void create_prompt_withFileUrl_throws() {
		assertThatThrownBy(() -> Product.create(
			UUID.randomUUID(), UUID.randomUUID(), ProductType.PROMPT,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", "products/x.pptx", null, List.of()
		)).isInstanceOf(ProductException.class);
	}

	@Test
	void create_ppt_withoutFileUrl_throws() {
		assertThatThrownBy(() -> Product.create(
			UUID.randomUUID(), UUID.randomUUID(), ProductType.PPT,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), null, null, null, List.of()
		)).isInstanceOf(ProductException.class);
	}

	@Test
	void create_notion_withExternalUrl_succeeds() {
		Product product = Product.create(
			UUID.randomUUID(), UUID.randomUUID(), ProductType.NOTION,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), null, null, "https://notion.so/t", List.of()
		);
		assertThat(product.getExternalUrl()).isEqualTo("https://notion.so/t");
	}

	@Test
	void create_ppt_withFileUrl_succeeds() {
		Product product = Product.create(
			UUID.randomUUID(), UUID.randomUUID(), ProductType.PPT,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), null, "products/1/file/a.pptx", null, List.of()
		);
		assertThat(product.getFileUrl()).isEqualTo("products/1/file/a.pptx");
	}
```

- [ ] **Step 2: 빌드로 실패 확인**

Run: `cd C:\programmers_prj\beadv6_6_3JMT_BE\product-service; .\gradlew.bat test --no-daemon --tests "com.prompthub.product.domain.model.entity.ProductTest"`
Expected: `create_prompt_withFileUrl_throws`, `create_ppt_withoutFileUrl_throws` FAIL (검증 미구현이라 예외 안 던짐)

- [ ] **Step 3: 에러 코드 추가**

`ProductErrorCode.java`의 `S3_PRESIGN_FAILED` 위 줄에 추가(`INVALID_PRODUCT_TYPE`가 P004이므로 P007 사용, P005/P006 이미 존재):

```java
	PRODUCT_TYPE_FIELD_MISMATCH(HttpStatus.BAD_REQUEST, "P007", "상품 유형에 맞지 않는 필드 구성입니다."),
```

- [ ] **Step 4: 검증 메서드 구현 + 호출**

`Product.java`에 private static 헬퍼 추가(파일 하단, import: `com.prompthub.product.exception.ProductException`, `com.prompthub.product.exception.enums.ProductErrorCode`):

```java
	private static void validateTypeFields(
		ProductType productType, String content, String fileUrl, String externalUrl
	) {
		boolean hasContent = content != null && !content.isBlank();
		boolean hasFileUrl = fileUrl != null && !fileUrl.isBlank();
		boolean hasExternalUrl = externalUrl != null && !externalUrl.isBlank();

		boolean ok = switch (productType) {
			case PROMPT -> hasContent && !hasFileUrl && !hasExternalUrl;
			case PPT, EXCEL -> hasFileUrl && !hasContent && !hasExternalUrl;
			case NOTION -> hasExternalUrl && !hasContent && !hasFileUrl;
		};
		if (!ok) {
			throw new ProductException(ProductErrorCode.PRODUCT_TYPE_FIELD_MISMATCH);
		}
	}
```

`create`/`update`/`nextVersion` 각 메서드 본문 **맨 앞**에 호출 추가:

```java
		validateTypeFields(productType, content, fileUrl, externalUrl);
```

- [ ] **Step 5: 빌드로 통과 확인**

Run: `cd C:\programmers_prj\beadv6_6_3JMT_BE\product-service; .\gradlew.bat clean build --no-daemon`
Expected: 전체 PASS. (Task1의 서비스 테스트가 유형에 맞는 조합을 쓰므로 계속 통과)

- [ ] **Step 6: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/exception/enums/ProductErrorCode.java product-service/src/main/java/com/prompthub/product/domain/model/entity/Product.java product-service/src/test/java/com/prompthub/product/domain/model/entity/ProductTest.java
git commit -m "feat: 상품 유형별 필드 정합성 도메인 검증 및 에러코드 추가"
```

---

### Task 3: 판매자 상세 응답에 필드 노출 (presign)

판매자가 자기 상품 상세에서 `fileUrl`(presigned) / `externalUrl`(원문)을 받도록 한다.

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/presentation/dto/response/SellerProductDetailResponse.java`
- Test: `product-service/src/test/java/com/prompthub/product/application/service/ProductSellerServiceTest.java`

**Interfaces:**
- Consumes: `Product.getFileUrl()`, `Product.getExternalUrl()`, `StorageClient.generatePresignedDownloadUrl(String)`
- Produces: `SellerProductDetailResponse`에 `fileUrl`, `externalUrl` 필드 추가

- [ ] **Step 1: 실패 테스트 작성**

`ProductSellerServiceTest.java`의 `GetMyProduct` nested class에 추가(헬퍼 `product(...)`는 PROMPT라 fileUrl/externalUrl이 null이므로, 필드를 세팅해 검증):

```java
		@Test
		@DisplayName("판매자 상세는 fileUrl을 presigned로, externalUrl을 원문으로 반환한다")
		void getMyProduct_exposesTypeFields() {
			Product onSale = product(PRODUCT_ID, null, ProductStatus.ON_SALE, (short) 1, (short) 0);
			ReflectionTestUtils.setField(onSale, "fileUrl", "products/1/file/a.pptx");
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(onSale));
			given(storageClient.generatePresignedDownloadUrl("products/1/file/a.pptx"))
				.willReturn("https://s3/presigned-file");

			com.prompthub.product.presentation.dto.response.SellerProductDetailResponse result =
				productSellerService.getMyProduct(SELLER_ID, PRODUCT_ID);

			assertThat(result.fileUrl()).isEqualTo("https://s3/presigned-file");
			assertThat(result.externalUrl()).isNull();
		}
```

- [ ] **Step 2: 빌드로 실패 확인**

Run: `cd C:\programmers_prj\beadv6_6_3JMT_BE\product-service; .\gradlew.bat test --no-daemon --tests "com.prompthub.product.application.service.ProductSellerServiceTest"`
Expected: 컴파일 실패(`result.fileUrl()` 없음)

- [ ] **Step 3: 응답 DTO에 필드 추가**

`SellerProductDetailResponse.java`의 record 컴포넌트에 `content` 다음 줄로 추가:

```java
	String content,
	String fileUrl,
	String externalUrl,
```

그리고 `from(...)` 팩토리의 `new SellerProductDetailResponse(...)` 호출에서 `product.getContent(),` 다음에 두 줄 삽입:

```java
			product.getContent(),
			toUrl(product.getFileUrl(), storageClient),
			product.getExternalUrl(),
```

(`toUrl` private 헬퍼는 파일에 이미 존재하며 null/blank면 null 반환한다.)

- [ ] **Step 4: 빌드로 통과 확인**

Run: `cd C:\programmers_prj\beadv6_6_3JMT_BE\product-service; .\gradlew.bat clean build --no-daemon`
Expected: 전체 PASS

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/presentation/dto/response/SellerProductDetailResponse.java product-service/src/test/java/com/prompthub/product/application/service/ProductSellerServiceTest.java
git commit -m "feat: 판매자 상품 상세 응답에 유형별 필드(fileUrl presigned, externalUrl) 노출"
```

---

### Task 4: docs 동기화 + 배포 DB ALTER DDL 산출

`sync-product-docs` 스킬 기준으로 루트 docs를 갱신하고, 사용자가 배포 DB에 적용할 DDL을 문서로 남긴다.

**Files:**
- Modify: `docs/api-spec/product.md` (유형별 요청/응답 필드 계약)
- Modify: `docs/error-codes.md` (PRODUCT 섹션에 P007)
- Modify: `docs/erd/schema.md` (Product 섹션에 `file_url`, `external_url`)
- Create: `product-service/docs/plans/2026-07-13-product-type-fields-alter.sql` (배포 DB용 ALTER)

- [ ] **Step 1: 각 docs 파일을 먼저 읽어 기존 형식 확인**

Run(읽기): `docs/api-spec/product.md`, `docs/error-codes.md`, `docs/erd/schema.md`의 Product/PRODUCT 섹션. 기존 표·행 포맷을 그대로 따른다.

- [ ] **Step 2: error-codes.md에 P007 추가**

PRODUCT 섹션의 P006 아래에 기존 행 포맷과 동일하게 추가:

```
| P007 | 400 | 상품 유형에 맞지 않는 필드 구성입니다. |
```

- [ ] **Step 3: schema.md Product 섹션에 컬럼 2개 추가**

`content` 행 아래에 기존 컬럼 행 포맷과 동일하게 `file_url`(TEXT, NULL, "유형별 산출물 파일 스토리지 키(PPT/EXCEL)")와 `external_url`(TEXT, NULL, "외부 노션 링크 원문(NOTION)") 추가.

- [ ] **Step 4: api-spec/product.md에 유형별 필드 계약 추가**

판매자 생성/수정 요청 필드에 `fileUrl`, `externalUrl`(nullable) 및 `content`가 이제 유형별(PROMPT 필수)임을 명시하고, 유형별 매트릭스 표(설계 §3/§6)와 판매자 상세 응답의 `fileUrl`(presigned)/`externalUrl`(원문) 필드를 추가한다. 공개 상세에는 미노출임을 한 줄 명시.

- [ ] **Step 5: 배포 DB ALTER 스크립트 산출**

`product-service/docs/plans/2026-07-13-product-type-fields-alter.sql` 생성:

```sql
-- 배포 DB에 사용자가 직접 적용 (로컬/테스트는 JPA ddl-auto가 자동 생성)
ALTER TABLE product ADD COLUMN file_url TEXT;
ALTER TABLE product ADD COLUMN external_url TEXT;
```

- [ ] **Step 6: 커밋**

```bash
git add docs/api-spec/product.md docs/error-codes.md docs/erd/schema.md product-service/docs/plans/2026-07-13-product-type-fields-alter.sql
git commit -m "docs: 상품 유형별 필드 API/에러코드/스키마 동기화 및 배포 ALTER 산출"
```

---

## Self-Review 결과

- **Spec 커버리지**: §4 컬럼→Task1 Step2 + Task4; §5 도메인/시그니처→Task1; §6 검증→Task2; §7 스토리지 배선→Task1 Step3; §8 요청 DTO/판매자 응답→Task1/Task3(공개 응답 제외 반영); §9 에러코드→Task2; §10 테스트→각 Task; §11 docs→Task4. 누락 없음.
- **Placeholder**: 없음(모든 코드 블록 실제 내용).
- **타입 일관성**: create/update/nextVersion 시그니처의 `fileUrl, externalUrl` 위치가 세 Task에서 동일. `getFileUrl()/getExternalUrl()`, `PRODUCT_TYPE_FIELD_MISMATCH` 이름 일치.
- **주의(구현자 확인 필요)**: `SellerInfo` 생성자 인자 순서는 실제 파일로 확인(Task1 Step5 주석). schema.md/error-codes.md/api-spec 실제 표 포맷은 파일을 읽고 맞출 것(Task4 Step1).
