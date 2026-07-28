# Product 버전 이력(row-chain) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **저장 위치 규칙(중요):** 이 문서는 `product-service/.claude/skills/save-plan-docs/SKILL.md`
> 규칙에 따라 `product-service/docs/plans/`에 저장된 일회성 계획 문서다. 구현이 모두 끝나고
> merge되면 **이 plan 파일은 삭제**한다(설계 문서 `2026-07-08-product-major-version-history-design.md`는
> 영구 보존, 삭제하지 않는다).

**Goal:** 상품이 `ON_SALE`이 된 이후의 모든 PATCH/MAJOR 수정을 in-place 덮어쓰기 대신
`parent_id`로 연결된 새 row(버전 이력)로 처리해, MAJOR 검수 중에도 기존 승인 콘텐츠가 유지되고
반려 시 자동 복원되도록 한다.

**Architecture:** `Product` 엔티티에 family(계보) 개념과 `nextVersion()`/`supersede()` 도메인
메서드를 추가하고, family 내 대표 row를 고르는 로직은 신규 `ProductFamily` 값 객체로 분리한다.
쓰기 경로(`ProductSellerService`, `ProductService`)는 이 도메인 메서드로 새 row 생성/전환을
수행하고, 읽기 경로(공개 조회 + 4종 내부 gRPC)는 `ProductRepository.findAllByFamilyRootIds`로
family를 조회해 대표 row를 resolve한다.

**Tech Stack:** Spring Boot, Spring Data JPA(Hibernate, `ddl-auto=create-only`, Flyway/Liquibase
없음), JUnit5 + AssertJ + Mockito(BDDMockito), gRPC(Java).

## Global Constraints

- 다른 서비스(order/user/settlement-service) 코드와 `.proto` 계약(필드 구조)은 변경하지 않는다
  — product-service 내부 gRPC 서버 구현(응답 값 계산 로직)만 바꾼다.
- 기존 API 응답 필드는 제거/이름변경하지 않는다. 새 필드는 항상 끝에 추가한다(하위 호환).
- 이 리포지토리는 `ddl-auto=create-only`이고 Flyway/Liquibase 마이그레이션 파일이 없다. 설계
  문서가 언급한 DB partial unique index(family당 `ON_SALE` 1개 보장)는 이번 구현 범위에서
  **추가하지 않는다** — 애플리케이션 레벨(트랜잭션 내에서 항상 supersede와 신규 row 생성을
  같이 커밋)로만 불변식을 보장하고, 이를 검증하는 서비스 테스트로 대체한다.
- `docs/erd/schema.md`의 `product_status_type` enum 목록에 `SUPERSEDED`를 추가하는 문서 동기화는
  `sync-product-docs` skill로 마지막에 처리한다(Task 15).
- 체크스타일: wildcard import 금지, unused import 금지, 빈 catch block 금지
  (`style/checkstyle/prompthub-checkstyle-rules.xml`).
- 커밋은 각 Task 단위로 나눠서 한다.

---

### Task 1: `ProductStatus`에 `SUPERSEDED` 추가

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/domain/model/enums/ProductStatus.java`

**Interfaces:**
- Produces: `ProductStatus.SUPERSEDED` — 이후 모든 Task에서 사용.

- [ ] **Step 1: enum 값 추가**

```java
package com.prompthub.product.domain.model.enums;

public enum ProductStatus {
	DRAFT,
	PENDING_REVIEW,
	ON_SALE,
	REJECTED,
	STOPPED,
	SUPERSEDED
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd product-service && .\gradlew.bat compileJava --no-daemon` (Windows) 또는
`./gradlew compileJava --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/domain/model/enums/ProductStatus.java
git commit -m "feat: ProductStatus에 SUPERSEDED 상태 추가"
```

---

### Task 2: `Product` 도메인에 family/버전 메서드 추가

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/domain/model/entity/Product.java`
- Test: `product-service/src/test/java/com/prompthub/product/domain/model/entity/ProductTest.java`

**Interfaces:**
- Produces:
  - `UUID Product.familyRootId()`
  - `boolean Product.isFamilyRoot()`
  - `Product Product.nextVersion(boolean isMajor, ProductType productType, String name, String description, String model, AmountType amountType, int amount, String thumbnailUrl, List<String> imageUrls, String content, List<String> tags, String changeReason)`
    — **반드시 family의 현재 `ON_SALE` row 위에서 호출**한다(`this`가 base 버전).
  - `void Product.supersede()` — precondition: `status == ON_SALE`.
  - `void Product.restoreFromSuperseded()` — precondition: `status == SUPERSEDED`.

- [ ] **Step 1: 실패하는 테스트 작성**

`ProductTest.java`에 아래 테스트를 추가한다(기존 테스트는 그대로 둔다).

```java
	@Test
	void familyRootId_returnsSelfId_whenRoot() {
		Product product = Product.create(
			UUID.randomUUID(), UUID.randomUUID(), ProductType.PROMPT,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", List.of()
		);

		assertThat(product.familyRootId()).isEqualTo(product.getId());
		assertThat(product.isFamilyRoot()).isTrue();
	}

	@Test
	void nextVersion_major_createsPendingReviewChildLinkedToFamilyRoot() {
		Product onSale = Product.create(
			UUID.randomUUID(), UUID.randomUUID(), ProductType.PROMPT,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", List.of()
		);
		ReflectionTestUtils.setField(onSale, "status", ProductStatus.ON_SALE);
		ReflectionTestUtils.setField(onSale, "majorVersion", (short) 2);
		ReflectionTestUtils.setField(onSale, "patchVersion", (short) 3);

		Product next = onSale.nextVersion(
			true, ProductType.NOTION, "새 제목", "새 설명", "model2", AmountType.PAID, 2000,
			null, List.of(), "content2", List.of(), "메이저 변경"
		);

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
		Product onSale = Product.create(
			UUID.randomUUID(), UUID.randomUUID(), ProductType.PROMPT,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", List.of()
		);
		ReflectionTestUtils.setField(onSale, "status", ProductStatus.ON_SALE);
		ReflectionTestUtils.setField(onSale, "majorVersion", (short) 2);
		ReflectionTestUtils.setField(onSale, "patchVersion", (short) 3);

		Product next = onSale.nextVersion(
			false, ProductType.PROMPT, "제목", "설명", "model", AmountType.PAID, 1500,
			null, List.of(), "content", List.of(), null
		);

		assertThat(next.getMajorVersion()).isEqualTo((short) 2);
		assertThat(next.getPatchVersion()).isEqualTo((short) 4);
		assertThat(next.getStatus()).isEqualTo(ProductStatus.ON_SALE);
		assertThat(next.getAmount()).isEqualTo(1500);
	}

	@Test
	void supersede_onSaleRow_transitionsToSuperseded() {
		Product product = Product.create(
			UUID.randomUUID(), UUID.randomUUID(), ProductType.PROMPT,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", List.of()
		);
		ReflectionTestUtils.setField(product, "status", ProductStatus.ON_SALE);

		product.supersede();

		assertThat(product.getStatus()).isEqualTo(ProductStatus.SUPERSEDED);
	}

	@Test
	void supersede_nonOnSaleRow_throws() {
		Product product = Product.create(
			UUID.randomUUID(), UUID.randomUUID(), ProductType.PROMPT,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", List.of()
		);

		assertThatThrownBy(product::supersede).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void restoreFromSuperseded_supersededRow_transitionsToOnSale() {
		Product product = Product.create(
			UUID.randomUUID(), UUID.randomUUID(), ProductType.PROMPT,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", List.of()
		);
		ReflectionTestUtils.setField(product, "status", ProductStatus.SUPERSEDED);

		product.restoreFromSuperseded();

		assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
	}
```

파일 상단 import에 아래를 추가한다(없는 것만):

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import org.springframework.test.util.ReflectionTestUtils;
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.domain.model.entity.ProductTest" --no-daemon`
Expected: FAIL — `familyRootId`, `nextVersion`, `supersede`, `restoreFromSuperseded` 메서드가
없어 컴파일 에러.

- [ ] **Step 3: `Product`에 메서드 구현**

`Product.java`의 `submitForReview()` 메서드 앞에 아래 메서드들을 추가한다.

```java
	public UUID familyRootId() {
		return this.parentId != null ? this.parentId : this.id;
	}

	public boolean isFamilyRoot() {
		return this.parentId == null;
	}

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
		List<String> tags,
		String changeReason
	) {
		Product next = new Product();
		next.id = UUID.randomUUID();
		next.parentId = this.familyRootId();
		next.sellerId = this.sellerId;
		next.productType = productType;
		next.name = name;
		next.description = description;
		next.model = model;
		next.amountType = amountType;
		next.amount = amount;
		next.thumbnailUrl = thumbnailUrl;
		next.imageUrls = imageUrls != null ? imageUrls : new ArrayList<>();
		next.content = content;
		next.tags = tags != null ? tags : new ArrayList<>();
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

	public void supersede() {
		if (this.status != ProductStatus.ON_SALE) {
			throw new IllegalStateException("ON_SALE 상태의 상품만 SUPERSEDED로 전환할 수 있습니다. current=" + this.status);
		}
		this.status = ProductStatus.SUPERSEDED;
		this.updatedAt = LocalDateTime.now();
	}

	public void restoreFromSuperseded() {
		if (this.status != ProductStatus.SUPERSEDED) {
			throw new IllegalStateException("SUPERSEDED 상태의 상품만 ON_SALE로 복원할 수 있습니다. current=" + this.status);
		}
		this.status = ProductStatus.ON_SALE;
		this.updatedAt = LocalDateTime.now();
	}
```

- [ ] **Step 4: 테스트 재실행 → 통과 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.domain.model.entity.ProductTest" --no-daemon`
Expected: PASS (전체 8개 테스트: 기존 2개 + 신규 6개)

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/domain/model/entity/Product.java product-service/src/test/java/com/prompthub/product/domain/model/entity/ProductTest.java
git commit -m "feat: Product에 family/버전 이력 도메인 메서드 추가"
```

---

### Task 3: `ProductFamily` 값 객체 추가 — family 내 대표 row 선택 로직

**Files:**
- Create: `product-service/src/main/java/com/prompthub/product/domain/model/entity/ProductFamily.java`
- Test: `product-service/src/test/java/com/prompthub/product/domain/model/entity/ProductFamilyTest.java`

**Interfaces:**
- Consumes: `Product.familyRootId()`, `Product.getStatus()`, `Product.getMajorVersion()`,
  `Product.getPatchVersion()` (Task 2).
- Produces:
  - `ProductFamily.of(UUID familyRootId, List<Product> members)`
  - `Optional<Product> currentOnSale()`
  - `Optional<Product> currentForWishlist()` — 우선순위 `ON_SALE > STOPPED > REJECTED > PENDING_REVIEW > DRAFT`
  - `Optional<Product> currentForSeller()` — 우선순위 `PENDING_REVIEW > ON_SALE > REJECTED > DRAFT`
  - `Optional<Product> pendingReview()`
  - `Optional<Product> mostRecentSuperseded()`
  - `boolean hasEverBeenOnSale()`
  - `List<Product> publicHistory()` — `ON_SALE`+`SUPERSEDED`, 버전 내림차순
  - `List<Product> sellerHistory()` — 전체, 버전 내림차순

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.prompthub.product.domain.model.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ProductFamilyTest {

	@Test
	void currentOnSale_returnsTheOnlyOnSaleRow() {
		Product root = product(ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		Product child = product(ProductStatus.ON_SALE, (short) 2, (short) 0);
		ProductFamily family = ProductFamily.of(root.getId(), List.of(root, child));

		assertThat(family.currentOnSale()).contains(child);
	}

	@Test
	void currentForWishlist_prefersOnSaleOverStopped() {
		Product stopped = product(ProductStatus.STOPPED, (short) 1, (short) 0);
		Product onSale = product(ProductStatus.ON_SALE, (short) 2, (short) 0);
		ProductFamily family = ProductFamily.of(stopped.getId(), List.of(stopped, onSale));

		assertThat(family.currentForWishlist()).contains(onSale);
	}

	@Test
	void currentForWishlist_neverReturnsSuperseded() {
		Product superseded = product(ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		ProductFamily family = ProductFamily.of(superseded.getId(), List.of(superseded));

		assertThat(family.currentForWishlist()).isEmpty();
	}

	@Test
	void currentForSeller_prefersPendingReviewOverOnSale() {
		Product onSale = product(ProductStatus.ON_SALE, (short) 2, (short) 0);
		Product pending = product(ProductStatus.PENDING_REVIEW, (short) 3, (short) 0);
		ProductFamily family = ProductFamily.of(onSale.getId(), List.of(onSale, pending));

		assertThat(family.currentForSeller()).contains(pending);
	}

	@Test
	void hasEverBeenOnSale_falseWhenOnlyDraftOrPendingOrRejected() {
		Product draft = product(ProductStatus.DRAFT, (short) 1, (short) 0);
		ProductFamily family = ProductFamily.of(draft.getId(), List.of(draft));

		assertThat(family.hasEverBeenOnSale()).isFalse();
	}

	@Test
	void hasEverBeenOnSale_trueWhenSupersededExists() {
		Product superseded = product(ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		Product onSale = product(ProductStatus.ON_SALE, (short) 2, (short) 0);
		ProductFamily family = ProductFamily.of(superseded.getId(), List.of(superseded, onSale));

		assertThat(family.hasEverBeenOnSale()).isTrue();
	}

	@Test
	void publicHistory_excludesRejectedAndPendingReview_sortedDescending() {
		Product superseded = product(ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		Product rejected = product(ProductStatus.REJECTED, (short) 2, (short) 0);
		Product onSale = product(ProductStatus.ON_SALE, (short) 3, (short) 0);
		ProductFamily family = ProductFamily.of(superseded.getId(), List.of(superseded, rejected, onSale));

		assertThat(family.publicHistory()).containsExactly(onSale, superseded);
	}

	@Test
	void mostRecentSuperseded_returnsHighestVersionSuperseded() {
		Product old = product(ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		Product recent = product(ProductStatus.SUPERSEDED, (short) 2, (short) 0);
		Product onSale = product(ProductStatus.ON_SALE, (short) 3, (short) 0);
		ProductFamily family = ProductFamily.of(old.getId(), List.of(old, recent, onSale));

		assertThat(family.mostRecentSuperseded()).contains(recent);
	}

	private Product product(ProductStatus status, short majorVersion, short patchVersion) {
		Product product = Product.create(
			UUID.randomUUID(), UUID.randomUUID(), ProductType.PROMPT,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", List.of()
		);
		ReflectionTestUtils.setField(product, "status", status);
		ReflectionTestUtils.setField(product, "majorVersion", majorVersion);
		ReflectionTestUtils.setField(product, "patchVersion", patchVersion);
		return product;
	}
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.domain.model.entity.ProductFamilyTest" --no-daemon`
Expected: FAIL — `ProductFamily` 클래스가 없어 컴파일 에러.

- [ ] **Step 3: `ProductFamily` 구현**

```java
package com.prompthub.product.domain.model.entity;

import com.prompthub.product.domain.model.enums.ProductStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ProductFamily {

	private static final List<ProductStatus> WISHLIST_PRIORITY = List.of(
		ProductStatus.ON_SALE,
		ProductStatus.STOPPED,
		ProductStatus.REJECTED,
		ProductStatus.PENDING_REVIEW,
		ProductStatus.DRAFT
	);

	private static final List<ProductStatus> SELLER_PRIORITY = List.of(
		ProductStatus.PENDING_REVIEW,
		ProductStatus.ON_SALE,
		ProductStatus.REJECTED,
		ProductStatus.DRAFT
	);

	private final UUID familyRootId;
	private final List<Product> members;

	private ProductFamily(UUID familyRootId, List<Product> members) {
		this.familyRootId = familyRootId;
		this.members = members;
	}

	public static ProductFamily of(UUID familyRootId, List<Product> members) {
		return new ProductFamily(familyRootId, members);
	}

	public UUID familyRootId() {
		return familyRootId;
	}

	public List<Product> members() {
		return members;
	}

	public Optional<Product> currentOnSale() {
		return latestByStatus(ProductStatus.ON_SALE);
	}

	public Optional<Product> currentForWishlist() {
		return firstMatchByPriority(WISHLIST_PRIORITY);
	}

	public Optional<Product> currentForSeller() {
		return firstMatchByPriority(SELLER_PRIORITY);
	}

	public Optional<Product> pendingReview() {
		return latestByStatus(ProductStatus.PENDING_REVIEW);
	}

	public Optional<Product> mostRecentSuperseded() {
		return latestByStatus(ProductStatus.SUPERSEDED);
	}

	public boolean hasEverBeenOnSale() {
		return members.stream().anyMatch(p ->
			p.getStatus() == ProductStatus.ON_SALE || p.getStatus() == ProductStatus.SUPERSEDED);
	}

	public List<Product> publicHistory() {
		return members.stream()
			.filter(p -> p.getStatus() == ProductStatus.ON_SALE || p.getStatus() == ProductStatus.SUPERSEDED)
			.sorted(versionDescending())
			.toList();
	}

	public List<Product> sellerHistory() {
		return members.stream().sorted(versionDescending()).toList();
	}

	private Optional<Product> firstMatchByPriority(List<ProductStatus> priority) {
		for (ProductStatus status : priority) {
			Optional<Product> match = latestByStatus(status);
			if (match.isPresent()) {
				return match;
			}
		}
		return Optional.empty();
	}

	private Optional<Product> latestByStatus(ProductStatus status) {
		return members.stream()
			.filter(p -> p.getStatus() == status)
			.max(versionAscending());
	}

	private Comparator<Product> versionAscending() {
		return Comparator
			.comparingInt((Product p) -> (int) p.getMajorVersion())
			.thenComparingInt(p -> (int) p.getPatchVersion());
	}

	private Comparator<Product> versionDescending() {
		return versionAscending().reversed();
	}
}
```

- [ ] **Step 4: 테스트 재실행 → 통과 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.domain.model.entity.ProductFamilyTest" --no-daemon`
Expected: PASS (8개 테스트)

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/domain/model/entity/ProductFamily.java product-service/src/test/java/com/prompthub/product/domain/model/entity/ProductFamilyTest.java
git commit -m "feat: family 대표 row 선택 로직을 담은 ProductFamily 값 객체 추가"
```

---

### Task 4: `ProductRepository`에 `findAllByFamilyRootIds` 추가

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/domain/repository/ProductRepository.java`
- Modify: `product-service/src/main/java/com/prompthub/product/infra/persistence/ProductJpaRepository.java`
- Modify: `product-service/src/main/java/com/prompthub/product/infra/persistence/ProductRepositoryAdapter.java`
- Test: `product-service/src/test/java/com/prompthub/product/infra/persistence/ProductJpaRepositoryTest.java` (신규)

**Interfaces:**
- Consumes: `Product` entity, `EntityManager`/`@DataJpaTest`.
- Produces: `List<Product> ProductRepository.findAllByFamilyRootIds(List<UUID> familyRootIds)`
  — 주어진 각 id에 대해 `id = :id` 이거나 `parent_id = :id`인 모든 row를 반환(여러 family를
  한 번에 조회 가능). 이후 모든 서비스 계층 Task가 이 메서드를 사용한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`product-service/src/test/resources/application-test.yml` 기준 `@DataJpaTest`를 사용한다(기존
`ProductJpaRepositoryTest`가 없으므로 신규 생성).

```java
package com.prompthub.product.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@ActiveProfiles("test")
class ProductJpaRepositoryTest {

	@Autowired
	private ProductJpaRepository productJpaRepository;

	@Test
	void findAllByFamilyRootIds_returnsRootAndChildren() {
		Product root = product(null, ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		Product child = product(root.getId(), ProductStatus.ON_SALE, (short) 2, (short) 0);
		Product unrelated = product(null, ProductStatus.ON_SALE, (short) 1, (short) 0);
		productJpaRepository.saveAll(List.of(root, child, unrelated));

		List<Product> result = productJpaRepository.findAllByFamilyRootIds(List.of(root.getId()));

		assertThat(result).extracting(Product::getId).containsExactlyInAnyOrder(root.getId(), child.getId());
	}

	private Product product(UUID parentId, ProductStatus status, short majorVersion, short patchVersion) {
		Product product = Product.create(
			UUID.randomUUID(), UUID.randomUUID(), ProductType.PROMPT,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", List.of()
		);
		ReflectionTestUtils.setField(product, "parentId", parentId);
		ReflectionTestUtils.setField(product, "status", status);
		ReflectionTestUtils.setField(product, "majorVersion", majorVersion);
		ReflectionTestUtils.setField(product, "patchVersion", patchVersion);
		return product;
	}
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.infra.persistence.ProductJpaRepositoryTest" --no-daemon`
Expected: FAIL — `findAllByFamilyRootIds` 메서드가 없어 컴파일 에러.

- [ ] **Step 3: 리포지토리 3계층에 메서드 추가**

`ProductRepository.java`에 추가:

```java
	List<Product> findAllByFamilyRootIds(List<UUID> familyRootIds);
```

`ProductJpaRepository.java`에 추가(파일 마지막 `findAllAdminProducts()` 아래):

```java
	@Query("""
		select p
		from Product p
		where p.id in :familyRootIds
			or p.parentId in :familyRootIds
		""")
	List<Product> findAllByFamilyRootIds(@Param("familyRootIds") List<UUID> familyRootIds);
```

`ProductRepositoryAdapter.java`에 추가:

```java
	@Override
	public List<Product> findAllByFamilyRootIds(List<UUID> familyRootIds) {
		return productJpaRepository.findAllByFamilyRootIds(familyRootIds);
	}
```

- [ ] **Step 4: 테스트 재실행 → 통과 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.infra.persistence.ProductJpaRepositoryTest" --no-daemon`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/domain/repository/ProductRepository.java product-service/src/main/java/com/prompthub/product/infra/persistence/ProductJpaRepository.java product-service/src/main/java/com/prompthub/product/infra/persistence/ProductRepositoryAdapter.java product-service/src/test/java/com/prompthub/product/infra/persistence/ProductJpaRepositoryTest.java
git commit -m "feat: family 전체 row 조회용 findAllByFamilyRootIds 추가"
```

---

### Task 5: 리뷰/평점을 family root 기준으로 집계하도록 JPQL 수정

리뷰가 특정 버전 row에 묶여 있으면, 버전업(새 row 생성)마다 그 row에는 리뷰가 하나도 없어
평점이 0으로 보이는 회귀가 생긴다. `findPublicProducts`/`findRelatedProducts`의 리뷰 join
조건을 "그 row 자신"이 아니라 "그 row가 속한 family root"로 바꿔, family 어디에 리뷰가
달려 있든(Task 6에서 리뷰는 항상 root에 붙이도록 만든다) 항상 함께 집계되게 한다.

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/infra/persistence/ProductJpaRepository.java`
- Modify: `product-service/src/test/java/com/prompthub/product/application/service/ProductQueryServiceTest.java` (해당 없음 — 이 Task는 순수 쿼리 변경이므로 Task 4와 동일한 `ProductJpaRepositoryTest`에 테스트 추가)

**Interfaces:**
- Consumes: `Review.product`(FK), `Product.parentId`.
- Produces: `findPublicProducts`/`findRelatedProducts`의 평점이 family 전체 리뷰를 반영.

- [ ] **Step 1: 실패하는 테스트 추가**

`ProductJpaRepositoryTest.java`에 아래 테스트를 추가한다(리뷰 저장을 위해
`ReviewJpaRepository`도 주입받는다).

```java
	@Autowired
	private ReviewJpaRepository reviewJpaRepository;

	@Test
	void findPublicProducts_aggregatesRatingAcrossFamilyRoot() {
		Product root = product(null, ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		Product current = product(root.getId(), ProductStatus.ON_SALE, (short) 2, (short) 0);
		productJpaRepository.saveAll(List.of(root, current));

		Review review = Review.create(UUID.randomUUID(), root, (short) 5);
		reviewJpaRepository.save(review);

		List<ProductListProjection> result = productJpaRepository.findPublicProducts(
			"", "all", "popular", ProductStatus.ON_SALE, ReviewStatus.ACTIVE,
			org.springframework.data.domain.PageRequest.of(0, 20)
		);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).rating()).isEqualTo(5.0);
	}
```

파일 상단에 필요한 import를 추가한다:
`com.prompthub.product.domain.model.entity.Review`,
`com.prompthub.product.domain.model.enums.ReviewStatus`,
`com.prompthub.product.domain.model.projection.ProductListProjection`.

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.infra.persistence.ProductJpaRepositoryTest" --no-daemon`
Expected: FAIL — `rating`이 `0.0`으로 나옴(현재는 `r.product = p`로 join하는데 리뷰는 `root`,
목록에 뜨는 row는 `current`라서 매칭되지 않음).

- [ ] **Step 3: join 조건 수정**

`ProductJpaRepository.java`의 `findPublicProducts`와 `findRelatedProducts` 쿼리에서
`left join Review r on r.product = p`를 아래로 교체한다(두 군데 모두).

```java
		left join Review r on r.product.id = coalesce(p.parentId, p.id) and r.status = :activeReviewStatus and r.deletedAt is null
```

- [ ] **Step 4: 테스트 재실행 → 통과 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.infra.persistence.ProductJpaRepositoryTest" --no-daemon`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/infra/persistence/ProductJpaRepository.java product-service/src/test/java/com/prompthub/product/infra/persistence/ProductJpaRepositoryTest.java
git commit -m "fix: 상품 목록/관련상품 평점을 family root 기준으로 집계"
```

---

### Task 6: `upsertReview`가 항상 family root에 리뷰를 귀속시키도록 변경

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/application/service/ProductInternalService.java`
- Test: `product-service/src/test/java/com/prompthub/product/application/service/ProductInternalServiceTest.java`

**Interfaces:**
- Consumes: `Product.familyRootId()`(Task 2), `ProductRepository.findById`.
- Produces: `upsertReview`가 리뷰를 항상 root `Product`에 연결.

- [ ] **Step 1: 실패하는 테스트 작성**

`ProductInternalServiceTest.java`에 새 `@Nested` 클래스를 추가한다.

```java
	@Nested
	@DisplayName("리뷰 등록/수정")
	class UpsertReview {

		@Test
		@DisplayName("자식 row의 id로 요청해도 family root에 리뷰가 귀속된다")
		void upsertReview_attachesReviewToFamilyRoot() {
			UUID rootId = UUID.fromString("44444444-4444-4444-4444-444444444444");
			Product root = product(rootId, SELLER_ID, ProductStatus.SUPERSEDED);
			Product child = product(PRODUCT_ID, SELLER_ID, ProductStatus.ON_SALE);
			ReflectionTestUtils.setField(child, "parentId", rootId);

			given(productRepository.findById(PRODUCT_ID)).willReturn(java.util.Optional.of(child));
			given(productRepository.findById(rootId)).willReturn(java.util.Optional.of(root));
			given(reviewRepository.findByUserIdAndProductId(SELLER_ID, rootId)).willReturn(java.util.Optional.empty());

			productInternalService.upsertReview(SELLER_ID, PRODUCT_ID, 5);

			org.mockito.ArgumentCaptor<com.prompthub.product.domain.model.entity.Review> captor =
				org.mockito.ArgumentCaptor.forClass(com.prompthub.product.domain.model.entity.Review.class);
			then(reviewRepository).should().save(captor.capture());
			assertThat(captor.getValue().getProduct()).isEqualTo(root);
		}
	}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.application.service.ProductInternalServiceTest" --no-daemon`
Expected: FAIL — 현재 구현은 `child`(PRODUCT_ID)에 바로 리뷰를 붙여 `captor.getValue().getProduct()`가
`root`가 아닌 `child`가 됨.

- [ ] **Step 3: `upsertReview` 수정**

`ProductInternalService.java`의 `upsertReview`를 아래로 교체한다.

```java
	@Override
	@Transactional
	public void upsertReview(UUID buyerId, UUID productId, Integer rating) {
		Product anchor = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
		Product root = productRepository.findById(anchor.familyRootId())
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
		reviewRepository.findByUserIdAndProductId(buyerId, root.getId())
			.ifPresentOrElse(
				review -> review.updateRating((short) rating.intValue()),
				() -> reviewRepository.save(Review.create(buyerId, root, (short) rating.intValue()))
			);
	}
```

- [ ] **Step 4: 테스트 재실행 → 통과 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.application.service.ProductInternalServiceTest" --no-daemon`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/application/service/ProductInternalService.java product-service/src/test/java/com/prompthub/product/application/service/ProductInternalServiceTest.java
git commit -m "fix: 리뷰를 family root row에 귀속시켜 버전업에도 유지되게 함"
```

---

### Task 7: `ProductSellerService.updateProduct` — PATCH/MAJOR 분기 재작성

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/application/service/ProductSellerService.java`
- Create: `product-service/src/test/java/com/prompthub/product/application/service/ProductSellerServiceTest.java`

**Interfaces:**
- Consumes: `Product.familyRootId()`, `Product.nextVersion(...)`, `Product.supersede()`(Task 2),
  `ProductFamily`(Task 3), `ProductRepository.findAllByFamilyRootIds`(Task 4).
- Produces: `updateProduct`가 family 상태에 따라 in-place 수정 또는 새 row 생성으로 분기.

- [ ] **Step 1: 실패하는 테스트 작성**

새 파일 `ProductSellerServiceTest.java`를 만든다.

```java
package com.prompthub.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.product.application.client.SellerClient;
import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.infra.messaging.producer.ProductEventProducer;
import com.prompthub.product.presentation.dto.request.ProductUpdateRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductSellerServiceTest {

	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Mock
	private ProductRepository productRepository;

	@Mock
	private SellerClient sellerClient;

	@Mock
	private ProductEventProducer productEventProducer;

	@Mock
	private StorageClient storageClient;

	@InjectMocks
	private ProductSellerService productSellerService;

	@Nested
	@DisplayName("상품 수정")
	class UpdateProduct {

		@Test
		@DisplayName("한 번도 ON_SALE된 적 없으면 in-place로 수정한다")
		void updateProduct_neverOnSale_updatesInPlace() {
			Product draft = product(PRODUCT_ID, null, ProductStatus.DRAFT, (short) 1, (short) 0);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(draft));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(draft));

			productSellerService.updateProduct(SELLER_ID, PRODUCT_ID, request("MINOR"));

			ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
			then(productRepository).should().save(captor.capture());
			assertThat(captor.getValue()).isSameAs(draft);
			assertThat(draft.getName()).isEqualTo("새 제목");
		}

		@Test
		@DisplayName("ON_SALE 이후 MAJOR 수정은 새 PENDING_REVIEW row를 만들고 기존 ON_SALE은 그대로 둔다")
		void updateProduct_majorAfterOnSale_createsPendingReviewChild_keepsOnSaleUntouched() {
			UUID familyRootId = PRODUCT_ID;
			Product onSale = product(PRODUCT_ID, null, ProductStatus.ON_SALE, (short) 2, (short) 0);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(familyRootId))).willReturn(List.of(onSale));

			productSellerService.updateProduct(SELLER_ID, PRODUCT_ID, request("MAJOR"));

			ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
			then(productRepository).should().save(captor.capture());
			Product saved = captor.getValue();
			assertThat(saved).isNotSameAs(onSale);
			assertThat(saved.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
			assertThat(saved.getMajorVersion()).isEqualTo((short) 3);
			assertThat(saved.getParentId()).isEqualTo(familyRootId);
			assertThat(onSale.getStatus()).isEqualTo(ProductStatus.ON_SALE);
			assertThat(onSale.getName()).isEqualTo("제목");
		}

		@Test
		@DisplayName("ON_SALE 이후 PATCH 수정은 새 ON_SALE row를 만들고 기존 row는 SUPERSEDED로 전환한다")
		void updateProduct_patchAfterOnSale_createsOnSaleChild_supersedesPrevious() {
			Product onSale = product(PRODUCT_ID, null, ProductStatus.ON_SALE, (short) 2, (short) 0);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(onSale));

			productSellerService.updateProduct(SELLER_ID, PRODUCT_ID, request("MINOR"));

			ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
			then(productRepository).should(org.mockito.Mockito.times(2)).save(captor.capture());
			List<Product> saved = captor.getAllValues();
			assertThat(onSale.getStatus()).isEqualTo(ProductStatus.SUPERSEDED);
			assertThat(saved).anySatisfy(p -> {
				assertThat(p.getStatus()).isEqualTo(ProductStatus.ON_SALE);
				assertThat(p.getPatchVersion()).isEqualTo((short) 1);
			});
		}

		@Test
		@DisplayName("이미 PENDING_REVIEW인 MAJOR 변경이 있으면 재제출을 거부한다")
		void updateProduct_majorWhilePendingReviewExists_throws() {
			Product onSale = product(PRODUCT_ID, null, ProductStatus.ON_SALE, (short) 2, (short) 0);
			Product pending = product(UUID.randomUUID(), PRODUCT_ID, ProductStatus.PENDING_REVIEW, (short) 3, (short) 0);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(onSale, pending));

			assertThatThrownBy(() -> productSellerService.updateProduct(SELLER_ID, PRODUCT_ID, request("MAJOR")))
				.isInstanceOf(ProductException.class);
		}
	}

	private ProductUpdateRequest request(String versionType) {
		return new ProductUpdateRequest(
			"새 제목", "PROMPT", "model2", "새 설명", 2000, "content2",
			null, List.of(), List.of(), "변경 사유", versionType
		);
	}

	private Product product(UUID id, UUID parentId, ProductStatus status, short majorVersion, short patchVersion) {
		Product product = Product.create(
			id, SELLER_ID, ProductType.PROMPT,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", List.of()
		);
		ReflectionTestUtils.setField(product, "parentId", parentId);
		ReflectionTestUtils.setField(product, "status", status);
		ReflectionTestUtils.setField(product, "majorVersion", majorVersion);
		ReflectionTestUtils.setField(product, "patchVersion", patchVersion);
		return product;
	}
}
```

주의: `Product.create`는 `id`를 파라미터로 받지 않는다(내부에서 `UUID.randomUUID()`를
쓰지 않고 외부에서 넘겨준 `id`를 그대로 쓰는지 `Product.java` Step 3 코드를 확인 —
`Product.create(id, sellerId, productType, ...)`가 첫 인자로 `id`를 받으므로 위 fixture는
정확하다.

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.application.service.ProductSellerServiceTest" --no-daemon`
Expected: FAIL — 현재 `updateProduct`는 항상 in-place `product.update(...)`만 호출하므로
MAJOR/PATCH 분기 테스트가 실패.

- [ ] **Step 3: `updateProduct` 재작성**

`ProductSellerService.java`의 `updateProduct` 메서드를 아래로 교체한다.

```java
	@Override
	public void updateProduct(UUID sellerId, UUID productId, ProductUpdateRequest request) {
		Product anchor = getProductForSeller(sellerId, productId);

		ProductType productType = parseProductType(request.productType());
		AmountType amountType = request.amount() == 0 ? AmountType.FREE : AmountType.PAID;
		boolean isMajor = "MAJOR".equalsIgnoreCase(request.versionType());
		String newThumbnailKey = moveToProductPath(extractKey(request.thumbnailUrl()), productId);
		List<String> newImageKeys = moveToProductPaths(extractKeys(request.imageUrls()), productId);

		UUID familyRootId = anchor.familyRootId();
		ProductFamily family = ProductFamily.of(familyRootId, productRepository.findAllByFamilyRootIds(List.of(familyRootId)));

		int previousPrice;
		if (!family.hasEverBeenOnSale()) {
			previousPrice = anchor.getAmount();
			anchor.update(
				productType, request.title(), request.desc(), request.model(), amountType, request.amount(),
				newThumbnailKey, newImageKeys, request.content(), request.tags(), request.changeReason(), isMajor
			);
			productRepository.save(anchor);
		} else {
			Product onSale = family.currentOnSale()
				.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_INVALID_STATUS));
			previousPrice = onSale.getAmount();

			if (isMajor) {
				if (family.pendingReview().isPresent()) {
					throw new ProductException(ProductErrorCode.PRODUCT_INVALID_STATUS);
				}
				Product next = onSale.nextVersion(
					true, productType, request.title(), request.desc(), request.model(), amountType, request.amount(),
					newThumbnailKey, newImageKeys, request.content(), request.tags(), request.changeReason()
				);
				productRepository.save(next);
			} else {
				Product next = onSale.nextVersion(
					false, productType, request.title(), request.desc(), request.model(), amountType, request.amount(),
					newThumbnailKey, newImageKeys, request.content(), request.tags(), request.changeReason()
				);
				onSale.supersede();
				productRepository.save(onSale);
				productRepository.save(next);
			}
		}

		if (previousPrice != request.amount()) {
			productEventProducer.publishPriceChanged(productId, previousPrice, request.amount());
		}
	}
```

`ProductSellerService.java` 상단 import에 아래를 추가한다:

```java
import com.prompthub.product.domain.model.entity.ProductFamily;
```

- [ ] **Step 4: 테스트 재실행 → 통과 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.application.service.ProductSellerServiceTest" --no-daemon`
Expected: PASS (5개 테스트)

- [ ] **Step 5: 기존 `ProductControllerTest`/관련 컨트롤러 테스트가 깨지지 않는지 전체 확인**

Run: `cd product-service && .\gradlew.bat test --no-daemon`
Expected: 전체 PASS (Task 8~14 완료 전까지는 seller/admin 쪽 다른 메서드가 아직 이전
동작이므로, 이 시점에는 seller update 관련 기존 테스트가 없었다는 점을 감안하고 실패가
있다면 원인이 이번 변경과 무관한지 확인한다).

- [ ] **Step 6: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/application/service/ProductSellerService.java product-service/src/test/java/com/prompthub/product/application/service/ProductSellerServiceTest.java
git commit -m "feat: 판매자 상품 수정이 ON_SALE 이후엔 새 버전 row를 생성하도록 변경"
```

---

### Task 8: `getMyProducts` — family당 대표 row 1개만 노출

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/application/service/ProductSellerService.java`
- Modify: `product-service/src/test/java/com/prompthub/product/application/service/ProductSellerServiceTest.java`

**Interfaces:**
- Consumes: `ProductFamily.currentForSeller()`(Task 3), `ProductRepository.findBySellerId`(기존).

- [ ] **Step 1: 실패하는 테스트 작성**

`ProductSellerServiceTest.java`에 새 `@Nested` 클래스를 추가한다.

```java
	@Nested
	@DisplayName("내 상품 목록 조회")
	class GetMyProducts {

		@Test
		@DisplayName("같은 family의 여러 row 중 대표 row 1개만 반환한다")
		void getMyProducts_returnsOneRepresentativeRowPerFamily() {
			Product superseded = product(UUID.randomUUID(), null, ProductStatus.SUPERSEDED, (short) 1, (short) 0);
			UUID familyRootId = superseded.getId();
			Product onSale = product(UUID.randomUUID(), familyRootId, ProductStatus.ON_SALE, (short) 2, (short) 0);
			Product pending = product(UUID.randomUUID(), familyRootId, ProductStatus.PENDING_REVIEW, (short) 3, (short) 0);
			given(productRepository.findBySellerId(SELLER_ID)).willReturn(List.of(superseded, onSale, pending));

			List<com.prompthub.product.presentation.dto.response.SellerProductListItemResponse> result =
				productSellerService.getMyProducts(SELLER_ID);

			assertThat(result).hasSize(1);
			assertThat(result.get(0).productId()).isEqualTo(pending.getId());
		}
	}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.application.service.ProductSellerServiceTest" --no-daemon`
Expected: FAIL — 현재 `getMyProducts`는 3개 row를 그대로 반환.

- [ ] **Step 3: `getMyProducts` 수정**

```java
	@Override
	@Transactional(readOnly = true)
	public List<SellerProductListItemResponse> getMyProducts(UUID sellerId) {
		List<Product> all = productRepository.findBySellerId(sellerId);
		Map<UUID, List<Product>> byFamily = all.stream()
			.collect(Collectors.groupingBy(Product::familyRootId));
		return byFamily.entrySet().stream()
			.map(entry -> ProductFamily.of(entry.getKey(), entry.getValue()))
			.map(family -> family.currentForSeller()
				.orElseThrow(() -> new IllegalStateException("family에 대표 row가 없습니다. familyRootId=" + family.familyRootId())))
			.sorted(Comparator.comparing(Product::getUpdatedAt).reversed())
			.map(SellerProductListItemResponse::from)
			.toList();
	}
```

`ProductSellerService.java` 상단 import에 `java.util.Comparator`, `java.util.Map`,
`java.util.stream.Collectors`를 추가한다.

- [ ] **Step 4: 테스트 재실행 → 통과 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.application.service.ProductSellerServiceTest" --no-daemon`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/application/service/ProductSellerService.java product-service/src/test/java/com/prompthub/product/application/service/ProductSellerServiceTest.java
git commit -m "feat: 내 상품 목록이 family당 대표 row 1개만 노출하도록 변경"
```

---

### Task 9: `getMyProduct` — 라이브 버전 정보 + 버전 이력 노출

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/presentation/dto/response/SellerProductDetailResponse.java`
- Create: `product-service/src/main/java/com/prompthub/product/presentation/dto/response/SellerProductVersionResponse.java`
- Modify: `product-service/src/main/java/com/prompthub/product/application/service/ProductSellerService.java`
- Modify: `product-service/src/test/java/com/prompthub/product/application/service/ProductSellerServiceTest.java`

**Interfaces:**
- Produces: `SellerProductDetailResponse`에 `liveVersion`(nullable), `versions` 필드 추가(기존
  필드는 그대로 유지 — 하위 호환).

- [ ] **Step 1: 실패하는 테스트 작성**

`ProductSellerServiceTest.java`에 새 `@Nested` 클래스를 추가한다.

```java
	@Nested
	@DisplayName("내 상품 상세 조회")
	class GetMyProduct {

		@Test
		@DisplayName("PENDING_REVIEW 대표 row와 별도로 현재 라이브 ON_SALE 버전 정보를 함께 반환한다")
		void getMyProduct_includesLiveVersion_whenPendingReviewExistsAlongsideOnSale() {
			Product onSale = product(PRODUCT_ID, null, ProductStatus.ON_SALE, (short) 2, (short) 0);
			Product pending = product(UUID.randomUUID(), PRODUCT_ID, ProductStatus.PENDING_REVIEW, (short) 3, (short) 0);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(onSale, pending));

			com.prompthub.product.presentation.dto.response.SellerProductDetailResponse result =
				productSellerService.getMyProduct(SELLER_ID, PRODUCT_ID);

			assertThat(result.productId()).isEqualTo(pending.getId());
			assertThat(result.liveVersion()).isEqualTo("2.0");
			assertThat(result.versions()).hasSize(2);
		}
	}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.application.service.ProductSellerServiceTest" --no-daemon`
Expected: FAIL — `SellerProductDetailResponse`에 `liveVersion`/`versions` 필드가 없어 컴파일
에러.

- [ ] **Step 3: `SellerProductVersionResponse` 생성**

```java
package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.domain.model.entity.Product;

public record SellerProductVersionResponse(
	String version,
	String status,
	String date,
	String changeReason,
	String rejectionReason
) {
	public static SellerProductVersionResponse from(Product product) {
		return new SellerProductVersionResponse(
			product.getMajorVersion() + "." + product.getPatchVersion(),
			product.getStatus().name(),
			product.getUpdatedAt().toLocalDate().toString(),
			product.getChangeReason(),
			product.getRejectionReason()
		);
	}
}
```

- [ ] **Step 4: `SellerProductDetailResponse` 수정**

```java
package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.domain.model.entity.Product;
import java.util.List;
import java.util.UUID;

public record SellerProductDetailResponse(
	UUID productId,
	String title,
	String productType,
	String model,
	int amount,
	String desc,
	String content,
	String status,
	String version,
	String thumbnailUrl,
	List<String> imageUrls,
	List<String> tags,
	String liveVersion,
	List<SellerProductVersionResponse> versions
) {
	public static SellerProductDetailResponse from(
		Product product,
		Product liveOnSale,
		List<Product> historyMembers,
		StorageClient storageClient
	) {
		return new SellerProductDetailResponse(
			product.getId(),
			product.getName(),
			product.getProductType().name(),
			product.getModel(),
			product.getAmount(),
			product.getDescription(),
			product.getContent(),
			product.getStatus().name(),
			product.getMajorVersion() + "." + product.getPatchVersion(),
			toUrl(product.getThumbnailUrl(), storageClient),
			toUrls(product.getImageUrls(), storageClient),
			product.getTags(),
			liveOnSale != null ? liveOnSale.getMajorVersion() + "." + liveOnSale.getPatchVersion() : null,
			historyMembers.stream().map(SellerProductVersionResponse::from).toList()
		);
	}

	private static String toUrl(String key, StorageClient storageClient) {
		if (key == null || key.isBlank()) return null;
		return storageClient.generatePresignedDownloadUrl(key);
	}

	private static List<String> toUrls(List<String> keys, StorageClient storageClient) {
		if (keys == null || keys.isEmpty()) return List.of();
		return keys.stream()
			.map(key -> storageClient.generatePresignedDownloadUrl(key))
			.toList();
	}
}
```

- [ ] **Step 5: `ProductSellerService.getMyProduct` 수정**

```java
	@Override
	@Transactional(readOnly = true)
	public SellerProductDetailResponse getMyProduct(UUID sellerId, UUID productId) {
		Product anchor = getProductForSeller(sellerId, productId);
		UUID familyRootId = anchor.familyRootId();
		List<Product> members = productRepository.findAllByFamilyRootIds(List.of(familyRootId));
		ProductFamily family = ProductFamily.of(familyRootId, members);
		Product representative = family.currentForSeller().orElse(anchor);
		Product liveOnSale = family.currentOnSale().orElse(null);
		return SellerProductDetailResponse.from(representative, liveOnSale, family.sellerHistory(), storageClient);
	}
```

- [ ] **Step 6: 테스트 재실행 → 통과 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.application.service.ProductSellerServiceTest" --no-daemon`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/presentation/dto/response/SellerProductDetailResponse.java product-service/src/main/java/com/prompthub/product/presentation/dto/response/SellerProductVersionResponse.java product-service/src/main/java/com/prompthub/product/application/service/ProductSellerService.java product-service/src/test/java/com/prompthub/product/application/service/ProductSellerServiceTest.java
git commit -m "feat: 내 상품 상세에 라이브 버전 정보와 버전 이력 추가"
```

---

### Task 10: `ProductAdminService.approveProduct` — 승인 시 기존 ON_SALE supersede

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/application/service/ProductAdminService.java`
- Create: `product-service/src/test/java/com/prompthub/product/application/service/ProductAdminServiceTest.java`

**Interfaces:**
- Consumes: `ProductFamily`(Task 3), `ProductRepository.findAllByFamilyRootIds`(Task 4).

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.prompthub.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.exception.ProductException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductAdminServiceTest {

	private static final String ADMIN_ROLE = "ADMIN";
	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID FAMILY_ROOT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Mock
	private ProductRepository productRepository;

	@InjectMocks
	private ProductAdminService productAdminService;

	@Nested
	@DisplayName("상품 승인")
	class ApproveProduct {

		@Test
		@DisplayName("기존 ON_SALE row가 있으면 SUPERSEDED로 전환하고 대상 row를 ON_SALE로 승인한다")
		void approveProduct_supersedesPreviousOnSale() {
			Product onSale = product(FAMILY_ROOT_ID, null, ProductStatus.ON_SALE, (short) 2, (short) 0);
			Product pending = product(UUID.randomUUID(), FAMILY_ROOT_ID, ProductStatus.PENDING_REVIEW, (short) 3, (short) 0);
			given(productRepository.findById(pending.getId())).willReturn(Optional.of(pending));
			given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(onSale, pending));

			productAdminService.approveProduct(ADMIN_ROLE, pending.getId());

			assertThat(onSale.getStatus()).isEqualTo(ProductStatus.SUPERSEDED);
			assertThat(pending.getStatus()).isEqualTo(ProductStatus.ON_SALE);
			then(productRepository).should().save(onSale);
			then(productRepository).should().save(pending);
		}

		@Test
		@DisplayName("기존 ON_SALE row가 없으면(최초 승인) supersede 없이 승인만 한다")
		void approveProduct_firstApproval_noSupersede() {
			Product pending = product(FAMILY_ROOT_ID, null, ProductStatus.PENDING_REVIEW, (short) 1, (short) 0);
			given(productRepository.findById(pending.getId())).willReturn(Optional.of(pending));
			given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(pending));

			productAdminService.approveProduct(ADMIN_ROLE, pending.getId());

			assertThat(pending.getStatus()).isEqualTo(ProductStatus.ON_SALE);
			then(productRepository).should(org.mockito.Mockito.times(1)).save(pending);
		}

		@Test
		@DisplayName("PENDING_REVIEW가 아닌 상품은 승인할 수 없다")
		void approveProduct_notPendingReview_throws() {
			Product onSale = product(FAMILY_ROOT_ID, null, ProductStatus.ON_SALE, (short) 1, (short) 0);
			given(productRepository.findById(FAMILY_ROOT_ID)).willReturn(Optional.of(onSale));

			assertThatThrownBy(() -> productAdminService.approveProduct(ADMIN_ROLE, FAMILY_ROOT_ID))
				.isInstanceOf(ProductException.class);
		}
	}

	private Product product(UUID id, UUID parentId, ProductStatus status, short majorVersion, short patchVersion) {
		Product product = Product.create(
			id, SELLER_ID, ProductType.PROMPT,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", List.of()
		);
		ReflectionTestUtils.setField(product, "parentId", parentId);
		ReflectionTestUtils.setField(product, "status", status);
		ReflectionTestUtils.setField(product, "majorVersion", majorVersion);
		ReflectionTestUtils.setField(product, "patchVersion", patchVersion);
		return product;
	}
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.application.service.ProductAdminServiceTest" --no-daemon`
Expected: FAIL — 현재 `approveProduct`는 supersede 로직이 없어 `onSale.getStatus()`가
여전히 `ON_SALE`(SUPERSEDED로 전환 안 됨) → 첫 번째 테스트 실패.

- [ ] **Step 3: `approveProduct` 수정**

```java
	@Override
	public void approveProduct(String role, UUID productId) {
		validateAdmin(role);
		Product target = getProductInPendingReview(productId);
		UUID familyRootId = target.familyRootId();
		ProductFamily family = ProductFamily.of(familyRootId, productRepository.findAllByFamilyRootIds(List.of(familyRootId)));
		family.currentOnSale().ifPresent(previous -> {
			previous.supersede();
			productRepository.save(previous);
		});
		target.approve();
		productRepository.save(target);
	}
```

`ProductAdminService.java` 상단 import에 `com.prompthub.product.domain.model.entity.ProductFamily`를
추가한다.

- [ ] **Step 4: 테스트 재실행 → 통과 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.application.service.ProductAdminServiceTest" --no-daemon`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/application/service/ProductAdminService.java product-service/src/test/java/com/prompthub/product/application/service/ProductAdminServiceTest.java
git commit -m "feat: 상품 승인 시 기존 ON_SALE row를 SUPERSEDED로 전환"
```

---

### Task 11: `rejectProduct` 무변화 검증 + `revertProductToPendingReview` 짝 복원

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/application/service/ProductAdminService.java`
- Modify: `product-service/src/test/java/com/prompthub/product/application/service/ProductAdminServiceTest.java`

**Interfaces:**
- Consumes: `Product.restoreFromSuperseded()`(Task 2), `ProductFamily.mostRecentSuperseded()`(Task 3).

- [ ] **Step 1: 실패하는 테스트 작성**

`ProductAdminServiceTest.java`에 새 `@Nested` 클래스 2개를 추가한다.

```java
	@Nested
	@DisplayName("상품 반려")
	class RejectProduct {

		@Test
		@DisplayName("반려 대상 row만 변경되고 family의 다른 row는 그대로 유지된다")
		void rejectProduct_onlyTargetRowChanges_othersUntouched() {
			Product onSale = product(FAMILY_ROOT_ID, null, ProductStatus.ON_SALE, (short) 2, (short) 0);
			Product pending = product(UUID.randomUUID(), FAMILY_ROOT_ID, ProductStatus.PENDING_REVIEW, (short) 3, (short) 0);
			given(productRepository.findById(pending.getId())).willReturn(Optional.of(pending));

			productAdminService.rejectProduct(ADMIN_ROLE, pending.getId(), "콘텐츠 미흡");

			assertThat(pending.getStatus()).isEqualTo(ProductStatus.REJECTED);
			assertThat(pending.getRejectionReason()).isEqualTo("콘텐츠 미흡");
			assertThat(onSale.getStatus()).isEqualTo(ProductStatus.ON_SALE);
			then(productRepository).should(org.mockito.Mockito.never()).save(onSale);
		}
	}

	@Nested
	@DisplayName("검수 대기로 되돌리기")
	class RevertProductToPendingReview {

		@Test
		@DisplayName("ON_SALE row를 되돌리면 짝이었던 SUPERSEDED row를 ON_SALE로 복원한다")
		void revert_onSaleRow_restoresPairedSupersededRow() {
			Product superseded = product(FAMILY_ROOT_ID, null, ProductStatus.SUPERSEDED, (short) 2, (short) 0);
			Product onSale = product(UUID.randomUUID(), FAMILY_ROOT_ID, ProductStatus.ON_SALE, (short) 3, (short) 0);
			given(productRepository.findById(onSale.getId())).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(superseded, onSale));

			productAdminService.revertProductToPendingReview(ADMIN_ROLE, onSale.getId());

			assertThat(onSale.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
			assertThat(superseded.getStatus()).isEqualTo(ProductStatus.ON_SALE);
		}

		@Test
		@DisplayName("짝이 없으면(최초 승인) 대상 row만 되돌린다")
		void revert_onSaleRow_noSupersededPair_onlyTargetChanges() {
			Product onSale = product(FAMILY_ROOT_ID, null, ProductStatus.ON_SALE, (short) 1, (short) 0);
			given(productRepository.findById(FAMILY_ROOT_ID)).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(onSale));

			productAdminService.revertProductToPendingReview(ADMIN_ROLE, FAMILY_ROOT_ID);

			assertThat(onSale.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
		}

		@Test
		@DisplayName("REJECTED row를 되돌릴 때는 family 조회 없이 대상만 변경한다")
		void revert_rejectedRow_doesNotTouchFamily() {
			Product rejected = product(FAMILY_ROOT_ID, null, ProductStatus.REJECTED, (short) 1, (short) 0);
			given(productRepository.findById(FAMILY_ROOT_ID)).willReturn(Optional.of(rejected));

			productAdminService.revertProductToPendingReview(ADMIN_ROLE, FAMILY_ROOT_ID);

			assertThat(rejected.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
			then(productRepository).should(org.mockito.Mockito.never()).findAllByFamilyRootIds(org.mockito.ArgumentMatchers.anyList());
		}
	}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.application.service.ProductAdminServiceTest" --no-daemon`
Expected: `RejectProduct`의 테스트는 이미 통과할 것(기존 로직이 그대로 요구사항을 만족),
`RevertProductToPendingReview`의 첫 번째 테스트(`restoresPairedSupersededRow`)는 FAIL —
현재 구현은 `superseded`를 복원하지 않음.

- [ ] **Step 3: `revertProductToPendingReview` 수정**

```java
	@Override
	public void revertProductToPendingReview(String role, UUID productId) {
		validateAdmin(role);
		Product target = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
		if (target.getStatus() != ProductStatus.ON_SALE && target.getStatus() != ProductStatus.REJECTED) {
			throw new ProductException(ProductErrorCode.PRODUCT_INVALID_STATUS);
		}

		if (target.getStatus() == ProductStatus.ON_SALE) {
			UUID familyRootId = target.familyRootId();
			ProductFamily family = ProductFamily.of(familyRootId, productRepository.findAllByFamilyRootIds(List.of(familyRootId)));
			family.mostRecentSuperseded().ifPresent(paired -> {
				paired.restoreFromSuperseded();
				productRepository.save(paired);
			});
		}

		target.revertToPendingReview();
		productRepository.save(target);
	}
```

- [ ] **Step 4: 테스트 재실행 → 통과 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.application.service.ProductAdminServiceTest" --no-daemon`
Expected: PASS (전체)

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/application/service/ProductAdminService.java product-service/src/test/java/com/prompthub/product/application/service/ProductAdminServiceTest.java
git commit -m "feat: 검수 되돌리기 시 SUPERSEDED로 전환됐던 짝 row를 함께 복원"
```

---

### Task 12: `ProductQueryService` — 공개 조회 family resolution + 버전 이력

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/application/service/ProductQueryService.java`
- Modify: `product-service/src/test/java/com/prompthub/product/application/service/ProductQueryServiceTest.java`

**Interfaces:**
- Consumes: `ProductFamily`(Task 3), `ProductRepository.findAllByFamilyRootIds`(Task 4).
- Produces: `getProduct`/`getRelatedProducts`/`getProductReviews`가 요청 id가 `SUPERSEDED`/
  `REJECTED`인 과거 row여도 family의 현재 `ON_SALE` row로 resolve.

- [ ] **Step 1: 실패하는 테스트 작성**

`ProductQueryServiceTest.java`에 새 `@Nested` 클래스를 추가한다(기존 `GetProducts` 클래스
옆에).

```java
	@Nested
	@DisplayName("family resolution")
	class FamilyResolution {

		@Test
		@DisplayName("SUPERSEDED된 옛 id로 조회해도 family의 현재 ON_SALE row로 resolve한다")
		void getProduct_oldSupersededId_resolvesToCurrentOnSale() {
			UUID oldId = PRODUCT_ID;
			UUID currentId = RELATED_PRODUCT_ID;
			Product old = productFixture(oldId, null, ProductStatus.SUPERSEDED, (short) 1, (short) 0);
			Product current = productFixture(currentId, oldId, ProductStatus.ON_SALE, (short) 2, (short) 0);

			given(productRepository.findById(oldId)).willReturn(Optional.of(old));
			given(productRepository.findAllByFamilyRootIds(List.of(oldId))).willReturn(List.of(old, current));
			given(productRepository.getAverageRating(oldId)).willReturn(4.5);
			given(sellerClient.getSellerInfo(SELLER_ID))
				.willReturn(new com.prompthub.product.application.client.SellerInfo(SELLER_ID, "판매자", null, "ACTIVE"));
			given(productRepository.countOnSaleProductsBySellerId(SELLER_ID)).willReturn(1L);

			ProductDetailResponse result = productQueryService.getProduct(oldId);

			assertThat(result.id()).isEqualTo(currentId);
			assertThat(result.versions()).hasSize(2);
		}

		@Test
		@DisplayName("family에 ON_SALE row가 없으면 404를 던진다")
		void getProduct_noOnSaleInFamily_throwsNotFound() {
			Product rejected = productFixture(PRODUCT_ID, null, ProductStatus.REJECTED, (short) 1, (short) 0);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(rejected));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(rejected));

			assertThatThrownBy(() -> productQueryService.getProduct(PRODUCT_ID))
				.isInstanceOf(ProductException.class);
		}
	}

	private Product productFixture(UUID id, UUID parentId, ProductStatus status, short majorVersion, short patchVersion) {
		Product product = Product.create(
			id, SELLER_ID, ProductType.PROMPT,
			"제목", "설명", "model", com.prompthub.product.domain.model.enums.AmountType.PAID, 1000,
			null, List.of(), "content", List.of()
		);
		ReflectionTestUtils.setField(product, "parentId", parentId);
		ReflectionTestUtils.setField(product, "status", status);
		ReflectionTestUtils.setField(product, "majorVersion", majorVersion);
		ReflectionTestUtils.setField(product, "patchVersion", patchVersion);
		ReflectionTestUtils.setField(product, "createdAt", CREATED_AT);
		ReflectionTestUtils.setField(product, "updatedAt", UPDATED_AT);
		return product;
	}
```

(파일에 이미 `ProductType`, `ProductException`, `ReflectionTestUtils`, `assertThatThrownBy`
import가 있는지 확인하고 없으면 추가한다.)

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.application.service.ProductQueryServiceTest" --no-daemon`
Expected: FAIL — 현재 `getOnSaleProduct`는 `findById(oldId)`가 반환한 `old`(SUPERSEDED)를
그대로 써서 `PRODUCT_NOT_FOUND` 예외가 던져짐(SUPERSEDED != ON_SALE).

- [ ] **Step 3: `getOnSaleProduct`/버전 이력 수정**

`ProductQueryService.java`의 `getOnSaleProduct`를 아래로 교체한다.

```java
	private Product getOnSaleProduct(UUID productId) {
		Product anchor = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
		UUID familyRootId = anchor.familyRootId();
		List<Product> members = productRepository.findAllByFamilyRootIds(List.of(familyRootId));
		ProductFamily family = ProductFamily.of(familyRootId, members);
		return family.currentOnSale()
			.filter(p -> p.getDeletedAt() == null)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
	}

	private List<ProductVersionResponse> toVersionHistory(UUID familyRootId) {
		List<Product> members = productRepository.findAllByFamilyRootIds(List.of(familyRootId));
		return ProductFamily.of(familyRootId, members).publicHistory().stream()
			.map(this::toVersionResponse)
			.toList();
	}
```

`getProduct` 메서드에서 아래 두 곳을 수정한다.

1. `List.of(toVersionResponse(product))` → `toVersionHistory(product.familyRootId())`
2. `double rating = productRepository.getAverageRating(productId);` →
   `double rating = productRepository.getAverageRating(product.familyRootId());`
   (Task 6에서 리뷰를 항상 family root에 귀속시키므로, 평점도 family root 기준으로 조회해야
   요청 id가 과거 버전이어도 정확한 평점이 나온다. `product`는 `getOnSaleProduct(productId)`가
   반환한 **resolve된 현재 row**이고, `product.familyRootId()`는 이 row의 부모 체인을 타고
   구한 family root id다.)

`getProductReviews` 메서드에서도 아래로 수정한다.

```java
	public List<ProductReviewResponse> getProductReviews(UUID productId) {
		Product product = getOnSaleProduct(productId);

		return productRepository.findActiveReviews(product.familyRootId())
			.stream()
			.map(this::toReviewResponse)
			.toList();
	}
```

`ProductQueryService.java` 상단 import에
`com.prompthub.product.domain.model.entity.ProductFamily`를 추가한다.

- [ ] **Step 4: 테스트 재실행 → 통과 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.application.service.ProductQueryServiceTest" --no-daemon`
Expected: PASS (전체)

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/application/service/ProductQueryService.java product-service/src/test/java/com/prompthub/product/application/service/ProductQueryServiceTest.java
git commit -m "feat: 공개 상품 조회가 family 기준으로 현재 ON_SALE row를 resolve하도록 변경"
```

---

### Task 13: 내부 gRPC 4종 — family resolution 적용

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/application/service/ProductInternalService.java`
- Modify: `product-service/src/main/java/com/prompthub/product/presentation/dto/response/ProductOrderSnapshotResponse.java`
- Modify: `product-service/src/main/java/com/prompthub/product/presentation/dto/response/ProductCartSnapshotResponse.java`
- Modify: `product-service/src/main/java/com/prompthub/product/presentation/dto/response/ProductContentResponse.java`
- Modify: `product-service/src/test/java/com/prompthub/product/application/service/ProductInternalServiceTest.java`

**Interfaces:**
- Consumes: `ProductFamily`(Task 3), `ProductRepository.findAllByFamilyRootIds`(Task 4),
  `ProductRepository.findAllByIdIn`(기존).
- Produces: `getProductsByIds`/`getOrderSnapshots`/`getCartSnapshots`/`getCartSnapshot`/
  `getProductContent`가 요청받은 id가 family 내 과거 row여도 family 기준 대표 row로 resolve.
  응답의 `productId`는 항상 **호출자가 요청한 원래 id**를 반환한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`ProductInternalServiceTest.java`의 `GetCartSnapshots` 클래스에 테스트를 추가한다.

```java
			@Test
			@DisplayName("요청 id가 SUPERSEDED된 옛 row여도 family의 현재 ON_SALE row 데이터로 응답하고 productId는 요청 id를 유지한다")
			void getCartSnapshots_resolvesSupersededIdToCurrentOnSale() {
				UUID oldId = PRODUCT_ID;
				UUID currentId = UUID.fromString("55555555-5555-5555-5555-555555555555");
				Product old = product(oldId, SELLER_ID, ProductStatus.SUPERSEDED);
				Product current = product(currentId, SELLER_ID, ProductStatus.ON_SALE);
				ReflectionTestUtils.setField(current, "parentId", oldId);

				given(productRepository.findAllByIdIn(List.of(oldId))).willReturn(List.of(old));
				given(productRepository.findAllByFamilyRootIds(List.of(oldId))).willReturn(List.of(old, current));
				given(sellerClient.getSellerInfo(SELLER_ID))
					.willReturn(new SellerInfo(SELLER_ID, "프롬프트상점", null, "ACTIVE"));

				List<ProductCartSnapshotResponse> result = productInternalService.getCartSnapshots(List.of(oldId));

				assertThat(result).hasSize(1);
				assertThat(result.get(0).productId()).isEqualTo(oldId);
				assertThat(result.get(0).productTitle()).isEqualTo("면접 답변 프롬프트");
			}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.application.service.ProductInternalServiceTest" --no-daemon`
Expected: FAIL — 현재 `getCartSnapshots`는 `findOnSaleByIdIn(List.of(oldId))`를 호출하는데
`oldId` row는 SUPERSEDED라서 빈 리스트가 반환되어 `result`가 비어 있음.

- [ ] **Step 3: `resolveFamilyRepresentatives` 헬퍼 추가 + 4개 메서드 수정**

`ProductInternalService.java`를 아래로 교체한다.

```java
package com.prompthub.product.application.service;

import com.prompthub.product.application.client.SellerClient;
import com.prompthub.product.application.usecase.ProductInternalUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.entity.Review;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.domain.repository.ReviewRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.response.ProductCartSnapshotResponse;
import com.prompthub.product.presentation.dto.response.ProductContentResponse;
import com.prompthub.product.presentation.dto.response.ProductCountResponse;
import com.prompthub.product.presentation.dto.response.ProductOrderSnapshotResponse;
import com.prompthub.product.presentation.dto.response.ProductsByIdsResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductInternalService implements ProductInternalUseCase {

	private final ProductRepository productRepository;
	private final ReviewRepository reviewRepository;
	private final SellerClient sellerClient;

	@Override
	public List<ProductsByIdsResponse> getProductsByIds(List<UUID> productIds) {
		Map<UUID, Product> resolved = resolveFamilyRepresentatives(productIds, ProductFamily::currentForWishlist);
		return productIds.stream()
			.filter(resolved::containsKey)
			.map(id -> {
				Product p = resolved.get(id);
				return new ProductsByIdsResponse(
					id,
					p.getSellerId(),
					p.getName(),
					p.getAmount(),
					p.getThumbnailUrl(),
					p.getProductType().name(),
					p.getModel() != null ? p.getModel() : "",
					p.getSalesCount(),
					productRepository.getAverageRating(p.familyRootId()),
					p.getStatus().name()
				);
			})
			.toList();
	}

	@Override
	public List<ProductOrderSnapshotResponse> getOrderSnapshots(List<UUID> productIds) {
		Map<UUID, Product> resolved = resolveFamilyRepresentatives(productIds, ProductFamily::currentOnSale);
		return productIds.stream()
			.filter(resolved::containsKey)
			.map(id -> ProductOrderSnapshotResponse.from(id, resolved.get(id)))
			.toList();
	}

	@Override
	public ProductCartSnapshotResponse getCartSnapshot(UUID productId) {
		Map<UUID, Product> resolved = resolveFamilyRepresentatives(List.of(productId), ProductFamily::currentOnSale);
		Product product = resolved.get(productId);
		if (product == null) {
			throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
		}
		String sellerNickname = sellerClient.getSellerInfo(product.getSellerId()).sellerName();
		return ProductCartSnapshotResponse.from(productId, product, sellerNickname);
	}

	@Override
	public List<ProductCartSnapshotResponse> getCartSnapshots(List<UUID> productIds) {
		Map<UUID, Product> resolved = resolveFamilyRepresentatives(productIds, ProductFamily::currentOnSale);
		Map<UUID, String> sellerNicknames = resolved.values().stream()
			.map(Product::getSellerId)
			.distinct()
			.collect(Collectors.toMap(id -> id, id -> sellerClient.getSellerInfo(id).sellerName()));
		return productIds.stream()
			.filter(resolved::containsKey)
			.map(id -> {
				Product product = resolved.get(id);
				return ProductCartSnapshotResponse.from(id, product, sellerNicknames.get(product.getSellerId()));
			})
			.toList();
	}

	@Override
	public ProductContentResponse getProductContent(UUID productId) {
		Map<UUID, Product> resolved = resolveFamilyRepresentatives(List.of(productId), ProductFamily::currentOnSale);
		Product product = resolved.get(productId);
		if (product == null) {
			throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
		}
		return ProductContentResponse.from(productId, product);
	}

	@Override
	@Transactional
	public void upsertReview(UUID buyerId, UUID productId, Integer rating) {
		Product anchor = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
		Product root = productRepository.findById(anchor.familyRootId())
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
		reviewRepository.findByUserIdAndProductId(buyerId, root.getId())
			.ifPresentOrElse(
				review -> review.updateRating((short) rating.intValue()),
				() -> reviewRepository.save(Review.create(buyerId, root, (short) rating.intValue()))
			);
	}

	@Override
	public ProductCountResponse getProductCount(UUID sellerId) {
		return new ProductCountResponse(sellerId, productRepository.countBySellerId(sellerId));
	}

	private Map<UUID, Product> resolveFamilyRepresentatives(
		List<UUID> requestedIds,
		Function<ProductFamily, Optional<Product>> selector
	) {
		List<Product> anchors = productRepository.findAllByIdIn(requestedIds);
		Map<UUID, UUID> familyRootByRequestedId = anchors.stream()
			.collect(Collectors.toMap(Product::getId, Product::familyRootId));
		List<UUID> familyRootIds = familyRootByRequestedId.values().stream().distinct().toList();
		List<Product> allMembers = familyRootIds.isEmpty()
			? List.of()
			: productRepository.findAllByFamilyRootIds(familyRootIds);
		Map<UUID, List<Product>> membersByFamily = allMembers.stream()
			.collect(Collectors.groupingBy(Product::familyRootId));

		Map<UUID, Product> result = new LinkedHashMap<>();
		for (UUID requestedId : requestedIds) {
			UUID familyRootId = familyRootByRequestedId.get(requestedId);
			if (familyRootId == null) {
				continue;
			}
			ProductFamily family = ProductFamily.of(familyRootId, membersByFamily.getOrDefault(familyRootId, List.of()));
			selector.apply(family).ifPresent(product -> result.put(requestedId, product));
		}
		return result;
	}
}
```

`ProductOrderSnapshotResponse.java`의 `from`을 아래로 교체한다.

```java
	public static ProductOrderSnapshotResponse from(UUID productId, Product product) {
		return new ProductOrderSnapshotResponse(
			productId,
			product.getSellerId(),
			product.getName(),
			product.getProductType().name(),
			product.getAmount(),
			product.getModel() != null ? product.getModel() : ""
		);
	}
```

`ProductCartSnapshotResponse.java`의 `from`을 아래로 교체한다.

```java
	public static ProductCartSnapshotResponse from(UUID productId, Product product, String sellerNickname) {
		return new ProductCartSnapshotResponse(
			productId,
			product.getSellerId(),
			product.getName(),
			product.getProductType().name(),
			product.getAmount(),
			product.getThumbnailUrl(),
			sellerNickname,
			product.getStatus().name()
		);
	}
```

`ProductContentResponse.java`의 `from`을 아래로 교체한다.

```java
	public static ProductContentResponse from(UUID productId, Product product) {
		return new ProductContentResponse(productId, product.getContent());
	}
```

- [ ] **Step 4: 테스트 재실행 → 통과 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.application.service.ProductInternalServiceTest" --no-daemon`
Expected: PASS (Task 6에서 추가한 `UpsertReview` 테스트 포함 전체 통과)

- [ ] **Step 5: gRPC 서비스 구현체가 새 시그니처와 맞는지 컴파일 확인**

`OrderProductGrpcService`/`UserProductGrpcService`는 `productInternalUseCase`의 반환 DTO
필드만 사용하므로 코드 변경이 필요 없다. 컴파일만 확인한다.

Run: `cd product-service && .\gradlew.bat compileJava --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/application/service/ProductInternalService.java product-service/src/main/java/com/prompthub/product/presentation/dto/response/ProductOrderSnapshotResponse.java product-service/src/main/java/com/prompthub/product/presentation/dto/response/ProductCartSnapshotResponse.java product-service/src/main/java/com/prompthub/product/presentation/dto/response/ProductContentResponse.java product-service/src/test/java/com/prompthub/product/application/service/ProductInternalServiceTest.java
git commit -m "feat: 내부 gRPC 4종이 family 기준으로 대표 row를 resolve하도록 변경"
```

---

### Task 14: `ProductControllerTest` 신규 작성

이슈 #223 본문에 명시된 대로 현재 `ProductControllerTest`가 존재하지 않는다.
`ProductControllerTest.java`의 스타일(`@WebMvcTest` 또는 기존 컨트롤러 테스트 패턴)을 그대로
따른다.

**Files:**
- Create: `product-service/src/test/java/com/prompthub/product/presentation/controller/AdminProductControllerTest.java`

**Interfaces:**
- Consumes: `ProductUseCase`(mock), `ProductController`.

- [ ] **Step 1: 기존 `ProductControllerTest.java`의 테스트 설정 방식 확인**

`product-service/src/test/java/com/prompthub/product/presentation/controller/ProductControllerTest.java`
상단의 `@WebMvcTest`/`@MockBean` 또는 `@ExtendWith(MockitoExtension.class)` +
`MockMvcBuilders.standaloneSetup(...)` 방식 중 어떤 것을 쓰는지 읽고, 아래 테스트를 **그
방식과 동일하게** 작성한다(이 Plan은 어떤 방식인지 가정하지 않는다 — 실행자가 실제 파일을
열어 확인 후 아래 테스트 케이스 내용만 동일한 스타일로 옮겨 적는다).

- [ ] **Step 2: 실패하는 테스트 작성**

아래는 `MockMvc` 기반 최소 테스트 케이스 3개다(정확한 setup 보일러플레이트는 Step 1에서
확인한 `ProductControllerTest.java` 스타일을 그대로 복사해서 맞춘다).

```java
	@Test
	@DisplayName("ADMIN이 아니면 검수 대기 목록 조회를 거부한다")
	void getPendingReviewProducts_forbiddenForNonAdmin() throws Exception {
		given(productAdminUseCase.getPendingReviewProducts("BUYER"))
			.willThrow(new ProductException(ProductErrorCode.PRODUCT_FORBIDDEN));

		mockMvc.perform(get("/api/v1/admin/products").header("X-User-Role", "BUYER"))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("승인 성공 시 200과 공통 응답 포맷을 반환한다")
	void approveProduct_success() throws Exception {
		UUID productId = UUID.randomUUID();

		mockMvc.perform(patch("/api/v1/admin/products/{productId}/approve", productId)
				.header("X-User-Role", "ADMIN"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		then(productAdminUseCase).should().approveProduct("ADMIN", productId);
	}

	@Test
	@DisplayName("반려 성공 시 사유를 함께 전달한다")
	void rejectProduct_success() throws Exception {
		UUID productId = UUID.randomUUID();

		mockMvc.perform(patch("/api/v1/admin/products/{productId}/reject", productId)
				.header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"콘텐츠 미흡\"}"))
			.andExpect(status().isOk());

		then(productAdminUseCase).should().rejectProduct("ADMIN", productId, "콘텐츠 미흡");
	}
```

- [ ] **Step 3: 테스트 실행 → 실패 확인**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.presentation.controller.AdminProductControllerTest" --no-daemon`
Expected: FAIL(파일이 아직 setup 보일러플레이트 없이 미완성이거나, mock 설정이 실제
`ProductUseCase` 예외 처리와 안 맞으면 실패). 실패 원인을 보고 Step 1에서 확인한
`ProductControllerTest.java`의 실제 exception handler/응답 포맷에 맞춰 조정한다.

- [ ] **Step 4: 통과할 때까지 setup 보정 후 재실행**

Run: `cd product-service && .\gradlew.bat test --tests "com.prompthub.product.presentation.controller.AdminProductControllerTest" --no-daemon`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/test/java/com/prompthub/product/presentation/controller/AdminProductControllerTest.java
git commit -m "test: AdminProductController 컨트롤러 테스트 추가"
```

---

### Task 15: 문서 동기화 + 규칙 검증 + 전체 빌드

**Files:**
- Modify: `docs/erd/schema.md` (product_status_type enum에 SUPERSEDED 추가 — `sync-product-docs` skill 사용)
- Modify: `docs/api-spec/product.md` (필요 시 — 판매자 상세 응답 필드 추가분 반영)
- Modify: `docs/error-codes.md` (필요 시 — 이번 Task에서 새 에러 코드를 추가하지 않았으므로 변경 없을 가능성 높음)

- [ ] **Step 1: `sync-product-docs` skill 실행**

이 skill을 호출해 컨트롤러/DTO/엔티티 변경사항과 루트 docs를 대조하고 사용자 승인 하에
동기화한다. 특히 `docs/erd/schema.md`의 `product_status_type` enum 값 목록에 `SUPERSEDED`를
추가해야 한다.

- [ ] **Step 2: `verify-rules` skill 실행**

architecture/product-api/testing/git-workflow 4종 규칙 위반 여부를 확인한다.

- [ ] **Step 3: 전체 빌드**

Run:
```powershell
cd product-service
.\gradlew.bat clean build --no-daemon
```
Expected: BUILD SUCCESSFUL (checkstyle 포함, 전체 테스트 통과)

- [ ] **Step 4: 커밋**

```bash
git add docs/erd/schema.md docs/api-spec/product.md docs/error-codes.md
git commit -m "docs: SUPERSEDED 상태 및 판매자 상세 응답 변경사항 문서 동기화"
```

---

## Self-Review 메모

- **Spec coverage**: 설계 문서의 핵심 개념(family, 상태 전이/분기 규칙, 불변식), 데이터 모델
  변경(SUPERSEDED — 단 DB 마이그레이션은 ddl-auto 환경 특성상 애플리케이션 레벨로 대체),
  도메인 모델 변경, `ProductSellerService`/`ProductService` 변경, family resolution
  적용 5개 호출처, 판매자 화면 변경, 테스트 계획이 모두 Task 1~14에 매핑된다. 설계 문서에
  없던 리뷰/평점 family 집계 문제(Task 5~6)는 계획 수립 중 발견해 사용자 승인 후 추가했다.
- **미해결 사항 반영**: 설계 문서의 "관리자 목록에 구분 필드 추가는 범위 밖" 결정을 그대로
  따라 `AdminProductListItemResponse`는 변경하지 않았다. `Product.update()`의 in-place 경로는
  기존 시그니처를 그대로 재사용하기로 결정했다(Task 7).
- **Deviation from design doc**: 설계 문서의 DB partial unique index는 이 리포지토리에
  Flyway/Liquibase가 없고 `ddl-auto=create-only`라 실제로 적용할 마이그레이션 수단이 없어
  이번 구현 범위에서 제외했다(Global Constraints에 명시). 대신 트랜잭션 단위로 항상
  supersede와 신규 row 생성을 함께 커밋하는 서비스 코드 + 그 동작을 검증하는 서비스
  테스트로 대체했다.
