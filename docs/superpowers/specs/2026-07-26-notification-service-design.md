# Notification Service Design

## Goal

Add a standalone `notification-service` that persists and delivers real-time
order-paid and order-refund notifications, while leaving every existing service
unchanged.

## Scope and Compatibility Constraints

- Create or modify only `notification-service/**` plus the root Gradle module
  registration required to compile the new module.
- Do not modify `order-service`, `user-service`, `apigateway`, shared gRPC
  contracts, Docker Compose, or Kubernetes resources.
- Consume the existing `order-events` topic and rely on the existing
  `order-events.DLT` convention.
- Preserve the existing `EventMessage<T>` envelope and event names:
  `ORDER_PAID` and `ORDER_REFUND`.
- Expose the already-reserved `/api/v2/notifications/**` API. The Gateway
  already contains this route and requires no change.
- Do not implement recipient lookup, SMTP delivery, `email_delivery`, or an
  email worker. The existing user-service has no service-to-service recipient
  query contract, and its `/api/v2/users/me` API must not be used internally.

## Module Structure

`notification-service` follows the existing Spring MVC/JPA/Kafka structure:

```text
notification-service/
  presentation/                 HTTP and SSE controllers, request/response DTOs
  application/
    service/                    notification commands, queries, event handlers
    usecase/                    controller-facing contracts
  domain/
    model/                      Notification and ProcessedEvent entities
    repository/                 persistence ports
    enums/                      notification type
  infra/
    persistence/                JPA repositories and adapters
    messaging/kafka/            consumer, router, handlers, configuration
    sse/                        connection registry and publisher
  global/exception/             error codes, exception, HTTP advice
```

The module depends on `common-module`, Spring Web MVC, Validation, Spring Data
JPA, Flyway, Kafka, Eureka, springdoc, PostgreSQL, and H2 for tests. It is
registered as a Java 21 Spring Boot business service in the root Gradle build.

## Kafka Processing

The consumer receives raw `EventMessage<JsonNode>` values from `order-events`
using manual acknowledgement.

1. The consumer deserializes the common envelope and delegates to
   `OrderEventRouter`.
2. The router selects `OrderPaidEventHandler` for `ORDER_PAID` and
   `OrderRefundEventHandler` for `ORDER_REFUND`.
3. Each handler maps the JSON payload to a notification-local wire DTO that is
   compatible with the existing order payload. The new module never imports
   order-service classes.
4. In one transaction, the handler checks `processed_event`, creates one
   `notification`, and records the processed event.
5. After commit, the consumer acknowledges Kafka and publishes the committed
   notification to active SSE connections.

The `processed_event` unique key is `(event_id, consumer_group)`. A duplicate
event completes normally and creates no second notification. Unsupported event
types are logged and acknowledged. Malformed JSON, required-field violations,
and database failures propagate to the configured Kafka error handler, which
publishes to `order-events.DLT` after its retry policy.

## Persistence

Flyway owns the new service schema.

`notification`

- `id` UUID primary key
- `recipient_id` UUID, indexed with `sequence`
- `sequence` positive long, unique per recipient
- `event_id` UUID, `type`, `title`, `message`
- `reference_type`, `reference_id`, `read_at`, `created_at`

`processed_event`

- `event_id` UUID
- `consumer_group` string
- `notification_id` UUID
- `processed_at` timestamp
- unique `(event_id, consumer_group)`

`notification_recipient_sequence` stores one locked counter row per recipient
to allocate the next sequence safely. The sequence is the SSE event id.
Reconnect replay queries notifications for the authenticated recipient with a
sequence greater than `Last-Event-ID`.

## HTTP and SSE API

All endpoints use the Gateway-provided `X-User-Id` and never accept a recipient
identifier from query parameters or request bodies.

- `GET /api/v2/notifications`: paginated list in `PageResponse`
- `GET /api/v2/notifications/unread-count`: unread count in `ApiResult`
- `GET /api/v2/notifications/stream`: `text/event-stream`, not wrapped in JSON
- `PATCH /api/v2/notifications/{notificationId}/read`: mark one owned item read
- `PATCH /api/v2/notifications/read`: mark all owned items read

`N001` is notification not found (404), `N002` is cross-user access (403), and
`N003` is the per-user SSE connection cap (429).

`SseConnectionRegistry` holds multiple emitters per user in-memory, sends
heartbeat comments/events, and removes emitters on timeout, completion, and
errors. It deliberately does not implement cross-instance fan-out.

## Tests

- Handler tests for paid and refund notification creation
- Duplicate `eventId` tests proving exactly one notification and processed row
- Consumer/router tests for unknown-event acknowledgement and DLT-bound errors
- Repository-backed transaction tests for persistence rollback and idempotency
- Controller tests for list, unread count, ownership, and shared response types
- SSE tests for delivery to multiple connections, offline persistence, replay,
  and connection-limit cleanup
- Kafka configuration tests for manual acknowledgement and DLT handling

## Explicit Follow-up

Email delivery remains a separate issue. It requires a user-service-owned,
service-to-service recipient contract before a notification-service recipient
port and SMTP adapter can be connected safely.
