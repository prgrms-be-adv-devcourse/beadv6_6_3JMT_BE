# ES 검색 동기화 재설계 (#376 재설계) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** #376의 outbox 기반 색인 동기화를 걷어내고, "product-service 자체 변화는 실시간 이벤트, admin-service발 변화/삭제/판매중단은 7일 주기 전체 재조정 배치"로 단순화한다.

**Architecture:** product-service는 create/패치버전 갱신 시에만 신규 이벤트(`PRODUCT_CHANGED`)를 발행하고 search가 즉시 소비해 무조건 upsert한다(삭제 판단 없음). admin-service는 Kafka를 전혀 모른 채 지금처럼 공유 DB에 직접 쓰기만 한다. 7일 주기 배치(및 온디맨드 컨트롤러가 공유하는 같은 로직)가 RDB의 ON_SALE 전체와 ES 색인 전체를 비교해 diff만 `_bulk`로 반영한다 — 여기서 신규 승인 반영과 삭제(판매중단/삭제/승인취소) 전부 처리된다.

**Tech Stack:** Spring Boot, Spring Kafka(`@KafkaListener`/`@Scheduled`), Spring Data JPA, `co.elastic.clients:elasticsearch-java:9.0.2`(`client.bulk`/`client.search`), JUnit5 + Mockito.

## Global Constraints

- 설계 문서: `docs/superpowers/specs/2026-07-23-es-376-search-sync-redesign-design.md` (완료·확정, 로컬 전용·git 미추적)
- 이 재설계는 **공개 검색/목록 API가 아직 ES를 안 쓰고 RDB만 본다는 전제** 위에 있다(#377에서 재검토 필요 — 설계 문서 §8).
- order-service가 소비하는 기존 이벤트(`PRODUCT_STOPPED`/`PRODUCT_DELETED`/`PRODUCT_PRICE_CHANGED`)와 그 발행 방식(`ProductEventProducer`의 3개 메서드, AFTER_COMMIT 직접 발행)은 **어떤 태스크에서도 손대지 않는다.**
- `feat/#376-es-indexing-pipeline` 브랜치(develop에 미머지)에서 작업. 브랜치/커밋 컨벤션은 `.claude/skills/commit/SKILL.md` 따름.
- 각 태스크 끝에 `cd product-service && .\gradlew.bat compileJava compileTestJava --no-daemon`로 컴파일 확인, 마지막 태스크에서 전체 `clean build` 실행.
- Windows/PowerShell 환경 — 명령어는 PowerShell 기준.

---

### Task 1: search 전용 이벤트 도입 — `PRODUCT_CHANGED`로 리네임 + create에도 발행

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/infra/messaging/producer/ProductEventType.java`
- Modify: `product-service/src/main/java/com/prompthub/product/infra/messaging/producer/ProductEventProducer.java`
- Create: `product-service/src/main/java/com/prompthub/product/infra/messaging/producer/event/ProductChangedPayload.java`
- Delete: `product-service/src/main/java/com/prompthub/product/infra/messaging/producer/event/ProductOnSaleChangedPayload.java`
- Modify: `product-service/src/main/java/com/prompthub/product/application/service/ProductSellerService.java`
- Modify: `product-service/src/test/java/com/prompthub/product/infra/messaging/producer/ProductEventProducerTest.java`
- Modify: `product-service/src/test/java/com/prompthub/product/application/service/ProductSellerServiceTest.java`

**Interfaces:**
- Produces: `ProductEventProducer.publishProductChanged(UUID familyRootId)` — Task 2(컨슈머)가 이 이벤트를 구독한다. `ProductEventType.PRODUCT_CHANGED` (code = "PRODUCT_CHANGED").

- [ ] **Step 1: `ProductOnSaleChangedPayload`를 `ProductChangedPayload`로 리네임**

```java
// product-service/src/main/java/com/prompthub/product/infra/messaging/producer/event/ProductChangedPayload.java
package com.prompthub.product.infra.messaging.producer.event;

import java.util.UUID;

public record ProductChangedPayload(UUID familyRootId) {

	public static ProductChangedPayload of(UUID familyRootId) {
		return new ProductChangedPayload(familyRootId);
	}
}
```

기존 `ProductOnSaleChangedPayload.java`는 삭제한다.

- [ ] **Step 2: `ProductEventType`의 enum 값을 `PRODUCT_CHANGED`로 변경**

`ProductEventType.java`에서 `PRODUCT_ON_SALE_CHANGED;`를 `PRODUCT_CHANGED;`로 바꾼다(다른 부분은 그대로).

```java
public enum ProductEventType implements EventType {

	PRODUCT_STOPPED,
	PRODUCT_DELETED,
	PRODUCT_PRICE_CHANGED,
	PRODUCT_CHANGED;

	@Override
	public String code() {
		return name();
	}

	public static Optional<ProductEventType> from(String code) {
		return Arrays.stream(values()).filter(type -> type.name().equals(code)).findFirst();
	}
}
```

- [ ] **Step 3: `ProductEventProducer`의 `publishOnSaleChanged`를 `publishProductChanged`로 변경**

`ProductEventProducer.java`에서 import를 `ProductOnSaleChangedPayload` → `ProductChangedPayload`로 바꾸고, 메서드를 아래로 교체한다(다른 3개 메서드·`publish()`·`send()`는 그대로 유지).

```java
public void publishProductChanged(UUID familyRootId) {
	publish(ProductEventType.PRODUCT_CHANGED, familyRootId, ProductChangedPayload.of(familyRootId));
}
```

- [ ] **Step 4: `ProductEventProducerTest`에 `publishProductChanged` 테스트 추가**

기존 파일에 아래 `@Nested` 클래스를 추가한다(기존 3개 Nested 클래스는 그대로 둠). import에 `ProductChangedPayload` 추가.

```java
@Nested
@DisplayName("PRODUCT_CHANGED 이벤트 발행")
class PublishProductChanged {

	@Test
	@DisplayName("EventMessage 봉투로 감싸 familyRootId를 payload로 발행한다")
	void publishProductChanged_sendsEnvelope() {
		productEventProducer.publishProductChanged(PRODUCT_ID);

		EventMessage<?> message = captureMessage();
		assertThat(message.eventId()).isNotNull();
		assertThat(message.eventType()).isEqualTo("PRODUCT_CHANGED");
		assertThat(message.aggregateType()).isEqualTo("PRODUCT");
		assertThat(message.aggregateId()).isEqualTo(PRODUCT_ID);
		assertThat(message.payload()).isInstanceOf(ProductChangedPayload.class);
		assertThat(((ProductChangedPayload) message.payload()).familyRootId()).isEqualTo(PRODUCT_ID);
	}
}
```

- [ ] **Step 5: 실행해서 컴파일 실패 확인(메서드 없음)**

```powershell
cd product-service
.\gradlew.bat compileTestJava --no-daemon
```
Expected: FAIL — `publishProductChanged`가 아직 없어서 컴파일 에러.

Step 3까지 마치면 컴파일이 되므로, Step 3을 먼저 적용한 뒤 Step 4~5를 실행해도 무방하다(Java는 실패하는 단위 테스트가 아니라 컴파일 자체가 막히므로 구현을 먼저 하고 테스트로 검증하는 순서로 진행).

- [ ] **Step 6: 테스트 실행해서 통과 확인**

```powershell
.\gradlew.bat test --tests "com.prompthub.product.infra.messaging.producer.ProductEventProducerTest" --no-daemon
```
Expected: PASS (4개 Nested 클래스 전부)

- [ ] **Step 7: `createProduct()`에서도 `publishProductChanged` 발행하도록 추가**

`ProductSellerService.java`의 `createProduct()`에서 `Product saved = productRepository.save(product);` 바로 다음 줄에 추가한다.

```java
Product saved = productRepository.save(product);
productEventProducer.publishProductChanged(saved.familyRootId());
```

(신규 생성 직후엔 `parentId`가 없어 `familyRootId() == saved.getId()`이지만, family 개념과 일관되게 `familyRootId()`를 호출한다.)

- [ ] **Step 8: `updateProduct()`의 패치버전 분기에서 메서드명만 교체**

`ProductSellerService.java`의 `updateProduct()` 안, 기존 `productEventProducer.publishOnSaleChanged(familyRootId);` 한 줄을 아래로 교체한다.

```java
productEventProducer.publishProductChanged(familyRootId);
```

- [ ] **Step 9: `ProductSellerServiceTest`에 create 이벤트 검증 테스트 추가, 기존 patch 테스트 메서드명 갱신**

`CreateProduct` nested 클래스에 아래 테스트를 추가한다(기존 두 테스트는 그대로 둠).

```java
@Test
@DisplayName("생성 시 PRODUCT_CHANGED 이벤트를 발행한다")
void createProduct_publishesProductChangedEvent() {
	given(productRepository.save(org.mockito.ArgumentMatchers.any(Product.class)))
		.willAnswer(inv -> inv.getArgument(0));

	productSellerService.createProduct(SELLER_ID,
		new com.prompthub.product.presentation.dto.request.ProductCreateRequest(
			"노션 상품", "NOTION", "model", "설명", 1000,
			null, null, "https://notion.so/my-template", null, List.of(), List.of()
		));

	ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
	then(productRepository).should().save(captor.capture());
	then(productEventProducer).should().publishProductChanged(captor.getValue().getId());
}
```

`UpdateProduct` nested 클래스의 `updateProduct_patchAfterOnSale_createsOnSaleChild_supersedesPrevious()` 테스트 안, 아래 줄을

```java
then(productEventProducer).should().publishOnSaleChanged(PRODUCT_ID);
```

아래로 교체한다.

```java
then(productEventProducer).should().publishProductChanged(PRODUCT_ID);
```

- [ ] **Step 10: 테스트 실행해서 통과 확인**

```powershell
.\gradlew.bat test --tests "com.prompthub.product.application.service.ProductSellerServiceTest" --no-daemon
```
Expected: PASS

- [ ] **Step 11: Commit**

```powershell
git add product-service/src/main/java/com/prompthub/product/infra/messaging/producer/ProductEventType.java `
  product-service/src/main/java/com/prompthub/product/infra/messaging/producer/ProductEventProducer.java `
  product-service/src/main/java/com/prompthub/product/infra/messaging/producer/event/ProductChangedPayload.java `
  product-service/src/main/java/com/prompthub/product/application/service/ProductSellerService.java `
  product-service/src/test/java/com/prompthub/product/infra/messaging/producer/ProductEventProducerTest.java `
  product-service/src/test/java/com/prompthub/product/application/service/ProductSellerServiceTest.java
git rm product-service/src/main/java/com/prompthub/product/infra/messaging/producer/event/ProductOnSaleChangedPayload.java
git commit -m "refactor: PRODUCT_ON_SALE_CHANGED를 PRODUCT_CHANGED로 통합하고 생성 시에도 발행"
```

---

### Task 2: search 컨슈머·핸들러 단순화 — 단일 이벤트, 무조건 upsert

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/search/infra/messaging/ProductSearchEventConsumer.java`
- Modify: `product-service/src/main/java/com/prompthub/search/application/ProductSearchEventHandler.java`
- Modify: `product-service/src/test/java/com/prompthub/search/application/ProductSearchEventHandlerTest.java`

**Interfaces:**
- Consumes: `ProductEventType.PRODUCT_CHANGED`(Task 1), `ProductSearchIndexer.upsert(...)`(기존, 변경 없음 — Task 3에서 인터페이스 나머지가 바뀌지만 `upsert` 시그니처는 그대로).
- Produces: `ProductSearchEventHandler.handleProductChanged(UUID eventId, LocalDateTime occurredAt, UUID familyRootId)` — Task 2 자체에서 컨슈머가 바로 사용.

- [ ] **Step 1: `ProductSearchEventHandlerTest`를 새 동작 기준으로 재작성**

기존 4개 테스트(`handleOnSaleChanged_*` 2개, `handleOnSaleChanged_이미_처리한...`, `handlePriceChanged_*`)를 지우고 아래로 교체한다.

```java
package com.prompthub.search.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProcessedEventRepository;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.support.ProductContentFixtures;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductSearchEventHandlerTest {

	private static final UUID FAMILY_ROOT_ID = UUID.randomUUID();
	private static final UUID EVENT_ID = UUID.randomUUID();

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ProcessedEventRepository processedEventRepository;

	@Mock
	private ProductSearchIndexer productSearchIndexer;

	private ProductSearchEventHandler handler;

	@BeforeEach
	void setUp() {
		handler = new ProductSearchEventHandler(productRepository, processedEventRepository, productSearchIndexer);
	}

	@Test
	void handleProductChanged_ON_SALE_멤버가_있으면_그걸_대표로_upsert한다() {
		Product onSale = product(FAMILY_ROOT_ID, ProductStatus.ON_SALE);
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(onSale));
		given(productRepository.getAverageRating(FAMILY_ROOT_ID)).willReturn(4.5);
		given(productRepository.sumSalesCountByFamilyRootId(FAMILY_ROOT_ID)).willReturn(10L);

		handler.handleProductChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productSearchIndexer).upsert(onSale, 10L, 4.5, onSale.getCreatedAt());
	}

	@Test
	void handleProductChanged_ON_SALE_멤버가_없어도_최신_버전을_대표로_upsert한다() {
		Product draft = product(FAMILY_ROOT_ID, ProductStatus.DRAFT);
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(draft));
		given(productRepository.getAverageRating(FAMILY_ROOT_ID)).willReturn(0.0);
		given(productRepository.sumSalesCountByFamilyRootId(FAMILY_ROOT_ID)).willReturn(0L);

		handler.handleProductChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productSearchIndexer).upsert(draft, 0L, 0.0, draft.getCreatedAt());
	}

	@Test
	void handleProductChanged_이미_처리한_eventId면_아무것도_하지_않는다() {
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(true);

		handler.handleProductChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productRepository, never()).findAllByFamilyRootIds(any());
	}

	private Product product(UUID id, ProductStatus status) {
		Product product = Product.create(id, UUID.randomUUID(), ProductContentFixtures.promptContent());
		ReflectionTestUtils.setField(product, "status", status);
		return product;
	}
}
```

- [ ] **Step 2: 실행해서 컴파일 실패 확인**

```powershell
.\gradlew.bat compileTestJava --no-daemon
```
Expected: FAIL — `handleProductChanged`가 아직 없음.

- [ ] **Step 3: `ProductSearchEventHandler`를 단일 핸들러로 재작성**

```java
package com.prompthub.search.application;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.entity.ProductProcessedEvent;
import com.prompthub.product.domain.repository.ProcessedEventRepository;
import com.prompthub.product.domain.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * product-events 소비의 멱등성(eventId+consumerGroup="product-service-search")과
 * ES 색인 반영을 한 트랜잭션으로 묶는다. PRODUCT_CHANGED는 create·패치버전 갱신에서만
 * 발행되므로(결과가 항상 upsert 대상), 재조회 후 무조건 upsert한다 — 삭제 판단은
 * 7일 주기 전체 재조정 배치(ProductReindexService)의 몫이다.
 */
@Service
@RequiredArgsConstructor
public class ProductSearchEventHandler {

	private static final String CONSUMER_GROUP = "product-service-search";

	private final ProductRepository productRepository;
	private final ProcessedEventRepository processedEventRepository;
	private final ProductSearchIndexer productSearchIndexer;

	@Transactional
	public void handleProductChanged(UUID eventId, LocalDateTime occurredAt, UUID familyRootId) {
		if (alreadyProcessed(eventId)) {
			return;
		}
		reconcileFamily(familyRootId);
		markProcessed(eventId, occurredAt);
	}

	private void reconcileFamily(UUID familyRootId) {
		List<Product> members = productRepository.findAllByFamilyRootIds(List.of(familyRootId));
		ProductFamily family = ProductFamily.of(familyRootId, members);
		Product representative = family.currentOnSale().orElseGet(() -> family.sellerHistory().get(0));
		double averageRating = productRepository.getAverageRating(familyRootId);
		long familySalesCount = productRepository.sumSalesCountByFamilyRootId(familyRootId);
		LocalDateTime firstPublishedAt = members.stream()
			.map(Product::getCreatedAt)
			.min(Comparator.naturalOrder())
			.orElse(representative.getCreatedAt());
		productSearchIndexer.upsert(representative, familySalesCount, averageRating, firstPublishedAt);
	}

	private boolean alreadyProcessed(UUID eventId) {
		return processedEventRepository.existsByEventIdAndConsumerGroup(eventId, CONSUMER_GROUP);
	}

	private void markProcessed(UUID eventId, LocalDateTime occurredAt) {
		processedEventRepository.save(ProductProcessedEvent.create(eventId, CONSUMER_GROUP, "PRODUCT_CHANGED", occurredAt));
	}
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

```powershell
.\gradlew.bat test --tests "com.prompthub.search.application.ProductSearchEventHandlerTest" --no-daemon
```
Expected: PASS

- [ ] **Step 5: `ProductSearchEventConsumer`를 단일 이벤트 구독으로 단순화**

```java
package com.prompthub.search.infra.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prompthub.common.event.EventMessage;
import com.prompthub.product.infra.messaging.producer.ProductEventType;
import com.prompthub.search.application.ProductSearchEventHandler;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductSearchEventConsumer {

	private final ObjectMapper objectMapper;
	private final ProductSearchEventHandler productSearchEventHandler;

	@KafkaListener(
		topics = "product-events",
		groupId = "product-service-search",
		containerFactory = "productEventContainerFactory"
	)
	public void consume(String message, Acknowledgment acknowledgment) {
		EventMessage<JsonNode> event = parse(message);

		if (event.eventId() == null || event.eventType() == null) {
			throw new IllegalArgumentException("eventId 또는 eventType이 없습니다. message=" + message);
		}

		if (ProductEventType.PRODUCT_CHANGED.name().equals(event.eventType())) {
			UUID familyRootId = UUID.fromString(event.payload().get("familyRootId").asText());
			productSearchEventHandler.handleProductChanged(event.eventId(), event.occurredAt(), familyRootId);
		} else {
			log.info("색인 컨슈머가 지원하지 않는 eventType입니다. eventType={}", event.eventType());
		}

		acknowledgment.acknowledge();
	}

	private EventMessage<JsonNode> parse(String message) {
		try {
			return objectMapper.readValue(message, new TypeReference<EventMessage<JsonNode>>() { });
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("product-events 메시지 역직렬화에 실패했습니다.", e);
		}
	}
}
```

- [ ] **Step 6: 전체 컴파일 확인**

```powershell
.\gradlew.bat compileJava compileTestJava --no-daemon
```
Expected: BUILD SUCCESSFUL (Task 3에서 `ProductSearchIndexer` 인터페이스를 바꾸기 전까지는 `delete`/`updatePrice`/`updateCounts`가 여전히 인터페이스에 있어도 무방 — 이번 태스크에서 호출부만 없앴을 뿐 인터페이스 자체는 아직 안 건드림)

- [ ] **Step 7: Commit**

```powershell
git add product-service/src/main/java/com/prompthub/search/infra/messaging/ProductSearchEventConsumer.java `
  product-service/src/main/java/com/prompthub/search/application/ProductSearchEventHandler.java `
  product-service/src/test/java/com/prompthub/search/application/ProductSearchEventHandlerTest.java
git commit -m "refactor: search 컨슈머를 PRODUCT_CHANGED 단일 이벤트+무조건 upsert로 단순화"
```

---

### Task 3: `ProductSearchIndexer` 인터페이스 개편 — bulk 반영 추가, 부분갱신 제거

**Files:**
- Create: `product-service/src/main/java/com/prompthub/search/application/FamilyUpsertInput.java`
- Modify: `product-service/src/main/java/com/prompthub/search/application/ProductSearchIndexer.java`
- Modify: `product-service/src/main/java/com/prompthub/search/infra/es/ElasticsearchProductSearchIndexer.java`

**Interfaces:**
- Produces: `ProductSearchIndexer.findAllIndexedFamilyRootIds(): Set<UUID>`, `ProductSearchIndexer.bulkReconcile(List<FamilyUpsertInput>, List<UUID>)` — Task 4(`ProductReindexService`)가 사용.
- Consumes: 없음(하위 인프라 계층 변경).

- [ ] **Step 1: `FamilyUpsertInput` 레코드 생성**

```java
package com.prompthub.search.application;

import com.prompthub.product.domain.model.entity.Product;
import java.time.LocalDateTime;

public record FamilyUpsertInput(Product onSale, long familySalesCount, double averageRating, LocalDateTime firstPublishedAt) {
}
```

- [ ] **Step 2: `ProductSearchIndexer` 인터페이스를 아래로 교체**

`upsert`는 그대로 유지하고, `delete`/`updatePrice`/`updateCounts`를 제거, `findAllIndexedFamilyRootIds`/`bulkReconcile`을 추가한다.

```java
package com.prompthub.search.application;

import com.prompthub.product.domain.model.entity.Product;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ProductSearchIndexer {

	void upsert(Product onSale, long familySalesCount, double averageRating, LocalDateTime firstPublishedAt);

	Set<UUID> findAllIndexedFamilyRootIds();

	void bulkReconcile(List<FamilyUpsertInput> toUpsert, List<UUID> toDelete);
}
```

- [ ] **Step 3: 컴파일해서 `ElasticsearchProductSearchIndexer`가 깨지는 것 확인**

```powershell
.\gradlew.bat compileJava --no-daemon
```
Expected: FAIL — `ElasticsearchProductSearchIndexer`가 삭제된 메서드(`delete`/`updatePrice`/`updateCounts`)를 `@Override`로 구현하고 있어 컴파일 에러(또는 신규 메서드 미구현 에러).

- [ ] **Step 4: `ElasticsearchProductSearchIndexer`를 새 인터페이스에 맞게 재작성**

```java
package com.prompthub.search.infra.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.search.application.FamilyUpsertInput;
import com.prompthub.search.application.ProductSearchIndexer;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * reviewCount는 아직 집계 쿼리가 없어 0으로 둔다 — 필요해지면
 * ProductRepository에 countActiveReviews(familyRootId) 추가 후 채운다.
 */
@Component
@RequiredArgsConstructor
public class ElasticsearchProductSearchIndexer implements ProductSearchIndexer {

	private final ElasticsearchClient client;

	@Override
	public void upsert(Product onSale, long familySalesCount, double averageRating, LocalDateTime firstPublishedAt) {
		ProductSearchDocument document = buildDocument(onSale, familySalesCount, averageRating, firstPublishedAt);
		try {
			client.index(i -> i.index(ProductIndexBootstrap.ALIAS).id(document.familyRootId().toString()).document(document));
		} catch (IOException e) {
			throw new IllegalStateException("ES 색인에 실패했습니다. familyRootId=" + document.familyRootId(), e);
		}
	}

	@Override
	public Set<UUID> findAllIndexedFamilyRootIds() {
		try {
			SearchResponse<Void> response = client.search(s -> s
				.index(ProductIndexBootstrap.ALIAS)
				.source(src -> src.fetch(false))
				.size(10000), Void.class);
			return response.hits().hits().stream()
				.map(Hit::id)
				.filter(Objects::nonNull)
				.map(UUID::fromString)
				.collect(Collectors.toSet());
		} catch (IOException e) {
			throw new IllegalStateException("ES 색인 목록 조회에 실패했습니다.", e);
		}
	}

	@Override
	public void bulkReconcile(List<FamilyUpsertInput> toUpsert, List<UUID> toDelete) {
		if (toUpsert.isEmpty() && toDelete.isEmpty()) {
			return;
		}
		try {
			client.bulk(b -> {
				for (FamilyUpsertInput input : toUpsert) {
					ProductSearchDocument document = buildDocument(
						input.onSale(), input.familySalesCount(), input.averageRating(), input.firstPublishedAt());
					b.operations(op -> op.index(idx -> idx
						.index(ProductIndexBootstrap.ALIAS)
						.id(document.familyRootId().toString())
						.document(document)));
				}
				for (UUID familyRootId : toDelete) {
					b.operations(op -> op.delete(d -> d
						.index(ProductIndexBootstrap.ALIAS)
						.id(familyRootId.toString())));
				}
				return b;
			});
		} catch (IOException e) {
			throw new IllegalStateException("ES 벌크 반영에 실패했습니다.", e);
		}
	}

	private ProductSearchDocument buildDocument(
		Product onSale, long familySalesCount, double averageRating, LocalDateTime firstPublishedAt
	) {
		return new ProductSearchDocument(
			onSale.familyRootId(),
			onSale.getId(),
			onSale.getSellerId(),
			onSale.getName(),
			onSale.getDescription(),
			onSale.getContent(),
			onSale.getTags(),
			onSale.getProductType().name(),
			onSale.getModel(),
			onSale.getAmount(),
			onSale.getAmountType().name(),
			onSale.getThumbnailUrl(),
			onSale.getBadge(),
			(int) familySalesCount,
			onSale.getViewCount(),
			0,
			averageRating,
			firstPublishedAt,
			onSale.getUpdatedAt()
		);
	}
}
```

**참고(YAGNI 경계 명시)**: `findAllIndexedFamilyRootIds()`는 ES `size(10000)`(기본 `index.max_result_window`) 한 번으로 전체를 가져온다. 카탈로그가 1만 건을 넘으면 scroll/search_after 페이징이 필요해지는데, 지금 규모에서는 불필요한 선행 투자라 넣지 않는다 — 나중에 실제로 이 한계에 부딪히면 그때 추가한다.

- [ ] **Step 5: 컴파일 확인**

```powershell
.\gradlew.bat compileJava --no-daemon
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```powershell
git add product-service/src/main/java/com/prompthub/search/application/FamilyUpsertInput.java `
  product-service/src/main/java/com/prompthub/search/application/ProductSearchIndexer.java `
  product-service/src/main/java/com/prompthub/search/infra/es/ElasticsearchProductSearchIndexer.java
git commit -m "refactor: ProductSearchIndexer에 bulk 반영 추가, 부분갱신 메서드 제거"
```

---

### Task 4: 7일 전체 재조정 배치 — `ProductReindexService`/`ReindexController`/신규 스케줄러

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/search/application/ProductReindexService.java`
- Modify: `product-service/src/main/java/com/prompthub/product/presentation/controller/ReindexController.java`
- Create: `product-service/src/main/java/com/prompthub/search/infra/batch/ProductReconcileScheduler.java`
- Delete: `product-service/src/main/java/com/prompthub/search/infra/batch/ProductCountSyncScheduler.java`
- Modify: `product-service/src/main/java/com/prompthub/product/ProductApplication.java`
- Modify: `product-service/src/test/java/com/prompthub/search/application/ProductReindexServiceTest.java`
- Modify: `config/src/main/resources/configs/product-service.yml`
- Modify: `product-service/src/main/resources/application-local.yml`

**Interfaces:**
- Consumes: `ProductSearchIndexer.findAllIndexedFamilyRootIds()`/`bulkReconcile(...)`(Task 3), `ProductRepository.findAllByStatus(ProductStatus.ON_SALE)`(기존).
- Produces: `ProductReindexService.reconcileAll()` — `ReindexController`(온디맨드)와 `ProductReconcileScheduler`(7일) 양쪽이 호출.

- [ ] **Step 1: `ProductReindexServiceTest`를 `reconcileAll` 기준으로 재작성**

```java
package com.prompthub.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.support.ProductContentFixtures;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductReindexServiceTest {

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ProductSearchIndexer productSearchIndexer;

	private ProductReindexService reindexService;

	@BeforeEach
	void setUp() {
		reindexService = new ProductReindexService(productRepository, productSearchIndexer);
	}

	@Test
	@SuppressWarnings("unchecked")
	void reconcileAll_ON_SALE_family는_upsert_대상에_담는다() {
		UUID familyRootId = UUID.randomUUID();
		Product onSale = product(familyRootId, ProductStatus.ON_SALE);
		given(productRepository.findAllByStatus(ProductStatus.ON_SALE)).willReturn(List.of(onSale));
		given(productRepository.findAllByFamilyRootIds(List.of(familyRootId))).willReturn(List.of(onSale));
		given(productRepository.getAverageRating(familyRootId)).willReturn(4.0);
		given(productRepository.sumSalesCountByFamilyRootId(familyRootId)).willReturn(5L);
		given(productSearchIndexer.findAllIndexedFamilyRootIds()).willReturn(Set.of(familyRootId));

		reindexService.reconcileAll();

		ArgumentCaptor<List<FamilyUpsertInput>> upsertCaptor = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<List<UUID>> deleteCaptor = ArgumentCaptor.forClass(List.class);
		verify(productSearchIndexer).bulkReconcile(upsertCaptor.capture(), deleteCaptor.capture());
		assertThat(upsertCaptor.getValue()).anySatisfy(input -> {
			assertThat(input.onSale().familyRootId()).isEqualTo(familyRootId);
			assertThat(input.familySalesCount()).isEqualTo(5L);
			assertThat(input.averageRating()).isEqualTo(4.0);
		});
		assertThat(deleteCaptor.getValue()).isEmpty();
	}

	@Test
	@SuppressWarnings("unchecked")
	void reconcileAll_ES에만_있고_더_이상_ON_SALE_아닌_family는_삭제_대상에_담는다() {
		UUID staleFamilyRootId = UUID.randomUUID();
		given(productRepository.findAllByStatus(ProductStatus.ON_SALE)).willReturn(List.of());
		given(productSearchIndexer.findAllIndexedFamilyRootIds()).willReturn(Set.of(staleFamilyRootId));

		reindexService.reconcileAll();

		ArgumentCaptor<List<UUID>> deleteCaptor = ArgumentCaptor.forClass(List.class);
		verify(productSearchIndexer).bulkReconcile(any(), deleteCaptor.capture());
		assertThat(deleteCaptor.getValue()).containsExactly(staleFamilyRootId);
	}

	private Product product(UUID id, ProductStatus status) {
		Product product = Product.create(id, UUID.randomUUID(), ProductContentFixtures.promptContent());
		ReflectionTestUtils.setField(product, "status", status);
		return product;
	}
}
```

- [ ] **Step 2: 컴파일 실패 확인**

```powershell
.\gradlew.bat compileTestJava --no-daemon
```
Expected: FAIL — `reconcileAll`/`bulkReconcile` 관련 미구현.

- [ ] **Step 3: `ProductReindexService`를 `reconcileAll()`로 재작성**

```java
package com.prompthub.search.application;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * RDB의 ON_SALE 상품 전체와 ES 색인 전체를 비교해 다른 부분만 반영하는 전체 재조정.
 * 온디맨드 컨트롤러(/internal/search/reindex)와 7일 주기 스케줄러(ProductReconcileScheduler)
 * 양쪽에서 호출한다. 실시간 이벤트가 없는 admin-service발 변화(승인/승인취소)와,
 * 판매중단/삭제(실시간 이벤트를 발행하지 않기로 한 경로)를 여기서 최종적으로 맞춘다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductReindexService {

	private final ProductRepository productRepository;
	private final ProductSearchIndexer productSearchIndexer;

	public void reconcileAll() {
		List<Product> onSaleProducts = productRepository.findAllByStatus(ProductStatus.ON_SALE);
		Map<UUID, List<Product>> byFamily = onSaleProducts.stream()
			.collect(Collectors.groupingBy(Product::familyRootId));

		List<FamilyUpsertInput> toUpsert = new ArrayList<>();
		for (UUID familyRootId : byFamily.keySet()) {
			List<Product> members = productRepository.findAllByFamilyRootIds(List.of(familyRootId));
			ProductFamily family = ProductFamily.of(familyRootId, members);
			family.currentOnSale().ifPresent(onSale -> {
				double averageRating = productRepository.getAverageRating(familyRootId);
				long familySalesCount = productRepository.sumSalesCountByFamilyRootId(familyRootId);
				LocalDateTime firstPublishedAt = members.stream()
					.map(Product::getCreatedAt)
					.min(Comparator.naturalOrder())
					.orElse(onSale.getCreatedAt());
				toUpsert.add(new FamilyUpsertInput(onSale, familySalesCount, averageRating, firstPublishedAt));
			});
		}

		Set<UUID> onSaleFamilyRootIds = byFamily.keySet();
		Set<UUID> indexedFamilyRootIds = productSearchIndexer.findAllIndexedFamilyRootIds();
		List<UUID> toDelete = indexedFamilyRootIds.stream()
			.filter(id -> !onSaleFamilyRootIds.contains(id))
			.toList();

		productSearchIndexer.bulkReconcile(toUpsert, toDelete);
		log.info("전체 재조정 완료. upsert={}, delete={}", toUpsert.size(), toDelete.size());
	}
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

```powershell
.\gradlew.bat test --tests "com.prompthub.search.application.ProductReindexServiceTest" --no-daemon
```
Expected: PASS

- [ ] **Step 5: `ReindexController`가 `reconcileAll()`을 호출하도록 수정**

```java
package com.prompthub.product.presentation.controller;

import com.prompthub.search.application.ProductReindexService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/search")
public class ReindexController {

	private final ProductReindexService productReindexService;

	@PostMapping("/reindex")
	public void reindex() {
		productReindexService.reconcileAll();
	}
}
```

- [ ] **Step 6: 기존 `ProductCountSyncScheduler` 삭제, 신규 `ProductReconcileScheduler` 생성**

```powershell
git rm product-service/src/main/java/com/prompthub/search/infra/batch/ProductCountSyncScheduler.java
```

```java
// product-service/src/main/java/com/prompthub/search/infra/batch/ProductReconcileScheduler.java
package com.prompthub.search.infra.batch;

import com.prompthub.search.application.ProductReindexService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RDB ON_SALE 전체와 ES 색인 전체를 7일마다 비교해 맞춘다. 실시간 이벤트가 없는
 * admin-service발 변화(승인/승인취소)와 삭제/판매중단을 여기서 최종적으로 잡아낸다.
 * (2026-07-23-es-376-search-sync-redesign-design.md §2, §8 — 공개 검색 API가 ES를
 * 아직 라이브로 쓰지 않는 동안만 유효한 전제. #377에서 재검토 필요.)
 */
@Component
@RequiredArgsConstructor
public class ProductReconcileScheduler {

	private final ProductReindexService productReindexService;

	@Scheduled(fixedDelayString = "${prompthub.search.reconcile.fixed-delay-ms:604800000}")
	public void reconcile() {
		productReindexService.reconcileAll();
	}
}
```

- [ ] **Step 7: `OutboxRelayConfig` 삭제로 사라질 `@EnableScheduling`을 `ProductApplication`으로 옮김**

`OutboxRelayConfig`(Task 5에서 삭제 예정)가 지금 앱의 유일한 `@EnableScheduling` 선언이다. 먼저 옮겨두지 않으면 Task 5 이후 `@Scheduled`(신규 스케줄러 포함) 전체가 조용히 동작을 멈춘다.

```java
package com.prompthub.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = {"com.prompthub.product", "com.prompthub.search"})
public class ProductApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProductApplication.class, args);
	}
}
```

- [ ] **Step 8: config yml 갱신 — `outbox-relay`/`count-sync` 제거, `reconcile` 추가**

`config/src/main/resources/configs/product-service.yml`에서 아래 블록을

```yaml
prompthub:
  outbox-relay:
    enabled: true
    fixed-delay-ms: 5000
    batch-size: 100
    max-retry-count: 3
  search:
    count-sync:
      fixed-delay-ms: 600000
```

아래로 교체한다.

```yaml
prompthub:
  search:
    reconcile:
      fixed-delay-ms: 604800000
```

`product-service/src/main/resources/application-local.yml`도 동일하게 교체한다.

- [ ] **Step 9: 전체 컴파일 확인**

```powershell
.\gradlew.bat compileJava compileTestJava --no-daemon
```
Expected: BUILD SUCCESSFUL (Task 5에서 outbox 관련 클래스를 지우기 전까지, `OutboxRelayConfig`가 여전히 남아있어 `@EnableScheduling` 중복 선언 상태이지만 Spring은 이를 허용한다 — 중복은 Task 5에서 자연히 해소됨)

- [ ] **Step 10: Commit**

```powershell
git add product-service/src/main/java/com/prompthub/search/application/ProductReindexService.java `
  product-service/src/main/java/com/prompthub/product/presentation/controller/ReindexController.java `
  product-service/src/main/java/com/prompthub/search/infra/batch/ProductReconcileScheduler.java `
  product-service/src/main/java/com/prompthub/product/ProductApplication.java `
  product-service/src/test/java/com/prompthub/search/application/ProductReindexServiceTest.java `
  config/src/main/resources/configs/product-service.yml `
  product-service/src/main/resources/application-local.yml
git rm product-service/src/main/java/com/prompthub/search/infra/batch/ProductCountSyncScheduler.java
git commit -m "feat: 10분 카운트 배치를 7일 주기 전체 재조정 배치로 교체"
```

---

### Task 5: outbox 인프라 삭제 (product-service)

**Files:**
- Delete: `product-service/src/main/java/com/prompthub/product/infra/messaging/producer/OutboxRelay.java`
- Delete: `product-service/src/main/java/com/prompthub/product/infra/messaging/producer/OutboxRelayProperties.java`
- Delete: `product-service/src/main/java/com/prompthub/product/infra/messaging/config/OutboxRelayConfig.java`
- Delete: `product-service/src/main/java/com/prompthub/product/domain/model/entity/OutboxEvent.java`
- Delete: `product-service/src/main/java/com/prompthub/product/domain/model/enums/OutboxEventStatus.java`
- Delete: `product-service/src/main/java/com/prompthub/product/domain/repository/OutboxEventRepository.java`
- Delete: `product-service/src/main/java/com/prompthub/product/infra/persistence/outbox/OutboxEventAdapter.java`
- Delete: `product-service/src/main/java/com/prompthub/product/infra/persistence/outbox/OutboxEventPersistence.java`
- Delete: `product-service/src/main/java/com/prompthub/product/application/service/outbox/OutboxEventAppender.java`
- Delete: `product-service/src/main/resources/db/migration/V3__product_outbox_event.sql`
- Delete: `product-service/src/test/java/com/prompthub/product/domain/model/entity/OutboxEventTest.java`
- Delete: `product-service/src/test/java/com/prompthub/product/infra/messaging/producer/OutboxRelayTest.java`

**Interfaces:** 없음 — 이 태스크는 순수 삭제이며, Task 1~4까지 완료된 시점엔 이 파일들을 참조하는 코드가 하나도 남아있지 않다(Task 4 Step 7에서 `@EnableScheduling`을 이미 옮겨뒀음을 확인).

- [ ] **Step 1: 삭제 전 참조 잔존 여부 확인**

```powershell
cd product-service
git grep -l "OutboxRelay\|OutboxEvent\|OutboxEventAppender" -- "src/main" | Select-String -NotMatch "OutboxRelay.java|OutboxRelayProperties.java|OutboxRelayConfig.java|OutboxEvent.java|OutboxEventStatus.java|OutboxEventRepository.java|OutboxEventAdapter.java|OutboxEventPersistence.java|OutboxEventAppender.java"
```
Expected: 출력 없음(이번에 지울 파일들 자기 자신 외에 참조하는 곳이 없어야 함).

- [ ] **Step 2: 파일 삭제**

```powershell
git rm product-service/src/main/java/com/prompthub/product/infra/messaging/producer/OutboxRelay.java
git rm product-service/src/main/java/com/prompthub/product/infra/messaging/producer/OutboxRelayProperties.java
git rm product-service/src/main/java/com/prompthub/product/infra/messaging/config/OutboxRelayConfig.java
git rm product-service/src/main/java/com/prompthub/product/domain/model/entity/OutboxEvent.java
git rm product-service/src/main/java/com/prompthub/product/domain/model/enums/OutboxEventStatus.java
git rm product-service/src/main/java/com/prompthub/product/domain/repository/OutboxEventRepository.java
git rm product-service/src/main/java/com/prompthub/product/infra/persistence/outbox/OutboxEventAdapter.java
git rm product-service/src/main/java/com/prompthub/product/infra/persistence/outbox/OutboxEventPersistence.java
git rm product-service/src/main/java/com/prompthub/product/application/service/outbox/OutboxEventAppender.java
git rm product-service/src/main/resources/db/migration/V3__product_outbox_event.sql
git rm product-service/src/test/java/com/prompthub/product/domain/model/entity/OutboxEventTest.java
git rm product-service/src/test/java/com/prompthub/product/infra/messaging/producer/OutboxRelayTest.java
```

(`infra/persistence/outbox/`, `application/service/outbox/` 디렉토리가 비면 git이 자동으로 추적을 그만둔다 — 별도 디렉토리 삭제 불필요.)

- [ ] **Step 3: 전체 컴파일 + 테스트 확인**

```powershell
.\gradlew.bat compileJava compileTestJava --no-daemon
.\gradlew.bat test --no-daemon
```
Expected: BUILD SUCCESSFUL, 모든 테스트 PASS

- [ ] **Step 4: Commit**

```powershell
git commit -m "chore: outbox 인프라 전체 삭제 (product-service)"
```

---

### Task 6: outbox 인프라 삭제 + 발행 호출 제거 (admin-service)

**Files:**
- Delete: `admin-service/src/main/java/com/prompthub/admin/product/application/service/ProductOnSaleChangedEventFactory.java`
- Delete: `admin-service/src/main/java/com/prompthub/admin/product/domain/model/entity/OutboxEvent.java`
- Delete: `admin-service/src/main/java/com/prompthub/admin/product/domain/repository/OutboxEventRepository.java`
- Delete: `admin-service/src/main/java/com/prompthub/admin/product/infrastructure/persistence/OutboxEventJpaRepository.java`
- Delete: `admin-service/src/main/java/com/prompthub/admin/product/infrastructure/persistence/OutboxEventRepositoryAdapter.java`
- Modify: `admin-service/src/main/java/com/prompthub/admin/product/application/service/ProductService.java`
- Modify: `admin-service/src/test/java/com/prompthub/admin/product/application/service/ProductServiceTest.java`

**Interfaces:** 없음 — admin-service는 이 태스크 이후 Kafka/이벤트를 전혀 참조하지 않는다.

- [ ] **Step 1: `ProductService.java`에서 outbox 관련 import·필드·호출 제거**

import에서 아래 2줄을 삭제한다.

```java
import com.prompthub.admin.product.domain.model.entity.OutboxEvent;
import com.prompthub.admin.product.domain.repository.OutboxEventRepository;
```

필드 선언에서 아래 2줄을 삭제한다.

```java
private final OutboxEventRepository outboxEventRepository;
private final ProductOnSaleChangedEventFactory eventFactory;
```

`approveProduct()`에서 끝의 outbox insert 블록을 삭제한다(메서드의 나머지는 그대로).

```java
// 삭제 대상
outboxEventRepository.append(
	OutboxEvent.create(familyRootId, "PRODUCT_ON_SALE_CHANGED", eventFactory.createEnvelopeJson(familyRootId))
);
```

`revertProductToPendingReview()`에서 끝의 `if (wasOnSale) { outboxEventRepository.append(...); }` 블록 전체를 삭제한다(그 위의 `boolean wasOnSale = ...`와 family 복원 로직은 그대로 유지 — `wasOnSale`은 그 복원 분기에서도 쓰이므로 변수 자체는 남긴다).

```java
// 삭제 대상
if (wasOnSale) {
	outboxEventRepository.append(
		OutboxEvent.create(familyRootId, "PRODUCT_ON_SALE_CHANGED", eventFactory.createEnvelopeJson(familyRootId))
	);
}
```

- [ ] **Step 2: `ProductServiceTest.java`에서 outbox 관련 mock·assertion 제거**

import에서 삭제:

```java
import com.prompthub.admin.product.domain.repository.OutboxEventRepository;
```

필드에서 삭제:

```java
@Mock
private OutboxEventRepository outboxEventRepository;

@Mock
private ProductOnSaleChangedEventFactory eventFactory;
```

아래 5개 assertion 블록을 각각의 테스트 메서드에서 삭제한다(테스트 메서드 자체는 유지, 그 안의 이 부분만 제거).

- `approveProduct_supersedesPreviousOnSale()`의:
```java
then(outboxEventRepository).should().append(org.mockito.ArgumentMatchers.argThat(event ->
	event.getAggregateId().equals(FAMILY_ROOT_ID) && event.getEventType().equals("PRODUCT_ON_SALE_CHANGED")));
```
- `approveProduct_firstApproval_noSupersede()`의 동일 블록
- `revert_onSaleRow_restoresPairedSupersededRow()`의:
```java
then(outboxEventRepository).should().append(org.mockito.ArgumentMatchers.argThat(event ->
	event.getAggregateId().equals(FAMILY_ROOT_ID)));
```
- `revert_onSaleRow_noSupersededPair_onlyTargetChanges()`의 동일 블록
- `revert_rejectedRow_doesNotTouchFamily()`의:
```java
then(outboxEventRepository).should(org.mockito.Mockito.never()).append(org.mockito.ArgumentMatchers.any());
```

- [ ] **Step 3: 컴파일 + 테스트 확인**

```powershell
cd admin-service
.\gradlew.bat compileJava compileTestJava --no-daemon
.\gradlew.bat test --no-daemon
```
Expected: BUILD SUCCESSFUL, 모든 테스트 PASS

- [ ] **Step 4: outbox 관련 파일 삭제**

```powershell
git rm admin-service/src/main/java/com/prompthub/admin/product/application/service/ProductOnSaleChangedEventFactory.java
git rm admin-service/src/main/java/com/prompthub/admin/product/domain/model/entity/OutboxEvent.java
git rm admin-service/src/main/java/com/prompthub/admin/product/domain/repository/OutboxEventRepository.java
git rm admin-service/src/main/java/com/prompthub/admin/product/infrastructure/persistence/OutboxEventJpaRepository.java
git rm admin-service/src/main/java/com/prompthub/admin/product/infrastructure/persistence/OutboxEventRepositoryAdapter.java
```

- [ ] **Step 5: 최종 컴파일 확인**

```powershell
.\gradlew.bat compileJava compileTestJava --no-daemon
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```powershell
git add admin-service/src/main/java/com/prompthub/admin/product/application/service/ProductService.java `
  admin-service/src/test/java/com/prompthub/admin/product/application/service/ProductServiceTest.java
git commit -m "chore: outbox 인프라 전체 삭제 (admin-service) — Kafka/이벤트 의존 제거"
```

---

### Task 7: 문서 정리 및 최종 검증

**Files:**
- Delete: `product-service/docs/plans/2026-07-23-es-376-indexing-pipeline-plan.md`

**Interfaces:** 없음 — 마무리 태스크.

- [ ] **Step 1: 옛 plan 문서 삭제**

`save-plan-docs` 스킬 컨벤션(plan 문서는 구현 완료/대체되면 삭제)에 따라, 이번 재설계로 완전히 대체된 옛 plan 문서를 지운다.

```powershell
git rm product-service/docs/plans/2026-07-23-es-376-indexing-pipeline-plan.md
```

- [ ] **Step 2: product-service 전체 build**

```powershell
cd product-service
.\gradlew.bat clean build --no-daemon
```
Expected: BUILD SUCCESSFUL (checkstyle, 전체 테스트 포함)

- [ ] **Step 3: admin-service 전체 build**

```powershell
cd ..\admin-service
.\gradlew.bat clean build --no-daemon
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```powershell
git add product-service/docs/plans/2026-07-23-es-376-search-sync-redesign-plan.md
git commit -m "docs: 옛 #376 outbox 기반 plan 문서를 재설계 plan 문서로 교체"
```

(design 문서 `docs/superpowers/specs/2026-07-23-es-376-search-sync-redesign-design.md`는 `.gitignore`(`/docs/superpowers/`)에 의해 로컬 전용으로 유지되므로 git add 대상이 아니다.)

- [ ] **Step 5: 수동 E2E 확인(선택, 로컬 ES 떠있을 때)**

```powershell
# 셀러로 상품 생성 → 몇 초 내 색인 확인
curl.exe http://localhost:9200/products/_doc/{생성된 productId}

# 온디맨드 재조정 트리거 확인
curl.exe -X POST http://localhost:8080/internal/search/reindex
```
