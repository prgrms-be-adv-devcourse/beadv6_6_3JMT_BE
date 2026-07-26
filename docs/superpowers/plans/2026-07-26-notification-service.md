# Notification Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone notification-service that stores and streams order-paid and order-refund notifications from the existing Kafka contract.

**Architecture:** The new module consumes the existing `EventMessage<JsonNode>` records on `order-events` with a manual-ack Kafka consumer. Router-selected handlers persist a notification and idempotency row in one transaction; an after-commit callback sends the persisted record to in-memory SSE connections. MVC controllers expose only authenticated-user notification reads and state changes.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring MVC, Spring Data JPA, Flyway, Kafka, PostgreSQL, H2, springdoc, JUnit 5, Mockito, Embedded Kafka.

## Global Constraints

- Modify `notification-service/**` and only root Gradle registration needed to add the module.
- Do not modify order-service, user-service, apigateway, gRPC contracts, Docker Compose, or Kubernetes resources.
- Consume `order-events` and use the existing `EventMessage<T>` envelope with `ORDER_PAID` and `ORDER_REFUND` only.
- Public endpoints are `/api/v2/notifications/**`; authentication comes only from `X-User-Id`.
- Do not create email delivery, SMTP, recipient lookup, email workers, or user-service integration.
- Store an event before SSE transmission; a failed or absent SSE connection must never remove a stored notification.
- Keep each Kafka handler transaction atomic for notification creation and `(event_id, consumer_group)` idempotency recording.

---

## File Structure

| Path | Responsibility |
| --- | --- |
| `settings.gradle`, `build.gradle` | Register and configure the new business/Kafka service module. |
| `notification-service/build.gradle` | Module dependencies and test profile. |
| `notification-service/src/main/resources/application.yml` | Service name, local/test DB and Kafka properties, Flyway and SSE limits. |
| `notification-service/src/main/resources/db/migration/V1__create_notification_tables.sql` | `notification` and `processed_event` schema, constraints, and indexes. |
| `notification-service/.../domain/model/Notification.java` | Notification state, sequence, and read transition. |
| `notification-service/.../domain/model/ProcessedEvent.java` | Idempotency record. |
| `notification-service/.../domain/model/NotificationRecipientSequence.java` | Locked per-recipient sequence counter. |
| `notification-service/.../application/service/NotificationCommandService.java` | Atomic notification/idempotency creation and owned read transitions. |
| `notification-service/.../application/service/NotificationQueryService.java` | Owned list, unread count, and reconnect replay queries. |
| `notification-service/.../infra/sse/SseConnectionRegistry.java` | Per-user emitter lifecycle and send operations. |
| `notification-service/.../infra/messaging/kafka/*` | Kafka listener, event type, payload DTOs, router, handlers, and DLT configuration. |
| `notification-service/.../presentation/NotificationController.java` | v2 API and SSE endpoint. |
| `notification-service/src/test/java/...` | Unit, MVC, JPA transaction, SSE, and Kafka configuration coverage. |

## Task 1: Register and boot the standalone module

**Files:**
- Modify: `settings.gradle`
- Modify: `build.gradle`
- Create: `notification-service/build.gradle`
- Create: `notification-service/src/main/java/com/prompthub/notification/NotificationServiceApplication.java`
- Create: `notification-service/src/main/resources/application.yml`
- Create: `notification-service/src/test/resources/application-test.yml`
- Create: `notification-service/src/test/java/com/prompthub/notification/NotificationServiceApplicationTest.java`

**Interfaces:**
- Consumes: root Gradle Java 21, Spring Boot business-service, and Kafka-service conventions.
- Produces: runnable `com.prompthub.notification.NotificationServiceApplication` and Gradle task `:notification-service:test`.

- [ ] **Step 1: Write the failing application-context test**

```java
@SpringBootTest
@ActiveProfiles("test")
class NotificationServiceApplicationTest {
    @Test
    void contextLoads() {}
}
```

- [ ] **Step 2: Run the test to verify the module is not registered**

Run: `./gradlew :notification-service:test --tests "com.prompthub.notification.NotificationServiceApplicationTest"`

Expected: FAIL because Gradle cannot find project `notification-service`.

- [ ] **Step 3: Add the minimum module bootstrap**

Add `include 'notification-service'` to `settings.gradle`. Add `project(':notification-service')` to the root Spring Boot business-service and Kafka-service `configure(...)` lists. Create a module build file containing springdoc and the root-provided Web, JPA, Flyway, Eureka, Kafka, PostgreSQL, and H2 dependencies. Create:

```java
@SpringBootApplication
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
```

Set `spring.application.name: notification-service`; disable Eureka and config import in the test profile; configure H2 and Flyway test migrations; configure the local profile with PostgreSQL, `order-events`, manual Kafka acknowledgement, and `notification-service` consumer group.

- [ ] **Step 4: Run the bootstrap test**

Run: `./gradlew :notification-service:test --tests "com.prompthub.notification.NotificationServiceApplicationTest"`

Expected: PASS with a loaded context and migrated empty H2 schema.

- [ ] **Step 5: Commit the module bootstrap**

```bash
git add settings.gradle build.gradle notification-service/build.gradle notification-service/src
git commit -m "feat: add notification service module"
```

## Task 2: Add notification persistence and owned application services

**Files:**
- Create: `notification-service/src/main/resources/db/migration/V1__create_notification_tables.sql`
- Create: `notification-service/src/main/java/com/prompthub/notification/domain/enums/NotificationType.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/domain/model/Notification.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/domain/model/ProcessedEvent.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/domain/model/NotificationRecipientSequence.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/domain/repository/NotificationRepository.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/domain/repository/ProcessedEventRepository.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/infra/persistence/NotificationJpaRepository.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/infra/persistence/ProcessedEventJpaRepository.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/infra/persistence/NotificationRecipientSequenceJpaRepository.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/application/service/NotificationCommandService.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/application/service/NotificationQueryService.java`
- Test: `notification-service/src/test/java/com/prompthub/notification/application/service/NotificationCommandServiceTest.java`
- Test: `notification-service/src/test/java/com/prompthub/notification/application/service/NotificationQueryServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `UUID eventId`, `UUID recipientId`, `String consumerGroup`, and notification presentation fields.
- Produces: `NotificationCommandService.createIfAbsent(CreateNotificationCommand)` and `NotificationQueryService.findPage(UUID, int, int)`.

- [ ] **Step 1: Write failing idempotency and ownership tests**

```java
@Test
void sameEventAndConsumerGroupCreatesOneNotification() {
    Notification first = commandService.createIfAbsent(command);
    Notification second = commandService.createIfAbsent(command);

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(notificationJpaRepository.count()).isEqualTo(1);
    assertThat(processedEventJpaRepository.count()).isEqualTo(1);
}

@Test
void readOneRejectsAnotherUsersNotification() {
    assertThatThrownBy(() -> commandService.markRead(ownerTwo, ownerOnesNotificationId))
        .isInstanceOf(NotificationException.class)
        .extracting("errorCode").isEqualTo(NotificationErrorCode.NOTIFICATION_ACCESS_DENIED);
}
```

- [ ] **Step 2: Run the service tests to verify they fail**

Run: `./gradlew :notification-service:test --tests "*NotificationCommandServiceTest" --tests "*NotificationQueryServiceIntegrationTest"`

Expected: FAIL because notification entities, migration, and services do not exist.

- [ ] **Step 3: Implement schema, entities, repositories, and services**

Create `notification` with UUID `id`, `recipient_id`, `sequence`, `event_id`, `type`, `title`, `message`, `reference_type`, `reference_id`, `read_at`, and `created_at`. Add unique `(recipient_id, sequence)` and indexes on `(recipient_id, created_at)` and `(recipient_id, read_at)`. Create `processed_event` with unique `(event_id, consumer_group)`. Create `notification_recipient_sequence(recipient_id primary key, last_sequence bigint)` for locked per-recipient sequence allocation.

Implement the commands and records:

```java
public record CreateNotificationCommand(
    UUID eventId, UUID recipientId, NotificationType type,
    String title, String message, String referenceType, UUID referenceId,
    String consumerGroup
) {}

@Transactional
public Notification createIfAbsent(CreateNotificationCommand command) {
    return processedEventRepository
        .findByEventIdAndConsumerGroup(command.eventId(), command.consumerGroup())
        .flatMap(existing -> notificationRepository.findById(existing.notificationId()))
        .orElseGet(() -> createNew(command));
}

@Transactional
public void markRead(UUID requesterId, UUID notificationId) {
    Notification notification = notificationRepository.findById(notificationId)
        .orElseThrow(() -> new NotificationException(NOTIFICATION_NOT_FOUND));
    notification.assertOwnedBy(requesterId);
    notification.markRead(clock.instant());
}

@Transactional
public int markAllRead(UUID requesterId) {
    return notificationRepository.markAllUnreadAsRead(requesterId, clock.instant());
}
```

`createNew` obtains `NotificationRecipientSequence` with `LockModeType.PESSIMISTIC_WRITE`, increments `lastSequence`, saves the `Notification`, then saves `ProcessedEvent(eventId, consumerGroup, notificationId, processedAt)`. If concurrent first creation raises the recipient-sequence primary-key conflict, retry `createNew` once in a new transaction; the second attempt locks the created row.

- [ ] **Step 4: Run the persistence and service tests**

Run: `./gradlew :notification-service:test --tests "*NotificationCommandServiceTest" --tests "*NotificationQueryServiceIntegrationTest"`

Expected: PASS; duplicate events leave exactly one notification and cross-user reads return `N002`.

- [ ] **Step 5: Commit the persistence slice**

```bash
git add notification-service/src/main notification-service/src/test
git commit -m "feat: persist idempotent notifications"
```

## Task 3: Expose authenticated notification read and state APIs

**Files:**
- Create: `notification-service/src/main/java/com/prompthub/notification/presentation/NotificationController.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/presentation/dto/NotificationResponse.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/presentation/dto/UnreadCountResponse.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/global/web/AuthHeaders.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/global/exception/NotificationErrorCode.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/global/exception/NotificationException.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/global/exception/GlobalExceptionHandler.java`
- Test: `notification-service/src/test/java/com/prompthub/notification/presentation/NotificationControllerTest.java`

**Interfaces:**
- Consumes: `X-User-Id`, `page`, and `size` request values.
- Produces: `PageResponse<NotificationResponse>`, `ApiResult<UnreadCountResponse>`, and owner-only PATCH responses.

- [ ] **Step 1: Write failing MVC contract tests**

```java
mockMvc.perform(get("/api/v2/notifications")
        .header("X-User-Id", ownerId)
        .param("page", "0").param("size", "20"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.success").value(true))
    .andExpect(jsonPath("$.meta.total").value(1));

mockMvc.perform(patch("/api/v2/notifications/{id}/read", foreignNotificationId)
        .header("X-User-Id", ownerId))
    .andExpect(status().isForbidden())
    .andExpect(jsonPath("$.code").value("N002"));
```

- [ ] **Step 2: Run the MVC tests to verify they fail**

Run: `./gradlew :notification-service:test --tests "*NotificationControllerTest"`

Expected: FAIL because no controller maps `/api/v2/notifications`.

- [ ] **Step 3: Implement controller and error translation**

Map the list, unread-count, one-read, and all-read endpoints. Bind user identity only from:

```java
@RequestHeader(AuthHeaders.USER_ID) UUID userId
```

Return `PageResponse.success(items, page, size, total, hasNext)` for the list and `ApiResult.success(...)` for the other JSON endpoints. Map `NOTIFICATION_NOT_FOUND` to `N001`/404 and `NOTIFICATION_ACCESS_DENIED` to `N002`/403. Add `N003`/429 now so the SSE task can use the same advice.

- [ ] **Step 4: Run MVC tests**

Run: `./gradlew :notification-service:test --tests "*NotificationControllerTest"`

Expected: PASS; missing or malformed identity header follows the common invalid-authentication response path, and a user never reads another user’s row.

- [ ] **Step 5: Commit the HTTP API slice**

```bash
git add notification-service/src/main notification-service/src/test
git commit -m "feat: add notification read APIs"
```

## Task 4: Implement durable SSE delivery and reconnect replay

**Files:**
- Create: `notification-service/src/main/java/com/prompthub/notification/infra/sse/SseConnectionRegistry.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/infra/sse/SseNotificationPublisher.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/infra/sse/SseProperties.java`
- Modify: `notification-service/src/main/java/com/prompthub/notification/presentation/NotificationController.java`
- Modify: `notification-service/src/main/resources/application.yml`
- Test: `notification-service/src/test/java/com/prompthub/notification/infra/sse/SseConnectionRegistryTest.java`
- Test: `notification-service/src/test/java/com/prompthub/notification/presentation/NotificationSseControllerTest.java`

**Interfaces:**
- Consumes: committed `NotificationResponse`, user UUID, and optional numeric `Last-Event-ID`.
- Produces: `SseEmitter` streams whose event id equals `notification.sequence`.

- [ ] **Step 1: Write failing emitter lifecycle and replay tests**

```java
@Test
void sendsOneCommittedNotificationToEveryConnectionForItsRecipient() {
    SseEmitter first = registry.connect(recipientId, null);
    SseEmitter second = registry.connect(recipientId, null);

    publisher.publish(notification);

    assertThat(registry.connectionCount(recipientId)).isEqualTo(2);
}

@Test
void replayReturnsOnlySequencesAfterLastEventId() {
    SseEmitter emitter = registry.connect(recipientId, 4L);

    verify(queryService).findCreatedAfter(recipientId, 4L);
}
```

- [ ] **Step 2: Run the SSE tests to verify they fail**

Run: `./gradlew :notification-service:test --tests "*SseConnectionRegistryTest" --tests "*NotificationSseControllerTest"`

Expected: FAIL because no emitter registry or stream endpoint exists.

- [ ] **Step 3: Implement the registry and stream endpoint**

Create per-user `ConcurrentHashMap<UUID, Set<SseEmitter>>` storage. Enforce `SseProperties.maxConnectionsPerUser`; create emitters with `timeoutMillis`; register completion, timeout, and error callbacks that remove only that emitter. Send a scheduled heartbeat to every active emitter and remove an emitter on `IOException`.

Implement:

```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(
    @RequestHeader(AuthHeaders.USER_ID) UUID userId,
    @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
) {
    long cursor = lastEventId == null ? 0L : Long.parseLong(lastEventId);
    SseEmitter emitter = registry.connect(userId);
    queryService.findCreatedAfter(userId, cursor)
        .forEach(notification -> registry.send(emitter, notification));
    return emitter;
}
```

Emit `SseEmitter.event().id(Long.toString(sequence)).name("notification").data(response)`. Keep publisher invocation outside the database transaction by registering `TransactionSynchronization.afterCommit` in the Kafka handler task.

- [ ] **Step 4: Run SSE tests**

Run: `./gradlew :notification-service:test --tests "*SseConnectionRegistryTest" --tests "*NotificationSseControllerTest"`

Expected: PASS; multiple connections receive only their owner’s event, old sequence values are not replayed, and the cap maps to `N003`/429.

- [ ] **Step 5: Commit the SSE slice**

```bash
git add notification-service/src/main notification-service/src/test
git commit -m "feat: stream notification SSE events"
```

## Task 5: Consume existing order events with idempotency and DLT

**Files:**
- Create: `notification-service/src/main/java/com/prompthub/notification/infra/messaging/kafka/config/KafkaConfig.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/infra/messaging/kafka/consumer/OrderEventConsumer.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/infra/messaging/kafka/router/OrderEventRouter.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/infra/messaging/kafka/event/OrderEventType.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/infra/messaging/kafka/event/OrderPaidPayload.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/infra/messaging/kafka/event/OrderRefundPayload.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/application/service/OrderPaidEventHandler.java`
- Create: `notification-service/src/main/java/com/prompthub/notification/application/service/OrderRefundEventHandler.java`
- Test: `notification-service/src/test/java/com/prompthub/notification/infra/messaging/kafka/consumer/OrderEventConsumerTest.java`
- Test: `notification-service/src/test/java/com/prompthub/notification/infra/messaging/kafka/router/OrderEventRouterTest.java`
- Test: `notification-service/src/test/java/com/prompthub/notification/application/service/OrderEventHandlerIntegrationTest.java`

**Interfaces:**
- Consumes: Kafka string JSON shaped as `EventMessage<JsonNode>` from `order-events`.
- Produces: one notification per `(eventId, notification-service)` and an after-commit `SseNotificationPublisher.publish(Notification)` call.

- [ ] **Step 1: Write failing consumer, router, and transaction tests**

```java
@Test
void paidEventCreatesOneNotificationAndAcknowledgesAfterHandling() {
    consumer.consume(paidEventJson, acknowledgment);

    then(acknowledgment).should().acknowledge();
    assertThat(notificationRepository.count()).isEqualTo(1);
}

@Test
void unknownEventIsAcknowledgedWithoutPersistence() {
    consumer.consume(unknownEventJson, acknowledgment);

    then(acknowledgment).should().acknowledge();
    assertThat(notificationRepository.count()).isZero();
}

@Test
void malformedJsonThrowsSoTheErrorHandlerCanPublishDlt() {
    assertThatThrownBy(() -> consumer.consume("not-json", acknowledgment))
        .isInstanceOf(NotificationException.class);
    then(acknowledgment).shouldHaveNoInteractions();
}
```

- [ ] **Step 2: Run the event tests to verify they fail**

Run: `./gradlew :notification-service:test --tests "*OrderEventConsumerTest" --tests "*OrderEventRouterTest" --tests "*OrderEventHandlerIntegrationTest"`

Expected: FAIL because the listener, payload mappings, and handlers do not exist.

- [ ] **Step 3: Implement listener configuration, routing, and handlers**

Use a string-valued `ConsumerFactory` with `ErrorHandlingDeserializer`, `AckMode.MANUAL`, and a `DefaultErrorHandler` with a `DeadLetterPublishingRecoverer` that maps `record.topic() + ".DLT"`. Set the listener group id to `notification-service` and topic to `order-events`.

Implement the listener contract:

```java
@KafkaListener(topics = "${notification.kafka.order-topic:order-events}",
               groupId = "${notification.kafka.consumer-group:notification-service}",
               containerFactory = "orderEventKafkaListenerContainerFactory")
public void consume(String rawMessage, Acknowledgment acknowledgment) {
    EventMessage<JsonNode> message = objectMapper.readValue(rawMessage,
        new TypeReference<EventMessage<JsonNode>>() {});
    if (!router.supports(message.eventType())) {
        acknowledgment.acknowledge();
        return;
    }
    router.route(message);
    acknowledgment.acknowledge();
}
```

Map `ORDER_PAID` and `ORDER_REFUND` only. Build titles and messages from the local payload DTO fields (`orderId`, `buyerId`, `totalOrderAmount`, `paidAt` or `refundedAt`). Use `buyerId` as the recipient and `orderId` as the reference. Within each handler, call `NotificationCommandService.createIfAbsent(...)`; publish that returned notification only through an after-commit synchronization.

- [ ] **Step 4: Run unit and H2 transaction tests**

Run: `./gradlew :notification-service:test --tests "*OrderEventConsumerTest" --tests "*OrderEventRouterTest" --tests "*OrderEventHandlerIntegrationTest"`

Expected: PASS; supported events persist exactly once, unknown events ACK, malformed payloads do not ACK, and a persistence exception rolls back both rows.

- [ ] **Step 5: Commit the Kafka slice**

```bash
git add notification-service/src/main notification-service/src/test
git commit -m "feat: consume order notification events"
```

## Task 6: Verify real listener DLT behavior and API documentation

**Files:**
- Modify: `notification-service/src/main/java/com/prompthub/notification/presentation/NotificationController.java`
- Modify: `notification-service/src/main/java/com/prompthub/notification/infra/messaging/kafka/config/KafkaConfig.java`
- Create: `notification-service/src/test/java/com/prompthub/notification/infra/messaging/kafka/OrderEventKafkaIntegrationTest.java`
- Create: `notification-service/src/test/java/com/prompthub/notification/presentation/NotificationOpenApiTest.java`
- Modify: `notification-service/src/main/resources/application.yml`

**Interfaces:**
- Consumes: embedded Kafka `order-events` records and the generated Springdoc OpenAPI document.
- Produces: a recovered `order-events.DLT` record for malformed input and documented v2 notification endpoints.

- [ ] **Step 1: Write failing Embedded Kafka and OpenAPI tests**

```java
@Test
void invalidOrderEventIsPublishedToOrderEventsDlt() {
    kafkaTemplate.send("order-events", "order-1", "{").get();

    ConsumerRecord<String, String> dlt = KafkaTestUtils.getSingleRecord(consumer, "order-events.DLT");
    assertThat(dlt.value()).isEqualTo("{");
}

@Test
void openApiContainsTheNotificationStreamAsEventStream() {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(jsonPath("$.paths['/api/v2/notifications/stream'].get.responses['200'].content['text/event-stream']").exists());
}
```

- [ ] **Step 2: Run the integration tests to verify they fail**

Run: `./gradlew :notification-service:test --tests "*OrderEventKafkaIntegrationTest" --tests "*NotificationOpenApiTest"`

Expected: FAIL until the DLT recoverer is wired to the real listener container and Swagger annotations describe the stream.

- [ ] **Step 3: Finish recoverer settings and endpoint documentation**

Configure retry count and fixed backoff through `notification.kafka.retry-interval-ms` and `notification.kafka.max-retry-attempts`. Add springdoc `@Operation` and `@ApiResponse` annotations to each controller method, explicitly declaring `text/event-stream` for the stream and `N001`, `N002`, `N003` responses where applicable. Do not add Gateway Swagger aggregation because apigateway is outside scope.

- [ ] **Step 4: Run focused and module-wide verification**

Run: `./gradlew :notification-service:test`

Expected: PASS; the DLT consumer receives malformed source records, all MVC/SSE/Kafka tests pass, and no order-service source file changed.

- [ ] **Step 5: Commit final service verification**

```bash
git add notification-service/src/main notification-service/src/test
git commit -m "test: verify notification event delivery"
```

## Plan Self-Review

- Scope coverage: Tasks 1–6 cover module registration, Flyway persistence,
  idempotency, v2 HTTP API, SSE recovery, manual Kafka acknowledgement, DLT,
  OpenAPI, and all specified tests. Email, recipient lookup, and non-notification
  services are explicitly excluded by the global constraints.
- Type consistency: every Kafka task uses `EventMessage<JsonNode>`, every
  persistence task uses `CreateNotificationCommand`, and every SSE task uses
  `NotificationResponse` with `notification.sequence` as its event id.
- No placeholder language: concrete files, commands, signatures, assertions,
  and expected results are specified in every task.
