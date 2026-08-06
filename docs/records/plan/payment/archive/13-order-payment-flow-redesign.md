# 설계 스펙: 주문-결제 흐름 재설계 (payment-service 구현 범위)

> 출처: `.claude/docs/final/order-payment-flow-redesign.md`를 참고하되, 코드베이스 대조와 협의를 통해 payment-service 단독 구현 범위로 구체화한 문서.
> 작성일: 2026-07-08

## 0. 목표와 동기

현재 confirm은 클라이언트가 보낸 `amount`를 검증 없이 Toss로 전달한다(금액의 진실 공급원이 클라이언트). 또한 confirm 실패 시 `pay-{orderId}` 유니크 키를 FAILED Payment가 점유해 같은 주문 재결제가 영구 409가 된다. 본 재설계는:

1. **사전 금액 검증** — 주문 스냅샷(order 발행)을 진실 공급원으로 삼아 confirm 요청에서 `amount`를 제거한다.
2. **결제 시점 동기 결합 완화** — 평상시엔 로컬 스냅샷만 읽고 order와 왕복하지 않는다(이벤트 경로). 이벤트 유실·지연 시에만 gRPC 폴백.
3. **재결제 허용** — 중복 판정을 "존재 여부"에서 "진행·완료 상태 존재 여부"로 바꾼다.
4. **주문자 본인 검증** — confirm 요청자가 실제 주문자인지 스냅샷 기준으로 검증한다.
5. **결제 실패 이벤트 발행** — confirm 실패 시 `payment.failed`를 발행한다.

## 1. 범위와 산출물 경계

**이번 구현 대상: payment-service 전체 + 격리 테스트.**

- payment-service의 코드(컨슈머 / gRPC 클라이언트 / confirm 재작성 / payment.failed 발행)를 전부 작성한다.
- 테스트는 order-service 없이 격리 검증한다(Testcontainers Kafka에 `ORDER_CREATED` 직접 발행, `OrderGateway`는 mock, `PaymentGateway`는 `@MockitoBean`).
- **계약(proto + order-events payload)은 본 문서에서 동결**하여 order-service가 나중에 미러링한다(§2).

### E2E 갭 (order-service 후속 작업 — 이번 범위 밖)

아래가 order-service에 반영되기 전까지 **실서비스 end-to-end는 미완**이다. 본 문서는 payment-service 코드가 이 계약에 맞춰 준비 완료된 상태까지를 산출물로 한다.

- `ORDER_CREATED`를 `order-events`에 발행 (기존 outbox 패턴) + **평면 메시지 record 신설**(envelope 미사용, §2.1)
- gRPC 서버(`GetOrderPaymentInfo`) 신설, 포트 9083
- **payment 이벤트 구독 토픽 정정**: order-service는 현재 `payment.events`(dot)를 구독하나 payment는 `payment-events`(dash)로 발행한다. order가 dash로 바꾸기 전엔 우리가 발행하는 `payment.failed`(및 기존 approved/refunded)도 소비되지 않는다 → order-service의 topic 통일 선행 필요.
- `payment.failed` 소비 → Order PENDING → FAILED 전이 (order의 `PaymentEventConsumer.shouldIgnore`가 현재 `PAYMENT_FAILED`를 무시 목록에 포함 중 → 제거 필요)
- `handlePaymentApproved` 완화 → FAILED → PAID 복귀 허용 (재결제 정합성)

## 2. 동결 계약 (order-service가 맞출 대상)

### 2.1 order-events 메시지 (구독)

- **토픽**: `order-events` (기존) / **eventType**: `ORDER_CREATED` (order-service의 `ORDER_PAID`/`ORDER_REFUND`와 동일 대문자 스네이크 규칙) / **파티션 키**: `orderId`
- **envelope로 감싸지 않고 `eventType` 필드만 최상위에 둔 평면(flat) 메시지**로 발행한다 — payment-service의 `PaymentApprovedMessage`(`{eventType, paymentId, ...}`) 관례와 동일. Jackson camelCase. **메시지 구조**:

```json
{
  "eventType": "ORDER_CREATED",
  "orderId": "UUID",
  "buyerId": "UUID",
  "totalOrderAmount": 50000,
  "createdAt": "2026-07-05T12:00:00"
}
```

- payment-service는 최상위 `eventType`으로 필터링하고 **나머지 필드를 그대로 스냅샷으로 저장**한다(결제에 필요한 최소 필드, 상품/할인 분해 없음). `order-events`의 다른 eventType(`ORDER_PAID`, `ORDER_REFUND`)은 무시한다.
- **시각 필드 주의**: `createdAt`은 order-service에서 `LocalDateTime`(존 없음)으로 직렬화된다. payment의 `order_snapshot.order_created_at`은 `timestamptz`이므로, 컨슈머가 문자열을 파싱할 때 **KST 존을 부여**해 `OffsetDateTime`으로 저장한다(기존 payment의 KST 표기 관례와 일치).
- **협의 사항(형태 불일치)**: order-service의 기존 order-events(`ORDER_PAID`/`ORDER_REFUND`)는 `OrderEventEnvelope`로 감싸 발행되나, `ORDER_CREATED`는 위 **평면 구조로 발행**하기로 동결한다(요청 반영). 같은 토픽에 두 형태가 공존해도 우리 컨슈머는 최상위 `eventType`만으로 필터링·역직렬화하므로 무해하다(§8). order-service는 envelope을 거치지 않는 신규 메시지 record를 추가해 발행해야 한다.
- **주의**: `ORDER_CREATED`와 이 메시지 record는 order-service에 **아직 없다**(§1 E2E 갭). 위 필드명·구조는 본 문서가 동결하는 계약이며 order-service가 이대로 발행해야 한다.

### 2.2 gRPC 계약 (폴백)

order-service가 gRPC 서버를 신설한다. proto는 양쪽 저장소에 복제한다(order-service가 product proto를 복제하는 관례와 동일).

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
- 서버 포트: `${ORDER_GRPC_PORT:9083}`
- payment 측 deadline: `${ORDER_GRPC_DEADLINE_MS:2000}`

### 2.3 payment.failed (발행, 신규)

| 항목 | 값 |
|---|---|
| 토픽 / eventType | `payment-events` / `"payment.failed"` |
| 발행 시점 | PG 결제 승인 실패 (TX3) |
| payload | `{ eventType, paymentId, orderId, userId }` — orderId 중심 최소 필드 |
| 파티션 키 | `orderId` |
| 구독자 반응 (order-service) | PENDING → FAILED (재결제 시 FAILED → PAID 복귀) |

## 3. 협의로 확정된 설계 결정

| # | 결정 | 근거 |
|---|---|---|
| D1 | **범위 = payment-service 전체 구현 + 격리 테스트.** 계약 동결, E2E는 order-service 후속. | 계획서는 본질적으로 cross-service. payment 코드는 mock/Testcontainers로 단독 검증 가능. |
| D2 | **트랜잭션 = 기존 TX1/TX2/TX3 3분할 유지.** `noRollbackFor` 단일 TX 채택 안 함. | 현재 TX3가 이미 FAILED를 별도 커밋하므로 실패 기록/이벤트 목표는 3분할로 달성. 단일 TX는 Toss 호출 중 커넥션 점유 + `UnexpectedRollbackException`→500 엣지를 새로 유발. |
| D3 | **중복 판정 = `existsByOrderIdAndStatusIn(orderId, {PAID, REFUNDING, REFUNDED, UNKNOWN})`.** REQUESTED·FAILED·READY는 비차단. | D2와 한 몸. REQUESTED 비차단이므로 Toss 호출 중 크래시로 남은 고아 REQUESTED가 재결제를 영구 차단하지 않음. 동시성은 부분 유니크 인덱스(§5.3)로 방어. |
| D4 | **금액 컬럼 = `total_amount` 단일 유지.** `product_amount`/`discount_amount` 제거. | 스냅샷·payload의 금액 출처가 `totalAmount` 하나뿐. 분해 컬럼은 채울 값이 없음. |
| D5 | **confirm 계약 변경 4종 포함**: amount 제거(breaking) / 본인 검증 403 / 신규 에러코드 2개 / **멱등 일원화**(아래 D8). | §6 참조. |
| D6 | **`ddl-auto: update` 유지 + 멱등 `schema.sql`.** 컬럼 제거(`DROP COLUMN IF EXISTS`)와 부분 유니크 인덱스(`CREATE UNIQUE INDEX IF NOT EXISTS`)를 `schema.sql`에서 함께 수행. | create-drop을 채택하지 않음(재기동마다 데이터 초기화 → 수동 Toss 테스트에 불리). `IF EXISTS`/`IF NOT EXISTS`로 매 기동 멱등 실행 → 데이터 보존 + 수동 DDL 없음. |
| D7 | **신규 컨슈머 그룹 = `payment-service-order-events`(전용).** yaml 기본 `payment-service-group` 재사용 안 함. | 컨슈머 그룹은 오프셋 추적 단위. 토픽 목적별 전용 그룹이 의도가 명확하고 오프셋·스케일 독립. |
| D8 | **`idempotency_key` 컬럼 제거 → 멱등성을 `pg_tx_id`(=paymentKey)로 일원화.** `pg_tx_id`에 UNIQUE 부여 + `VARCHAR(255)`로 확장. | `pg_tx_id`는 이미 paymentKey를 담아 환불 cancel 경로(`/payments/{paymentKey}/cancel`)에 필수. `idempotency_key`는 유일 용도가 이중 confirm 차단 유니크뿐이고(조회 `findByIdempotencyKey`는 confirm 재작성으로 제거), Toss 응답에 별도 거래식별자가 없어 두 컬럼이 같은 paymentKey를 중복 저장. 한 컬럼으로 합쳐 중복 제거 + paymentKey 길이(≤약 200자) 수용. |

## 4. 빌드 변경

payment-service `build.gradle`을 **order-service `build.gradle` 그대로 미러링**한다.

- `apply plugin: 'com.google.protobuf'`
- `implementation 'io.grpc:grpc-protobuf'`, `'io.grpc:grpc-stub'`, `'com.google.protobuf:protobuf-java'`
- `runtimeOnly 'io.grpc:grpc-netty-shaded'`
- `testImplementation 'io.grpc:grpc-inprocess'`
- `protobuf { ... }` 코드젠 블록 (protoc/grpc 아티팩트)
- **버전은 루트 BOM/`ext`가 관리**(`grpcVersion=1.80.0`, `protobufVersion=4.34.2`, 플러그인 `0.9.6`). 참고 문서의 리터럴 버전(4.29.3 / 0.9.4)은 낡았으므로 무시.
- `src/main/proto/order_internal.proto` 복제 (§2.2).

## 5. DB 변경

### 5.1 `order_snapshot` 테이블 (신규)

| 컬럼 | 타입 | 제약 |
|---|---|---|
| id | UUID | PK |
| order_id | UUID | **UNIQUE**, NOT NULL |
| buyer_id | UUID | NOT NULL |
| total_amount | int | NOT NULL |
| source | VARCHAR(10) | NOT NULL — `EVENT` / `GRPC` |
| order_created_at | timestamptz | NOT NULL |
| created_at / updated_at | timestamptz | Auditing |

- **upsert 시맨틱**: `order_id` 충돌 시 무시(스냅샷은 불변 데이터). 이벤트 중복 도착, gRPC 폴백 직후 늦은 이벤트 도착 모두 안전.
- 신규 파일:
  - `domain/model/OrderSnapshot.java`
  - `domain/model/OrderSnapshotSource.java` (enum: `EVENT`, `GRPC`)
  - `domain/repository/OrderSnapshotRepository.java`
  - `infrastructure/persistence/OrderSnapshotRepositoryAdapter.java` + `OrderSnapshotJpaRepository.java`

### 5.2 스키마 관리 방식 (D6)

`ddl-auto: update`를 **유지**하고(현재 설정 그대로), Hibernate `update`가 표현·수행할 수 없는 것들 — ① `payment` 컬럼 제거, ② 기존 컬럼 타입 확장, ③ 부분 유니크 인덱스 — 를 **멱등 `schema.sql`**로 처리한다. `update`는 컬럼을 drop하지 못하고(엔티티에서 필드를 빼도 DB 컬럼은 남아 NOT NULL 삽입 실패), 기존 컬럼 길이도 넓히지 못하며, 부분 인덱스(`WHERE`)도 표현하지 못하기 때문이다.

- `src/main/resources/schema.sql` (모든 문장 멱등):

```sql
-- 금액 분해 컬럼 제거 (D4)
ALTER TABLE payment DROP COLUMN IF EXISTS product_amount;
ALTER TABLE payment DROP COLUMN IF EXISTS discount_amount;

-- 멱등키 일원화 (D8): idempotency_key 제거, pg_tx_id로 통합
ALTER TABLE payment DROP COLUMN IF EXISTS idempotency_key;
ALTER TABLE payment ALTER COLUMN pg_tx_id TYPE VARCHAR(255);          -- paymentKey(≤약 200자) 수용
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_pg_tx_id ON payment (pg_tx_id);  -- 동일 paymentKey 이중 confirm 차단

-- 동시성 방어용 부분 유니크 인덱스 (§5.3)
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_order_paid ON payment (order_id) WHERE status = 'PAID';
```

- `application.yaml`에 추가:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: update               # 현재 설정 유지
    defer-datasource-initialization: true   # Hibernate DDL 이후 schema.sql 실행
  sql:
    init:
      mode: always                   # PostgreSQL(비내장)에서도 schema.sql 실행
```

- 실행 순서: Hibernate `update`가 엔티티 기준 테이블/컬럼 생성 → `schema.sql`(deferred)이 분해 컬럼·`idempotency_key` 제거 + `pg_tx_id` 확장 + 유니크/부분 인덱스 생성. `IF EXISTS`/`IF NOT EXISTS`로 **매 기동 멱등** — 데이터는 보존되고 수동 DDL은 없다.
- 엔티티 `Payment` 변경: `productAmount`/`discountAmount` 필드 제거(`total_amount`만 유지, D4), **`idempotencyKey` 필드 제거**(D8), `pgTxId`는 `@Column(length = 255)`로 조정.
- 테스트(Testcontainers)도 동일 경로로 컬럼 제거·인덱스를 확보하므로 409 충돌 테스트가 성립한다.
- **기존 dev 데이터 주의(dev 한정)**: `uk_payment_pg_tx_id`는 기존 `payment` 행에 `pg_tx_id` 중복이 없어야 생성된다(정상 흐름에선 시도마다 고유 paymentKey라 중복 없음). 중복이 있으면 인덱스 생성이 실패하므로 dev DB를 정리하거나 재기동으로 초기화한다.

### 5.3 부분 유니크 인덱스의 역할 (동시성 방어, defense in depth)

서로 다른 paymentKey로 같은 주문을 동시 confirm하면 D3 상태 조회(REQUESTED 비차단)를 둘 다 통과할 수 있다. 1차 방어는 Toss(동일 Toss orderId 이중 승인 거부), 2차 방어가 `uk_payment_order_paid` 부분 유니크 인덱스다 — 같은 orderId로 두 번째 PAID 전이(TX2 save)가 `DataIntegrityViolationException`으로 차단되어 409로 매핑된다(§6.3). DDL 적용은 §5.2 참조.

## 6. confirm 재작성 (`ConfirmPaymentService`)

### 6.1 요청 계약 변경 (breaking, D5)

- `ConfirmPaymentRequest` / `ConfirmPaymentCommand`에서 **`amount` 제거** → `{ paymentKey, orderId }` (+ 컨트롤러가 주입하는 `userId`).
- 금액의 진실 공급원은 스냅샷. Toss에는 스냅샷 금액을 전달하므로 클라이언트가 Toss UI에서 다른 금액을 인증했다면 Toss가 불일치로 거부.

### 6.2 흐름 (3분할 TX 유지, D2)

```
TX1: 스냅샷 확보 + 검증 + Payment 생성/REQUESTED
  1. snapshot = orderSnapshotRepository.findByOrderId(orderId)
       .orElseGet(→ OrderGateway.getOrderPaymentInfo(orderId) → upsert(source=GRPC))
     · gRPC 타임아웃/불가        → 503 ORDER_INFO_UNAVAILABLE, Payment 미생성
     · 주문 없음(gRPC NOT_FOUND) → 404 ORDER_NOT_FOUND
  2. 본인 검증: snapshot.buyerId != X-User-Id → 403 (기존 권한 에러코드 재사용)
  3. 중복 판정 (D3): existsByOrderIdAndStatusIn(orderId, {PAID, REFUNDING, REFUNDED, UNKNOWN})
       → true면 409 DUPLICATE_PAYMENT
       (REQUESTED·FAILED·READY 비차단 → 재결제 허용, 시도마다 새 Payment 행)
  4. Payment.create(orderId, userId, paymentKey, provider, method, isTest, totalAmount)
       · pgTxId = paymentKey (idempotency_key 컬럼 제거, pg_tx_id UNIQUE로 동일 paymentKey 이중 confirm 차단, D8)
       · totalAmount = snapshot.totalAmount
     markRequested → save
[Toss confirm(paymentKey, orderId, snapshot.totalAmount)]   ← TX 밖 (기존 위치)
TX2(성공): payment.approve(...) + publishEvent(PaymentApprovedEvent)  ← 기존 그대로
TX3(실패): payment.fail(...) + publishEvent(PaymentFailedEvent)       ← §7 신규
```

### 6.3 신규/변경 요소

- **인터페이스**: `application/gateway/external/OrderGateway.java` (DIP) + `infrastructure/external/grpc/OrderGrpcClientAdapter.java` 구현.
- **`PaymentRepository` 메서드 변경**: `boolean existsByOrderIdAndStatusIn(UUID orderId, Collection<PaymentStatus> statuses)` **추가**, `findByIdempotencyKey(...)` **제거**(멱등키 컬럼 제거로 무의미 — 유일 호출부였던 confirm 중복 판정은 `existsByOrderIdAndStatusIn`로 대체). `PaymentRepository`/`PaymentJpaRepository`/`PaymentRepositoryAdapter` 3곳에서 제거.
- **신규 에러코드** (`application.exception`의 기존 enum에 추가): `ORDER_INFO_UNAVAILABLE`(503), `ORDER_NOT_FOUND`(404). 403은 기존 권한 에러코드 재사용.
- **ExceptionHandler**: 신규 503/404 코드 매핑 확인.
- **`Payment.create()` 시그니처 변경** (D4/D8):
  - amount 단일화 — `productAmount`/`discountAmount` 분리 제거, `totalAmount` 단일 파라미터.
  - `idempotencyKey` 필드·내부 하드코딩(`"pay-" + orderId`) 제거(D8). `pgTxId` 파라미터는 유지(호출부가 `command.paymentKey()` 전달 — 기존과 동일).
  - 최종 시그니처(안): `create(UUID orderId, UUID userId, String pgTxId, String provider, String paymentMethod, boolean isTest, int totalAmount)`.
- **멱등 일원화 근거(D8)**: paymentKey는 Toss가 결제 시도마다 발급하는 고유 키. 동일 paymentKey 재요청은 `pg_tx_id` UNIQUE로 차단(정확한 멱등성), 재결제는 새 paymentKey로 새 행이 생겨 성립. `idempotency_key` 컬럼은 유일 용도가 이 유니크뿐이었고 `pg_tx_id`가 이미 같은 값을 담아 중복이었으므로 컬럼을 제거해 일원화한다. 환불의 Toss `Idempotency-Key` 헤더는 `"refund-" + paymentId`(상수)라 이 변경과 무관.
- **동시성/이중 confirm 예외 매핑**: TX1 save 시 `uk_payment_pg_tx_id` 충돌(동일 paymentKey 재요청) 또는 TX2 save 시 `uk_payment_order_paid` 충돌(서로 다른 paymentKey 동시 결제)로 `DataIntegrityViolationException` 발생 → 둘 다 409 DUPLICATE_PAYMENT로 매핑(기존 catch 구조 확장).

## 7. payment.failed 이벤트 발행 (D2 기반)

- **신규 파일 2개**:
  - `domain/event/PaymentFailedEvent.java` — `record PaymentFailedEvent(Payment payment)`
  - `infrastructure/messaging/dto/PaymentFailedMessage.java` — `{ eventType, paymentId, orderId, userId }`
- **`ConfirmPaymentService` TX3**: `payment.fail(...)` 저장 후 `applicationEventPublisher.publishEvent(new PaymentFailedEvent(payment))` 추가. **`noRollbackFor` 불필요** — TX3가 이미 별도 트랜잭션으로 커밋되므로 AFTER_COMMIT 리스너가 정상 발화.
- **`KafkaPaymentEventPublisher`**: `@TransactionalEventListener(AFTER_COMMIT)` `onPaymentFailed(PaymentFailedEvent)` 리스너 추가 → `payment-events`에 `PaymentFailedMessage` 발행(키 orderId).

## 8. Kafka 컨슈머 (신규 — 현재 컨슈머 0개)

- `infrastructure/messaging/consumer/OrderEventConsumer.java` — `@KafkaListener(topics = "order-events", groupId = "payment-service-order-events", containerFactory = "orderEventKafkaListenerContainerFactory")` (D7: 전용 그룹).
- 메시지 타입(record)이 order 도메인 소속 계약이라 JsonDeserializer 타입 헤더에 의존할 수 없음 → **StringDeserializer + ObjectMapper 수동 파싱**. `KafkaConfig`에 전용 listener container factory 추가.
- **파싱** (평면 메시지 §2.1, order-service `PaymentEventConsumer`의 String+readTree 패턴 미러링):
  1. `objectMapper.readTree(message)`로 최상위 `root.path("eventType")`만 확인. `ORDER_CREATED`가 아니면 ack 후 무시(같은 토픽의 envelope 형식 `ORDER_PAID`/`ORDER_REFUND`도 이 단계에서 걸러짐 — 둘 다 최상위 `eventType` 보유).
  2. `ORDER_CREATED`면 **메시지 전체**를 `OrderCreatedMessage` record(`eventType, orderId, buyerId, totalOrderAmount, createdAt`)로 역직렬화 → application 서비스 호출로 스냅샷 upsert(source=EVENT). 필수 필드 누락 시 예외.
- **컨슈머 인프라(order-service KafkaConfig 미러링)**:
  - `ConsumerFactory`: `ErrorHandlingDeserializer`로 key/value를 감싸고 위임 대상은 `StringDeserializer`.
  - `ConcurrentKafkaListenerContainerFactory`: `AckMode.MANUAL`, `DefaultErrorHandler` 설정.
  - `DefaultErrorHandler`: `DeadLetterPublishingRecoverer`로 `{topic}.DLT`(= `order-events.DLT`) 발행 + `FixedBackOff(1000ms, 3회)`.
- **주의(발행 순서 vs 소비)**: 정상 흐름에서 `ORDER_CREATED`(order-events) 도착은 confirm보다 앞선다(Toss UI 소요 시간에 릴레이 폴링 지연 흡수). 늦게 도착해도 confirm의 gRPC 폴백이 스냅샷을 확보하고, 이후 도착하는 이벤트는 upsert 무시로 안전(§5.1).

## 9. gRPC 클라이언트

- `application/gateway/external/OrderGateway` 인터페이스: `OrderPaymentInfo getOrderPaymentInfo(UUID orderId)` (실패 시 도메인/애플리케이션 예외로 변환).
- `infrastructure/external/grpc/OrderGrpcClientAdapter` 구현: netty-shaded `ManagedChannel`, deadline 적용, `NOT_FOUND` → `ORDER_NOT_FOUND`, 그 외 실패/타임아웃 → `ORDER_INFO_UNAVAILABLE`로 변환.
- 설정(`application.yaml`): `ORDER_GRPC_HOST`(기본 localhost), `ORDER_GRPC_PORT:9083`, `ORDER_GRPC_DEADLINE_MS:2000`.
- Eureka 미경유(기존 관례대로 host/port 직접 설정).

## 10. 테스트 전략 (격리)

payment-service (Testcontainers PostgreSQL + Kafka, 통합 테스트는 루트 패키지 `com.prompthub.paymentservice`):

- **컨슈머**: `order-events`에 `ORDER_CREATED` 발행 → 스냅샷 저장 확인 / 동일 이벤트 중복 발행 → 스냅샷 1건 유지 / `ORDER_PAID` 등 타 타입 → 무시.
- **confirm**:
  - 스냅샷 존재 → 정상 승인(`@MockitoBean PaymentGateway` stub)
  - 스냅샷 부재 + gRPC 성공(`@MockitoBean OrderGateway`) → 승인 + 스냅샷 GRPC source 저장
  - 스냅샷 부재 + gRPC 실패 → 503 + **Payment 행 0건**
  - buyerId 불일치 → 403
  - FAILED Payment 존재 후 재confirm → 새 Payment로 승인
  - PAID 존재 후 재confirm → 409
- **payment.failed**: PG 실패 → `payment-events`에서 `eventType = "payment.failed"` 수신 + Payment FAILED 저장 확인.
- **기존 테스트 수정** (`findByIdempotencyKey`/`idempotencyKey` 참조는 §6.3의 컬럼 제거로 전부 정리):
  - `ConfirmPaymentIntegrationTest` — amount 제거 반영, `findByIdempotencyKey("pay-" + orderId)` 조회 → `findById(paymentId)` 또는 orderId/status 기반 검증으로 대체.
  - `PaymentJpaRepositoryTest` — `create(..., 10_000, 1_000)`(productAmount/discountAmount) → 단일 `totalAmount` 시그니처로 수정, `idempotencyKey` 라운드트립 단언 제거(`pgTxId` 단언으로 대체).
  - `ConfirmPaymentServiceTest`·`PaymentTest`·`RefundPaymentIntegrationTest` — `idempotencyKey` 참조/`create` 시그니처 변경분 반영(`grep`으로 확인된 5개 테스트 파일 전수 정리).

## 11. 환불 흐름 영향 — 구조 변경 불필요

환불은 confirm 하류에서 Payment 애그리거트만 보고 동작하므로 본 재설계의 영향을 받지 않는다.

- 다건 Payment(재결제 허용): 환불은 paymentId 기준 + `status == PAID` 검증이라 FAILED 행이 몇 건이든 무관. 클라이언트는 confirm 성공 시에만 paymentId 보유.
- 멱등키 일원화(D8) 무관: 환불의 Toss cancel 경로 키는 `pgTxId`(=paymentKey, 값 불변), Toss `Idempotency-Key` 헤더는 `refund-{paymentId}`(상수). `idempotency_key` 컬럼 제거는 환불이 이 컬럼을 읽지 않으므로 영향 없음.
- 스냅샷 테이블: 환불 흐름은 스냅샷을 읽지 않음.
- **개선점**: 주문자 본인 검증이 상류에서 `payment.userId == 실제 주문자` 전제를 보장 → 환불 권한 검증이 비로소 의도대로 동작.
- 정합성: 중복 판정 차단 목록에 REFUNDING·REFUNDED 포함 → 환불 실패 복귀(REFUNDING→PAID) 중 새 confirm 끼어듦 차단.

## 12. 작업 순서 (payment-service만)

1. `build.gradle` 미러링 + `order_internal.proto` 복제 (§4)
2. `OrderGateway` + `OrderGrpcClientAdapter` (§9)
3. `OrderSnapshot` 엔티티/리포지토리 (+영속성 테스트) (§5.1)
4. `OrderEventConsumer` + listener container factory (+통합 테스트) (§8)
5. confirm 재작성 — amount 제거·스냅샷 확보·본인 검증·중복 판정(`existsByOrderIdAndStatusIn`)·`findByIdempotencyKey` 제거·`Payment.create` 단일화 (+통합 테스트) (§6)
6. `PaymentFailedEvent`/`PaymentFailedMessage` + TX3 발행 + `onPaymentFailed` 리스너 (+통합 테스트) (§7)
7. `schema.sql`(분해 컬럼·`idempotency_key` 제거 + `pg_tx_id` 확장·UNIQUE + 부분 유니크 인덱스) + `application.yaml` 설정(`defer-datasource-initialization`, `sql.init.mode`) (§5.2, §5.3, D8)
8. 문서 반영 (§14) — 코드 변경 완료 후 일괄 수행

## 13. 트레이드오프 / 후속 과제

| 항목 | 내용 |
|---|---|
| 이중 경로 유지 비용 | 스냅샷 확보가 이벤트/gRPC 두 갈래. 폴백을 `orElseGet` 한 지점으로 격리해 confirm 본류는 단일 경로 유지. |
| 스냅샷 불변 가정 | 주문 금액이 생성 후 불변이라는 전제. 주문 수정 기능이 생기면 스냅샷 갱신 이벤트 필요. |
| 스냅샷 누적 | 미결제 주문 스냅샷 무한 보관 — 정리 스케줄러는 후속 과제. |
| FAILED Payment 다건 | 재결제 허용으로 한 주문에 FAILED 행 N개 가능 — 최신 상태는 진행·완료 상태 행 기준. |
| 멱등키 일원화(해결) | 기존 `idempotency_key`·`pg_tx_id` 이중 저장을 이번 작업에서 `pg_tx_id` 단일 컬럼으로 통합(D8, §6.3). `VARCHAR(255)` 확장으로 paymentKey 길이 여유 확보. 잔여: 컬럼명이 `pg_tx_id`(값은 Toss paymentKey)라 의미상 `payment_key`가 더 명확할 수 있으나, provider 중립적 명칭 유지 위해 이번엔 개명하지 않음. |
| schema.sql 운영 승격 | 멱등 `schema.sql`은 dev용. 운영 승격 시 Flyway 등 버전 마이그레이션으로 전환 권장. |
| E2E 미완 | §1의 order-service 후속 작업 전까지 실서비스 흐름 미완 — 계약 동결로 무결하게 이어받도록 준비. |

## 14. 문서 반영 계획 (코드 변경 후 일괄)

구현 완료 후, 아래 문서를 실제 변경에 맞춰 갱신한다. **수정 범위는 payment-service `.claude/` 및 모노레포 `../docs/`(payment 반영 목적)로 한정**(AI 작업 원칙).

### 14.1 `.claude/docs/` (도메인 참조 문서)

| 문서 | 갱신 내용 |
|---|---|
| `events.md` | ① 구독 섹션 신설 — `order-events`의 `ORDER_CREATED` 소비(**평면 메시지 계약** §2.1, 그룹 `payment-service-order-events`, DLT `order-events.DLT`). ② 발행에 `payment.failed`(payload `{eventType, paymentId, orderId, userId}`) 추가. ③ 토픽 `payment-events`(dash) 확정 반영. |
| `db-schema.md` | ① `order_snapshot` 테이블 추가(§5.1). ② `payment`에서 `product_amount`·`discount_amount` 제거, `total_amount` 단일화(D4). ③ **`idempotency_key` 컬럼 제거**(D8), `pg_tx_id`를 `VARCHAR(255)` + UNIQUE(`uk_payment_pg_tx_id`)로 갱신·설명(값=Toss paymentKey). ④ `uk_payment_order_paid` 부분 유니크 인덱스 기재. |
| `api-design.md` | confirm 계약 변경 — 요청 body `amount` 제거(`{paymentKey, orderId}`), 신규 응답코드(403 본인 아님 / 404 주문 없음 / 503 주문정보 확보불가), 409 의미 변화(진행·완료 상태 존재 시만, FAILED 후 재시도는 성공 경로). |

### 14.2 `.claude/rules/` (규칙 문서)

| 문서 | 갱신 내용 |
|---|---|
| `architecture.md` | 패키지 구조 트리에 신규 하위 패키지 반영 — `infrastructure/messaging/consumer`(Kafka 컨슈머), `infrastructure/external/grpc`(gRPC 클라이언트 어댑터). `application/gateway/external`에 `OrderGateway` 추가. 데이터 흐름 예시에 confirm의 스냅샷 확보 경로 보강 검토. |
| `api-error-handling.md` | 신규 에러코드(503 `ORDER_INFO_UNAVAILABLE`, 404 `ORDER_NOT_FOUND`)와 403 재사용 원칙이 기존 규칙과 일관한지 확인. 규칙 자체 변경은 최소(코드/철학 불변) — 필요 시 예시만 보강. |

### 14.3 `../docs/architecture/` (모노레포 — payment 반영 목적)

| 문서 | 갱신 내용 |
|---|---|
| `event-flow.md` | 발행/소비 매트릭스에 payment의 `order-events` 소비, `payment.failed` 발행 반영. ORDER_CREATED→snapshot→confirm 시나리오 추가. **order-service 측 변경(발행/소비)은 후속이므로 "예정" 표기 또는 payment 관점만 기술.** |
| `overview.md` | payment가 gRPC 클라이언트로 order(9083)를 호출하는 흐름, 신규 컨슈머 존재를 반영(해당 문서가 서비스별 포트/흐름을 다룰 경우). |

> 문서 갱신은 커밋 규칙(`docs:` 타입)을 따르며, 커밋·PR은 사용자 요청 시에만 수행한다.
