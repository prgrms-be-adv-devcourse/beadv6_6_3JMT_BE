# 작업 계획: 주문-결제 흐름 재설계 (ORDER_CREATED 이벤트 + gRPC 폴백)

## 목표 흐름

```
1. client ──POST /api/v1/orders──▶ order-service        주문 생성(PENDING), 응답 반환
2. order-service                                         같은 트랜잭션에 outbox 기록
   └─ OutboxRelay ──ORDER_CREATED──▶ Kafka(order-events) (~5초 내 발행)
3. payment-service                                       order-events 소비 → 주문 스냅샷 저장
4. client                                                Toss UI로 결제 진행 (금액 = 주문 응답의 totalAmount)
5. client ──POST /api/v1/payments/confirm──▶ payment    {paymentKey, orderId} (amount 없음)
   ├─ 스냅샷 있음  → 스냅샷 금액으로 검증·결제 진행
   └─ 스냅샷 없음  → order-service에 gRPC 조회(폴백) → 스냅샷 저장 후 진행
      └─ gRPC도 실패 → 503, Payment 미생성 (클라이언트 재시도 가능)
6. Toss confirm 성공 → Payment PAID  → payment.approved 발행 (기존과 동일)
   Toss confirm 실패 → Payment FAILED → payment.failed 발행 (신규 — 본 문서에 흡수)
7. order-service: payment.failed 소비 → Order PENDING → FAILED
   (재결제 성공 시 payment.approved 소비로 FAILED → PAID 복귀)
```

## 결정 배경

| 항목 | 결정 |
|---|---|
| 동기 | ① 사전 금액 검증 — 현재는 클라이언트 amount를 검증 없이 Toss에 전달(금액의 진실 공급원이 클라이언트) ② 결제 시점 동기 호출 제거(평상시 이벤트 경로) ③ 서비스 간 결합도 완화 |
| 이중 경로 | **이벤트(평상시) + gRPC(폴백)**. 평상시엔 로컬 스냅샷만 읽어 order와 왕복 없음. 이벤트 유실·지연 시에만 gRPC |
| 저장 모델 | **별도 주문 스냅샷 테이블**(`order_snapshot`). Payment 선생성(READY) 안 함 — Payment 생성 경로를 confirm 단일 경로로 유지, 버려진 주문이 Payment 테이블을 오염시키지 않음 |
| 이벤트 발행 | order-service **기존 outbox 패턴 재사용**. 토픽 `order-events`에 eventType `ORDER_CREATED` 추가, 기존 envelope 포맷 그대로. 릴레이 폴링(5초) 지연은 Toss UI 소요 시간(수십 초)에 흡수됨 |
| confirm API | **amount 필드 제거** — `{paymentKey, orderId}`만 수신. 금액의 진실 공급원은 스냅샷. Toss에는 스냅샷 금액을 전달하므로 클라이언트가 Toss UI에서 다른 금액을 인증했다면 Toss가 불일치로 거부 |
| 검증 범위 | **주문자 본인 검증 포함** — `snapshot.buyerId != X-User-Id` → 403. (스냅샷 데이터 자체는 order 발행이라 신뢰하지만, confirm *요청*은 클라이언트발이므로 요청자 신원은 검증 대상. 현재는 BUYER 권한만 있으면 남의 주문도 confirm 가능하고, 그 경우 Payment.userId가 타인으로 기록되어 진짜 주문자가 환불 불가) |
| 주문 상태 검증 | 생략 — 중복 결제는 payment 자체 중복 판정으로 차단 |
| 재결제 정책 | **FAILED 후 재결제 허용** — 현재는 confirm 실패 시 FAILED Payment가 `pay-{orderId}` 유니크 키를 점유해 같은 주문 재결제가 영구 409. 중복 판정을 "존재 여부"에서 "**진행·완료 상태 존재 여부**"로 변경 |
| 결제 실패 이벤트 (흡수, D5) | confirm 실패 시 `payment-events`에 `payment.failed` 발행, order는 PENDING → FAILED 전이 + **FAILED → PAID 복귀 허용**(D1). 구 failure-event-publishing.md의 결제 실패 파트를 본 문서로 흡수 — 발행(전이)과 복귀(재결제)가 같은 작업에서 배포되어 어긋난 중간 상태 없음 |
| 폴백 전부 실패 | 503 반환, **Payment 미생성** — 금액을 모르는 채 Toss 호출 불가. 실패 기록도 남기지 않아 재시도와 충돌 없음 |
| 스냅샷 정리 | 이번 범위에선 보관만. 미결제 스냅샷 정리 스케줄러는 후속 과제 |
| 기존 결정문서 2건 | `unify-payment-topic.md`는 **작업 1로 선행 완료 전제**(발행 토픽은 `payment-events`, eventType은 기존 유지). `failure-event-publishing.md`는 **해체·흡수** — 결제 실패는 본 문서, 환불 실패는 partial-refund-api.md (00-execution-order.md D5) |

---

## 이벤트 계약 (신규)

**토픽**: `order-events` (기존) / **eventType**: `ORDER_CREATED` / **파티션 키**: orderId (기존 릴레이 규칙)

order-service는 기존 `OrderEventEnvelope` 포맷으로 발행한다. payment-service 컨슈머는 `eventType` 확인(이벤트 타입 필터링)에만 envelope을 사용하고, **`payload` 필드만 추출**해 스냅샷으로 저장한다.

**payment-service가 처리하는 payload 구조**:

```json
{
  "orderId": "UUID",
  "buyerId": "UUID",
  "totalOrderAmount": 50000,
  "createdAt": "2026-07-05T12:00:00"
}
```

페이로드는 결제에 필요한 최소 필드만 담는다(상품 목록 미포함).

payment-service는 `order-events`의 다른 eventType(`ORDER_PAID`, `ORDER_REFUND`)은 무시(필터링)한다.

### payment.failed (신규 — `payment-events` 토픽, payment-service 발행)

| 항목 | 값 |
|---|---|
| 토픽 / eventType | `payment-events` / `"payment.failed"` |
| 발행 시점 | PG 결제 승인 실패 (이후 서킷 OPEN 경로 포함 — circuit-breaker 작업 4) |
| payload | `{eventType, paymentId, orderId, userId}` — orderId 중심 최소 필드 (failureCode/reason 제외) |
| 구독자 반응 | Order: PENDING → FAILED (재결제 시 FAILED → PAID 복귀) |

## gRPC 계약 (신규)

order-service가 **gRPC 서버**를 신설한다(현재 order는 클라이언트만 보유). proto는 기존 컨벤션대로 양쪽 저장소에 복제한다(order-service가 product proto를 복제하는 방식과 동일).

```proto
// order_internal.proto
syntax = "proto3";
package prompthub.order.v1;

service OrderInternalService {
  rpc GetOrderPaymentInfo(GetOrderPaymentInfoRequest) returns (GetOrderPaymentInfoResponse);
}

message GetOrderPaymentInfoRequest {
  string order_id = 1;
}

message GetOrderPaymentInfoResponse {
  string order_id = 1;
  string buyer_id = 2;
  int32 total_amount = 3;
  string created_at = 4; // ISO 8601
}
```

- 주문 없음 → `NOT_FOUND` status
- 서버 포트: `${ORDER_GRPC_PORT:9083}` (product 9082 관례를 따름. seller는 Config 기본값 9091 vs yaml 기본값 9081로 코드 내부 불일치가 있어 관례 근거에서 제외 — 9083 결정에는 영향 없음)
- payment 측 deadline: `${ORDER_GRPC_DEADLINE_MS:2000}` (기존 클라이언트 관례)

---

## payment-service 변경

### 1. DB — `order_snapshot` 테이블 (신규)

| 컬럼 | 타입 | 제약 |
|---|---|---|
| id | UUID | PK |
| order_id | UUID | **UNIQUE**, NOT NULL |
| buyer_id | UUID | NOT NULL |
| total_amount | int | NOT NULL |
| source | VARCHAR(10) | NOT NULL — `EVENT` / `GRPC` |
| order_created_at | timestamp | NOT NULL |
| created_at / updated_at | timestamp | Auditing |

- **upsert 시맨틱**: `order_id` 충돌 시 무시(스냅샷은 불변 데이터 — 이벤트 중복 도착, gRPC 폴백 직후 늦은 이벤트 도착 모두 안전).
- 신규 파일: `domain/model/OrderSnapshot.java`, `domain/model/OrderSnapshotSource.java`, `domain/repository/OrderSnapshotRepository.java`, `infrastructure/persistence/` 구현체.

### 2. Kafka 컨슈머 (신규 — 현재 payment는 컨슈머 0개)

- `infrastructure/messaging/consumer/OrderEventConsumer.java` — `@KafkaListener(topics = "order-events", groupId = "payment-service-group")`
- envelope 타입이 order 패키지 소속이라 JsonDeserializer 타입 헤더를 쓸 수 없음 → **StringDeserializer + ObjectMapper 수동 파싱** (order-service의 `PaymentEventConsumer` 패턴 미러링). `KafkaConfig`에 전용 listener container factory 추가.
- **2단계 파싱**: ① 수신 문자열을 최소한으로 역직렬화해 `eventType` 필드만 읽고 `ORDER_CREATED`가 아니면 ack 후 무시, ② `ORDER_CREATED`인 경우 `payload` 노드를 `OrderCreatedPayload` record(orderId, buyerId, totalOrderAmount, createdAt)로 역직렬화 → application 서비스 호출로 스냅샷 upsert.
- 처리 실패 시 재시도 + DLT(`order-events.DLT`)는 order-service의 `DefaultErrorHandler` 패턴을 따른다.

### 3. confirm 흐름 재작성 — `ConfirmPaymentService`

**요청 계약 변경(breaking)**: `ConfirmPaymentRequest` / `ConfirmPaymentCommand`에서 `amount` 제거 → `{paymentKey, orderId}`.

새 순서:

```
1. 스냅샷 확보
   snapshot = orderSnapshotRepository.findByOrderId(orderId)
     .orElseGet(→ OrderGateway.getOrderPaymentInfo(orderId) 폴백 → upsert 저장)
   폴백도 실패(gRPC 불가/타임아웃) → 503 ORDER_INFO_UNAVAILABLE, Payment 미생성
   주문 자체가 없음(NOT_FOUND)      → 404
2. 본인 검증: snapshot.buyerId != X-User-Id → 403 (기존 권한 에러 코드 재사용)
3. 중복 판정 변경: paymentRepository에 orderId + status IN
   (REQUESTED, PAID, REFUNDING, REFUNDED, UNKNOWN) 존재 → 409 DUPLICATE_PAYMENT
   (FAILED만 있으면 통과 → 재결제 허용, 시도마다 새 Payment 행)
4. Payment 생성: totalAmount = snapshot.totalAmount,
   idempotencyKey = paymentKey (시도 단위 자연 키 — 동일 paymentKey 이중 confirm은 DB 유니크로 차단)
5. Toss confirm(paymentKey, orderId, snapshot.totalAmount) → 이후 approve/fail/이벤트 발행은 기존 그대로
```

- 신규 인터페이스: `application/gateway/external/OrderGateway.java` + `infrastructure/external/grpc/OrderGrpcClientAdapter.java` (클린 아키텍처 DIP 관례 준수).
- **`domain.repository.PaymentRepository`에 메서드 추가**: 중복 판정을 `findByIdempotencyKey(String)` 방식에서 orderId+상태 복합 조회로 교체하므로 `boolean existsByOrderIdAndStatusIn(UUID orderId, Collection<PaymentStatus> statuses)` (또는 `List<Payment> findByOrderIdAndStatusIn(...)`) 신규 추가 필요.
- 신규 에러 코드: `ORDER_INFO_UNAVAILABLE`(503), `ORDER_NOT_FOUND`(404) — `application.exception`의 기존 enum에 추가.
- `Payment.create()` 시그니처 2가지 변경:
  ① **idempotencyKey 외부 주입** — 현재 `create()` 내부에서 `"pay-" + orderId`를 하드코딩. paymentKey 파라미터로 교체하고 내부 하드코딩 제거.
  ② **amount 파라미터 단일화** — 현재 `productAmount`/`discountAmount` 분리 후 내부에서 합산. `totalAmount`(스냅샷 금액)를 직접 전달하는 단일 파라미터로 변경.

### 4. 빌드 — gRPC 의존성 (신규)

`build.gradle`(Groovy)에 order-service와 동일 버전으로 추가: `grpc-netty-shaded / grpc-protobuf / grpc-stub 1.80.0`, `protobuf-java 4.29.3`, protobuf 플러그인 `0.9.4`. `src/main/proto/order_internal.proto` 복제.

### 5. 동시성 방어 (defense in depth)

서로 다른 paymentKey로 같은 주문을 동시 confirm하면 3단계 상태 조회를 둘 다 통과할 수 있다. 1차 방어는 Toss(동일 Toss orderId 이중 승인 거부), 2차 방어로 부분 유니크 인덱스를 수동 DDL로 추가한다(`ddl-auto: update`로는 표현 불가):

```sql
CREATE UNIQUE INDEX uk_payment_order_paid ON payment (order_id) WHERE status = 'PAID';
```

### 6. payment.failed 이벤트 발행 (구 failure-event-publishing.md 흡수)

**신규 파일 2개**: `domain/event/PaymentFailedEvent.java` (record, `Payment` 보유), `infrastructure/messaging/dto/PaymentFailedMessage.java` (`eventType, paymentId, orderId, userId`).

**`ConfirmPaymentService`** — 3의 confirm 재작성 시 트랜잭션 구조를 최종형으로 함께 작성:

- 현재 `TransactionTemplate` 기반 TX1(Payment 생성)/TX2(승인 반영)/TX3(실패 반영) 3분할 구조 → `confirm()` 메서드에 `@Transactional(noRollbackFor = BusinessException.class)` 단일 트랜잭션으로 통합. DB 커넥션을 Toss 호출 동안 유지하는 트레이드오프 감수 — 실패 Payment(FAILED) 저장이 롤백되지 않아야 실패 기록·이벤트가 남는다.
- `PaymentGatewayException` catch에서 `payment.fail(...)` 저장 후 `applicationEventPublisher.publishEvent(new PaymentFailedEvent(payment))` 추가.

**`KafkaPaymentEventPublisher`** — `@TransactionalEventListener(AFTER_COMMIT)` `onPaymentFailed()` 리스너 추가, `payment-events`에 발행(파티션 키 orderId).

> **트랜잭션 주의**: `noRollbackFor` 적용 시 동시 중복 요청의 `DataIntegrityViolationException` 경로에서
> rollback-only 마킹 상태로 커밋 시도 → `UnexpectedRollbackException` → 409 대신 500 반환 가능.
> 빈도 극히 낮고 재정적 피해 없음 — 후속 개선.

> **서킷 브레이커 교차점**: circuit-breaker(작업 4)의 OPEN 경로(`payment.fail("CIRCUIT_OPEN", ...)`)도
> 이 catch 구조 위에서 payment.failed를 발행한다. D1 복귀 정책 덕분에 일시 장애발 Order FAILED도 재결제로 복귀 가능.

---

## order-service 변경

### 1. ORDER_CREATED outbox 발행

- `OutboxEvent`에 `orderCreated(...)` 정적 팩토리 추가 (eventType `ORDER_CREATED`, topic `order-events` — 기존 `orderPaid`/`orderRefund`와 동일 패턴).
- `application/event/order/OrderCreatedEvent.java` 페이로드 record 신규.
- `OutboxEventAppender.appendOrderCreated(Order order)` 추가.
- `OrderService.createOrder()` 주문 저장 직후(같은 트랜잭션) append 호출 — outbox 패턴이므로 주문 생성과 이벤트 기록의 원자성 보장.

### 2. gRPC 서버 신설

- `src/main/proto/order_internal.proto` 추가(위 계약).
- `infra/grpc/server/OrderInternalGrpcService.java` — orderId로 주문 조회 후 응답, 없으면 `NOT_FOUND`.
- gRPC 서버 부트스트랩 빈(netty-shaded `ServerBuilder`, 포트 `${ORDER_GRPC_PORT:9083}`) — order-service는 서버가 처음이므로 lifecycle(start/shutdown hook) 포함.
- 의존성은 이미 보유(BOM 관리 — `grpc-protobuf`, `grpc-stub`, `protobuf-java`는 `implementation`, `grpc-netty-shaded`는 `runtimeOnly`) — 서버 아티팩트는 grpc-netty-shaded에 포함되므로 빌드 변경 없음.

### 3. payment.failed 소비 (구 failure-event-publishing.md 흡수)

- `PaymentEventType`의 `PAYMENT_FAILED` enum은 **이미 존재**하지만 두 가지 수정 필요:
  ① `from()` 메서드가 현재 `name().equals(value)` 방식이므로 `"payment.failed"` 소문자 dot 문자열을 직접 매칭하지 못함 — `"payment.failed"` → `PAYMENT_FAILED` 변환 로직 추가 필요(문자열 값 필드 도입 또는 from() 분기).
  ② 현재 `PaymentEventConsumer.shouldIgnore()`에서 `PAYMENT_FAILED`를 무시 목록에 포함 중 — 해당 항목 제거.
- `PaymentEventHandler.handle()`의 switch 케이스에 `PAYMENT_FAILED` 분기 추가 → `OrderPaymentEventService.handlePaymentFailed()` 호출 (실제 라우팅은 Consumer가 아닌 Handler에 위치).
- (전제) 작업 1: 현재 `PaymentEventConsumer` 구독 토픽이 `"payment.events"` (dot) → `"payment-events"` (dash)로 변경 선행 필요.
- `OrderPaymentEventService.handlePaymentFailed()` 신규 — 멱등(이미 FAILED면 ack 후 무시), **PAID·REFUNDED 등 진행/완료 상태면 무시**(재결제 시나리오에서 이전 시도의 늦은 실패 이벤트 방어), PENDING이면 `order.markFailed()`(기존 메서드, 하위 OrderProduct 포함).
- **`handlePaymentApproved()` 완화 (D1 핵심)** — 상태 검증을 "PENDING만 허용" → **"PENDING 또는 FAILED 허용"**으로 변경, `Order.markPaid()`/`OrderProduct.markPaid()` 전이 가드도 FAILED → PAID 허용. 이 완화 없이는 재결제 성공 시 승인 이벤트가 거부되어 "결제됐는데 주문 미결제" 정합성 붕괴.

### 4. 유지되는 것

- `payment.approved` 소비 시 주문 금액 사후 검증(`OrderPaymentEventService`) — **삭제하지 않고 유지**(defense in depth).
- `payment.refunded` 소비 흐름 변경 없음 (`payment.approved`는 위 3의 상태 검증 완화만 적용).

---

## 클라이언트 계약 변경 요약 (FE 공유 필요)

| 항목 | 변경 전 | 변경 후 |
|---|---|---|
| `POST /api/v1/payments/confirm` body | `{paymentKey, orderId, amount}` | `{paymentKey, orderId}` |
| 403 신규 사유 | — | 주문자 본인이 아닌 confirm |
| 404 신규 사유 | — | 존재하지 않는 주문 |
| 503 신규 사유 | — | 주문 정보 확보 불가(이벤트 미도착 + order 장애). **재시도 대상** |
| 409 의미 변화 | 동일 주문 confirm 이력 존재(FAILED 포함) | 진행·완료 상태 Payment 존재 시만. **FAILED 후 재시도는 200 경로** |

Toss UI 결제 금액은 주문 생성 응답의 `totalAmount`를 사용한다(기존과 동일 — 단, 이제 위변조해도 서버가 스냅샷 금액으로 confirm하므로 Toss가 거부).

---

## 환불 흐름 영향 분석 — 구조 변경 불필요

환불은 confirm 하류에서 **Payment 애그리거트만 보고 동작**하므로 이번 재설계의 영향을 받지 않는다. 환불이 의존하는 입력은 모두 confirm 이후 확정되는 값이다.

| 관점 | 판단 |
|---|---|
| 다건 Payment (재결제 허용) | 환불은 paymentId 기준 + `status == PAID` 검증(`RefundPaymentService`)이라 FAILED 행이 몇 건이든 환불 대상 불가. 클라이언트는 confirm 성공 시에만 paymentId를 받으므로 항상 성공 건만 보유 |
| idempotencyKey 규칙 변경 | 환불의 Toss 멱등 키는 `refund-{paymentId}`, PG 호출 키는 `pgTxId`(승인 시 저장) — `pay-{orderId}` → paymentKey 변경과 무관 |
| 스냅샷 테이블 | 환불 흐름은 스냅샷을 읽지 않음. REFUNDED 주문의 재confirm은 중복 판정(REFUNDED 포함)으로 차단 |
| 스케줄러(`PaymentRefundRetryScheduler`) | Payment 필드만 사용 — 영향 없음 |

**오히려 개선되는 부분**:

- 환불 권한 검증(`payment.userId == X-User-Id`)은 payment.userId가 실제 주문자라는 전제 위에 있는데, 현재는 confirm 시 타인 결제가 가능해 전제가 깨질 수 있었다. 새 설계의 주문자 본인 검증이 상류에서 이를 보장 → 환불 권한 검증이 비로소 의도대로 동작.
- 환불 금액(`payment.totalAmount`)이 클라이언트 입력값에서 스냅샷(주문 원본) 금액으로 바뀌어 환불 기록 정확성 향상.

**정합성 전제 2건 (본 계획에 이미 반영됨)**:

1. 환불 실패 시 `restoreToRefundFailed()`로 REFUNDING → PAID 복귀하는 동안 같은 주문의 새 confirm이 끼어들 이론적 창 → 중복 판정 차단 목록에 **REFUNDING·REFUNDED 포함**으로 차단.
2. 신규 order-events 컨슈머는 `ORDER_REFUND` 타입을 무시 → "환불은 payment 주도, order 후행 소비" 방향 유지.

**잔여 리스크(이번 범위 외)**: `payment.refund-failed` 이벤트 미구현으로 환불 실패를 order가 알 수 없는 기존 이슈는 `partial-refund-api.md`(작업 3) 범위 — 본 재설계로 악화되지 않음.

---

## 테스트 확인 포인트

payment-service (Testcontainers PostgreSQL + Kafka, 통합 테스트는 루트 패키지):

- `order-events`에 ORDER_CREATED 발행 → 스냅샷 저장 확인 / 동일 이벤트 중복 발행 → 스냅샷 1건 유지 / ORDER_PAID 등 타 타입 → 무시
- confirm: 스냅샷 존재 → 정상 승인(Toss stub) / 스냅샷 부재 + gRPC 성공 → 승인 + 스냅샷 GRPC source 저장 / 스냅샷 부재 + gRPC 실패 → 503 + **Payment 행 0건** / buyerId 불일치 → 403 / FAILED Payment 존재 후 재confirm → 새 Payment로 승인 / PAID 존재 후 재confirm → 409
- PG 실패 → `payment-events`에서 `eventType = "payment.failed"` 수신 + Payment FAILED 저장 확인
- 기존 `ConfirmPaymentIntegrationTest` — ① amount 제거 반영, ② `paymentJpaRepository.findByIdempotencyKey("pay-" + orderId)` 검증 코드를 paymentKey 기반 조회(`findByIdempotencyKey(paymentKey)`)로 변경

order-service:

- 주문 생성 → outbox에 ORDER_CREATED PENDING 기록 / 릴레이 발행 후 PUBLISHED 전이
- gRPC `GetOrderPaymentInfo` — 존재 주문 응답 필드 검증 / 미존재 주문 NOT_FOUND
- `payment.failed` 수신 → Order·OrderProduct FAILED 전이 / FAILED 주문에 `payment.approved` 수신 → PAID 복귀(재결제 경로) / PAID 주문에 늦은 `payment.failed` → 무시(상태 불변)

---

## 인지하고 가야 할 트레이드오프 / 후속 과제

| 항목 | 내용 |
|---|---|
| 이중 경로 유지 비용 | 스냅샷 확보 로직이 이벤트/gRPC 두 갈래. 폴백을 `orElseGet` 한 지점으로 격리해 confirm 본류는 단일 경로로 유지 |
| 스냅샷 불변 가정 | 주문 금액이 생성 후 변하지 않는다는 전제. 주문 수정 기능이 생기면 스냅샷 갱신 이벤트 필요 |
| 스냅샷 누적 | 미결제 주문 스냅샷 무한 보관 — 정리 스케줄러는 후속 과제 |
| 재결제 × 실패 이벤트 충돌 — **해소됨(D1)** | `payment.failed` 수신 시 order는 기존 `FAILED` 상태로 전이하되, `handlePaymentApproved`가 PENDING·FAILED 양쪽에서 PAID 전이를 허용해 재결제 복귀 경로를 보장 — **본 문서 범위에서 구현** (payment-service §6, order-service §3) |
| FAILED Payment 다건 | 재결제 허용으로 한 주문에 FAILED 행 N개 가능 — 조회/통계 시 최신 상태는 진행·완료 상태 행 기준 |
| Eureka 미경유 gRPC | 기존 관례대로 host/port 직접 설정. 서비스 디스커버리 통합은 범위 외 |

## 작업 순서

1. **order-service**: `OrderCreatedEvent` + `OutboxEvent.orderCreated` + appender + `createOrder()` 연결 (+테스트)
2. **order-service**: proto 정의 + gRPC 서버 부트스트랩 + `GetOrderPaymentInfo` 구현 (+테스트)
3. **payment-service**: gRPC 의존성 + proto 복제 + `OrderGateway`/어댑터
4. **payment-service**: `OrderSnapshot` 엔티티/리포지토리 (+영속성 테스트)
5. **payment-service**: Kafka 컨슈머 + listener factory (+통합 테스트)
6. **payment-service**: confirm 재작성 — amount 제거, 스냅샷 확보/본인 검증/중복 판정 변경/idempotencyKey 변경, 트랜잭션 구조 최종형(`noRollbackFor`) (+통합 테스트)
7. **payment-service**: `PaymentFailedEvent`/`PaymentFailedMessage` + catch 발행 + `onPaymentFailed()` 리스너 (+통합 테스트)
8. **order-service**: `PAYMENT_FAILED` 소비 — `handlePaymentFailed()` + `handlePaymentApproved()` 완화 (+통합 테스트)
9. 부분 유니크 인덱스 수동 DDL 적용
10. 문서 갱신: `events.md`(ORDER_CREATED 구독·`payment.failed` 발행 추가), `db-schema.md`(order_snapshot), `api-design.md`(confirm 계약 변경)
