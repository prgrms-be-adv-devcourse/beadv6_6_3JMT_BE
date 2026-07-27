# AI 상품 자동 검수 기능 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 판매자가 `submitForReview()`로 상품을 제출하면 product-service가 Kafka로 검수 요청을 발행하고, ai-service가 OpenAI vision으로 텍스트+이미지를 검수해 승인/반려를 판정한 뒤, product-service가 그 결과를 받아 상품 상태를 `ON_SALE`/`REJECTED`로 자동 전이시킨다.

**Architecture:** product-service(`product-events` 토픽에 `PRODUCT_REVIEW_REQUESTED` 발행, 상품 스냅샷+presigned 이미지 URL 포함) → ai-service(신규 `inspection` 도메인, Spring AI vision 호출로 판정 → `ai-events` 토픽에 `PRODUCT_INSPECTION_COMPLETED` 발행) → product-service(`ai-events` 소비 → `Product.approve()`/`reject()` 적용, PENDING_REVIEW 아니면 멱등 스킵). ai-service는 이 기능으로 최초로 Kafka consumer+producer를 갖는다.

**Tech Stack:** Spring Kafka(`EventMessage<T>` 공통 봉투), Spring AI(`spring-ai-starter-model-openai`, raw `ChatModel` + `BeanOutputConverter` 구조화 출력), AWS S3 presigned URL(기존 `StorageClient`).

**설계 문서:** `docs/superpowers/specs/2026-07-27-ai-product-inspection-design.md` (로컬 전용, git 미추적 — 이슈 #597에 결정 사항 발췌 필요 시 참고)

## Global Constraints

- product-service 패키지 루트는 `infra`(축약), ai-service 패키지 루트는 `infrastructure`(전체 표기) — 서비스별 기존 관례를 그대로 따른다. 헷갈리지 않게 주의.
- 이 프로젝트의 Jackson은 Jackson 3(`tools.jackson.*`)이다. `com.fasterxml.jackson.*`을 import하지 않는다.
- Kafka 이벤트는 항상 `com.prompthub.common.event.EventMessage<T>` 봉투로 감싼다. 개별 Envelope 타입을 새로 만들지 않는다(kafka-event.md §2).
- `eventType`은 String 유지, 도메인별 지원 타입은 발행측/소비측 각자 로컬 enum(`EventType` 구현)으로 표현한다(kafka-event.md §4, `OrderEventType`/`ProductEventType` 패턴).
- Kafka key = `aggregateId`(여기선 항상 `productId`). `aggregateType = "PRODUCT"`.
- 실패 처리: 컨슈머에서 예외를 던지면 `DefaultErrorHandler`(FixedBackOff 1s×2) 재시도 후 `{topic}.DLT`로 간다. 미지원 eventType과 "이미 처리된 상태"는 예외를 던지지 않고 로그+ack한다(DLT 아님).
- `Product.approve()`/`reject()`는 `PENDING_REVIEW`가 아니면 `IllegalStateException`을 던진다(기존 `supersede()`와 동일 스타일). 이 예외는 handler 계층에서만 잡아 "중복 이벤트 스킵"으로 처리한다 — 도메인 메서드 자체를 조용히 무시하게 만들지 않는다.
- 별도 이벤트 처리 이력 테이블(`ProcessedEventRepository`)은 이번 기능에 쓰지 않는다 — 상태 가드로 이미 자연 멱등이다(kafka-event.md §7의 "자연 멱등이면 이력 불필요" 조건에 해당).
- Product 엔티티의 `thumbnailUrl`/`imageUrls` 컬럼은 **raw S3 key**다. Kafka payload에 넣기 전 반드시 `StorageClient.generatePresignedDownloadUrl(key)`로 presign한다.
- 신규 DB 마이그레이션 없음 — `rejectionReason` 컬럼은 이미 존재한다.

---

## Task 1: Product 도메인 — `approve()` / `reject()`

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/domain/model/entity/Product.java`
- Test: `product-service/src/test/java/com/prompthub/product/domain/model/entity/ProductTest.java`

**Interfaces:**
- Produces: `Product.approve()` — `PENDING_REVIEW` → `ON_SALE`, 아니면 `IllegalStateException`. `Product.reject(String reason)` — `PENDING_REVIEW` → `REJECTED` + `rejectionReason` 설정, 아니면 `IllegalStateException`.

- [ ] **Step 1: 실패하는 테스트 작성**

`ProductTest.java`의 `supersede_nonOnSaleRow_throws()` 테스트 아래에 추가:

```java
	@Test
	void approve_pendingReview_transitionsToOnSale() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "status", ProductStatus.PENDING_REVIEW);

		product.approve();

		assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
	}

	@Test
	void approve_nonPendingReview_throws() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());

		assertThatThrownBy(product::approve).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void reject_pendingReview_transitionsToRejectedWithReason() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "status", ProductStatus.PENDING_REVIEW);

		product.reject("금지 콘텐츠 포함");

		assertThat(product.getStatus()).isEqualTo(ProductStatus.REJECTED);
		assertThat(product.getRejectionReason()).isEqualTo("금지 콘텐츠 포함");
	}

	@Test
	void reject_nonPendingReview_throws() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());

		assertThatThrownBy(() -> product.reject("사유")).isInstanceOf(IllegalStateException.class);
	}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run (레포 루트에서, gradle wrapper는 루트에만 있음):
```bash
./gradlew :product-service:test --tests "com.prompthub.product.domain.model.entity.ProductTest" --no-daemon
```
Expected: FAIL — `Product`에 `approve`/`reject` 메서드가 없어 컴파일 에러.

- [ ] **Step 3: 최소 구현 작성**

`Product.java`의 `submitForReview()` 메서드 아래에 추가:

```java
	public void approve() {
		if (this.status != ProductStatus.PENDING_REVIEW) {
			throw new IllegalStateException("PENDING_REVIEW 상태의 상품만 승인할 수 있습니다. current=" + this.status);
		}
		this.status = ProductStatus.ON_SALE;
		this.updatedAt = LocalDateTime.now();
	}

	public void reject(String reason) {
		if (this.status != ProductStatus.PENDING_REVIEW) {
			throw new IllegalStateException("PENDING_REVIEW 상태의 상품만 반려할 수 있습니다. current=" + this.status);
		}
		this.status = ProductStatus.REJECTED;
		this.rejectionReason = reason;
		this.updatedAt = LocalDateTime.now();
	}
```

- [ ] **Step 4: 테스트 실행해 통과 확인**

Run:
```bash
./gradlew :product-service:test --tests "com.prompthub.product.domain.model.entity.ProductTest" --no-daemon
```
Expected: PASS (4개 신규 테스트 포함 전체 통과)

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/domain/model/entity/Product.java \
  product-service/src/test/java/com/prompthub/product/domain/model/entity/ProductTest.java
git commit -m "$(cat <<'EOF'
feat: Product에 AI 검수 결과 반영용 approve/reject 도메인 메서드 추가

submitForReview()로 PENDING_REVIEW까지는 가지만 이 상태를 ON_SALE/REJECTED로
바꾸는 소비자가 지금까지 하나도 없었다(#597) — 검수 이후 단계가 통째로
비어 있었다. approve()/reject(reason)을 추가해 이 전이를 도메인 메서드로
명시한다.

supersede()와 동일하게 현재 상태가 PENDING_REVIEW가 아니면
IllegalStateException을 던진다. 조용히 무시하지 않는 이유는, "이미 처리된
중복 이벤트라 스킵"이라는 판단은 도메인이 아니라 Kafka 컨슈머/handler
계층의 관심사이기 때문이다 — 도메인 메서드는 상태 전이 규칙만 지키고,
멱등 처리는 이후 Task(ProductInspectionResultHandler)에서 이 예외를
잡아서 한다.
EOF
)"
```

---

## Task 2: `PRODUCT_REVIEW_REQUESTED` 이벤트 발행

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/infra/messaging/producer/ProductEventType.java`
- Create: `product-service/src/main/java/com/prompthub/product/infra/messaging/producer/event/ProductReviewRequestedPayload.java`
- Modify: `product-service/src/main/java/com/prompthub/product/infra/messaging/producer/ProductEventProducer.java`
- Test: `product-service/src/test/java/com/prompthub/product/infra/messaging/producer/ProductEventProducerTest.java`

**Interfaces:**
- Consumes: `Product`(Task 1의 getter들 — `getId()`, `getProductType()`, `getName()`, `getDescription()`, `getContent()`, `getTags()`)
- Produces: `ProductEventProducer.publishReviewRequested(Product product, String presignedThumbnailUrl, List<String> presignedImageUrls)` — Task 3이 사용.

- [ ] **Step 1: 실패하는 테스트 작성**

`ProductEventProducerTest.java` 상단 import에 추가:
```java
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.infra.messaging.producer.event.ProductReviewRequestedPayload;

import static com.prompthub.product.support.ProductContentFixtures.promptContent;
```

`PublishProductChanged` nested class 뒤에 추가:

```java
	@Nested
	@DisplayName("PRODUCT_REVIEW_REQUESTED 이벤트 발행")
	class PublishReviewRequested {

		@Test
		@DisplayName("상품 스냅샷과 presign된 이미지 URL을 payload로 담아 발행한다")
		void publishReviewRequested_sendsEnvelopeWithSnapshot() {
			Product product = Product.create(PRODUCT_ID, UUID.randomUUID(), promptContent());

			productEventProducer.publishReviewRequested(
				product, "https://s3/presigned-thumb", List.of("https://s3/presigned-1"));

			EventMessage<?> message = captureMessage();
			assertThat(message.eventType()).isEqualTo("PRODUCT_REVIEW_REQUESTED");
			assertThat(message.aggregateType()).isEqualTo("PRODUCT");
			assertThat(message.aggregateId()).isEqualTo(PRODUCT_ID);
			assertThat(message.payload()).isInstanceOf(ProductReviewRequestedPayload.class);
			ProductReviewRequestedPayload payload = (ProductReviewRequestedPayload) message.payload();
			assertThat(payload.productId()).isEqualTo(PRODUCT_ID);
			assertThat(payload.productType()).isEqualTo("PROMPT");
			assertThat(payload.name()).isEqualTo("제목");
			assertThat(payload.thumbnailUrl()).isEqualTo("https://s3/presigned-thumb");
			assertThat(payload.imageUrls()).containsExactly("https://s3/presigned-1");
		}
	}
```

`import java.util.List;`가 이미 있는지 확인(없으면 추가).

- [ ] **Step 2: 테스트 실행해 실패 확인**

```bash
./gradlew :product-service:test --tests "com.prompthub.product.infra.messaging.producer.ProductEventProducerTest" --no-daemon
```
Expected: FAIL — `ProductEventType.PRODUCT_REVIEW_REQUESTED`, `ProductReviewRequestedPayload`, `publishReviewRequested` 모두 없어 컴파일 에러.

- [ ] **Step 3: 최소 구현 작성**

`ProductEventType.java` 수정:

```java
public enum ProductEventType implements EventType {

	PRODUCT_STOPPED,
	PRODUCT_DELETED,
	PRODUCT_PRICE_CHANGED,
	PRODUCT_CHANGED,
	PRODUCT_REVIEW_REQUESTED;

	@Override
	public String code() {
		return name();
	}

	public static Optional<ProductEventType> from(String code) {
		return Arrays.stream(values()).filter(type -> type.name().equals(code)).findFirst();
	}
}
```

`ProductReviewRequestedPayload.java` 생성:

```java
package com.prompthub.product.infra.messaging.producer.event;

import java.util.List;
import java.util.UUID;

/**
 * PRODUCT_REVIEW_REQUESTED 이벤트 payload. (kafka-event.md §5)
 * ai-service가 재조회 없이 바로 검수할 수 있도록 콘텐츠 스냅샷을 담는다.
 * thumbnailUrl/imageUrls는 raw S3 key가 아니라 발행 시점에 presign된 URL이다.
 */
public record ProductReviewRequestedPayload(
	UUID productId,
	String productType,
	String name,
	String description,
	String content,
	List<String> tags,
	String thumbnailUrl,
	List<String> imageUrls
) {
	public static ProductReviewRequestedPayload of(
		UUID productId, String productType, String name, String description,
		String content, List<String> tags, String thumbnailUrl, List<String> imageUrls
	) {
		return new ProductReviewRequestedPayload(
			productId, productType, name, description, content, tags, thumbnailUrl, imageUrls);
	}
}
```

`ProductEventProducer.java`에 import 추가:
```java
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.infra.messaging.producer.event.ProductReviewRequestedPayload;
import java.util.List;
```

`publishProductChanged` 메서드 아래에 추가:

```java
	public void publishReviewRequested(Product product, String presignedThumbnailUrl, List<String> presignedImageUrls) {
		publish(ProductEventType.PRODUCT_REVIEW_REQUESTED, product.getId(),
			ProductReviewRequestedPayload.of(
				product.getId(),
				product.getProductType().name(),
				product.getName(),
				product.getDescription(),
				product.getContent(),
				product.getTags(),
				presignedThumbnailUrl,
				presignedImageUrls));
	}
```

- [ ] **Step 4: 테스트 실행해 통과 확인**

```bash
./gradlew :product-service:test --tests "com.prompthub.product.infra.messaging.producer.ProductEventProducerTest" --no-daemon
```
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/infra/messaging/producer/ \
  product-service/src/test/java/com/prompthub/product/infra/messaging/producer/ProductEventProducerTest.java
git commit -m "$(cat <<'EOF'
feat: PRODUCT_REVIEW_REQUESTED 이벤트 발행 추가

AI 자동 검수(#597)를 위해 ai-service가 재조회 없이 바로 판정할 수 있도록
상품 콘텐츠 스냅샷(name/description/content/tags/thumbnailUrl/imageUrls)을
payload에 통째로 담아 기존 product-events 토픽에 발행한다. 이벤트 타입만
새로 쪼개는 대신 kafka-event.md 컨벤션대로 토픽은 도메인당 하나로 유지한다.

thumbnailUrl/imageUrls는 아직 raw S3 key 그대로다 — presign은 이 이벤트를
실제로 발행하는 지점(submitForReview, 다음 Task)에서 하므로 여기 producer
메서드는 이미 presign된 URL을 그대로 받아 담기만 한다.
EOF
)"
```

---

## Task 3: `submitForReview` — presign 후 이벤트 발행 연동

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/application/service/ProductSellerService.java`
- Test: `product-service/src/test/java/com/prompthub/product/application/service/ProductSellerServiceTest.java`

**Interfaces:**
- Consumes: `StorageClient.generatePresignedDownloadUrl(String key)`(기존), `ProductEventProducer.publishReviewRequested(...)`(Task 2)

- [ ] **Step 1: 실패하는 테스트 작성**

`ProductSellerServiceTest.java`의 `UpdateProduct` nested class 앞(또는 뒤)에 추가:

```java
	@Nested
	@DisplayName("검수 제출")
	class SubmitForReview {

		@Test
		@DisplayName("썸네일/이미지를 presign해 PRODUCT_REVIEW_REQUESTED를 발행한다")
		void submitForReview_presignsImagesAndPublishesEvent() {
			Product product = product(PRODUCT_ID, null, ProductStatus.DRAFT, (short) 1, (short) 0);
			ReflectionTestUtils.setField(product, "thumbnailUrl", "products/1/thumbnail/a.png");
			ReflectionTestUtils.setField(product, "imageUrls", List.of("products/1/image/b.png"));
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(storageClient.generatePresignedDownloadUrl("products/1/thumbnail/a.png"))
				.willReturn("https://s3/presigned-thumb");
			given(storageClient.generatePresignedDownloadUrl("products/1/image/b.png"))
				.willReturn("https://s3/presigned-image");

			productSellerService.submitForReview(SELLER_ID, PRODUCT_ID);

			assertThat(product.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
			then(productEventProducer).should().publishReviewRequested(
				product, "https://s3/presigned-thumb", List.of("https://s3/presigned-image"));
		}

		@Test
		@DisplayName("썸네일이 없으면 presign 없이 null로 발행한다")
		void submitForReview_withoutThumbnail_publishesNullThumbnail() {
			Product product = product(PRODUCT_ID, null, ProductStatus.DRAFT, (short) 1, (short) 0);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			productSellerService.submitForReview(SELLER_ID, PRODUCT_ID);

			then(productEventProducer).should().publishReviewRequested(product, null, List.of());
			then(storageClient).shouldHaveNoInteractions();
		}
	}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

```bash
./gradlew :product-service:test --tests "com.prompthub.product.application.service.ProductSellerServiceTest" --no-daemon
```
Expected: FAIL — `submitForReview`가 아직 이벤트를 발행하지 않아 `publishReviewRequested` 호출 검증 실패.

- [ ] **Step 3: 최소 구현 작성**

`ProductSellerService.java`의 `submitForReview` 메서드를 교체:

```java
	@Override
	public void submitForReview(UUID sellerId, UUID productId) {
		Product product = getProductForSeller(sellerId, productId);
		product.submitForReview();
		productRepository.save(product);

		String presignedThumbnailUrl = presignOrNull(product.getThumbnailUrl());
		List<String> presignedImageUrls = presignAll(product.getImageUrls());
		productEventProducer.publishReviewRequested(product, presignedThumbnailUrl, presignedImageUrls);
	}

	private String presignOrNull(String key) {
		return (key == null || key.isBlank()) ? null : storageClient.generatePresignedDownloadUrl(key);
	}

	private List<String> presignAll(List<String> keys) {
		if (keys == null || keys.isEmpty()) {
			return List.of();
		}
		return keys.stream().map(storageClient::generatePresignedDownloadUrl).toList();
	}
```

- [ ] **Step 4: 테스트 실행해 통과 확인**

```bash
./gradlew :product-service:test --tests "com.prompthub.product.application.service.ProductSellerServiceTest" --no-daemon
```
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/application/service/ProductSellerService.java \
  product-service/src/test/java/com/prompthub/product/application/service/ProductSellerServiceTest.java
git commit -m "$(cat <<'EOF'
feat: submitForReview 시 이미지 presign 후 검수 요청 이벤트 발행

Product 엔티티의 thumbnailUrl/imageUrls는 raw S3 key라 ai-service가 그대로는
못 받아온다(비공개 버킷). 기존 응답 변환 시점마다 하던 것과 동일하게
StorageClient.generatePresignedDownloadUrl로 presign한 뒤에야 이벤트
payload에 넣는다.

30분 GET presign 만료 안에 정상 처리되면 문제 없고, DLT 재처리처럼 지연되는
예외 상황에서만 걸린다 — 기존 시스템도 동일 TTL로 돌고 있어 별도 대응 없이
감수하기로 결정했다(설계 문서 참고).

썸네일이 없는 상품은 presign 호출 자체를 생략하고 null로 발행한다.
EOF
)"
```

---

## Task 4: product-service — `ai-events` 소비 & 검수 결과 반영

**Files:**
- Create: `product-service/src/main/java/com/prompthub/product/infra/messaging/consumer/ai/AiEventType.java`
- Create: `product-service/src/main/java/com/prompthub/product/infra/messaging/consumer/ai/ProductInspectionResultConsumer.java`
- Test: `product-service/src/test/java/com/prompthub/product/infra/messaging/consumer/ai/ProductInspectionResultConsumerTest.java`
- Create: `product-service/src/main/java/com/prompthub/product/application/service/ProductInspectionResultHandler.java`
- Test: `product-service/src/test/java/com/prompthub/product/application/service/ProductInspectionResultHandlerTest.java`
- Modify: `product-service/src/main/java/com/prompthub/product/infra/messaging/config/KafkaConfig.java`

**Interfaces:**
- Consumes: `Product.approve()`/`reject(String)`(Task 1)
- Produces: 없음(최종 소비자)

- [ ] **Step 1: Handler 실패하는 테스트 작성**

`ProductInspectionResultHandlerTest.java` 생성:

```java
package com.prompthub.product.application.service;

import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProductRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductInspectionResultHandlerTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Mock
	private ProductRepository productRepository;

	private ProductInspectionResultHandler handler;

	@Nested
	@DisplayName("승인/반려 반영")
	class Apply {

		@Test
		@DisplayName("approved=true면 PENDING_REVIEW 상품을 ON_SALE로 전이한다")
		void apply_approved_transitionsToOnSale() {
			handler = new ProductInspectionResultHandler(productRepository);
			Product product = pendingReviewProduct();
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			handler.apply(PRODUCT_ID, true, null);

			assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
		}

		@Test
		@DisplayName("approved=false면 사유와 함께 REJECTED로 전이한다")
		void apply_rejected_transitionsToRejectedWithReason() {
			handler = new ProductInspectionResultHandler(productRepository);
			Product product = pendingReviewProduct();
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			handler.apply(PRODUCT_ID, false, "금지 콘텐츠 포함");

			assertThat(product.getStatus()).isEqualTo(ProductStatus.REJECTED);
			assertThat(product.getRejectionReason()).isEqualTo("금지 콘텐츠 포함");
		}

		@Test
		@DisplayName("이미 PENDING_REVIEW가 아니면(중복 이벤트) 예외를 던지지 않고 조용히 넘어간다")
		void apply_alreadyProcessed_doesNotThrow() {
			handler = new ProductInspectionResultHandler(productRepository);
			Product product = pendingReviewProduct();
			ReflectionTestUtils.setField(product, "status", ProductStatus.ON_SALE);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			handler.apply(PRODUCT_ID, true, null);

			assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
		}

		@Test
		@DisplayName("상품이 없으면 조용히 넘어간다(삭제된 경우)")
		void apply_productNotFound_doesNotThrow() {
			handler = new ProductInspectionResultHandler(productRepository);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.empty());

			assertThatCode(() -> handler.apply(PRODUCT_ID, true, null)).doesNotThrowAnyException();
		}
	}

	private Product pendingReviewProduct() {
		Product product = Product.create(PRODUCT_ID, UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "status", ProductStatus.PENDING_REVIEW);
		return product;
	}
}
```

`import static org.assertj.core.api.Assertions.assertThatCode;` 을 static import 목록에 추가하는 것을 잊지 않는다.

- [ ] **Step 2: 테스트 실행해 실패 확인**

```bash
./gradlew :product-service:test --tests "com.prompthub.product.application.service.ProductInspectionResultHandlerTest" --no-daemon
```
Expected: FAIL — `ProductInspectionResultHandler` 클래스 없음(컴파일 에러).

- [ ] **Step 3: Handler 최소 구현 작성**

`ProductInspectionResultHandler.java` 생성:

```java
package com.prompthub.product.application.service;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.repository.ProductRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ai-events(PRODUCT_INSPECTION_COMPLETED) 처리. (kafka-event.md §7)
 * Product.approve()/reject()가 PENDING_REVIEW 가드를 갖고 있어 자연 멱등이다 —
 * 이미 처리된 상품에 대한 중복 이벤트는 IllegalStateException을 잡아 조용히 스킵한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductInspectionResultHandler {

	private final ProductRepository productRepository;

	@Transactional
	public void apply(UUID productId, boolean approved, String rejectionReason) {
		Product product = productRepository.findById(productId).orElse(null);
		if (product == null) {
			log.info("검수 대상 상품을 찾을 수 없어 결과를 스킵함. productId={}", productId);
			return;
		}
		try {
			if (approved) {
				product.approve();
			} else {
				product.reject(rejectionReason);
			}
		} catch (IllegalStateException e) {
			log.info("이미 처리된 검수 결과라 스킵함. productId={}, currentStatus={}",
				productId, product.getStatus());
			return;
		}
		productRepository.save(product);
	}
}
```

- [ ] **Step 4: Handler 테스트 실행해 통과 확인**

```bash
./gradlew :product-service:test --tests "com.prompthub.product.application.service.ProductInspectionResultHandlerTest" --no-daemon
```
Expected: PASS

- [ ] **Step 5: Consumer 실패하는 테스트 작성**

`ProductInspectionResultConsumerTest.java` 생성(`OrderEventConsumerTest`와 동일 스타일):

```java
package com.prompthub.product.infra.messaging.consumer.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.prompthub.product.application.service.ProductInspectionResultHandler;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ProductInspectionResultConsumerTest {

	private static final UUID EVENT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Mock
	private ProductInspectionResultHandler handler;

	@Mock
	private Acknowledgment acknowledgment;

	private ProductInspectionResultConsumer consumer;

	@BeforeEach
	void setUp() {
		consumer = new ProductInspectionResultConsumer(new ObjectMapper(), handler);
	}

	@Test
	@DisplayName("PRODUCT_INSPECTION_COMPLETED(승인)를 수신하면 approved=true로 handler를 호출한다")
	void consume_approved_callsHandler() {
		String message = eventMessage(true, null);

		consumer.consume(message, acknowledgment);

		then(handler).should().apply(PRODUCT_ID, true, null);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("PRODUCT_INSPECTION_COMPLETED(반려)를 수신하면 사유와 함께 handler를 호출한다")
	void consume_rejected_callsHandlerWithReason() {
		String message = eventMessage(false, "금지 콘텐츠");

		consumer.consume(message, acknowledgment);

		then(handler).should().apply(eq(PRODUCT_ID), eq(false), eq("금지 콘텐츠"));
		then(acknowledgment).should().acknowledge();
	}

	@Nested
	@DisplayName("예외/비지원 케이스")
	class EdgeCases {

		@Test
		@DisplayName("지원하지 않는 eventType은 handler를 호출하지 않고 acknowledge한다(DLT 아님)")
		void consume_unsupportedEventType_acknowledge() {
			String message = """
				{"eventId":"99999999-9999-9999-9999-999999999999","eventType":"SOMETHING_ELSE",\
				"aggregateType":"PRODUCT","payload":{"productId":"11111111-1111-1111-1111-111111111111"}}
				""";

			consumer.consume(message, acknowledgment);

			then(handler).shouldHaveNoInteractions();
			then(acknowledgment).should().acknowledge();
		}

		@Test
		@DisplayName("eventId가 없으면 예외를 던져 DLT로 보낸다")
		void consume_missingEventId_throws() {
			String message = """
				{"eventType":"PRODUCT_INSPECTION_COMPLETED","payload":{"productId":"11111111-1111-1111-1111-111111111111","approved":true}}
				""";

			assertThatThrownBy(() -> consumer.consume(message, acknowledgment))
				.isInstanceOf(IllegalArgumentException.class);
			then(handler).should(never()).apply(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any());
			then(acknowledgment).should(never()).acknowledge();
		}

		@Test
		@DisplayName("handler가 IllegalStateException(중복 처리됨)을 던지면 acknowledge하고 DLT로 보내지 않는다")
		void consume_alreadyProcessed_acknowledgesWithoutDlt() {
			org.mockito.BDDMockito.willThrow(new IllegalStateException("이미 처리됨"))
				.given(handler).apply(PRODUCT_ID, true, null);
			String message = eventMessage(true, null);

			consumer.consume(message, acknowledgment);

			then(acknowledgment).should().acknowledge();
		}
	}

	private String eventMessage(boolean approved, String rejectionReason) {
		String reasonField = rejectionReason == null ? "null" : "\"" + rejectionReason + "\"";
		return "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"PRODUCT_INSPECTION_COMPLETED\","
			+ "\"aggregateType\":\"PRODUCT\",\"payload\":{\"productId\":\"" + PRODUCT_ID
			+ "\",\"approved\":" + approved + ",\"rejectionReason\":" + reasonField + "}}";
	}
}
```

- [ ] **Step 6: 테스트 실행해 실패 확인**

```bash
./gradlew :product-service:test --tests "com.prompthub.product.infra.messaging.consumer.ai.ProductInspectionResultConsumerTest" --no-daemon
```
Expected: FAIL — `AiEventType`, `ProductInspectionResultConsumer` 없음(컴파일 에러).

- [ ] **Step 7: Consumer 최소 구현 작성**

`AiEventType.java` 생성:

```java
package com.prompthub.product.infra.messaging.consumer.ai;

import com.prompthub.common.event.EventType;
import java.util.Arrays;
import java.util.Optional;

/**
 * product-service가 소비하는 ai-events 의 지원 이벤트 타입. (kafka-event.md §4)
 */
public enum AiEventType implements EventType {

	PRODUCT_INSPECTION_COMPLETED;

	@Override
	public String code() {
		return name();
	}

	public static Optional<AiEventType> from(String code) {
		return Arrays.stream(values())
			.filter(type -> type.name().equals(code))
			.findFirst();
	}
}
```

`ProductInspectionResultConsumer.java` 생성:

```java
package com.prompthub.product.infra.messaging.consumer.ai;

import com.prompthub.common.event.EventMessage;
import com.prompthub.product.application.service.ProductInspectionResultHandler;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ai-events 소비 어댑터. (kafka-event.md §7)
 * 미지원 eventType 은 로그+Ack(DLT 아님). handler가 던지는 IllegalStateException(중복/이미
 * 처리된 상품)도 로그+Ack로 흡수한다 — 정상적인 중복 이벤트이지 처리 실패가 아니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductInspectionResultConsumer {

	private final ObjectMapper objectMapper;
	private final ProductInspectionResultHandler productInspectionResultHandler;

	@KafkaListener(
		topics = "ai-events",
		groupId = "product-service",
		containerFactory = "aiEventContainerFactory"
	)
	public void consume(String message, Acknowledgment acknowledgment) {
		EventMessage<JsonNode> event = parse(message);

		if (event.eventId() == null || event.eventType() == null) {
			throw new IllegalArgumentException("eventId/eventType 이 없는 ai 이벤트");
		}

		Optional<AiEventType> eventType = AiEventType.from(event.eventType());
		if (eventType.isEmpty()) {
			log.info("처리하지 않는 ai 이벤트 타입. eventId={}, eventType={}", event.eventId(), event.eventType());
			acknowledgment.acknowledge();
			return;
		}

		JsonNode payload = event.payload();
		UUID productId = UUID.fromString(payload.path("productId").stringValue(null));
		boolean approved = payload.path("approved").asBoolean(false);
		String rejectionReason = payload.path("rejectionReason").stringValue(null);

		try {
			productInspectionResultHandler.apply(productId, approved, rejectionReason);
		} catch (IllegalStateException e) {
			log.info("이미 처리된 상품 검수 결과라 스킵함. productId={}", productId);
		}
		acknowledgment.acknowledge();
	}

	private EventMessage<JsonNode> parse(String message) {
		try {
			return objectMapper.readValue(message, new TypeReference<EventMessage<JsonNode>>() {
			});
		} catch (Exception e) {
			throw new IllegalArgumentException("ai 이벤트 역직렬화 실패", e);
		}
	}
}
```

- [ ] **Step 8: KafkaConfig에 `ai-events` 소비용 factory 추가**

`KafkaConfig.java`의 `productEventErrorHandler` 빈 정의 뒤에 추가:

```java
	// ai-events 소비(#597 AI 상품 검수) — order-events/product-events 소비와 다른 별도
	// consumer group("product-service")을 이미 order-events와 공유하지만, 토픽이 달라 리밸런싱은 분리된다.
	@Bean
	public ConsumerFactory<String, String> aiEventConsumerFactory() {
		Map<String, Object> config = new HashMap<>();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
		config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enableAutoCommit);
		config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		return new DefaultKafkaConsumerFactory<>(config);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, String> aiEventContainerFactory(
		ConsumerFactory<String, String> aiEventConsumerFactory,
		DefaultErrorHandler aiEventErrorHandler
	) {
		ConcurrentKafkaListenerContainerFactory<String, String> factory =
			new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(aiEventConsumerFactory);
		factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
		factory.setCommonErrorHandler(aiEventErrorHandler);
		return factory;
	}

	// 처리 실패 이벤트는 재시도 후 원본 토픽의 DLT(`ai-events.DLT`)로 보낸다. (kafka-event.md §7)
	@Bean
	public DefaultErrorHandler aiEventErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
			kafkaTemplate,
			(record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition())
		);
		return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
	}
```

- [ ] **Step 9: 테스트 실행해 통과 확인**

```bash
./gradlew :product-service:test --tests "com.prompthub.product.infra.messaging.consumer.ai.ProductInspectionResultConsumerTest" --no-daemon
./gradlew :product-service:build --no-daemon
```
Expected: PASS, `KafkaConfig` 포함 전체 빌드 성공(checkstyle 포함).

- [ ] **Step 10: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/infra/messaging/consumer/ai/ \
  product-service/src/test/java/com/prompthub/product/infra/messaging/consumer/ai/ \
  product-service/src/main/java/com/prompthub/product/application/service/ProductInspectionResultHandler.java \
  product-service/src/test/java/com/prompthub/product/application/service/ProductInspectionResultHandlerTest.java \
  product-service/src/main/java/com/prompthub/product/infra/messaging/config/KafkaConfig.java
git commit -m "$(cat <<'EOF'
feat: ai-events(PRODUCT_INSPECTION_COMPLETED) 소비 후 상품 상태 반영

ai-service의 검수 판정을 반영하는 마지막 고리다. OrderEventConsumer와
동일하게 컨슈머는 얇게 유지하고(EventMessage<JsonNode> 파싱 → eventType
확인 → handler 호출), 상태 전이 자체는 ProductInspectionResultHandler가
Product.approve()/reject()로 처리한다.

별도 이벤트 처리 이력 테이블은 두지 않았다 — approve()/reject()가 이미
PENDING_REVIEW 가드로 자연 멱등이라, handler에서 IllegalStateException만
잡아 중복 이벤트를 스킵하면 충분하다(kafka-event.md §7의 "자연 멱등이면
이력 불필요" 조건). 이 예외는 처리 실패가 아니라 정상적인 중복 케이스라
DLT로 보내지 않고 로그만 남기고 ack한다 — 미지원 eventType과 같은 취급이다.

product-events 소비(order-events)와는 별도 consumer factory(ai-events용
DLT 파트너 `ai-events.DLT`)를 KafkaConfig에 추가했다.
EOF
)"
```

---

## Task 5: ai-service — Kafka 인프라 스캐폴딩(최초 도입)

**Files:**
- Modify: `build.gradle`(레포 루트) — Kafka 사용 서비스 목록에 `ai-service` 추가
- Modify: `ai-service/src/main/resources/application-local.yml` — `spring.kafka.*` 추가
- Create: `ai-service/src/main/java/com/prompthub/ai/inspection/infrastructure/messaging/kafka/config/KafkaConfig.java`

**Interfaces:**
- Produces: `KafkaTemplate<String, Object> kafkaTemplate` 빈(Task 6이 사용), `productEventContainerFactory` 컨테이너 팩토리(Task 8이 사용)

이 Task는 순수 설정/스캐폴딩이라 브랜치 로직이 없다 — 별도 단위 테스트 대신 모듈 빌드로 검증한다(product-service의 `KafkaConfig`도 동일하게 전용 단위 테스트가 없다).

- [ ] **Step 1: 루트 `build.gradle` 수정**

`build.gradle`의 "Kafka 사용 서비스 4개" 블록(134~145행)을 아래로 교체:

```groovy
// ── Kafka 사용 서비스 5개 (user-service는 이벤트 미사용) ────────────────────
configure([
    project(':order-service'),
    project(':payment-service'),
    project(':product-service'),
    project(':settlement-service'),
    project(':ai-service')
]) {
    dependencies {
        implementation 'org.springframework.boot:spring-boot-starter-kafka'
        testImplementation 'org.springframework.kafka:spring-kafka-test'
    }
}
```

- [ ] **Step 2: ai-service Kafka 연결 설정 추가**

`ai-service/src/main/resources/application-local.yml`의 `spring:` 블록에 추가(기존 `data.redis`, `ai.openai` 항목과 같은 레벨):

```yaml
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: ai-service
      auto-offset-reset: earliest
      enable-auto-commit: false
```

- [ ] **Step 3: KafkaConfig 작성**

`ai-service/src/main/java/com/prompthub/ai/inspection/infrastructure/messaging/kafka/config/KafkaConfig.java` 생성(product-service `KafkaConfig`와 동일 패턴, producer는 `ai-events` 발행용, consumer는 `product-events` 구독용):

```java
package com.prompthub.ai.inspection.infrastructure.messaging.kafka.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

@EnableKafka
@Configuration
public class KafkaConfig {

	@Value("${spring.kafka.bootstrap-servers}")
	private String bootstrapServers;

	@Value("${spring.kafka.consumer.group-id}")
	private String groupId;

	@Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
	private String autoOffsetReset;

	@Value("${spring.kafka.consumer.enable-auto-commit:false}")
	private boolean enableAutoCommit;

	@Bean
	public ProducerFactory<String, Object> producerFactory() {
		Map<String, Object> config = new HashMap<>();
		config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
		return new DefaultKafkaProducerFactory<>(config);
	}

	@Bean
	public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
		return new KafkaTemplate<>(producerFactory);
	}

	@Bean
	public ConsumerFactory<String, String> productEventConsumerFactory() {
		Map<String, Object> config = new HashMap<>();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
		config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enableAutoCommit);
		config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		return new DefaultKafkaConsumerFactory<>(config);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, String> productEventContainerFactory(
		ConsumerFactory<String, String> productEventConsumerFactory,
		DefaultErrorHandler productEventErrorHandler
	) {
		ConcurrentKafkaListenerContainerFactory<String, String> factory =
			new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(productEventConsumerFactory);
		factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
		factory.setCommonErrorHandler(productEventErrorHandler);
		return factory;
	}

	// 처리 실패 이벤트는 재시도 후 원본 토픽의 DLT(`product-events.DLT`)로 보낸다. (kafka-event.md §7)
	@Bean
	public DefaultErrorHandler productEventErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
			kafkaTemplate,
			(record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition())
		);
		return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
	}
}
```

- [ ] **Step 4: 빌드로 검증**

```bash
./gradlew :ai-service:build --no-daemon
```
Expected: BUILD SUCCESSFUL (Kafka 의존성 다운로드 + `KafkaConfig` 컴파일 확인)

- [ ] **Step 5: 커밋**

```bash
git add build.gradle ai-service/src/main/resources/application-local.yml \
  ai-service/src/main/java/com/prompthub/ai/inspection/infrastructure/messaging/kafka/config/KafkaConfig.java
git commit -m "$(cat <<'EOF'
feat: ai-service에 Kafka 의존성/설정 추가(product-events 소비, ai-events 발행 준비)

AI 상품 자동 검수(#597)를 위해 ai-service가 처음으로 Kafka consumer+producer를
동시에 갖는다. 지금까지 ai-service는 정산 도메인에서 Redis pub/sub(SSE
스트리밍용)과 gRPC(user-service 동기 조회)만 썼고 Kafka는 전혀 쓰지
않았다 — 루트 build.gradle의 "Kafka 사용 서비스" 목록에도 빠져 있었다.

product-service KafkaConfig와 동일한 패턴(EventMessage 봉투, JacksonJsonSerializer,
MANUAL ack, FixedBackOff 1s×2 재시도 후 {topic}.DLT)을 그대로 재사용한다 —
서비스마다 다른 재시도/DLT 정책을 만들지 않기 위해서다.

이 커밋은 순수 설정/스캐폴딩이라 분기 로직이 없다. product-service의
KafkaConfig도 전용 단위 테스트가 없는 것과 동일하게, 이 클래스는 모듈
빌드로만 검증한다.
EOF
)"
```

---

## Task 6: ai-service — `ai-events`(PRODUCT_INSPECTION_COMPLETED) 발행

**Files:**
- Create: `ai-service/src/main/java/com/prompthub/ai/inspection/infrastructure/messaging/kafka/producer/InspectionEventType.java`
- Create: `ai-service/src/main/java/com/prompthub/ai/inspection/infrastructure/messaging/kafka/producer/event/ProductInspectionCompletedPayload.java`
- Create: `ai-service/src/main/java/com/prompthub/ai/inspection/infrastructure/messaging/kafka/producer/InspectionEventProducer.java`
- Test: `ai-service/src/test/java/com/prompthub/ai/inspection/infrastructure/messaging/kafka/producer/InspectionEventProducerTest.java`

**Interfaces:**
- Produces: `InspectionEventProducer.publish(UUID productId, boolean approved, String rejectionReason)` — Task 7이 사용.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;

import com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer.event.ProductInspectionCompletedPayload;
import com.prompthub.common.event.EventMessage;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class InspectionEventProducerTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final String TOPIC = "ai-events";

	@Mock
	private KafkaTemplate<String, Object> kafkaTemplate;

	@InjectMocks
	private InspectionEventProducer inspectionEventProducer;

	private EventMessage<?> captureMessage() {
		ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
		then(kafkaTemplate).should().send(eq(TOPIC), eq(PRODUCT_ID.toString()), captor.capture());
		return (EventMessage<?>) captor.getValue();
	}

	@Nested
	@DisplayName("PRODUCT_INSPECTION_COMPLETED 이벤트 발행")
	class Publish {

		@Test
		@DisplayName("승인 결과를 EventMessage 봉투로 감싸 ai-events에 발행한다")
		void publish_approved_sendsEnvelope() {
			inspectionEventProducer.publish(PRODUCT_ID, true, null);

			EventMessage<?> message = captureMessage();
			assertThat(message.eventId()).isNotNull();
			assertThat(message.eventType()).isEqualTo("PRODUCT_INSPECTION_COMPLETED");
			assertThat(message.aggregateType()).isEqualTo("PRODUCT");
			assertThat(message.aggregateId()).isEqualTo(PRODUCT_ID);
			assertThat(message.payload()).isInstanceOf(ProductInspectionCompletedPayload.class);
			ProductInspectionCompletedPayload payload = (ProductInspectionCompletedPayload) message.payload();
			assertThat(payload.productId()).isEqualTo(PRODUCT_ID);
			assertThat(payload.approved()).isTrue();
			assertThat(payload.rejectionReason()).isNull();
		}

		@Test
		@DisplayName("반려 결과를 사유와 함께 발행한다")
		void publish_rejected_sendsEnvelopeWithReason() {
			inspectionEventProducer.publish(PRODUCT_ID, false, "금지 콘텐츠 포함");

			EventMessage<?> message = captureMessage();
			ProductInspectionCompletedPayload payload = (ProductInspectionCompletedPayload) message.payload();
			assertThat(payload.approved()).isFalse();
			assertThat(payload.rejectionReason()).isEqualTo("금지 콘텐츠 포함");
		}
	}
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer.InspectionEventProducerTest" --no-daemon
```
Expected: FAIL — 클래스들이 없어 컴파일 에러.

- [ ] **Step 3: 최소 구현 작성**

`InspectionEventType.java`:

```java
package com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer;

import com.prompthub.common.event.EventType;

/**
 * ai-service가 발행하는 ai-events 의 이벤트 타입. (kafka-event.md §4)
 */
public enum InspectionEventType implements EventType {

	PRODUCT_INSPECTION_COMPLETED;

	@Override
	public String code() {
		return name();
	}
}
```

`event/ProductInspectionCompletedPayload.java`:

```java
package com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer.event;

import java.util.UUID;

/**
 * PRODUCT_INSPECTION_COMPLETED 이벤트 payload. (kafka-event.md §5)
 * 필드명(productId/approved/rejectionReason)은 product-service 소비측 계약이므로 변경하지 않는다.
 */
public record ProductInspectionCompletedPayload(
	UUID productId,
	boolean approved,
	String rejectionReason
) {
	public static ProductInspectionCompletedPayload of(UUID productId, boolean approved, String rejectionReason) {
		return new ProductInspectionCompletedPayload(productId, approved, rejectionReason);
	}
}
```

`InspectionEventProducer.java`:

```java
package com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer;

import com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer.event.ProductInspectionCompletedPayload;
import com.prompthub.common.event.EventMessage;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * ai-events 발행 어댑터. (kafka-event.md §6)
 * ai-service는 이 발행에 앞선 DB 트랜잭션이 없으므로(무상태 검수) product-service의
 * ProductEventProducer와 달리 AFTER_COMMIT 지연 없이 즉시 발행한다.
 */
@Component
@RequiredArgsConstructor
public class InspectionEventProducer {

	private static final String TOPIC = "ai-events";
	private static final String AGGREGATE_TYPE = "PRODUCT";

	private final KafkaTemplate<String, Object> kafkaTemplate;

	public void publish(UUID productId, boolean approved, String rejectionReason) {
		EventMessage<Object> message = new EventMessage<>(
			UUID.randomUUID(),
			InspectionEventType.PRODUCT_INSPECTION_COMPLETED.code(),
			LocalDateTime.now(),
			AGGREGATE_TYPE,
			productId,
			ProductInspectionCompletedPayload.of(productId, approved, rejectionReason)
		);
		kafkaTemplate.send(TOPIC, productId.toString(), message);
	}
}
```

- [ ] **Step 4: 테스트 실행해 통과 확인**

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer.InspectionEventProducerTest" --no-daemon
```
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add ai-service/src/main/java/com/prompthub/ai/inspection/infrastructure/messaging/kafka/producer/ \
  ai-service/src/test/java/com/prompthub/ai/inspection/infrastructure/messaging/kafka/producer/
git commit -m "$(cat <<'EOF'
feat: ai-service에 PRODUCT_INSPECTION_COMPLETED 이벤트 발행 추가

검수 판정을 product-service에 돌려주는 발행측이다. 실패 시 별도 "검수 실패"
이벤트는 만들지 않는다 — 상품은 PENDING_REVIEW에 그대로 남는 게 안전한
기본값이고, 자동 ON_SALE(위험)도 거짓 REJECTED(오탐 신호)도 실패의 대안이
될 수 없다. 실패는 컨슈머 쪽 재시도/DLT로만 신호한다(설계 문서 "실패 처리"
참고).

product-service의 ProductEventProducer와 달리 AFTER_COMMIT 지연 발행을
하지 않는다 — 이 발행 앞에 DB 트랜잭션이 없는 무상태 검수라 즉시 발행이
곧 정확한 시점이다.
EOF
)"
```

---

## Task 7: ai-service — 검수 유스케이스(`inspection` 애플리케이션 계층)

**Files:**
- Create: `ai-service/src/main/java/com/prompthub/ai/inspection/domain/model/InspectionVerdict.java`
- Create: `ai-service/src/main/java/com/prompthub/ai/inspection/application/dto/ProductInspectionRequest.java`
- Create: `ai-service/src/main/java/com/prompthub/ai/inspection/application/port/ProductInspectionAiPort.java`
- Create: `ai-service/src/main/java/com/prompthub/ai/inspection/application/usecase/ProductInspectionUseCase.java`
- Create: `ai-service/src/main/java/com/prompthub/ai/inspection/application/service/ProductInspectionService.java`
- Test: `ai-service/src/test/java/com/prompthub/ai/inspection/application/service/ProductInspectionServiceTest.java`

**Interfaces:**
- Consumes: `InspectionEventProducer.publish(...)`(Task 6)
- Produces: `ProductInspectionUseCase.inspect(ProductInspectionRequest request)` — Task 8(consumer)이 호출. `ProductInspectionAiPort.inspect(ProductInspectionRequest request): InspectionVerdict` — Task 9(OpenAI 어댑터)가 구현.

- [ ] **Step 1: 값 타입/포트/유스케이스 인터페이스 작성**

`domain/model/InspectionVerdict.java`:

```java
package com.prompthub.ai.inspection.domain.model;

/**
 * AI 검수 판정 결과. approved=false일 때만 rejectionReason이 채워진다.
 */
public record InspectionVerdict(boolean approved, String rejectionReason) {
}
```

`application/dto/ProductInspectionRequest.java`:

```java
package com.prompthub.ai.inspection.application.dto;

import java.util.List;
import java.util.UUID;

/**
 * PRODUCT_REVIEW_REQUESTED 이벤트 payload를 애플리케이션 계층 입력으로 옮긴 값.
 * thumbnailUrl/imageUrls는 product-service가 presign한 URL이다.
 */
public record ProductInspectionRequest(
	UUID productId,
	String productType,
	String name,
	String description,
	String content,
	List<String> tags,
	String thumbnailUrl,
	List<String> imageUrls
) {
}
```

`application/port/ProductInspectionAiPort.java`:

```java
package com.prompthub.ai.inspection.application.port;

import com.prompthub.ai.inspection.application.dto.ProductInspectionRequest;
import com.prompthub.ai.inspection.domain.model.InspectionVerdict;

public interface ProductInspectionAiPort {

	InspectionVerdict inspect(ProductInspectionRequest request);
}
```

`application/usecase/ProductInspectionUseCase.java`:

```java
package com.prompthub.ai.inspection.application.usecase;

import com.prompthub.ai.inspection.application.dto.ProductInspectionRequest;

public interface ProductInspectionUseCase {

	void inspect(ProductInspectionRequest request);
}
```

- [ ] **Step 2: Service 실패하는 테스트 작성**

```java
package com.prompthub.ai.inspection.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.inspection.application.dto.ProductInspectionRequest;
import com.prompthub.ai.inspection.application.port.ProductInspectionAiPort;
import com.prompthub.ai.inspection.domain.model.InspectionVerdict;
import com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer.InspectionEventProducer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class ProductInspectionServiceTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Mock
	private ProductInspectionAiPort aiPort;

	@Mock
	private InspectionEventProducer inspectionEventProducer;

	@InjectMocks
	private ProductInspectionService productInspectionService;

	@Test
	@DisplayName("AI가 승인하면 approved=true, 사유 없이 이벤트를 발행한다")
	void inspect_approved_publishesApprovedEvent() {
		given(aiPort.inspect(any())).willReturn(new InspectionVerdict(true, null));

		productInspectionService.inspect(request());

		then(inspectionEventProducer).should().publish(PRODUCT_ID, true, null);
	}

	@Test
	@DisplayName("AI가 반려하면 approved=false, 사유와 함께 이벤트를 발행한다")
	void inspect_rejected_publishesRejectedEventWithReason() {
		given(aiPort.inspect(any())).willReturn(new InspectionVerdict(false, "금지 콘텐츠 포함"));

		productInspectionService.inspect(request());

		then(inspectionEventProducer).should().publish(PRODUCT_ID, false, "금지 콘텐츠 포함");
	}

	@Test
	@DisplayName("AI 호출이 실패하면 예외를 그대로 전파하고 이벤트를 발행하지 않는다(재시도/DLT는 상위에서 처리)")
	void inspect_aiPortThrows_propagatesWithoutPublishing() {
		willThrow(new AiException(AiErrorCode.AI_PROVIDER_UNAVAILABLE)).given(aiPort).inspect(any());

		assertThatThrownBy(() -> productInspectionService.inspect(request()))
			.isInstanceOf(AiException.class);
		then(inspectionEventProducer).shouldHaveNoInteractions();
	}

	private ProductInspectionRequest request() {
		return new ProductInspectionRequest(
			PRODUCT_ID, "PROMPT", "제목", "설명", "content", List.of("tag1"),
			"https://s3/presigned-thumb", List.of("https://s3/presigned-1"));
	}
}
```

- [ ] **Step 3: 테스트 실행해 실패 확인**

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.inspection.application.service.ProductInspectionServiceTest" --no-daemon
```
Expected: FAIL — `ProductInspectionService` 없음(컴파일 에러).

- [ ] **Step 4: Service 최소 구현 작성**

```java
package com.prompthub.ai.inspection.application.service;

import com.prompthub.ai.inspection.application.dto.ProductInspectionRequest;
import com.prompthub.ai.inspection.application.port.ProductInspectionAiPort;
import com.prompthub.ai.inspection.application.usecase.ProductInspectionUseCase;
import com.prompthub.ai.inspection.domain.model.InspectionVerdict;
import com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer.InspectionEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductInspectionService implements ProductInspectionUseCase {

	private final ProductInspectionAiPort aiPort;
	private final InspectionEventProducer inspectionEventProducer;

	@Override
	public void inspect(ProductInspectionRequest request) {
		InspectionVerdict verdict = aiPort.inspect(request);
		inspectionEventProducer.publish(request.productId(), verdict.approved(), verdict.rejectionReason());
		log.info("상품 검수 완료. productId={}, approved={}", request.productId(), verdict.approved());
	}
}
```

- [ ] **Step 5: 테스트 실행해 통과 확인**

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.inspection.application.service.ProductInspectionServiceTest" --no-daemon
```
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add ai-service/src/main/java/com/prompthub/ai/inspection/domain/ \
  ai-service/src/main/java/com/prompthub/ai/inspection/application/ \
  ai-service/src/test/java/com/prompthub/ai/inspection/application/
git commit -m "$(cat <<'EOF'
feat: 상품 검수 유스케이스(ProductInspectionService) 추가

검수 요청 → AI 판정 → 결과 발행을 조율하는 오케스트레이션 계층이다.
실제 AI 호출은 ProductInspectionAiPort 포트 뒤로 숨겨 아직 구현체가 없어도
(다음 Task에서 Spring AI 어댑터로 구현) 이 조율 로직을 독립적으로
테스트할 수 있게 했다 — settlement 도메인의 SettlementAgent 포트 패턴과
동일하다.

AI 포트 호출이 실패하면 예외를 그대로 전파하고 이벤트를 발행하지 않는다.
이 예외는 Kafka 컨슈머까지 올라가 재시도/DLT로 처리된다 — 서비스 계층에서
잡아 흡수하지 않는다.
EOF
)"
```

---

## Task 8: ai-service — `product-events` 소비(`PRODUCT_REVIEW_REQUESTED`)

**Files:**
- Create: `ai-service/src/main/java/com/prompthub/ai/inspection/infrastructure/messaging/kafka/consumer/ProductReviewEventType.java`
- Create: `ai-service/src/main/java/com/prompthub/ai/inspection/infrastructure/messaging/kafka/consumer/ProductReviewRequestedConsumer.java`
- Test: `ai-service/src/test/java/com/prompthub/ai/inspection/infrastructure/messaging/kafka/consumer/ProductReviewRequestedConsumerTest.java`

**Interfaces:**
- Consumes: `ProductInspectionUseCase.inspect(ProductInspectionRequest)`(Task 7)

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.prompthub.ai.inspection.infrastructure.messaging.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.prompthub.ai.inspection.application.dto.ProductInspectionRequest;
import com.prompthub.ai.inspection.application.usecase.ProductInspectionUseCase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ProductReviewRequestedConsumerTest {

	private static final UUID EVENT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Mock
	private ProductInspectionUseCase productInspectionUseCase;

	@Mock
	private Acknowledgment acknowledgment;

	private ProductReviewRequestedConsumer consumer;

	@BeforeEach
	void setUp() {
		consumer = new ProductReviewRequestedConsumer(new ObjectMapper(), productInspectionUseCase);
	}

	@Test
	@DisplayName("PRODUCT_REVIEW_REQUESTED를 수신하면 payload를 파싱해 유스케이스를 호출한다")
	void consume_reviewRequested_callsUseCase() {
		String message = eventMessage();
		ArgumentCaptor<ProductInspectionRequest> captor = ArgumentCaptor.forClass(ProductInspectionRequest.class);

		consumer.consume(message, acknowledgment);

		then(productInspectionUseCase).should().inspect(captor.capture());
		ProductInspectionRequest request = captor.getValue();
		assertThat(request.productId()).isEqualTo(PRODUCT_ID);
		assertThat(request.productType()).isEqualTo("PROMPT");
		assertThat(request.name()).isEqualTo("상품명");
		assertThat(request.tags()).containsExactly("tag1", "tag2");
		assertThat(request.imageUrls()).containsExactly("https://s3/presigned-1");
		then(acknowledgment).should().acknowledge();
	}

	@Nested
	@DisplayName("예외/비지원 케이스")
	class EdgeCases {

		@Test
		@DisplayName("PRODUCT_CHANGED 등 다른 product-events 타입은 유스케이스를 호출하지 않고 acknowledge한다")
		void consume_unsupportedEventType_acknowledge() {
			String message = """
				{"eventId":"99999999-9999-9999-9999-999999999999","eventType":"PRODUCT_CHANGED",\
				"aggregateType":"PRODUCT","payload":{"familyRootId":"11111111-1111-1111-1111-111111111111"}}
				""";

			consumer.consume(message, acknowledgment);

			then(productInspectionUseCase).shouldHaveNoInteractions();
			then(acknowledgment).should().acknowledge();
		}

		@Test
		@DisplayName("eventId가 없으면 예외를 던져 DLT로 보낸다")
		void consume_missingEventId_throws() {
			String message = """
				{"eventType":"PRODUCT_REVIEW_REQUESTED","payload":{"productId":"11111111-1111-1111-1111-111111111111"}}
				""";

			assertThatThrownBy(() -> consumer.consume(message, acknowledgment))
				.isInstanceOf(IllegalArgumentException.class);
			then(productInspectionUseCase).should(never()).inspect(any());
			then(acknowledgment).should(never()).acknowledge();
		}
	}

	private String eventMessage() {
		return "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"PRODUCT_REVIEW_REQUESTED\","
			+ "\"aggregateType\":\"PRODUCT\",\"payload\":{"
			+ "\"productId\":\"" + PRODUCT_ID + "\","
			+ "\"productType\":\"PROMPT\",\"name\":\"상품명\",\"description\":\"설명\",\"content\":\"내용\","
			+ "\"tags\":[\"tag1\",\"tag2\"],"
			+ "\"thumbnailUrl\":\"https://s3/presigned-thumb\","
			+ "\"imageUrls\":[\"https://s3/presigned-1\"]}}";
	}
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.inspection.infrastructure.messaging.kafka.consumer.ProductReviewRequestedConsumerTest" --no-daemon
```
Expected: FAIL — 클래스 없음(컴파일 에러).

- [ ] **Step 3: 최소 구현 작성**

`ProductReviewEventType.java`:

```java
package com.prompthub.ai.inspection.infrastructure.messaging.kafka.consumer;

import com.prompthub.common.event.EventType;
import java.util.Arrays;
import java.util.Optional;

/**
 * ai-service가 소비하는 product-events 중 실제로 처리하는 타입.
 * product-events에는 PRODUCT_STOPPED/DELETED/PRICE_CHANGED/CHANGED도 함께 흐르므로
 * 여기 없는 타입은 지원하지 않음으로 로그+Ack 한다.
 */
public enum ProductReviewEventType implements EventType {

	PRODUCT_REVIEW_REQUESTED;

	@Override
	public String code() {
		return name();
	}

	public static Optional<ProductReviewEventType> from(String code) {
		return Arrays.stream(values())
			.filter(type -> type.name().equals(code))
			.findFirst();
	}
}
```

`ProductReviewRequestedConsumer.java`:

```java
package com.prompthub.ai.inspection.infrastructure.messaging.kafka.consumer;

import com.prompthub.ai.inspection.application.dto.ProductInspectionRequest;
import com.prompthub.ai.inspection.application.usecase.ProductInspectionUseCase;
import com.prompthub.common.event.EventMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * product-events 소비 어댑터. (kafka-event.md §7)
 * PRODUCT_REVIEW_REQUESTED 외 타입(PRODUCT_STOPPED 등)은 로그+Ack(DLT 아님).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductReviewRequestedConsumer {

	private final ObjectMapper objectMapper;
	private final ProductInspectionUseCase productInspectionUseCase;

	@KafkaListener(
		topics = "product-events",
		groupId = "ai-service",
		containerFactory = "productEventContainerFactory"
	)
	public void consume(String message, Acknowledgment acknowledgment) {
		EventMessage<JsonNode> event = parse(message);

		if (event.eventId() == null || event.eventType() == null) {
			throw new IllegalArgumentException("eventId/eventType 이 없는 product 이벤트");
		}

		Optional<ProductReviewEventType> eventType = ProductReviewEventType.from(event.eventType());
		if (eventType.isEmpty()) {
			log.info("처리하지 않는 product 이벤트 타입. eventId={}, eventType={}", event.eventId(), event.eventType());
			acknowledgment.acknowledge();
			return;
		}

		productInspectionUseCase.inspect(toRequest(event.payload()));
		acknowledgment.acknowledge();
	}

	private ProductInspectionRequest toRequest(JsonNode payload) {
		return new ProductInspectionRequest(
			UUID.fromString(payload.path("productId").stringValue(null)),
			payload.path("productType").stringValue(null),
			payload.path("name").stringValue(null),
			payload.path("description").stringValue(null),
			payload.path("content").stringValue(null),
			toStringList(payload.path("tags")),
			payload.path("thumbnailUrl").stringValue(null),
			toStringList(payload.path("imageUrls")));
	}

	private List<String> toStringList(JsonNode arrayNode) {
		List<String> values = new ArrayList<>();
		for (JsonNode element : arrayNode) {
			String value = element.stringValue(null);
			if (value != null) {
				values.add(value);
			}
		}
		return values;
	}

	private EventMessage<JsonNode> parse(String message) {
		try {
			return objectMapper.readValue(message, new TypeReference<EventMessage<JsonNode>>() {
			});
		} catch (Exception e) {
			throw new IllegalArgumentException("product 이벤트 역직렬화 실패", e);
		}
	}
}
```

- [ ] **Step 4: 테스트 실행해 통과 확인**

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.inspection.infrastructure.messaging.kafka.consumer.ProductReviewRequestedConsumerTest" --no-daemon
```
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add ai-service/src/main/java/com/prompthub/ai/inspection/infrastructure/messaging/kafka/consumer/ \
  ai-service/src/test/java/com/prompthub/ai/inspection/infrastructure/messaging/kafka/consumer/
git commit -m "$(cat <<'EOF'
feat: product-events(PRODUCT_REVIEW_REQUESTED) 소비 후 상품 검수 실행

product-events 토픽에는 PRODUCT_STOPPED/DELETED/PRICE_CHANGED/CHANGED도
함께 흐른다. ai-service는 이 중 PRODUCT_REVIEW_REQUESTED만 관심 있으므로
product-service의 ProductEventType을 그대로 가져다 쓰지 않고, ai-service
로컬 ProductReviewEventType으로 지원 타입만 별도 정의한다(kafka-event.md §4,
OrderEventType이 product-service 안에서 order-service 계약을 로컬로
미러링하는 것과 같은 패턴). 나머지 타입은 로그+ack로 넘기고 DLT로 보내지
않는다.
EOF
)"
```

---

## Task 9: ai-service — OpenAI vision 검수 어댑터

**Files:**
- Create: `ai-service/src/main/java/com/prompthub/ai/inspection/infrastructure/client/openai/InspectionPromptFactory.java`
- Create: `ai-service/src/main/java/com/prompthub/ai/inspection/infrastructure/client/openai/SpringAiProductInspectionAgent.java`
- Test: `ai-service/src/test/java/com/prompthub/ai/inspection/infrastructure/client/openai/SpringAiProductInspectionAgentTest.java`

**Interfaces:**
- Consumes: `ChatModel`(Spring AI 자동 설정 빈), `AiSettlementProperties.model()`(기존, `ai.model` 전역 설정값 — 이름은 정산 도메인이지만 모델 설정은 서비스 전역값이라 재사용)
- Produces: `ProductInspectionAiPort` 구현체(Task 7의 포트를 만족 — `@Component`로 등록되면 `ProductInspectionService`에 자동 주입됨)

- [ ] **Step 1: PromptFactory 작성**

```java
package com.prompthub.ai.inspection.infrastructure.client.openai;

import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import com.prompthub.ai.inspection.domain.model.InspectionVerdict;

@Component
public class InspectionPromptFactory {

	private static final String SYSTEM_PROMPT = """
			당신은 PromptHub 오픈마켓의 상품 등록 검수 담당자다.
			판매자가 등록한 상품의 텍스트(이름/설명/본문/태그)와 이미지(썸네일/상세 이미지)를 보고
			아래 두 기준 중 하나라도 위반하면 반려한다.
			1. 금지/불법 콘텐츠: 저작권 침해, 불법 복제물, 음란물/성인물, 사기·허위 광고.
			2. 스팸/저품질 콘텐츠: 의미 없는 텍스트, 상품과 무관한 텍스트/이미지, 극단적으로 불성실한 설명.
			위반 여부가 확실하지 않으면 애매한 케이스도 반려로 판단한다(보수적 기본값) —
			정상 상품을 오탐 반려하는 것보다 위반 콘텐츠를 통과시키는 위험을 더 크게 본다.
			반려 시 rejectionReason은 판매자가 이해할 수 있는 한국어 한 문장으로 구체적인 사유를 적는다.
			승인 시 rejectionReason은 null로 둔다.
			""";

	public String systemPrompt() {
		return SYSTEM_PROMPT;
	}

	public String formatInstructions(BeanOutputConverter<InspectionVerdict> converter) {
		return converter.getFormat();
	}

	public String userPrompt(String productType, String name, String description, String content, java.util.List<String> tags) {
		return """
				상품 유형: %s
				상품명: %s
				설명: %s
				본문: %s
				태그: %s
				""".formatted(productType, name, description, content, String.join(", ", tags));
	}
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
package com.prompthub.ai.inspection.infrastructure.client.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.inspection.application.dto.ProductInspectionRequest;
import com.prompthub.ai.inspection.domain.model.InspectionVerdict;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class SpringAiProductInspectionAgentTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Test
	@DisplayName("이미지가 있으면 UserMessage에 media로 첨부해 호출한다")
	void inspect_withImages_attachesMedia() {
		ChatModel chatModel = mock(ChatModel.class);
		given(chatModel.call(any(Prompt.class))).willReturn(textResponse(
			"{\"approved\":true,\"rejectionReason\":null}"));
		SpringAiProductInspectionAgent agent = new SpringAiProductInspectionAgent(
			chatModel, new InspectionPromptFactory(), properties());

		InspectionVerdict verdict = agent.inspect(request(List.of("https://s3/presigned-1.png")));

		assertThat(verdict.approved()).isTrue();
		assertThat(verdict.rejectionReason()).isNull();
		ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
		verify(chatModel).call(captor.capture());
		UserMessage userMessage = (UserMessage) captor.getValue().getInstructions().stream()
			.filter(UserMessage.class::isInstance)
			.findFirst()
			.orElseThrow();
		assertThat(userMessage.getMedia()).hasSize(1);
	}

	@Test
	@DisplayName("반려 응답을 파싱해 사유를 그대로 담는다")
	void inspect_rejected_parsesReason() {
		ChatModel chatModel = mock(ChatModel.class);
		given(chatModel.call(any(Prompt.class))).willReturn(textResponse(
			"{\"approved\":false,\"rejectionReason\":\"금지 콘텐츠 포함\"}"));
		SpringAiProductInspectionAgent agent = new SpringAiProductInspectionAgent(
			chatModel, new InspectionPromptFactory(), properties());

		InspectionVerdict verdict = agent.inspect(request(List.of()));

		assertThat(verdict.approved()).isFalse();
		assertThat(verdict.rejectionReason()).isEqualTo("금지 콘텐츠 포함");
	}

	@Test
	@DisplayName("모델 응답이 없으면 AiException을 던진다")
	void inspect_noAssistantMessage_throwsAiException() {
		ChatModel chatModel = mock(ChatModel.class);
		given(chatModel.call(any(Prompt.class))).willReturn(ChatResponse.builder().generations(List.of()).build());
		SpringAiProductInspectionAgent agent = new SpringAiProductInspectionAgent(
			chatModel, new InspectionPromptFactory(), properties());

		assertThatThrownBy(() -> agent.inspect(request(List.of()))).isInstanceOf(AiException.class);
	}

	private ChatResponse textResponse(String text) {
		return ChatResponse.builder()
			.generations(List.of(new Generation(new AssistantMessage(text))))
			.build();
	}

	private ProductInspectionRequest request(List<String> imageUrls) {
		return new ProductInspectionRequest(
			PRODUCT_ID, "PROMPT", "제목", "설명", "내용", List.of("tag1"),
			imageUrls.isEmpty() ? null : "https://s3/presigned-thumb.png", imageUrls);
	}

	private AiSettlementProperties properties() {
		return new AiSettlementProperties(
			"gpt-5.6-luna", "none", 2000, 8000,
			java.time.Duration.ofSeconds(90), java.time.Duration.ofSeconds(3),
			new AiSettlementProperties.Execution(4),
			new AiSettlementProperties.Conversation(java.time.Duration.ofHours(24), 20),
			new AiSettlementProperties.Sse(java.time.Duration.ofSeconds(15)),
			new AiSettlementProperties.Settlement(new AiSettlementProperties.Chat(false)),
			"token");
	}
}
```

- [ ] **Step 3: 테스트 실행해 실패 확인**

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.inspection.infrastructure.client.openai.SpringAiProductInspectionAgentTest" --no-daemon
```
Expected: FAIL — `SpringAiProductInspectionAgent` 없음(컴파일 에러).

- [ ] **Step 4: 최소 구현 작성**

```java
package com.prompthub.ai.inspection.infrastructure.client.openai;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.inspection.application.dto.ProductInspectionRequest;
import com.prompthub.ai.inspection.application.port.ProductInspectionAiPort;
import com.prompthub.ai.inspection.domain.model.InspectionVerdict;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

@Component
public class SpringAiProductInspectionAgent implements ProductInspectionAiPort {

	private static final int MAX_COMPLETION_TOKENS = 300;
	private static final Map<String, MimeType> IMAGE_MIME_TYPES = Map.of(
		"jpg", Media.Format.IMAGE_JPEG,
		"jpeg", Media.Format.IMAGE_JPEG,
		"png", Media.Format.IMAGE_PNG,
		"gif", Media.Format.IMAGE_GIF,
		"webp", Media.Format.IMAGE_WEBP
	);

	private final ChatModel chatModel;
	private final InspectionPromptFactory promptFactory;
	private final AiSettlementProperties properties;

	public SpringAiProductInspectionAgent(
		ChatModel chatModel, InspectionPromptFactory promptFactory, AiSettlementProperties properties
	) {
		this.chatModel = chatModel;
		this.promptFactory = promptFactory;
		this.properties = properties;
	}

	@Override
	public InspectionVerdict inspect(ProductInspectionRequest request) {
		BeanOutputConverter<InspectionVerdict> converter = new BeanOutputConverter<>(InspectionVerdict.class);

		List<Message> messages = new ArrayList<>();
		messages.add(new SystemMessage(promptFactory.systemPrompt()));
		messages.add(new SystemMessage(promptFactory.formatInstructions(converter)));
		messages.add(userMessage(request));

		OpenAiChatOptions options = OpenAiChatOptions.builder()
			.model(properties.model())
			.maxCompletionTokens(MAX_COMPLETION_TOKENS)
			.build();
		ChatResponse response = chatModel.call(new Prompt(messages, options));
		String text = requireAssistantText(response);
		return converter.convert(text);
	}

	private UserMessage userMessage(ProductInspectionRequest request) {
		String text = promptFactory.userPrompt(
			request.productType(), request.name(), request.description(), request.content(), request.tags());
		List<Media> media = collectMedia(request);
		return UserMessage.builder().text(text).media(media.toArray(new Media[0])).build();
	}

	private List<Media> collectMedia(ProductInspectionRequest request) {
		List<Media> media = new ArrayList<>();
		if (request.thumbnailUrl() != null) {
			toMedia(request.thumbnailUrl()).ifPresent(media::add);
		}
		for (String imageUrl : request.imageUrls()) {
			toMedia(imageUrl).ifPresent(media::add);
		}
		return media;
	}

	private java.util.Optional<Media> toMedia(String presignedUrl) {
		MimeType mimeType = guessMimeType(presignedUrl);
		if (mimeType == null) {
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(new Media(mimeType, URI.create(presignedUrl)));
	}

	private MimeType guessMimeType(String presignedUrl) {
		String path = presignedUrl.split("\\?")[0];
		int dot = path.lastIndexOf('.');
		if (dot < 0) {
			return null;
		}
		String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
		return IMAGE_MIME_TYPES.get(ext);
	}

	private String requireAssistantText(ChatResponse response) {
		if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
			throw new AiException(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
		}
		AssistantMessage output = response.getResult().getOutput();
		if (output.getText() == null || output.getText().isBlank()) {
			throw new AiException(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
		}
		return output.getText();
	}
}
```

- [ ] **Step 5: 테스트 실행해 통과 확인**

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.inspection.infrastructure.client.openai.SpringAiProductInspectionAgentTest" --no-daemon
```
Expected: PASS

- [ ] **Step 6: 전체 모듈 빌드로 최종 확인**

```bash
./gradlew :ai-service:build :product-service:build --no-daemon
```
Expected: BUILD SUCCESSFUL(두 모듈 모두, checkstyle 포함)

- [ ] **Step 7: 커밋**

```bash
git add ai-service/src/main/java/com/prompthub/ai/inspection/infrastructure/client/openai/ \
  ai-service/src/test/java/com/prompthub/ai/inspection/infrastructure/client/openai/
git commit -m "$(cat <<'EOF'
feat: Spring AI vision으로 상품 검수 판정하는 어댑터 추가

ProductInspectionAiPort의 실제 구현체다. SpringAiSettlementAgent처럼 raw
ChatModel + Prompt/ChatResponse를 직접 다룬다(ChatClient 고수준 API 아님) —
기존 코드베이스 관례를 그대로 따른다. 구조화 출력은 ChatClient 전용
.entity()가 아니라 BeanOutputConverter로 JSON 포맷 지시문을 system prompt에
넣고 응답 텍스트를 직접 파싱하는 방식이다.

이미지는 presigned URL을 다운로드하지 않고 Media(mimeType, URI)로 그대로
전달한다 — OpenAI가 URL을 직접 fetch하므로 ai-service가 이미지 바이트를
들고 있을 필요가 없다. mimeType은 presigned URL 경로의 확장자로 추정한다
(FileUploadController가 업로드를 허용하는 jpg/jpeg/png/gif/webp만 지원).

프롬프트에는 "위반 여부가 확실하지 않으면 반려로 판단"을 명시해 애매한
케이스가 승인 쪽으로 새지 않게 한다(설계 문서에서 정한 보수적 기본값
정책) — 판정 스키마 자체는 승인/반려 이진값 그대로 유지한다.
EOF
)"
```

---

## Task 10: End-to-End 수동 검증

이 흐름은 두 서비스 + Kafka + S3 + OpenAI가 실제로 연결돼야 관찰 가능해 자동화 테스트로 대체하지 않는다. `superpowers:verification-before-completion` 기준의 증거 확보 단계다.

**Files:** 없음(코드 변경 아님)

- [ ] **Step 1: 로컬 인프라 기동**

```bash
cd product-service && docker compose up -d   # product-kafka 포함
```

- [ ] **Step 2: 두 서비스를 각각 로컬 프로필로 기동**

```bash
./gradlew :product-service:bootRun --args='--spring.profiles.active=local'
./gradlew :ai-service:bootRun --args='--spring.profiles.active=local'
```
`OPENAI_API_KEY`, `AI_USER_GRPC_TOKEN` 등 필요한 환경변수는 로컬 셸에 미리 설정해 둔다.

- [ ] **Step 3: 판매자로 상품을 만들고 제출**

```bash
curl -X POST http://localhost:8082/api/v2/products \
  -H "X-User-Id: <테스트 seller UUID>" -H "Content-Type: application/json" \
  -d '{"title":"테스트 상품","productType":"PROMPT","desc":"설명","amount":1000,"content":"프롬프트 본문","tags":[]}'
# 응답의 productId로:
curl -X POST http://localhost:8082/api/v2/products/<productId>/submit-for-review \
  -H "X-User-Id: <테스트 seller UUID>"
```
(정확한 경로는 `ProductController`의 `submitForReview` 매핑을 확인해 맞춘다.)

- [ ] **Step 4: 관찰**

- ai-service 로그에 `ProductReviewRequestedConsumer` 수신 로그와 `ProductInspectionService` "상품 검수 완료" 로그가 찍히는지 확인.
- product-service 로그에 `ProductInspectionResultConsumer` 처리 로그가 찍히는지 확인.
- `GET /api/v1/products/{productId}`(또는 판매자 상세 API)로 상태가 `ON_SALE` 또는 `REJECTED`(사유 포함)로 바뀌었는지 확인.

- [ ] **Step 5: 커밋 없음**

이 Task는 검증 전용이라 커밋할 코드 변경이 없다. 문제를 발견하면 해당 Task로 돌아가 수정 후 다시 커밋한다.
