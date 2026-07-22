# ES 색인 파이프라인(#376) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 상품(product-service) 데이터가 Elasticsearch `products` 인덱스에 실시간으로 색인되도록 파이프라인을 구축한다 — ON_SALE 전환 신호(아웃박스 포함) → 색인 컨슈머 → ES, 그리고 초기 적재·보정용 온디맨드 풀 리인덱스와 10분 주기 카운트 동기화까지 포함한다.

**Architecture:** product-service 안에 `search`라는 형제 패키지를 신설한다(모듈러 모놀리스, D1). `product` 패키지는 무변경 — `product-events` 발행만 하고 검색의 존재를 모른다. `search`는 `product-events`를 소비해 ES에 반영하며, `product`의 조회 포트(`ProductRepository`)만 읽기 전용으로 참조한다. `search`는 이번 계획에서 presentation(컨트롤러)을 갖지 않는다 — 외부/내부 API는 항상 `product.presentation`이 창구이고, `search`는 그 뒤에서 계산·색인만 담당하는 내부 엔진이다. admin-service가 발행하는 승인/복귀 신호는 자기 스키마를 만들 수 없어(`ddl-auto: none`) product_service 스키마의 아웃박스 테이블에 insert만 하고, product-service가 그 아웃박스를 폴링해 Kafka로 발행한다(order-service의 기존 아웃박스 패턴 재사용).

**Tech Stack:** Elasticsearch 9.4.3(+ nori 플러그인, 커스텀 Docker 이미지) · `co.elastic.clients:elasticsearch-java` · Spring Kafka(기존) · Testcontainers(ES) · Flyway.

## Global Constraints

- 기존 `product` 패키지(presentation/application/domain/infra/config)는 재배치하지 않는다 — 형제 패키지 `search`만 신설한다.
- `search`는 이번 계획에서 `presentation`(컨트롤러)을 갖지 않는다. 외부 HTTP 진입점은 항상 `product.presentation`.
- 임베딩(dense_vector)·자모/초성 자동완성 서브필드는 매핑에는 포함하되(리인덱스 예방), 이번 계획에서 값은 채우지 않는다(Phase 2 범위).
- Kafka 이벤트는 `kafka-event.md`의 `EventMessage<T>` 봉투, 토픽 `product-events`, aggregateType `PRODUCT` 규칙을 그대로 따른다.
- 풀 리인덱스·카운트 동기화 배치는 자동 스케줄이 아니라 **온디맨드/주기(10분) 트리거만** 존재한다 — "이벤트 유실 복구"용 자동 배치는 두지 않는다(아웃박스로 이미 신뢰성 확보).
- **Task 4(admin-service 변경)는 다른 서비스 코드 변경이라 실행 직전 반드시 사용자에게 별도로 명시적 승인을 다시 받는다.** 그 전에는 절대 admin-service 파일을 수정하지 않는다.
- 참고 설계 문서: `docs/superpowers/specs/2026-07-16-es-0-overview-design.md`, `2026-07-16-es-1-index-modeling-design.md`, `2026-07-16-es-6-packages-phases-design.md`, `2026-07-21-admin-product-onsale-event-design.md`.

---

### Task 1: `product_outbox_event` 테이블 + `OutboxEvent` 엔티티/리포지토리 (product-service)

order-service의 `order_outbox_event`(`OutboxEvent`/`OutboxEventRepository`/`OutboxEventAdapter`) 패턴을 그대로 복제한다.

**Files:**
- Create: `product-service/src/main/resources/db/migration/V3__product_outbox_event.sql`
- Create: `product-service/src/main/java/com/prompthub/product/domain/model/enums/OutboxEventStatus.java`
- Create: `product-service/src/main/java/com/prompthub/product/domain/model/entity/OutboxEvent.java`
- Create: `product-service/src/main/java/com/prompthub/product/domain/repository/OutboxEventRepository.java`
- Create: `product-service/src/main/java/com/prompthub/product/infra/persistence/outbox/OutboxEventPersistence.java`
- Create: `product-service/src/main/java/com/prompthub/product/infra/persistence/outbox/OutboxEventAdapter.java`
- Test: `product-service/src/test/java/com/prompthub/product/domain/model/entity/OutboxEventTest.java`

**Interfaces:**
- Produces: `OutboxEvent.create(UUID eventId, UUID aggregateId, String eventType, String payload, LocalDateTime occurredAt)`, `markPublished(LocalDateTime)`, `recordPublishFailure(int maxRetryCount)`. `OutboxEventRepository.save(OutboxEvent)`, `findPendingEvents(int batchSize)`. Task 2(`OutboxEventAppender`/`OutboxRelay`)가 이 타입들을 그대로 사용한다.

- [ ] **Step 1: Flyway 마이그레이션 작성**

```sql
--
-- product-service V3
--
-- ES 색인 파이프라인(#376) 선행 작업: admin-service가 상품 ON_SALE 전환 시 안전하게
-- 이벤트를 발행할 수 있도록 아웃박스 테이블을 product_service 스키마에 둔다.
-- admin-service는 ddl-auto=none이라 스키마를 만들 수 없어, 테이블 소유는 product-service가 갖는다.
-- (docs/superpowers/specs/2026-07-21-admin-product-onsale-event-design.md)
--

CREATE TABLE product_outbox_event (
    event_id uuid NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type character varying(100) NOT NULL,
    payload text NOT NULL,
    status character varying(20) NOT NULL,
    retry_count integer NOT NULL,
    occurred_at timestamp(6) without time zone NOT NULL,
    published_at timestamp(6) without time zone,
    CONSTRAINT product_outbox_event_pkey PRIMARY KEY (event_id),
    CONSTRAINT product_outbox_event_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PUBLISHED'::character varying)::text, ('FAILED'::character varying)::text])))
);

CREATE INDEX idx_product_outbox_event_status_occurred_at ON product_outbox_event USING btree (status, occurred_at);
CREATE INDEX idx_product_outbox_event_aggregate_id ON product_outbox_event USING btree (aggregate_id);
```

- [ ] **Step 2: `OutboxEventStatus` enum 작성**

```java
package com.prompthub.product.domain.model.enums;

public enum OutboxEventStatus {
	PENDING, PUBLISHED, FAILED
}
```

- [ ] **Step 3: `OutboxEvent` 엔티티 작성**

```java
package com.prompthub.product.domain.model.entity;

import com.prompthub.product.domain.model.enums.OutboxEventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
	name = "product_outbox_event",
	schema = "product_service",
	indexes = {
		@Index(name = "idx_product_outbox_event_status_occurred_at", columnList = "status, occurred_at"),
		@Index(name = "idx_product_outbox_event_aggregate_id", columnList = "aggregate_id")
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

	@Id
	@Column(name = "event_id", columnDefinition = "uuid")
	private UUID eventId;

	@Column(name = "aggregate_id", columnDefinition = "uuid", nullable = false)
	private UUID aggregateId;

	@Column(name = "event_type", length = 100, nullable = false)
	private String eventType;

	@Column(name = "payload", columnDefinition = "text", nullable = false)
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 20, nullable = false)
	private OutboxEventStatus status;

	@Column(name = "retry_count", nullable = false)
	private int retryCount;

	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;

	@Column(name = "published_at")
	private LocalDateTime publishedAt;

	private OutboxEvent(
		UUID eventId, UUID aggregateId, String eventType, String payload,
		OutboxEventStatus status, int retryCount, LocalDateTime occurredAt, LocalDateTime publishedAt
	) {
		this.eventId = eventId;
		this.aggregateId = aggregateId;
		this.eventType = eventType;
		this.payload = payload;
		this.status = status;
		this.retryCount = retryCount;
		this.occurredAt = occurredAt;
		this.publishedAt = publishedAt;
	}

	public static OutboxEvent create(
		UUID eventId, UUID aggregateId, String eventType, String payload, LocalDateTime occurredAt
	) {
		return new OutboxEvent(eventId, aggregateId, eventType, payload, OutboxEventStatus.PENDING, 0, occurredAt, null);
	}

	public void markPublished(LocalDateTime publishedAt) {
		this.status = OutboxEventStatus.PUBLISHED;
		this.publishedAt = publishedAt;
	}

	public void recordPublishFailure(int maxRetryCount) {
		this.retryCount++;
		if (this.retryCount >= maxRetryCount) {
			this.status = OutboxEventStatus.FAILED;
		}
	}
}
```

- [ ] **Step 4: 도메인 포트 + JPA 리포지토리 + 어댑터 작성**

```java
// product-service/src/main/java/com/prompthub/product/domain/repository/OutboxEventRepository.java
package com.prompthub.product.domain.repository;

import com.prompthub.product.domain.model.entity.OutboxEvent;
import java.util.List;

public interface OutboxEventRepository {
	OutboxEvent save(OutboxEvent outboxEvent);
	List<OutboxEvent> findPendingEvents(int batchSize);
}
```

```java
// product-service/src/main/java/com/prompthub/product/infra/persistence/outbox/OutboxEventPersistence.java
package com.prompthub.product.infra.persistence.outbox;

import com.prompthub.product.domain.model.entity.OutboxEvent;
import com.prompthub.product.domain.model.enums.OutboxEventStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventPersistence extends JpaRepository<OutboxEvent, UUID> {
	List<OutboxEvent> findByStatusOrderByOccurredAtAsc(OutboxEventStatus status, Pageable pageable);
}
```

```java
// product-service/src/main/java/com/prompthub/product/infra/persistence/outbox/OutboxEventAdapter.java
package com.prompthub.product.infra.persistence.outbox;

import com.prompthub.product.domain.model.entity.OutboxEvent;
import com.prompthub.product.domain.model.enums.OutboxEventStatus;
import com.prompthub.product.domain.repository.OutboxEventRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxEventAdapter implements OutboxEventRepository {

	private final OutboxEventPersistence outboxEventPersistence;

	@Override
	public OutboxEvent save(OutboxEvent outboxEvent) {
		return outboxEventPersistence.save(outboxEvent);
	}

	@Override
	public List<OutboxEvent> findPendingEvents(int batchSize) {
		return outboxEventPersistence.findByStatusOrderByOccurredAtAsc(OutboxEventStatus.PENDING, PageRequest.of(0, batchSize));
	}
}
```

- [ ] **Step 5: 도메인 단위 테스트 작성**

```java
package com.prompthub.product.domain.model.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.product.domain.model.enums.OutboxEventStatus;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxEventTest {

	@Test
	void create_생성하면_PENDING_상태다() {
		OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "PRODUCT_ON_SALE_CHANGED", "{}", LocalDateTime.now());

		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(event.getRetryCount()).isZero();
	}

	@Test
	void markPublished_호출하면_PUBLISHED로_전이하고_시각을_남긴다() {
		OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "PRODUCT_ON_SALE_CHANGED", "{}", LocalDateTime.now());
		LocalDateTime publishedAt = LocalDateTime.now();

		event.markPublished(publishedAt);

		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
		assertThat(event.getPublishedAt()).isEqualTo(publishedAt);
	}

	@Test
	void recordPublishFailure_최대횟수_미만이면_PENDING을_유지한다() {
		OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "PRODUCT_ON_SALE_CHANGED", "{}", LocalDateTime.now());

		event.recordPublishFailure(3);

		assertThat(event.getRetryCount()).isEqualTo(1);
		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
	}

	@Test
	void recordPublishFailure_최대횟수에_도달하면_FAILED로_전이한다() {
		OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "PRODUCT_ON_SALE_CHANGED", "{}", LocalDateTime.now());

		event.recordPublishFailure(1);

		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
	}
}
```

- [ ] **Step 6: 빌드 및 테스트 실행**

Run: `./gradlew :product-service:test --tests "com.prompthub.product.domain.model.entity.OutboxEventTest"`
Expected: PASS (4개 테스트)

- [ ] **Step 7: 커밋**

```bash
git add product-service/src/main/resources/db/migration/V3__product_outbox_event.sql
git add product-service/src/main/java/com/prompthub/product/domain/model/enums/OutboxEventStatus.java
git add product-service/src/main/java/com/prompthub/product/domain/model/entity/OutboxEvent.java
git add product-service/src/main/java/com/prompthub/product/domain/repository/OutboxEventRepository.java
git add product-service/src/main/java/com/prompthub/product/infra/persistence/outbox/
git add product-service/src/test/java/com/prompthub/product/domain/model/entity/OutboxEventTest.java
git commit -m "feat: product_outbox_event 아웃박스 인프라 추가 (#376)"
```

---

### Task 2: `OutboxEventAppender` + `OutboxRelay` (product-service 발행 측)

**Files:**
- Create: `product-service/src/main/java/com/prompthub/product/application/service/outbox/OutboxEventAppender.java`
- Create: `product-service/src/main/java/com/prompthub/product/infra/messaging/producer/OutboxRelay.java`
- Create: `product-service/src/main/java/com/prompthub/product/infra/messaging/producer/OutboxRelayProperties.java`
- Create: `product-service/src/main/java/com/prompthub/product/infra/messaging/config/OutboxRelayConfig.java`
- Modify: `product-service/src/main/java/com/prompthub/product/infra/messaging/config/KafkaConfig.java` (raw-string `KafkaTemplate<String,String>` 빈 추가)
- Modify: `product-service/src/main/resources/application-local.yml`
- Modify: `config/src/main/resources/configs/product-service.yml`
- Test: `product-service/src/test/java/com/prompthub/product/infra/messaging/producer/OutboxRelayTest.java`

**Interfaces:**
- Consumes: Task 1의 `OutboxEvent`, `OutboxEventRepository`.
- Produces: `OutboxEventAppender.append(EventMessage<?> message)` — Task 3(패치버전 발행)과 Task 4(admin-service, 단 admin은 자체 팩토리 사용)가 아니라, product-service 자체 발행 경로에서 쓰임. (Task 3은 기존 `ProductEventProducer`의 AFTER_COMMIT 직접 발행을 그대로 쓰므로 실제로는 `OutboxEventAppender`를 호출하지 않는다 — 이 컴포넌트는 이후 다른 product-service 자체 아웃박스 발행이 필요해질 때를 대비해 order-service와 동일한 형태로만 둔다. 지금 당장 실제로 호출하는 곳은 없어도, `OutboxRelay`가 폴링해서 발행하는 대상은 **admin-service가 insert한 행**이므로 Task 2/4가 함께 있어야 완결된다.)

- [ ] **Step 1: `OutboxEventAppender` 작성**

```java
package com.prompthub.product.application.service.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prompthub.common.event.EventMessage;
import com.prompthub.product.domain.model.entity.OutboxEvent;
import com.prompthub.product.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxEventAppender {

	private final ObjectMapper objectMapper;
	private final OutboxEventRepository outboxEventRepository;

	public void append(EventMessage<?> message) {
		String payloadJson = serialize(message);
		OutboxEvent entity = OutboxEvent.create(
			message.eventId(), message.aggregateId(), message.eventType(), payloadJson, message.occurredAt()
		);
		outboxEventRepository.save(entity);
	}

	private String serialize(EventMessage<?> message) {
		try {
			return objectMapper.writeValueAsString(message);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("아웃박스 이벤트 직렬화에 실패했습니다. eventId=" + message.eventId(), e);
		}
	}
}
```

- [ ] **Step 2: `OutboxRelayProperties` + `OutboxRelayConfig` 작성**

```java
// product-service/src/main/java/com/prompthub/product/infra/messaging/producer/OutboxRelayProperties.java
package com.prompthub.product.infra.messaging.producer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "prompthub.outbox-relay")
public record OutboxRelayProperties(
	boolean enabled,
	long fixedDelayMs,
	int batchSize,
	int maxRetryCount,
	@DefaultValue("product-events") String topic
) {
}
```

```java
// product-service/src/main/java/com/prompthub/product/infra/messaging/config/OutboxRelayConfig.java
package com.prompthub.product.infra.messaging.config;

import com.prompthub.product.infra.messaging.producer.OutboxRelayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxRelayProperties.class)
public class OutboxRelayConfig {
}
```

- [ ] **Step 3: `OutboxRelay` 작성**

```java
package com.prompthub.product.infra.messaging.producer;

import com.prompthub.product.domain.model.entity.OutboxEvent;
import com.prompthub.product.domain.repository.OutboxEventRepository;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "prompthub.outbox-relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

	private final OutboxEventRepository outboxEventRepository;
	private final KafkaTemplate<String, String> stringKafkaTemplate;
	private final OutboxRelayProperties properties;

	@Transactional
	@Scheduled(fixedDelayString = "${prompthub.outbox-relay.fixed-delay-ms:5000}")
	public void publishPendingEvents() {
		outboxEventRepository.findPendingEvents(properties.batchSize()).forEach(this::publish);
	}

	private void publish(OutboxEvent event) {
		try {
			stringKafkaTemplate.send(properties.topic(), event.getAggregateId().toString(), event.getPayload()).get();
			event.markPublished(LocalDateTime.now());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			recordFailure(event, exception);
		} catch (ExecutionException exception) {
			recordFailure(event, exception);
		}
	}

	private void recordFailure(OutboxEvent event, Exception exception) {
		event.recordPublishFailure(properties.maxRetryCount());
		log.warn(
			"아웃박스 이벤트 발행에 실패했습니다. outboxEventId={}, eventType={}, retryCount={}, status={}",
			event.getEventId(), event.getEventType(), event.getRetryCount(), event.getStatus(), exception
		);
	}
}
```

- [ ] **Step 4: `KafkaConfig`에 raw-string `KafkaTemplate` 빈 추가**

`product-service/src/main/java/com/prompthub/product/infra/messaging/config/KafkaConfig.java`의 기존 `kafkaTemplate(...)` 빈 아래에 추가:

```java
	@Bean
	public ProducerFactory<String, String> stringProducerFactory() {
		Map<String, Object> config = new HashMap<>();
		config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		return new DefaultKafkaProducerFactory<>(config);
	}

	@Bean
	public KafkaTemplate<String, String> stringKafkaTemplate(ProducerFactory<String, String> stringProducerFactory) {
		return new KafkaTemplate<>(stringProducerFactory);
	}
```

- [ ] **Step 5: 설정값 추가**

`product-service/src/main/resources/application-local.yml`의 `kafka:` 블록 아래, 최상위에 추가:

```yaml
prompthub:
  outbox-relay:
    enabled: true
    fixed-delay-ms: 5000
    batch-size: 100
    max-retry-count: 3
```

`config/src/main/resources/configs/product-service.yml`에도 동일하게 추가(배포 환경용, `configs/`는 전원 공유 영역이라 직접 수정 가능).

- [ ] **Step 6: `OutboxRelay` 단위 테스트 작성**

```java
package com.prompthub.product.infra.messaging.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.prompthub.product.domain.model.entity.OutboxEvent;
import com.prompthub.product.domain.model.enums.OutboxEventStatus;
import com.prompthub.product.domain.repository.OutboxEventRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

	private static final OutboxRelayProperties PROPERTIES = new OutboxRelayProperties(true, 5000L, 100, 3, "product-events");

	@Mock
	private OutboxEventRepository outboxEventRepository;

	@Mock
	private KafkaTemplate<String, String> stringKafkaTemplate;

	private OutboxRelay outboxRelay;

	@BeforeEach
	void setUp() {
		outboxRelay = new OutboxRelay(outboxEventRepository, stringKafkaTemplate, PROPERTIES);
	}

	@Test
	void publishPendingEvents_성공하면_PUBLISHED로_전이한다() {
		OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "PRODUCT_ON_SALE_CHANGED", "{}", LocalDateTime.now());
		given(outboxEventRepository.findPendingEvents(100)).willReturn(List.of(event));
		given(stringKafkaTemplate.send(eq("product-events"), any(), any()))
			.willReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

		outboxRelay.publishPendingEvents();

		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
		verify(stringKafkaTemplate).send("product-events", event.getAggregateId().toString(), event.getPayload());
	}

	@Test
	void publishPendingEvents_실패하면_재시도횟수만_증가하고_PENDING을_유지한다() {
		OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "PRODUCT_ON_SALE_CHANGED", "{}", LocalDateTime.now());
		given(outboxEventRepository.findPendingEvents(100)).willReturn(List.of(event));
		CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
		failed.completeExceptionally(new RuntimeException("kafka down"));
		given(stringKafkaTemplate.send(eq("product-events"), any(), any())).willReturn(failed);

		outboxRelay.publishPendingEvents();

		assertThat(event.getRetryCount()).isEqualTo(1);
		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
	}

	@Test
	void publishPendingEvents_최대재시도_도달하면_FAILED로_전이한다() {
		OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "PRODUCT_ON_SALE_CHANGED", "{}", LocalDateTime.now());
		event.recordPublishFailure(3);
		event.recordPublishFailure(3);
		given(outboxEventRepository.findPendingEvents(100)).willReturn(List.of(event));
		CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
		failed.completeExceptionally(new RuntimeException("kafka down"));
		given(stringKafkaTemplate.send(eq("product-events"), any(), any())).willReturn(failed);

		outboxRelay.publishPendingEvents();

		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
	}
}
```

- [ ] **Step 7: 빌드/테스트 실행**

Run: `./gradlew :product-service:test --tests "com.prompthub.product.infra.messaging.producer.OutboxRelayTest"`
Expected: PASS (3개 테스트)

- [ ] **Step 8: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/application/service/outbox/
git add product-service/src/main/java/com/prompthub/product/infra/messaging/producer/OutboxRelay.java
git add product-service/src/main/java/com/prompthub/product/infra/messaging/producer/OutboxRelayProperties.java
git add product-service/src/main/java/com/prompthub/product/infra/messaging/config/OutboxRelayConfig.java
git add product-service/src/main/java/com/prompthub/product/infra/messaging/config/KafkaConfig.java
git add product-service/src/main/resources/application-local.yml
git add config/src/main/resources/configs/product-service.yml
git add product-service/src/test/java/com/prompthub/product/infra/messaging/producer/OutboxRelayTest.java
git commit -m "feat: product-service OutboxRelay 추가 (#376)"
```

---

### Task 3: `PRODUCT_ON_SALE_CHANGED` 발행 — product-service 자체 트리거(패치버전)

셀러가 패치버전을 등록하면 검수 없이 즉시 ON_SALE이 된다(`ProductSellerService.updateProduct`, `!isMajor` 분기). 이 경로는 product-service 자신의 쓰기이므로 기존 `ProductEventProducer`(AFTER_COMMIT 직접 발행)에 케이스를 추가하는 것으로 충분하다 — 아웃박스 불필요.

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/infra/messaging/producer/ProductEventType.java`
- Create: `product-service/src/main/java/com/prompthub/product/infra/messaging/producer/event/ProductOnSaleChangedPayload.java`
- Modify: `product-service/src/main/java/com/prompthub/product/infra/messaging/producer/ProductEventProducer.java`
- Modify: `product-service/src/main/java/com/prompthub/product/application/service/ProductSellerService.java`
- Modify: `product-service/src/test/java/com/prompthub/product/application/service/ProductSellerServiceTest.java`

**Interfaces:**
- Produces: `ProductEventProducer.publishOnSaleChanged(UUID familyRootId)` — Kafka key/aggregateId는 **familyRootId**(기존 3개 이벤트는 productId가 key인 것과 다름, admin-onsale-event-design.md §3 결정). `ProductEventType.from(String code): Optional<ProductEventType>` — Task 7의 컨슈머가 사용.

- [ ] **Step 1: `ProductEventType`에 값 추가 + `from()` 팩토리 추가**

```java
package com.prompthub.product.infra.messaging.producer;

import com.prompthub.common.event.EventType;
import java.util.Arrays;
import java.util.Optional;

public enum ProductEventType implements EventType {

	PRODUCT_STOPPED,
	PRODUCT_DELETED,
	PRODUCT_PRICE_CHANGED,
	PRODUCT_ON_SALE_CHANGED;

	@Override
	public String code() {
		return name();
	}

	public static Optional<ProductEventType> from(String code) {
		return Arrays.stream(values()).filter(type -> type.name().equals(code)).findFirst();
	}
}
```

- [ ] **Step 2: payload record 작성**

```java
package com.prompthub.product.infra.messaging.producer.event;

import java.util.UUID;

public record ProductOnSaleChangedPayload(UUID familyRootId) {

	public static ProductOnSaleChangedPayload of(UUID familyRootId) {
		return new ProductOnSaleChangedPayload(familyRootId);
	}
}
```

- [ ] **Step 3: `ProductEventProducer`에 `publishOnSaleChanged` 추가**

`ProductEventProducer.java` 상단 import에 `com.prompthub.product.infra.messaging.producer.event.ProductOnSaleChangedPayload` 추가, `publishPriceChanged` 메서드 아래에 추가:

```java
	public void publishOnSaleChanged(UUID familyRootId) {
		publish(ProductEventType.PRODUCT_ON_SALE_CHANGED, familyRootId, ProductOnSaleChangedPayload.of(familyRootId));
	}
```

- [ ] **Step 4: `ProductSellerService.updateProduct` 패치버전 분기에 발행 추가**

`ProductSellerService.java`의 `else` 분기(패치버전, 110~115행)를 아래로 교체:

```java
			} else {
				Product next = onSale.nextVersion(false, content, request.changeReason());
				onSale.supersede();
				productRepository.save(onSale);
				productRepository.save(next);
				productEventProducer.publishOnSaleChanged(familyRootId);
			}
```

- [ ] **Step 5: 기존 테스트에 발행 검증 추가**

`ProductSellerServiceTest.java`의 `updateProduct_patchAfterOnSale_createsOnSaleChild_supersedesPrevious`(90~105행) 끝에 한 줄 추가:

```java
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
			then(productEventProducer).should().publishOnSaleChanged(PRODUCT_ID);
		}
```

(`PRODUCT_ID`가 곧 familyRootId인 이유: `product(PRODUCT_ID, null, ...)`로 `parentId=null`이라 `familyRootId() == PRODUCT_ID`.)

- [ ] **Step 6: 빌드/테스트 실행**

Run: `./gradlew :product-service:test --tests "com.prompthub.product.application.service.ProductSellerServiceTest"`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/infra/messaging/producer/
git add product-service/src/main/java/com/prompthub/product/application/service/ProductSellerService.java
git add product-service/src/test/java/com/prompthub/product/application/service/ProductSellerServiceTest.java
git commit -m "feat: 패치버전 등록 시 PRODUCT_ON_SALE_CHANGED 발행 (#376)"
```

---

### Task 4 ⚠️ admin-service 발행 측 — 실행 전 필수 재승인

> **STOP: 이 태스크를 시작하기 전에 반드시 사용자에게 "admin-service의 `approveProduct()`/`revertProductToPendingReview()`에 outbox insert를 추가해도 될까요?"라고 다시 확인한다. `product-service/.claude/rules/architecture.md`의 "다른 서비스 코드 변경 전 사용자 승인 필요" 규칙 대상이다 — 이전에 이 plan 전체에 대해 받은 승인과는 별개로, 이 태스크 실행 직전 재확인이 필요하다.**

admin-service는 Kafka·common-module을 전혀 모른다(의존성 없음). `product_outbox_event`에 이미 알고 있는 방식(`product_service.product` 테이블에 직접 쓰는 것)과 동일하게 행 하나를 insert하는 것만 추가한다.

**Files:**
- Create: `admin-service/src/main/java/com/prompthub/admin/product/domain/model/entity/OutboxEvent.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/product/domain/repository/OutboxEventRepository.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/product/infrastructure/persistence/OutboxEventJpaRepository.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/product/infrastructure/persistence/OutboxEventRepositoryAdapter.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/product/application/service/ProductOnSaleChangedEventFactory.java`
- Modify: `admin-service/src/main/java/com/prompthub/admin/product/application/service/ProductService.java`
- Modify: `admin-service/src/test/java/com/prompthub/admin/product/application/service/ProductServiceTest.java`

**Interfaces:**
- Produces: `OutboxEventRepository.append(OutboxEvent)`, `ProductOnSaleChangedEventFactory.createEnvelopeJson(UUID familyRootId): String` — Task 7의 색인 컨슈머가 이 envelope JSON을 `product-events`에서 그대로 읽는다(`EventMessage<JsonNode>` 형태와 정확히 일치해야 함).

- [ ] **Step 1: admin-service용 `OutboxEvent` 엔티티 작성 (읽기 없이 insert 전용)**

```java
package com.prompthub.admin.product.domain.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * product_service.product_outbox_event에 매핑되는 admin-service 전용 엔티티.
 * admin-service는 ddl-auto=none이라 이 테이블을 만들 수 없다 — 소유·폴링은
 * product-service의 OutboxRelay가 담당하고, admin-service는 승인/복귀 트랜잭션
 * 끝에 행을 insert만 한다. (2026-07-21-admin-product-onsale-event-design.md)
 */
@Getter
@Entity
@Table(name = "product_outbox_event", schema = "product_service")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

	@Id
	@Column(name = "event_id", columnDefinition = "uuid")
	private UUID eventId;

	@Column(name = "aggregate_id", columnDefinition = "uuid", nullable = false)
	private UUID aggregateId;

	@Column(name = "event_type", length = 100, nullable = false)
	private String eventType;

	@Column(name = "payload", columnDefinition = "text", nullable = false)
	private String payload;

	@Column(name = "status", length = 20, nullable = false)
	private String status;

	@Column(name = "retry_count", nullable = false)
	private int retryCount;

	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;

	private OutboxEvent(UUID eventId, UUID aggregateId, String eventType, String payload, LocalDateTime occurredAt) {
		this.eventId = eventId;
		this.aggregateId = aggregateId;
		this.eventType = eventType;
		this.payload = payload;
		this.status = "PENDING";
		this.retryCount = 0;
		this.occurredAt = occurredAt;
	}

	public static OutboxEvent create(UUID aggregateId, String eventType, String payload) {
		return new OutboxEvent(UUID.randomUUID(), aggregateId, eventType, payload, LocalDateTime.now());
	}
}
```

- [ ] **Step 2: 도메인 포트 + JPA 리포지토리 + 어댑터**

```java
// admin-service/src/main/java/com/prompthub/admin/product/domain/repository/OutboxEventRepository.java
package com.prompthub.admin.product.domain.repository;

import com.prompthub.admin.product.domain.model.entity.OutboxEvent;

public interface OutboxEventRepository {
	void append(OutboxEvent outboxEvent);
}
```

```java
// admin-service/src/main/java/com/prompthub/admin/product/infrastructure/persistence/OutboxEventJpaRepository.java
package com.prompthub.admin.product.infrastructure.persistence;

import com.prompthub.admin.product.domain.model.entity.OutboxEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEvent, UUID> {
}
```

```java
// admin-service/src/main/java/com/prompthub/admin/product/infrastructure/persistence/OutboxEventRepositoryAdapter.java
package com.prompthub.admin.product.infrastructure.persistence;

import com.prompthub.admin.product.domain.model.entity.OutboxEvent;
import com.prompthub.admin.product.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxEventRepositoryAdapter implements OutboxEventRepository {

	private final OutboxEventJpaRepository outboxEventJpaRepository;

	@Override
	public void append(OutboxEvent outboxEvent) {
		outboxEventJpaRepository.save(outboxEvent);
	}
}
```

- [ ] **Step 3: `EventMessage` 봉투를 직접 조립하는 팩토리 작성**

```java
package com.prompthub.admin.product.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * admin-service는 common-module(EventMessage)에 의존하지 않는다(Kafka를 모름).
 * product-events가 기대하는 EventMessage 봉투와 동일한 구조를 이 안에서만 직접
 * 조립한다. (kafka-event.md §2 EventMessage 계약, admin-onsale-event-design.md §3)
 */
@Component
@RequiredArgsConstructor
public class ProductOnSaleChangedEventFactory {

	private static final String EVENT_TYPE = "PRODUCT_ON_SALE_CHANGED";
	private static final String AGGREGATE_TYPE = "PRODUCT";

	private final ObjectMapper objectMapper;

	public String createEnvelopeJson(UUID familyRootId) {
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("eventId", UUID.randomUUID());
		envelope.put("eventType", EVENT_TYPE);
		envelope.put("occurredAt", LocalDateTime.now());
		envelope.put("aggregateType", AGGREGATE_TYPE);
		envelope.put("aggregateId", familyRootId);
		envelope.put("payload", Map.of("familyRootId", familyRootId));
		try {
			return objectMapper.writeValueAsString(envelope);
		} catch (Exception e) {
			throw new IllegalStateException("PRODUCT_ON_SALE_CHANGED 이벤트 직렬화에 실패했습니다. familyRootId=" + familyRootId, e);
		}
	}
}
```

- [ ] **Step 4: `ProductService`에 outbox insert 추가**

`admin-service/src/main/java/com/prompthub/admin/product/application/service/ProductService.java` 수정 — 상단에 필드 2개 추가(`@RequiredArgsConstructor`이므로 생성자 자동 반영):

```java
	private final OutboxEventRepository outboxEventRepository;
	private final ProductOnSaleChangedEventFactory eventFactory;
```

`approveProduct` 전체를 아래로 교체:

```java
	@Override
	public void approveProduct(UUID productId) {
		Product target = getProductInPendingReview(productId);
		UUID familyRootId = target.familyRootId();
		ProductFamily family = ProductFamily.of(
			familyRootId,
			productRepository.findAllByFamilyRootIds(List.of(familyRootId))
		);
		family.currentOnSale().ifPresent(previous -> {
			previous.supersede();
			productRepository.save(previous);
		});
		target.approve();
		productRepository.save(target);

		outboxEventRepository.append(
			OutboxEvent.create(familyRootId, "PRODUCT_ON_SALE_CHANGED", eventFactory.createEnvelopeJson(familyRootId))
		);
	}
```

`revertProductToPendingReview` 전체를 아래로 교체:

```java
	@Override
	public void revertProductToPendingReview(UUID productId) {
		Product target = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(AdminErrorCode.PRODUCT_NOT_FOUND));
		if (target.getStatus() != ProductStatus.ON_SALE && target.getStatus() != ProductStatus.REJECTED) {
			throw new ProductException(AdminErrorCode.PRODUCT_INVALID_STATUS);
		}

		boolean wasOnSale = target.getStatus() == ProductStatus.ON_SALE;
		UUID familyRootId = target.familyRootId();

		if (wasOnSale) {
			ProductFamily family = ProductFamily.of(
				familyRootId,
				productRepository.findAllByFamilyRootIds(List.of(familyRootId))
			);
			family.mostRecentSuperseded().ifPresent(paired -> {
				paired.restoreFromSuperseded();
				productRepository.save(paired);
			});
		}

		target.revertToPendingReview();
		productRepository.save(target);

		if (wasOnSale) {
			outboxEventRepository.append(
				OutboxEvent.create(familyRootId, "PRODUCT_ON_SALE_CHANGED", eventFactory.createEnvelopeJson(familyRootId))
			);
		}
	}
```

(import 추가: `com.prompthub.admin.product.domain.model.entity.OutboxEvent`, `com.prompthub.admin.product.domain.repository.OutboxEventRepository`)

- [ ] **Step 5: 기존 테스트 갱신 — mock 추가 + 발행 검증**

`ProductServiceTest.java` 상단에 mock 추가:

```java
	@Mock
	private OutboxEventRepository outboxEventRepository;

	@Mock
	private ProductOnSaleChangedEventFactory eventFactory;
```

`ApproveProduct` 중첩 클래스의 두 테스트 끝에 각각 추가:

```java
		@Test
		@DisplayName("기존 ON_SALE row가 있으면 SUPERSEDED로 전환하고 대상 row를 ON_SALE로 승인한다")
		void approveProduct_supersedesPreviousOnSale() {
			Product onSale = product(FAMILY_ROOT_ID, null, ProductStatus.ON_SALE, (short) 2, (short) 0);
			Product pending = product(UUID.randomUUID(), FAMILY_ROOT_ID, ProductStatus.PENDING_REVIEW, (short) 3, (short) 0);
			given(productRepository.findById(pending.getId())).willReturn(Optional.of(pending));
			given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(onSale, pending));

			productAdminService.approveProduct(pending.getId());

			assertThat(onSale.getStatus()).isEqualTo(ProductStatus.SUPERSEDED);
			assertThat(pending.getStatus()).isEqualTo(ProductStatus.ON_SALE);
			then(productRepository).should().save(onSale);
			then(productRepository).should().save(pending);
			then(outboxEventRepository).should().append(org.mockito.ArgumentMatchers.argThat(event ->
				event.getAggregateId().equals(FAMILY_ROOT_ID) && event.getEventType().equals("PRODUCT_ON_SALE_CHANGED")));
		}

		@Test
		@DisplayName("기존 ON_SALE row가 없으면(최초 승인) supersede 없이 승인만 한다")
		void approveProduct_firstApproval_noSupersede() {
			Product pending = product(FAMILY_ROOT_ID, null, ProductStatus.PENDING_REVIEW, (short) 1, (short) 0);
			given(productRepository.findById(pending.getId())).willReturn(Optional.of(pending));
			given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(pending));

			productAdminService.approveProduct(pending.getId());

			assertThat(pending.getStatus()).isEqualTo(ProductStatus.ON_SALE);
			then(productRepository).should(org.mockito.Mockito.times(1)).save(pending);
			then(outboxEventRepository).should().append(org.mockito.ArgumentMatchers.argThat(event ->
				event.getAggregateId().equals(FAMILY_ROOT_ID) && event.getEventType().equals("PRODUCT_ON_SALE_CHANGED")));
		}
```

`RevertProductToPendingReview` 중첩 클래스 세 테스트 끝에 각각 추가:

```java
		@Test
		@DisplayName("ON_SALE row를 되돌리면 짝이었던 SUPERSEDED row를 ON_SALE로 복원한다")
		void revert_onSaleRow_restoresPairedSupersededRow() {
			Product superseded = product(FAMILY_ROOT_ID, null, ProductStatus.SUPERSEDED, (short) 2, (short) 0);
			Product onSale = product(UUID.randomUUID(), FAMILY_ROOT_ID, ProductStatus.ON_SALE, (short) 3, (short) 0);
			given(productRepository.findById(onSale.getId())).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(superseded, onSale));

			productAdminService.revertProductToPendingReview(onSale.getId());

			assertThat(onSale.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
			assertThat(superseded.getStatus()).isEqualTo(ProductStatus.ON_SALE);
			then(outboxEventRepository).should().append(org.mockito.ArgumentMatchers.argThat(event ->
				event.getAggregateId().equals(FAMILY_ROOT_ID)));
		}

		@Test
		@DisplayName("짝이 없으면(최초 승인) 대상 row만 되돌린다")
		void revert_onSaleRow_noSupersededPair_onlyTargetChanges() {
			Product onSale = product(FAMILY_ROOT_ID, null, ProductStatus.ON_SALE, (short) 1, (short) 0);
			given(productRepository.findById(FAMILY_ROOT_ID)).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(onSale));

			productAdminService.revertProductToPendingReview(FAMILY_ROOT_ID);

			assertThat(onSale.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
			then(outboxEventRepository).should().append(org.mockito.ArgumentMatchers.argThat(event ->
				event.getAggregateId().equals(FAMILY_ROOT_ID)));
		}

		@Test
		@DisplayName("REJECTED row를 되돌릴 때는 family 조회 없이 대상만 변경한다")
		void revert_rejectedRow_doesNotTouchFamily() {
			Product rejected = product(FAMILY_ROOT_ID, null, ProductStatus.REJECTED, (short) 1, (short) 0);
			given(productRepository.findById(FAMILY_ROOT_ID)).willReturn(Optional.of(rejected));

			productAdminService.revertProductToPendingReview(FAMILY_ROOT_ID);

			assertThat(rejected.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
			then(productRepository).should(org.mockito.Mockito.never()).findAllByFamilyRootIds(org.mockito.ArgumentMatchers.anyList());
			then(outboxEventRepository).should(org.mockito.Mockito.never()).append(org.mockito.ArgumentMatchers.any());
		}
```

- [ ] **Step 6: 빌드/테스트 실행**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.product.application.service.ProductServiceTest"`
Expected: PASS

- [ ] **Step 7: 커밋 (admin-service 브랜치/PR 관례를 따른다 — product-service와 별도 관리 권장)**

```bash
git add admin-service/src/main/java/com/prompthub/admin/product/domain/model/entity/OutboxEvent.java
git add admin-service/src/main/java/com/prompthub/admin/product/domain/repository/OutboxEventRepository.java
git add admin-service/src/main/java/com/prompthub/admin/product/infrastructure/persistence/OutboxEventJpaRepository.java
git add admin-service/src/main/java/com/prompthub/admin/product/infrastructure/persistence/OutboxEventRepositoryAdapter.java
git add admin-service/src/main/java/com/prompthub/admin/product/application/service/ProductOnSaleChangedEventFactory.java
git add admin-service/src/main/java/com/prompthub/admin/product/application/service/ProductService.java
git add admin-service/src/test/java/com/prompthub/admin/product/application/service/ProductServiceTest.java
git commit -m "feat: 승인/복귀 시 PRODUCT_ON_SALE_CHANGED 아웃박스 발행 (#376 선행)"
```

---

### Task 5: 로컬 ES 인프라 (nori 커스텀 이미지, docker-compose, 빌드 의존성)

**Files:**
- Create: `product-service/docker/elasticsearch/Dockerfile`
- Modify: `product-service/docker-compose.yml`
- Modify: `product-service/build.gradle`
- Modify: `product-service/src/main/resources/application-local.yml`
- Modify: `config/src/main/resources/configs/product-service.yml`

- [ ] **Step 1: nori 커스텀 Dockerfile 작성**

```dockerfile
FROM docker.elastic.co/elasticsearch/elasticsearch:9.4.3
RUN bin/elasticsearch-plugin install --batch analysis-nori
```

- [ ] **Step 2: docker-compose에 서비스 추가**

`product-service/docker-compose.yml`에 `product-kafka` 서비스 아래 추가:

```yaml
  product-elasticsearch:
    build:
      context: ./docker/elasticsearch
    container_name: product-elasticsearch-dev
    environment:
      discovery.type: single-node
      xpack.security.enabled: 'false'
      ES_JAVA_OPTS: '-Xms1g -Xmx1g'
    ports:
      - '9200:9200'
```

- [ ] **Step 3: 빌드 의존성 추가**

`product-service/build.gradle`의 `dependencies { ... }` 블록, gRPC 의존성 아래에 추가:

```groovy
    // Elasticsearch (검색 색인 파이프라인, #376) — 정확한 patch 버전은 ES 서버(9.4.3)와
    // 맞춰 Maven Central에서 확인 후 고정한다.
    implementation 'co.elastic.clients:elasticsearch-java:9.0.2'
    implementation 'com.fasterxml.jackson.core:jackson-databind'

    // 실행 중 발견: org.testcontainers:elasticsearch 전용 모듈은 testcontainers-core
    // 2.x 라인에 맞는 릴리스가 아직 없다(Maven Central 최신 1.21.4, core는 2.0.5로 resolve됨).
    // 별도 의존성 추가 없이 testcontainers-junit-jupiter가 가져오는 core의
    // GenericContainer로 직접 띄운다(Task 6 Step 4).
```

- [ ] **Step 4: 로컬/배포 설정값 추가**

`product-service/src/main/resources/application-local.yml`의 `cloud.aws` 블록 아래 최상위에 추가:

```yaml
elasticsearch:
  uris: ${ES_URIS:http://localhost:9200}
```

`config/src/main/resources/configs/product-service.yml`에도 동일하게 추가:

```yaml
elasticsearch:
  uris: ${ES_URIS}
```

- [ ] **Step 5: 로컬 기동 검증 (자동화 테스트 아님 — 수동 확인)**

Run:
```bash
cd product-service
docker compose up -d product-elasticsearch
curl -s localhost:9200/_cat/plugins?v
```
Expected: `analysis-nori` 플러그인이 목록에 보임. (CI에서도 이 이미지를 미리 빌드해야 Task 6 통합 테스트가 통과한다 — `.github/workflows/product-service-ci.yml`에 `docker build -t product-elasticsearch-nori:9.4.3 product-service/docker/elasticsearch` 스텝 추가 필요. 이 CI 워크플로 수정은 별도 확인 후 진행한다.)

- [ ] **Step 6: 커밋**

```bash
git add product-service/docker/elasticsearch/Dockerfile
git add product-service/docker-compose.yml
git add product-service/build.gradle
git add product-service/src/main/resources/application-local.yml
git add config/src/main/resources/configs/product-service.yml
git commit -m "chore: 로컬 ES(nori) 인프라·의존성 추가 (#376)"
```

---

### Task 6: `products` 인덱스 매핑 + 부트스트랩

**Files:**
- Create: `product-service/src/main/resources/es/products-v1-mapping.json`
- Create: `product-service/src/main/java/com/prompthub/product/search/infra/es/ElasticsearchClientConfig.java`
- Create: `product-service/src/main/java/com/prompthub/product/search/infra/es/ProductIndexBootstrap.java`
- Create: `product-service/src/test/java/com/prompthub/product/search/support/ElasticsearchIntegrationTestSupport.java`
- Test: `product-service/src/test/java/com/prompthub/product/search/infra/es/ProductIndexBootstrapIntegrationTest.java`

**Interfaces:**
- Produces: `ProductIndexBootstrap.ALIAS = "products"` — Task 7·8·9의 색인기가 이 alias 상수를 그대로 참조한다. `ElasticsearchClient` 빈 — Task 7·8의 색인 로직이 주입받아 사용.

- [ ] **Step 1: 매핑 JSON 작성 (벡터·자동완성 서브필드 포함, 값은 미사용)**

```json
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0,
    "analysis": {
      "tokenizer": {
        "autocomplete_edge_ngram": {
          "type": "edge_ngram",
          "min_gram": 1,
          "max_gram": 20,
          "token_chars": ["letter", "digit"]
        }
      },
      "analyzer": {
        "korean": {
          "type": "custom",
          "tokenizer": "nori_tokenizer"
        },
        "autocomplete": {
          "type": "custom",
          "tokenizer": "autocomplete_edge_ngram"
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "familyRootId": { "type": "keyword" },
      "productId": { "type": "keyword" },
      "sellerId": { "type": "keyword" },
      "name": {
        "type": "text",
        "analyzer": "korean",
        "fields": {
          "keyword": { "type": "keyword" },
          "jamo": { "type": "text", "analyzer": "autocomplete" },
          "chosung": { "type": "text", "analyzer": "autocomplete" }
        }
      },
      "description": { "type": "text", "analyzer": "korean" },
      "content": { "type": "text", "analyzer": "korean" },
      "extractedText": { "type": "text", "analyzer": "korean" },
      "tags": {
        "type": "keyword",
        "fields": {
          "text": { "type": "text", "analyzer": "korean" }
        }
      },
      "productType": { "type": "keyword" },
      "model": { "type": "keyword" },
      "amount": { "type": "integer" },
      "amountType": { "type": "keyword" },
      "thumbnailUrl": { "type": "keyword", "index": false },
      "badge": { "type": "keyword" },
      "salesCount": { "type": "integer" },
      "viewCount": { "type": "integer" },
      "reviewCount": { "type": "integer" },
      "ratingAvg": { "type": "float" },
      "firstPublishedAt": { "type": "date" },
      "currentVersionAt": { "type": "date" },
      "embedding": {
        "type": "dense_vector",
        "dims": 1536,
        "similarity": "cosine",
        "index_options": { "type": "int8_hnsw" }
      },
      "embeddingSourceHash": { "type": "keyword" }
    }
  }
}
```

- [ ] **Step 2: ES 클라이언트 빈 등록**

> **실행 중 발견한 정정 1**: `elasticsearch-java:9.0.2`는 7.x/8.x의
> `org.elasticsearch.client.RestClient`/`RestClientTransport`를 더 이상 쓰지 않는다(그
> 클래스 자체가 클래스패스에 없음 — httpclient5 기반 `Rest5Client`/`Rest5ClientTransport`로
> 교체됐다). 실제 클래스패스를 리플렉션으로 확인해 정정했다.
>
> **실행 중 발견한 정정 2**: httpclient5의 기본 async `HttpClient`가 응답 gzip을
> 자동 압축해제하는 과정에서 로컬 ES(9.4.3) 대상 실제 호출(`client.info()`,
> `indices.create` 등 전부)이 `java.util.zip.ZipException: Not in GZIP format`으로
> 실패하는 걸 재현·격리했다(Testcontainers와 무관 — 이미 떠 있는 docker-compose ES에
> 직접 호출해도 동일). `Rest5ClientBuilder.setCompressionEnabled(false)`는 이 문제를
> 해결하지 못했고, `HttpAsyncClients.custom().disableContentCompression()`으로 만든
> 커스텀 `CloseableHttpAsyncClient`를 주입해야 해결됐다.

```java
package com.prompthub.product.search.infra.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.http.HttpHost;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchClientConfig {

	@Value("${elasticsearch.uris}")
	private String uris;

	@Bean(destroyMethod = "close")
	public CloseableHttpAsyncClient elasticsearchHttpAsyncClient() {
		CloseableHttpAsyncClient client = HttpAsyncClients.custom().disableContentCompression().build();
		client.start();
		return client;
	}

	@Bean(destroyMethod = "close")
	public Rest5Client rest5Client(CloseableHttpAsyncClient elasticsearchHttpAsyncClient) throws java.net.URISyntaxException {
		return Rest5Client.builder(HttpHost.create(uris))
			.setHttpClient(elasticsearchHttpAsyncClient)
			.build();
	}

	@Bean
	public ElasticsearchTransport elasticsearchTransport(Rest5Client rest5Client) {
		return new Rest5ClientTransport(rest5Client, new JacksonJsonpMapper());
	}

	@Bean
	public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
		return new ElasticsearchClient(transport);
	}
}
```

- [ ] **Step 3: 인덱스·alias 부트스트랩 작성**

```java
package com.prompthub.product.search.infra.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductIndexBootstrap {

	public static final String ALIAS = "products";
	private static final String INDEX = "products-v1";
	private static final String MAPPING_RESOURCE = "es/products-v1-mapping.json";

	private final ElasticsearchClient client;

	@EventListener(ApplicationReadyEvent.class)
	public void createIndexIfMissing() throws IOException {
		if (client.indices().existsAlias(e -> e.name(ALIAS)).value()) {
			log.info("alias={} 이미 존재합니다. 인덱스 생성을 건너뜁니다.", ALIAS);
			return;
		}

		try (InputStream mapping = new ClassPathResource(MAPPING_RESOURCE).getInputStream()) {
			client.indices().create(c -> c.index(INDEX).withJson(mapping));
		}
		client.indices().putAlias(a -> a.index(INDEX).name(ALIAS));
		log.info("index={} 생성 및 alias={} 연결 완료", INDEX, ALIAS);
	}
}
```

- [ ] **Step 4: Testcontainers ES 지원 베이스 작성**

`ImageFromDockerfile`이 테스트 실행 시 Task 5의 Dockerfile로 매번 이미지를 빌드하므로
별도 사전 빌드 스텝은 필요 없다(레이어 캐시로 재빌드는 빠름). Docker 데몬만 떠 있으면 된다.

```java
package com.prompthub.product.search.support;

import com.prompthub.product.search.infra.es.ElasticsearchClientConfig;
import com.prompthub.product.search.infra.es.ProductIndexBootstrap;
import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * org.testcontainers:elasticsearch 전용 모듈이 testcontainers-core 2.x 라인에 맞는
 * 릴리스가 아직 없어 GenericContainer로 직접 nori 커스텀 이미지를 빌드해 띄운다.
 * Dockerfile은 product-service/docker/elasticsearch/Dockerfile과 동일 소스.
 *
 * **실행 중 발견한 정정 3**: classes를 ES 관련 빈으로 한정해야 한다 — 기본
 * 부트스트랩 클래스(ProductApplication) 전체를 띄우면 이 테스트와 무관한
 * S3Config 등이 함께 로드돼 AWS 자격증명 부재로 컨텍스트 로딩 자체가 실패한다.
 */
@SpringBootTest(classes = {ElasticsearchClientConfig.class, ProductIndexBootstrap.class})
public abstract class ElasticsearchIntegrationTestSupport {

	private static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>(
		new ImageFromDockerfile("product-elasticsearch-nori-test", false)
			.withDockerfile(Path.of("docker/elasticsearch/Dockerfile"))
	)
		.withEnv("discovery.type", "single-node")
		.withEnv("xpack.security.enabled", "false")
		.withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
		.withExposedPorts(9200)
		.waitingFor(Wait.forHttp("/").forStatusCode(200));

	static {
		ELASTICSEARCH.start();
	}

	@DynamicPropertySource
	static void esProperties(DynamicPropertyRegistry registry) {
		registry.add("elasticsearch.uris", () ->
			"http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200));
	}
}
```

- [ ] **Step 5: 부트스트랩 통합 테스트 작성**

```java
package com.prompthub.product.search.infra.es;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.product.search.support.ElasticsearchIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProductIndexBootstrapIntegrationTest extends ElasticsearchIntegrationTestSupport {

	@Autowired
	private co.elastic.clients.elasticsearch.ElasticsearchClient client;

	@Test
	void createIndexIfMissing_alias와_nori_분석기가_준비된다() throws Exception {
		boolean aliasExists = client.indices().existsAlias(e -> e.name(ProductIndexBootstrap.ALIAS)).value();
		assertThat(aliasExists).isTrue();

		var analyzeResponse = client.indices().analyze(a -> a
			.index("products-v1")
			.analyzer("korean")
			.text("프롬프트"));
		assertThat(analyzeResponse.tokens()).isNotEmpty();
	}
}
```

- [ ] **Step 6: 빌드/테스트 실행**

Run: `./gradlew :product-service:test --tests "com.prompthub.product.search.infra.es.ProductIndexBootstrapIntegrationTest"`
Expected: PASS (Testcontainers가 `product-elasticsearch-nori:9.4.3` 이미지로 컨테이너를 띄운 뒤 통과)

- [ ] **Step 7: 커밋**

```bash
git add product-service/src/main/resources/es/products-v1-mapping.json
git add product-service/src/main/java/com/prompthub/product/search/
git add product-service/src/test/java/com/prompthub/product/search/
git commit -m "feat: products 인덱스 매핑·부트스트랩 추가 (#376)"
```

---

### Task 7: 색인 컨슈머 (product-events → ES 반영)

**Files:**
- Create: `product-service/src/main/java/com/prompthub/product/search/infra/es/ProductSearchDocument.java`
- Create: `product-service/src/main/java/com/prompthub/product/search/application/ProductSearchIndexer.java`
- Create: `product-service/src/main/java/com/prompthub/product/search/infra/es/ElasticsearchProductSearchIndexer.java`
- Create: `product-service/src/main/java/com/prompthub/product/search/application/ProductSearchEventHandler.java`
- Create: `product-service/src/main/java/com/prompthub/product/search/infra/messaging/ProductSearchEventConsumer.java`
- Modify: `product-service/src/main/java/com/prompthub/product/infra/messaging/config/KafkaConfig.java`
- Test: `product-service/src/test/java/com/prompthub/product/search/application/ProductSearchEventHandlerTest.java`

**Interfaces:**
- Consumes: Task 3/4가 발행하는 `PRODUCT_ON_SALE_CHANGED`(payload `{familyRootId}`), 기존 `PRODUCT_STOPPED`/`PRODUCT_DELETED`(payload `{productId}`)/`PRODUCT_PRICE_CHANGED`(payload `{productId, previousPrice, changedPrice}`). Task 6의 `ProductIndexBootstrap.ALIAS`, `ElasticsearchClient`.
- Produces: `ProductSearchIndexer`(포트) — Task 8·9가 동일 포트를 재사용한다: `upsert(Product onSale, long familySalesCount, double averageRating, LocalDateTime firstPublishedAt)`, `delete(UUID familyRootId)`, `updatePrice(UUID familyRootId, int changedPrice)`.

- [ ] **Step 1: ES 문서 레코드 작성**

```java
package com.prompthub.product.search.infra.es;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ProductSearchDocument(
	UUID familyRootId,
	UUID productId,
	UUID sellerId,
	String name,
	String description,
	String content,
	List<String> tags,
	String productType,
	String model,
	int amount,
	String amountType,
	String thumbnailUrl,
	String badge,
	int salesCount,
	int viewCount,
	int reviewCount,
	double ratingAvg,
	LocalDateTime firstPublishedAt,
	LocalDateTime currentVersionAt
) {
}
```

- [ ] **Step 2: `ProductSearchIndexer` 포트 + ES 어댑터 작성**

```java
package com.prompthub.product.search.application;

import com.prompthub.product.domain.model.entity.Product;
import java.time.LocalDateTime;
import java.util.UUID;

public interface ProductSearchIndexer {
	void upsert(Product onSale, long familySalesCount, double averageRating, LocalDateTime firstPublishedAt);
	void delete(UUID familyRootId);
	void updatePrice(UUID familyRootId, int changedPrice);
}
```

```java
package com.prompthub.product.search.infra.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.search.application.ProductSearchIndexer;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
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
		ProductSearchDocument document = new ProductSearchDocument(
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
		try {
			client.index(i -> i.index(ProductIndexBootstrap.ALIAS).id(document.familyRootId().toString()).document(document));
		} catch (IOException e) {
			throw new IllegalStateException("ES 색인에 실패했습니다. familyRootId=" + document.familyRootId(), e);
		}
	}

	@Override
	public void delete(UUID familyRootId) {
		try {
			client.delete(d -> d.index(ProductIndexBootstrap.ALIAS).id(familyRootId.toString()));
		} catch (IOException e) {
			throw new IllegalStateException("ES 문서 삭제에 실패했습니다. familyRootId=" + familyRootId, e);
		}
	}

	@Override
	public void updatePrice(UUID familyRootId, int changedPrice) {
		try {
			client.update(u -> u.index(ProductIndexBootstrap.ALIAS).id(familyRootId.toString()).doc(Map.of("amount", changedPrice)), Map.class);
		} catch (IOException e) {
			throw new IllegalStateException("ES 가격 부분 갱신에 실패했습니다. familyRootId=" + familyRootId, e);
		}
	}
}
```

- [ ] **Step 3: `ProductSearchEventHandler` 작성 (family 재조회·멱등성)**

```java
package com.prompthub.product.search.application;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.entity.ProductProcessedEvent;
import com.prompthub.product.domain.repository.ProcessedEventRepository;
import com.prompthub.product.domain.repository.ProductRepository;
import java.time.Comparator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * product-events 소비의 멱등성(eventId+consumerGroup="product-service-search")과
 * ES 색인 반영을 한 트랜잭션으로 묶는다. (kafka-event.md §7, es-1 §4)
 */
@Service
@RequiredArgsConstructor
public class ProductSearchEventHandler {

	private static final String CONSUMER_GROUP = "product-service-search";

	private final ProductRepository productRepository;
	private final ProcessedEventRepository processedEventRepository;
	private final ProductSearchIndexer productSearchIndexer;

	@Transactional
	public void handleOnSaleChanged(UUID eventId, LocalDateTime occurredAt, UUID familyRootId) {
		if (alreadyProcessed(eventId)) {
			return;
		}
		reconcileFamily(familyRootId);
		markProcessed(eventId, "PRODUCT_ON_SALE_CHANGED", occurredAt);
	}

	@Transactional
	public void handleStoppedOrDeleted(UUID eventId, LocalDateTime occurredAt, UUID productId, String eventType) {
		if (alreadyProcessed(eventId)) {
			return;
		}
		UUID familyRootId = productRepository.findById(productId).map(Product::familyRootId).orElse(productId);
		reconcileFamily(familyRootId);
		markProcessed(eventId, eventType, occurredAt);
	}

	@Transactional
	public void handlePriceChanged(UUID eventId, LocalDateTime occurredAt, UUID productId, int changedPrice) {
		if (alreadyProcessed(eventId)) {
			return;
		}
		UUID familyRootId = productRepository.findById(productId).map(Product::familyRootId).orElse(productId);
		productSearchIndexer.updatePrice(familyRootId, changedPrice);
		markProcessed(eventId, "PRODUCT_PRICE_CHANGED", occurredAt);
	}

	private void reconcileFamily(UUID familyRootId) {
		List<Product> members = productRepository.findAllByFamilyRootIds(List.of(familyRootId));
		ProductFamily family = ProductFamily.of(familyRootId, members);
		family.currentOnSale().ifPresentOrElse(
			onSale -> {
				double averageRating = productRepository.getAverageRating(familyRootId);
				long familySalesCount = productRepository.sumSalesCountByFamilyRootId(familyRootId);
				LocalDateTime firstPublishedAt = members.stream()
					.map(Product::getCreatedAt)
					.min(Comparator.naturalOrder())
					.orElse(onSale.getCreatedAt());
				productSearchIndexer.upsert(onSale, familySalesCount, averageRating, firstPublishedAt);
			},
			() -> productSearchIndexer.delete(familyRootId)
		);
	}

	private boolean alreadyProcessed(UUID eventId) {
		return processedEventRepository.existsByEventIdAndConsumerGroup(eventId, CONSUMER_GROUP);
	}

	private void markProcessed(UUID eventId, String eventType, LocalDateTime occurredAt) {
		processedEventRepository.save(ProductProcessedEvent.create(eventId, CONSUMER_GROUP, eventType, occurredAt));
	}
}
```

- [ ] **Step 4: 컨슈머 작성**

```java
package com.prompthub.product.search.infra.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prompthub.common.event.EventMessage;
import com.prompthub.product.infra.messaging.producer.ProductEventType;
import com.prompthub.product.search.application.ProductSearchEventHandler;
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

		ProductEventType.from(event.eventType()).ifPresentOrElse(
			type -> handle(type, event),
			() -> log.info("색인 컨슈머가 지원하지 않는 eventType입니다. eventType={}", event.eventType())
		);

		acknowledgment.acknowledge();
	}

	private void handle(ProductEventType type, EventMessage<JsonNode> event) {
		switch (type) {
			case PRODUCT_ON_SALE_CHANGED -> {
				UUID familyRootId = UUID.fromString(event.payload().get("familyRootId").asText());
				productSearchEventHandler.handleOnSaleChanged(event.eventId(), event.occurredAt(), familyRootId);
			}
			case PRODUCT_STOPPED, PRODUCT_DELETED -> {
				UUID productId = UUID.fromString(event.payload().get("productId").asText());
				productSearchEventHandler.handleStoppedOrDeleted(event.eventId(), event.occurredAt(), productId, event.eventType());
			}
			case PRODUCT_PRICE_CHANGED -> {
				UUID productId = UUID.fromString(event.payload().get("productId").asText());
				int changedPrice = event.payload().get("changedPrice").asInt();
				productSearchEventHandler.handlePriceChanged(event.eventId(), event.occurredAt(), productId, changedPrice);
			}
		}
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

- [ ] **Step 5: `KafkaConfig`에 자기 자신(product-events) 소비용 컨슈머 팩토리 추가**

`KafkaConfig.java`에 추가(별도 consumer group "product-service-search" — 기존 `orderEventConsumerFactory`의 `groupId`("product-service")와 충돌 방지):

```java
	@Bean
	public ConsumerFactory<String, String> productEventConsumerFactory() {
		Map<String, Object> config = new HashMap<>();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		config.put(ConsumerConfig.GROUP_ID_CONFIG, "product-service-search");
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
		ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
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
```

- [ ] **Step 6: `ProductSearchEventHandler` 단위 테스트 작성**

```java
package com.prompthub.product.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProcessedEventRepository;
import com.prompthub.product.domain.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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

	@org.junit.jupiter.api.BeforeEach
	void setUp() {
		handler = new ProductSearchEventHandler(productRepository, processedEventRepository, productSearchIndexer);
	}

	@Test
	void handleOnSaleChanged_ON_SALE_멤버가_있으면_upsert한다() {
		Product onSale = product(FAMILY_ROOT_ID, ProductStatus.ON_SALE);
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(onSale));
		given(productRepository.getAverageRating(FAMILY_ROOT_ID)).willReturn(4.5);
		given(productRepository.sumSalesCountByFamilyRootId(FAMILY_ROOT_ID)).willReturn(10L);

		handler.handleOnSaleChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productSearchIndexer).upsert(any(), org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq(4.5), any());
		verify(productSearchIndexer, never()).delete(any());
	}

	@Test
	void handleOnSaleChanged_ON_SALE_멤버가_없으면_delete한다() {
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of());

		handler.handleOnSaleChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productSearchIndexer).delete(FAMILY_ROOT_ID);
		verify(productSearchIndexer, never()).upsert(any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyDouble(), any());
	}

	@Test
	void handleOnSaleChanged_이미_처리한_eventId면_아무것도_하지_않는다() {
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(true);

		handler.handleOnSaleChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productRepository, never()).findAllByFamilyRootIds(any());
	}

	@Test
	void handlePriceChanged_familyRootId를_찾아서_가격만_갱신한다() {
		UUID productId = UUID.randomUUID();
		Product product = product(FAMILY_ROOT_ID, ProductStatus.ON_SALE);
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findById(productId)).willReturn(java.util.Optional.of(product));

		handler.handlePriceChanged(EVENT_ID, LocalDateTime.now(), productId, 9900);

		verify(productSearchIndexer).updatePrice(FAMILY_ROOT_ID, 9900);
	}

	private Product product(UUID id, ProductStatus status) {
		Product product = Product.create(id, UUID.randomUUID(), com.prompthub.product.support.ProductContentFixtures.defaultContent());
		ReflectionTestUtils.setField(product, "status", status);
		return product;
	}
}
```

(`ProductContentFixtures`는 기존 테스트 지원 클래스 — 존재하지 않거나 시그니처가 다르면, 기존 `ProductSellerServiceTest`가 쓰는 `Product` 생성 방식을 그대로 참고해 맞춘다.)

- [ ] **Step 7: 빌드/테스트 실행**

Run: `./gradlew :product-service:test --tests "com.prompthub.product.search.application.ProductSearchEventHandlerTest"`
Expected: PASS (4개 테스트)

- [ ] **Step 8: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/search/
git add product-service/src/main/java/com/prompthub/product/infra/messaging/config/KafkaConfig.java
git add product-service/src/test/java/com/prompthub/product/search/
git commit -m "feat: product-events 색인 컨슈머 추가 (#376)"
```

---

### Task 8: 풀 리인덱스 배치 (온디맨드)

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/domain/repository/ProductRepository.java`
- Modify: `product-service/src/main/java/com/prompthub/product/infra/persistence/ProductJpaRepository.java`
- Modify: `product-service/src/main/java/com/prompthub/product/infra/persistence/ProductRepositoryAdapter.java`
- Create: `product-service/src/main/java/com/prompthub/product/search/application/ProductReindexService.java`
- Create: `product-service/src/main/java/com/prompthub/product/presentation/controller/ReindexController.java`
- Test: `product-service/src/test/java/com/prompthub/product/search/application/ProductReindexServiceTest.java`

**Interfaces:**
- Produces: `ProductReindexService.reindexAll()` — Task 9(카운트 동기화 배치)가 그대로 재사용한다.

- [ ] **Step 1: `ProductRepository` 포트에 `findAllByStatus` 추가**

`ProductRepository.java`에 메서드 추가:

```java
	List<Product> findAllByStatus(ProductStatus productStatus);
```

(`ProductStatus` import 추가.)

- [ ] **Step 2: JPA 리포지토리·어댑터 구현**

`ProductJpaRepository.java`에 추가:

```java
	List<Product> findByStatusAndDeletedAtIsNull(ProductStatus status);
```

`ProductRepositoryAdapter.java`에 추가:

```java
	@Override
	public List<Product> findAllByStatus(ProductStatus productStatus) {
		return productJpaRepository.findByStatusAndDeletedAtIsNull(productStatus);
	}
```

- [ ] **Step 3: `ProductReindexService` 작성**

```java
package com.prompthub.product.search.application;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProductRepository;
import java.time.Comparator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ES 초기 적재·매핑 변경 시 전체 재생성용 온디맨드 배치. 자동 스케줄은 없다
 * (아웃박스로 실시간 경로 신뢰성이 확보돼 유실 복구 목적의 자동 배치는 불필요 —
 * admin-onsale-event-design.md §5).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductReindexService {

	private final ProductRepository productRepository;
	private final ProductSearchIndexer productSearchIndexer;

	public void reindexAll() {
		List<Product> onSaleProducts = productRepository.findAllByStatus(ProductStatus.ON_SALE);
		Map<UUID, List<Product>> byFamily = onSaleProducts.stream()
			.collect(Collectors.groupingBy(Product::familyRootId));

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
				productSearchIndexer.upsert(onSale, familySalesCount, averageRating, firstPublishedAt);
			});
		}
		log.info("풀 리인덱스 완료. family={}", byFamily.size());
	}
}
```

- [ ] **Step 4: 온디맨드 트리거 컨트롤러 작성 (internal, wrapper 없음)**

```java
package com.prompthub.product.presentation.controller;

import com.prompthub.product.search.application.ProductReindexService;
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
		productReindexService.reindexAll();
	}
}
```

- [ ] **Step 5: 단위 테스트 작성**

```java
package com.prompthub.product.search.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProductRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
	void reindexAll_ON_SALE_family마다_upsert를_호출한다() {
		UUID familyRootId = UUID.randomUUID();
		Product onSale = product(familyRootId, ProductStatus.ON_SALE);
		given(productRepository.findAllByStatus(ProductStatus.ON_SALE)).willReturn(List.of(onSale));
		given(productRepository.findAllByFamilyRootIds(List.of(familyRootId))).willReturn(List.of(onSale));
		given(productRepository.getAverageRating(familyRootId)).willReturn(4.0);
		given(productRepository.sumSalesCountByFamilyRootId(familyRootId)).willReturn(5L);

		reindexService.reindexAll();

		verify(productSearchIndexer).upsert(any(), org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.eq(4.0), any());
	}

	private Product product(UUID id, ProductStatus status) {
		Product product = Product.create(id, UUID.randomUUID(), com.prompthub.product.support.ProductContentFixtures.defaultContent());
		ReflectionTestUtils.setField(product, "status", status);
		return product;
	}
}
```

- [ ] **Step 6: 빌드/테스트 실행**

Run: `./gradlew :product-service:test --tests "com.prompthub.product.search.application.ProductReindexServiceTest"`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/domain/repository/ProductRepository.java
git add product-service/src/main/java/com/prompthub/product/infra/persistence/ProductJpaRepository.java
git add product-service/src/main/java/com/prompthub/product/infra/persistence/ProductRepositoryAdapter.java
git add product-service/src/main/java/com/prompthub/product/search/application/ProductReindexService.java
git add product-service/src/main/java/com/prompthub/product/presentation/controller/ReindexController.java
git add product-service/src/test/java/com/prompthub/product/search/application/ProductReindexServiceTest.java
git commit -m "feat: 온디맨드 풀 리인덱스 배치 추가 (#376)"
```

---

### Task 9: 카운트 동기화 배치 (10분 주기)

family 집계값(salesCount 등)은 이벤트마다 갱신하지 않고 10분마다 묶어서 반영한다(es-1 §5). 카탈로그 규모가 작아 Task 8의 `reindexAll()`을 그대로 재사용한다 — 규모가 커지면 ES `_bulk` API 전환을 후속 이슈로 남긴다.

**Files:**
- Create: `product-service/src/main/java/com/prompthub/product/search/infra/batch/ProductCountSyncScheduler.java`
- Modify: `product-service/src/main/resources/application-local.yml`
- Modify: `config/src/main/resources/configs/product-service.yml`

- [ ] **Step 1: 스케줄러 작성**

```java
package com.prompthub.product.search.infra.batch;

import com.prompthub.product.search.application.ProductReindexService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * family 집계 카운트(salesCount/viewCount/reviewCount/ratingAvg)를 10분마다 ES에
 * 반영한다. (es-1 §5) — 이벤트마다 문서를 갱신하지 않고 주기적으로 묶어서 반영해
 * 소형 ES 노드 부하를 줄인다. @EnableScheduling은 Task 2의 OutboxRelayConfig가 이미 켰다.
 */
@Component
@RequiredArgsConstructor
public class ProductCountSyncScheduler {

	private final ProductReindexService productReindexService;

	@Scheduled(fixedDelayString = "${prompthub.search.count-sync.fixed-delay-ms:600000}")
	public void syncCounts() {
		productReindexService.reindexAll();
	}
}
```

- [ ] **Step 2: 설정값 추가**

`product-service/src/main/resources/application-local.yml`의 `prompthub:` 블록 아래 추가:

```yaml
  search:
    count-sync:
      fixed-delay-ms: 600000
```

`config/src/main/resources/configs/product-service.yml`에도 동일하게 추가.

- [ ] **Step 3: 빌드 실행 (별도 신규 테스트 없음 — Task 8의 `ProductReindexServiceTest`가 실제 동작을 이미 검증)**

Run: `./gradlew :product-service:build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/search/infra/batch/
git add product-service/src/main/resources/application-local.yml
git add config/src/main/resources/configs/product-service.yml
git commit -m "feat: 10분 주기 카운트 동기화 배치 추가 (#376)"
```

---

## 전체 검증 (모든 태스크 완료 후)

```bash
cd product-service
docker compose up -d
docker build -t product-elasticsearch-nori:9.4.3 docker/elasticsearch/
cd ..
./gradlew :product-service:clean :product-service:build --no-daemon
```

Expected: BUILD SUCCESSFUL, 모든 신규 테스트 통과. 수동 확인: 셀러가 상품을 등록 → 관리자가 승인(admin-service) → 10초 안에(아웃박스 5초 폴링 + 컨슈머) ES에 문서가 생기는지 `curl localhost:9200/products/_doc/{familyRootId}`로 확인.
