# ProductContent 파라미터 객체 리팩토링 구현 계획 (#405)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `Product.create()/update()/nextVersion()`의 12개 "상품 내용" 파라미터를 `ProductContent` record로 묶어 Long Parameter List 스멜을 제거한다.

**Architecture:** expand–contract(확장 후 수축) 방식. 먼저 `ProductContent` VO와 새 오버로드를 추가해 기존 시그니처가 새 오버로드에 위임하게 만들고(모든 단계에서 빌드 그린 유지), 호출부를 파일 단위로 이관한 뒤, 마지막에 구 14-파라미터 시그니처를 제거한다. 유형별 필수 필드 검증(`validateTypeFields`)과 null 정규화는 record 생성자로 이동해 "잘못된 조합의 상자는 생성 불가"로 만든다.

**Tech Stack:** Java 21 record, Spring Boot, JUnit 5 + AssertJ. JPA 매핑·DB 스키마·API 계약 변경 없음.

## Global Constraints

- 설계 문서: `docs/superpowers/specs/2026-07-20-product-content-param-object-design.md` (근거, 로컬 보관 — git 미추적)
- 이슈: #405, 브랜치: `refactor/#405-product-domain-long-param-list`
- 동작 불변: 에러 코드(`PRODUCT_TYPE_FIELD_MISMATCH`)·상태 전이·API 응답 모두 기존과 동일해야 함
- record 필드 순서는 기존 시그니처 순서 고정: `productType, name, description, model, amountType, amount, thumbnailUrl, imageUrls, content, fileUrl, externalUrl, tags`
- `changeReason`/`isMajor`는 상자에 넣지 않음 (버전 관리 메타데이터)
- 들여쓰기는 탭 (기존 코드와 동일), wildcard/unused import 금지 (checkstyle)
- 각 Task 완료 시점에 빌드가 깨져 있으면 안 됨
- 커밋은 `.claude/skills/commit/SKILL.md` 게이트를 따르고, 실행 전 사용자에게 커밋 여부를 확인한다
- 테스트 실행: `cd C:\programmers_prj\beadv6_6_3JMT_BE\product-service` 후 `.\gradlew.bat test --tests "<클래스>"`. 전체 빌드는 `.\gradlew.bat clean build --no-daemon` (DB 필요 시 `docs`의 testing.md 환경변수 블록 사용)

---

### Task 1: `ProductContent` record + `ProductContentTest` (TDD)

**Files:**
- Create: `product-service/src/main/java/com/prompthub/product/domain/model/vo/ProductContent.java`
- Test: `product-service/src/test/java/com/prompthub/product/domain/model/vo/ProductContentTest.java`

**Interfaces:**
- Produces: `public record ProductContent(ProductType productType, String name, String description, String model, AmountType amountType, int amount, String thumbnailUrl, List<String> imageUrls, String content, String fileUrl, String externalUrl, List<String> tags)` — 생성 시 null 정규화 + 유형별 필드 검증(위반 시 `ProductException(PRODUCT_TYPE_FIELD_MISMATCH)`). 이후 모든 Task가 이 record를 사용한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`ProductContentTest.java` 전체 내용:

```java
package com.prompthub.product.domain.model.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.exception.ProductException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductContentTest {

	@Test
	void prompt_withFileUrl_throws() {
		assertThatThrownBy(() -> new ProductContent(
			ProductType.PROMPT, "제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", "products/x.pptx", null, List.of()
		)).isInstanceOf(ProductException.class);
	}

	@Test
	void ppt_withoutFileUrl_throws() {
		assertThatThrownBy(() -> new ProductContent(
			ProductType.PPT, "제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), null, null, null, List.of()
		)).isInstanceOf(ProductException.class);
	}

	@Test
	void notion_withExternalUrl_succeeds() {
		ProductContent content = new ProductContent(
			ProductType.NOTION, "제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), null, null, "https://notion.so/t", List.of()
		);
		assertThat(content.externalUrl()).isEqualTo("https://notion.so/t");
	}

	@Test
	void ppt_withFileUrl_succeeds() {
		ProductContent content = new ProductContent(
			ProductType.PPT, "제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), null, "products/1/file/a.pptx", null, List.of()
		);
		assertThat(content.fileUrl()).isEqualTo("products/1/file/a.pptx");
	}

	@Test
	void nullImageUrlsAndTags_normalizedToEmptyLists() {
		ProductContent content = new ProductContent(
			ProductType.PROMPT, "제목", "설명", "model", AmountType.PAID, 1000,
			null, null, "content", null, null, null
		);
		assertThat(content.imageUrls()).isEmpty();
		assertThat(content.tags()).isEmpty();
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `.\gradlew.bat test --tests "com.prompthub.product.domain.model.vo.ProductContentTest"`
Expected: 컴파일 실패 — `ProductContent` 클래스가 없음

- [ ] **Step 3: record 구현**

`ProductContent.java` 전체 내용 (`validateTypeFields`는 `Product.java:343-358`에서 그대로 가져온 것):

```java
package com.prompthub.product.domain.model.vo;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import java.util.ArrayList;
import java.util.List;

/**
 * 상품 내용 파라미터 객체 (Introduce Parameter Object, #405).
 * 생성 시 유형별 필수 필드 검증과 컬렉션 null 정규화를 수행하므로
 * 잘못된 조합의 인스턴스는 존재할 수 없다.
 */
public record ProductContent(
	ProductType productType,
	String name,
	String description,
	String model,
	AmountType amountType,
	int amount,
	String thumbnailUrl,
	List<String> imageUrls,
	String content,
	String fileUrl,
	String externalUrl,
	List<String> tags
) {

	public ProductContent {
		imageUrls = imageUrls != null ? imageUrls : new ArrayList<>();
		tags = tags != null ? tags : new ArrayList<>();
		validateTypeFields(productType, content, fileUrl, externalUrl);
	}

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
}
```

- [ ] **Step 4: 통과 확인**

Run: `.\gradlew.bat test --tests "com.prompthub.product.domain.model.vo.ProductContentTest"`
Expected: 5개 테스트 전부 PASS

- [ ] **Step 5: 커밋 (사용자 확인 후)**

```bash
git add product-service/src/main/java/com/prompthub/product/domain/model/vo/ProductContent.java product-service/src/test/java/com/prompthub/product/domain/model/vo/ProductContentTest.java
git commit -m "refactor: ProductContent 파라미터 객체(VO) 도입 (#405)"
```

---

### Task 2: `Product`에 ProductContent 오버로드 추가 (기존 시그니처는 위임으로 전환)

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/domain/model/entity/Product.java`

**Interfaces:**
- Consumes: Task 1의 `ProductContent`
- Produces: `public static Product create(UUID id, UUID sellerId, ProductContent productContent)`, `public void update(ProductContent productContent, String changeReason, boolean isMajor)`, `public Product nextVersion(boolean isMajor, ProductContent productContent, String changeReason)` — Task 3·4가 이 시그니처를 호출한다. 기존 14-파라미터 시그니처는 이 단계에서 새 오버로드에 위임하며 Task 5에서 제거된다.

리팩토링 단계이므로 새 테스트 없이 기존 테스트를 그린으로 유지하는 방식으로 진행한다
(기존 `ProductTest`가 위임을 통해 동작 보존을 검증한다).

- [ ] **Step 1: 베이스라인 그린 확인**

Run: `.\gradlew.bat test --tests "com.prompthub.product.domain.model.entity.ProductTest"`
Expected: 전부 PASS (시작점 확인)

- [ ] **Step 2: import 정리 및 `applyContent` 헬퍼 추가**

`Product.java`에서:

1. import 변경 — `ProductException`, `ProductErrorCode` import 삭제(더 이상 안 씀),
   `ProductContent` import 추가:

```java
import com.prompthub.product.domain.model.vo.ProductContent;
```

2. 클래스 끝부분의 `validateTypeFields()` 메서드(L343-358) 전체 삭제.

3. private 헬퍼 추가 (필드 대입 12줄을 한 곳으로):

```java
	private void applyContent(ProductContent productContent) {
		this.productType = productContent.productType();
		this.name = productContent.name();
		this.description = productContent.description();
		this.model = productContent.model();
		this.amountType = productContent.amountType();
		this.amount = productContent.amount();
		this.thumbnailUrl = productContent.thumbnailUrl();
		this.imageUrls = productContent.imageUrls();
		this.content = productContent.content();
		this.fileUrl = productContent.fileUrl();
		this.externalUrl = productContent.externalUrl();
		this.tags = productContent.tags();
	}
```

- [ ] **Step 3: 새 오버로드 3개 추가 + 기존 시그니처를 위임으로 교체**

새 `create` 오버로드 (기존 `create` 바로 위에 추가):

```java
	public static Product create(UUID id, UUID sellerId, ProductContent productContent) {
		Product product = new Product();
		product.id = id;
		product.sellerId = sellerId;
		product.applyContent(productContent);
		product.majorVersion = 1;
		product.patchVersion = 0;
		product.status = ProductStatus.DRAFT;
		product.salesCount = 0;
		product.viewCount = 0;
		product.wishCount = 0;
		product.createdAt = LocalDateTime.now();
		product.updatedAt = LocalDateTime.now();
		return product;
	}
```

기존 14-파라미터 `create`의 본문 전체를 위임 한 줄로 교체 (null 정규화·검증은 `ProductContent` 생성자가 수행):

```java
	public static Product create(
		UUID id,
		UUID sellerId,
		ProductType productType,
		String name,
		String description,
		String model,
		AmountType amountType,
		int amount,
		String thumbnailUrl,
		List<String> imageUrls,
		String content,
		String fileUrl,
		String externalUrl,
		List<String> tags
	) {
		return create(id, sellerId, new ProductContent(
			productType, name, description, model, amountType, amount,
			thumbnailUrl, imageUrls, content, fileUrl, externalUrl, tags
		));
	}
```

새 `update` 오버로드:

```java
	public void update(ProductContent productContent, String changeReason, boolean isMajor) {
		applyContent(productContent);
		this.changeReason = changeReason;
		if (isMajor) {
			this.majorVersion++;
			this.patchVersion = 0;
			this.status = ProductStatus.PENDING_REVIEW;
		} else {
			this.patchVersion++;
		}
		this.updatedAt = LocalDateTime.now();
	}
```

기존 14-파라미터 `update` 본문을 위임으로 교체:

```java
	public void update(
		ProductType productType,
		String name,
		String description,
		String model,
		AmountType amountType,
		int amount,
		String thumbnailUrl,
		List<String> imageUrls,
		String content,
		String fileUrl,
		String externalUrl,
		List<String> tags,
		String changeReason,
		boolean isMajor
	) {
		update(new ProductContent(
			productType, name, description, model, amountType, amount,
			thumbnailUrl, imageUrls, content, fileUrl, externalUrl, tags
		), changeReason, isMajor);
	}
```

새 `nextVersion` 오버로드 (badge 초기화 주석은 기존 것을 그대로 옮긴다):

```java
	public Product nextVersion(boolean isMajor, ProductContent productContent, String changeReason) {
		Product next = new Product();
		next.id = UUID.randomUUID();
		next.parentId = this.familyRootId();
		next.sellerId = this.sellerId;
		next.applyContent(productContent);
		next.changeReason = changeReason;
		next.badge = null; // 새 버전 row는 뱃지를 물려받지 않고 초기화한다(예: "신규" 뱃지가 계속 남는 걸 방지)
		if (isMajor) {
			next.majorVersion = (short) (this.majorVersion + 1);
			next.patchVersion = 0;
			next.status = ProductStatus.PENDING_REVIEW;
		} else {
			next.majorVersion = this.majorVersion;
			next.patchVersion = (short) (this.patchVersion + 1);
			next.status = ProductStatus.ON_SALE;
		}
		next.salesCount = 0;
		next.viewCount = 0;
		next.wishCount = 0;
		next.createdAt = LocalDateTime.now();
		next.updatedAt = LocalDateTime.now();
		return next;
	}
```

기존 14-파라미터 `nextVersion` 본문을 위임으로 교체:

```java
	public Product nextVersion(
		boolean isMajor,
		ProductType productType,
		String name,
		String description,
		String model,
		AmountType amountType,
		int amount,
		String thumbnailUrl,
		List<String> imageUrls,
		String content,
		String fileUrl,
		String externalUrl,
		List<String> tags,
		String changeReason
	) {
		return nextVersion(isMajor, new ProductContent(
			productType, name, description, model, amountType, amount,
			thumbnailUrl, imageUrls, content, fileUrl, externalUrl, tags
		), changeReason);
	}
```

- [ ] **Step 4: 그린 유지 확인**

Run: `.\gradlew.bat test --tests "com.prompthub.product.domain.model.entity.ProductTest" --tests "com.prompthub.product.domain.model.vo.ProductContentTest"`
Expected: 전부 PASS — 기존 테스트가 위임 경로로 동일 동작을 검증

- [ ] **Step 5: 커밋 (사용자 확인 후)**

```bash
git add product-service/src/main/java/com/prompthub/product/domain/model/entity/Product.java
git commit -m "refactor: Product 생성/수정 메서드에 ProductContent 오버로드 추가 (#405)"
```

---

### Task 3: `ProductSellerService` 호출부 이관

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/application/service/ProductSellerService.java`

**Interfaces:**
- Consumes: Task 2의 `Product.create(UUID, UUID, ProductContent)`, `update(ProductContent, String, boolean)`, `nextVersion(boolean, ProductContent, String)`

**동작 노트(승인된 허용 차이):** 기존에는 유형별 필드 검증이 `nextVersion()` 안(=family 조회 후)에서 실행됐지만, 이관 후에는 `new ProductContent(...)` 시점(=family 조회 전)에 실행된다. 잘못된 요청 + family 상태 이상이 동시에 있는 경우 어느 에러가 먼저 나는지만 달라지며, 정상/실패 여부 자체는 동일하다.

- [ ] **Step 1: import 추가**

```java
import com.prompthub.product.domain.model.vo.ProductContent;
```

- [ ] **Step 2: `createProduct()` 이관**

`ProductSellerService.java:58-74`의 `AmountType` 계산~`Product.create` 호출을 다음으로 교체:

```java
		AmountType amountType = request.amount() == 0 ? AmountType.FREE : AmountType.PAID;
		ProductContent content = new ProductContent(
			productType, request.title(), request.desc(), request.model(),
			amountType, request.amount(), thumbnailKey, imageKeys,
			request.content(), fileKey, request.externalUrl(), request.tags()
		);
		Product product = Product.create(productId, sellerId, content);
```

- [ ] **Step 3: `updateProduct()` 이관 — 상자 1회 생성, 세 분기 재사용**

`ProductSellerService.java:95-100`의 파생값 계산 블록 뒤에 `ProductContent` 생성을 추가:

```java
		ProductType productType = parseProductType(request.productType());
		AmountType amountType = request.amount() == 0 ? AmountType.FREE : AmountType.PAID;
		boolean isMajor = "MAJOR".equalsIgnoreCase(request.versionType());
		String newThumbnailKey = moveToProductPath(extractKey(request.thumbnailUrl()), productId);
		List<String> newImageKeys = moveToProductPaths(extractKeys(request.imageUrls()), productId);
		String newFileKey = moveToProductPath(extractKey(request.fileUrl()), productId);
		ProductContent content = new ProductContent(
			productType, request.title(), request.desc(), request.model(),
			amountType, request.amount(), newThumbnailKey, newImageKeys,
			request.content(), newFileKey, request.externalUrl(), request.tags()
		);
```

세 분기의 호출을 교체:

```java
			anchor.update(content, request.changeReason(), isMajor);
```

```java
				Product next = onSale.nextVersion(true, content, request.changeReason());
```

```java
				Product next = onSale.nextVersion(false, content, request.changeReason());
```

- [ ] **Step 4: 테스트 확인**

Run: `.\gradlew.bat test --tests "com.prompthub.product.application.service.ProductSellerServiceTest"`
Expected: 전부 PASS (서비스 동작 불변)

- [ ] **Step 5: 커밋 (사용자 확인 후)**

```bash
git add product-service/src/main/java/com/prompthub/product/application/service/ProductSellerService.java
git commit -m "refactor: ProductSellerService를 ProductContent 기반 호출로 전환 (#405)"
```

---

### Task 4: 테스트 픽스처 도입 및 테스트 호출부 이관

**Files:**
- Create: `product-service/src/test/java/com/prompthub/product/support/ProductContentFixtures.java`
- Modify: `product-service/src/test/java/com/prompthub/product/domain/model/entity/ProductTest.java` (전면 교체)
- Modify: `product-service/src/test/java/com/prompthub/product/domain/model/entity/ProductFamilyTest.java:88-92`
- Modify: `product-service/src/test/java/com/prompthub/product/infra/persistence/ProductJpaRepositoryTest.java:61-65`
- Modify: `product-service/src/test/java/com/prompthub/product/application/service/ProductSellerServiceTest.java:256-260`
- Modify: `product-service/src/test/java/com/prompthub/product/application/service/ProductAdminServiceTest.java:146-150`
- Modify: `product-service/src/test/java/com/prompthub/product/application/service/ProductQueryServiceTest.java:231-235`

**Interfaces:**
- Consumes: Task 1 `ProductContent`, Task 2 새 오버로드
- Produces: `com.prompthub.product.support.ProductContentFixtures` — `promptContent()`, `promptContent(String name, int amount)`, `notionContent(String name, int amount)`, `pptContent()` (모두 public static)

- [ ] **Step 1: 픽스처 클래스 생성**

`ProductContentFixtures.java` 전체 내용:

```java
package com.prompthub.product.support;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.vo.ProductContent;
import java.util.List;

/** 테스트 전용 ProductContent 픽스처 — 필드 변경 시 이 클래스만 수정하면 된다. */
public final class ProductContentFixtures {

	private ProductContentFixtures() {
	}

	public static ProductContent promptContent() {
		return promptContent("제목", 1000);
	}

	public static ProductContent promptContent(String name, int amount) {
		return new ProductContent(
			ProductType.PROMPT, name, "설명", "model", AmountType.PAID, amount,
			null, List.of(), "content", null, null, List.of()
		);
	}

	public static ProductContent notionContent(String name, int amount) {
		return new ProductContent(
			ProductType.NOTION, name, "새 설명", "model2", AmountType.PAID, amount,
			null, List.of(), null, null, "https://notion.so/x", List.of()
		);
	}

	public static ProductContent pptContent() {
		return new ProductContent(
			ProductType.PPT, "제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), null, "products/1/file/a.pptx", null, List.of()
		);
	}
}
```

- [ ] **Step 2: `ProductTest` 전면 교체**

기존 15개 호출부를 픽스처 기반으로 바꾸고, `create_prompt_withFileUrl_throws`·`create_ppt_withoutFileUrl_throws` 2개는 **삭제**한다(Task 1의 `ProductContentTest`가 같은 규칙을 검증 — 새 거처). `create_notion_withExternalUrl_succeeds`·`create_ppt_withFileUrl_succeeds`는 "엔티티에 값이 실리는지" 검증으로 유지하되 픽스처 값에 맞춰 기대값을 조정한다.

`ProductTest.java` 전체 내용:

```java
package com.prompthub.product.domain.model.entity;

import static com.prompthub.product.support.ProductContentFixtures.notionContent;
import static com.prompthub.product.support.ProductContentFixtures.pptContent;
import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.vo.ProductContent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ProductTest {

	@Test
	void create_withoutCategory_setsProductType() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), new ProductContent(
			ProductType.PROMPT, "상품명", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", null, null, List.of("tag1", "tag2")
		));

		assertThat(product.getProductType()).isEqualTo(ProductType.PROMPT);
		assertThat(product.getTags()).containsExactly("tag1", "tag2");
	}

	@Test
	void update_isMajor_bumpsMajorVersionAndSetsPendingReview() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());

		product.update(notionContent("새 제목", 2000), "변경 사유", true);

		assertThat(product.getProductType()).isEqualTo(ProductType.NOTION);
		assertThat(product.getMajorVersion()).isEqualTo((short) 2);
		assertThat(product.getPatchVersion()).isEqualTo((short) 0);
	}

	@Test
	void familyRootId_returnsSelfId_whenRoot() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());

		assertThat(product.familyRootId()).isEqualTo(product.getId());
		assertThat(product.isFamilyRoot()).isTrue();
	}

	@Test
	void nextVersion_major_createsPendingReviewChildLinkedToFamilyRoot() {
		Product onSale = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(onSale, "status", ProductStatus.ON_SALE);
		ReflectionTestUtils.setField(onSale, "majorVersion", (short) 2);
		ReflectionTestUtils.setField(onSale, "patchVersion", (short) 3);

		Product next = onSale.nextVersion(true, notionContent("새 제목", 2000), "메이저 변경");

		assertThat(next.getId()).isNotEqualTo(onSale.getId());
		assertThat(next.getParentId()).isEqualTo(onSale.familyRootId());
		assertThat(next.getMajorVersion()).isEqualTo((short) 3);
		assertThat(next.getPatchVersion()).isEqualTo((short) 0);
		assertThat(next.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
		assertThat(next.getName()).isEqualTo("새 제목");
		// 승인 전까지 기존 ON_SALE row는 변경되지 않는다
		assertThat(onSale.getStatus()).isEqualTo(ProductStatus.ON_SALE);
		assertThat(onSale.getName()).isEqualTo("제목");
	}

	@Test
	void nextVersion_patch_createsOnSaleChildAndKeepsMajorVersion() {
		Product onSale = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(onSale, "status", ProductStatus.ON_SALE);
		ReflectionTestUtils.setField(onSale, "majorVersion", (short) 2);
		ReflectionTestUtils.setField(onSale, "patchVersion", (short) 3);

		Product next = onSale.nextVersion(false, promptContent("제목", 1500), null);

		assertThat(next.getMajorVersion()).isEqualTo((short) 2);
		assertThat(next.getPatchVersion()).isEqualTo((short) 4);
		assertThat(next.getStatus()).isEqualTo(ProductStatus.ON_SALE);
		assertThat(next.getAmount()).isEqualTo(1500);
	}

	@Test
	void supersede_onSaleRow_transitionsToSuperseded() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "status", ProductStatus.ON_SALE);

		product.supersede();

		assertThat(product.getStatus()).isEqualTo(ProductStatus.SUPERSEDED);
	}

	@Test
	void supersede_nonOnSaleRow_throws() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());

		assertThatThrownBy(product::supersede).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void restoreFromSuperseded_supersededRow_transitionsToOnSale() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "status", ProductStatus.SUPERSEDED);

		product.restoreFromSuperseded();

		assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
	}

	@Test
	void create_notion_withExternalUrl_succeeds() {
		Product product = Product.create(
			UUID.randomUUID(), UUID.randomUUID(), notionContent("제목", 1000)
		);
		assertThat(product.getExternalUrl()).isEqualTo("https://notion.so/x");
	}

	@Test
	void create_ppt_withFileUrl_succeeds() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), pptContent());
		assertThat(product.getFileUrl()).isEqualTo("products/1/file/a.pptx");
	}
}
```

(원본에서 `ProductException` import는 삭제된 `_throws` 테스트에서만 쓰였으므로 제거됨.)

- [ ] **Step 3: 나머지 5개 테스트 파일의 헬퍼 이관**

각 파일의 `Product.create(...)` 호출(각 1곳, 헬퍼 메서드 안)을 교체하고, static import
`import static com.prompthub.product.support.ProductContentFixtures.promptContent;`를 추가한다.

`ProductFamilyTest.java:88-92`:

```java
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
```

`ProductJpaRepositoryTest.java:61-65`:

```java
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
```

`ProductSellerServiceTest.java:256-260`:

```java
		Product product = Product.create(id, SELLER_ID, promptContent());
```

`ProductAdminServiceTest.java:146-150`:

```java
		Product product = Product.create(id, SELLER_ID, promptContent());
```

`ProductQueryServiceTest.java:231-235`:

```java
		Product product = Product.create(id, SELLER_ID, promptContent());
```

각 파일에서 교체 후 `ProductType`·`AmountType`·`List` import가 다른 곳에서 안 쓰이면 삭제한다
(checkstyle이 unused import를 실패 처리함 — 컴파일/checkstyle 결과로 확인).

- [ ] **Step 4: 전체 테스트 확인**

Run: `.\gradlew.bat test`
Expected: 전부 PASS (JPA 테스트가 DB를 요구하면 `.claude/rules/testing.md`의 환경변수 블록 설정 후 재실행)

- [ ] **Step 5: 커밋 (사용자 확인 후)**

```bash
git add product-service/src/test
git commit -m "test: 테스트를 ProductContent 픽스처 기반으로 전환 (#405)"
```

---

### Task 5: 구 14-파라미터 시그니처 제거 (contract)

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/domain/model/entity/Product.java`

**Interfaces:**
- Consumes: Task 3·4에서 모든 호출부가 새 오버로드로 이관 완료된 상태
- Produces: `Product`에는 `ProductContent` 기반 시그니처 3개만 남는다

- [ ] **Step 1: 잔여 호출부 없음 확인**

Run (product-service 디렉터리에서):

```bash
grep -rn "Product.create(" src | grep -v "ProductContent\|promptContent\|notionContent\|pptContent"
```

Expected: 구 시그니처(개별 파라미터 나열) 호출이 한 건도 없음 — 있으면 해당 파일을 먼저 Task 3/4 방식으로 이관

- [ ] **Step 2: 구 시그니처 3개 삭제**

Task 2에서 위임으로 바꿔 둔 14-파라미터 `create`/`update`/`nextVersion` 메서드 3개를 통째로 삭제한다. 삭제 후 `ProductType`·`AmountType`·`List` import가 엔티티 필드 선언(`@Enumerated` 필드, `List<String>` 필드)에서 여전히 쓰이므로 **유지**한다.

- [ ] **Step 3: 컴파일 + 전체 테스트**

Run: `.\gradlew.bat test`
Expected: 컴파일 성공, 전부 PASS

- [ ] **Step 4: 커밋 (사용자 확인 후)**

```bash
git add product-service/src/main/java/com/prompthub/product/domain/model/entity/Product.java
git commit -m "refactor: Product 구 14-파라미터 시그니처 제거 (#405)"
```

---

### Task 6: 최종 검증

**Files:** 없음 (검증만)

- [ ] **Step 1: 클린 빌드**

Run (product-service 디렉터리에서): `.\gradlew.bat clean build --no-daemon`
Expected: BUILD SUCCESSFUL — 컴파일 + checkstyle + 전체 테스트 통과.
DB가 필요하면 `.claude/rules/testing.md`의 환경변수 블록(`DB_HOST=localhost` 등)을 설정하고 재실행.

- [ ] **Step 2: 규칙 검증**

`verify-rules` skill 실행 — architecture(계층·의존 방향), product-api, testing, git-workflow 기준 위반 없는지 확인. 특히 `domain.model.vo`가 다른 계층에 의존하지 않는지(엔티티와 동일하게 `ProductException`만 사용) 확인.

- [ ] **Step 3: docs 동기화 확인**

`sync-product-docs` skill 실행 — API 계약·에러 코드·DDL 변경이 없는 순수 구조 리팩토링이므로 "변경 없음" 확인만 하고 종료.

- [ ] **Step 4: PR 준비 여부 사용자 확인**

PR 생성(`create-github-pr` skill)은 사용자 지시가 있을 때만 진행한다.
