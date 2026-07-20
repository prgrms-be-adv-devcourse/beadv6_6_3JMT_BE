# 결제 실패 장바구니 보상 트랜잭션 설계

- 작성일: 2026-07-18
- 상태: 승인됨
- 대상: `order-service`

## 1. 배경

주문을 생성하면 주문 상품과 같은 상품을 구매자의 장바구니에서 제거한다. 이후 Payment Service가 결제 실패 이벤트를 발행하거나, 제한 시간 안에 어떤 결제 결과 이벤트도 도착하지 않으면 주문은 결제에 성공하지 못했지만 장바구니 상품은 이미 사라진 상태가 된다.

Order Service는 주문과 장바구니를 같은 데이터베이스에서 소유하므로, 실패 주문 상태 변경과 장바구니 복구를 하나의 로컬 보상 트랜잭션으로 처리한다.

이 설계는 다음 기존 v2 초안 가정을 대체한다.

- 한 결제에 여러 주문을 연결한다는 가정
- 주문 생성 시 장바구니 상품을 유지한다는 가정
- 결제 실패와 주문 만료 시 장바구니를 변경하지 않는다는 가정

확정된 정책은 판매자 수와 관계없이 결제 한 건당 주문 한 건을 생성하고, 주문 생성 시 대상 상품을 장바구니에서 제거하며, 결제 실패 또는 타임아웃 시 동일 상품을 복구하는 것이다.

## 2. 목표

- `PAYMENT_FAILED` 수신 즉시 실패 주문의 상품을 장바구니에 복구한다.
- 결제 결과 이벤트가 도착하지 않은 주문도 기존 만료 Worker를 통해 같은 보상 트랜잭션으로 복구한다.
- 주문·주문상품 상태, 장바구니, Kafka 처리 이력을 하나의 DB 트랜잭션으로 반영한다.
- 실패 이벤트, 타임아웃, 늦은 승인, 사용자 장바구니 수정이 경합해도 일관된 최종 상태를 보장한다.
- 중복 이벤트와 Worker 재실행에도 장바구니 상품이 중복 생성되지 않게 한다.
- 장바구니 구매와 바로 구매를 구분하지 않고 동일한 실패 보상 정책을 사용한다.

## 3. 비목표

- Payment Service의 결제 승인·실패 판정 또는 재시도 정책을 변경하지 않는다.
- 환불 성공·실패 및 부분 환불 처리 흐름을 변경하지 않는다.
- 새로운 Kafka DLT 재처리기나 Redis DLQ 관리 API를 추가하지 않는다.
- 보상 트랜잭션 안에서 Product Service를 호출해 판매 가능 여부를 다시 검증하지 않는다.
- Cart를 별도 서비스로 분리하거나 분산 Saga를 도입하지 않는다.

### 3.1 변경 범위 제한

구현과 문서 수정은 저장소의 다음 경로로 제한한다.

- `order-service/**`
- `order-service/docs/**` (`order-service` 디렉터리 기준 `docs/**`)

다음 경로는 수정하지 않는다.

- `payment-service/**`
- `common-module/**`
- 다른 서비스 모듈
- 저장소 루트의 공통 Gradle·Docker·CI 설정
- 저장소 루트의 공유 gRPC·Protobuf 계약

서비스 간 계약 불일치가 발견되면 이 작업에서 외부 모듈을 변경하지 않고, 구현 계획과 결과에 선행 조건 또는 잔여 위험으로 기록한다. Order Service 안에서 기존 계약과 호환되게 처리할 수 있는 범위만 구현한다.

## 4. 확정 비즈니스 정책

1. 판매자가 여러 명이어도 결제 한 건당 주문은 한 건이다.
2. 주문 생성 시 주문 상품과 같은 상품을 장바구니에서 제거한다.
	주문, 주문상품, 장바구니 제거는 하나의 DB 트랜잭션으로 처리한다. 외부 Kafka `ORDER_CREATED` Outbox는 생성하지 않고, Redis 만료 등록을 위한 내부 `OrderCreatedEvent`만 발행한다.
3. 바로 구매 상품이 기존 장바구니에 있으면 주문 생성·결제 성공 시 제거한다.
4. 결제 성공 처리에서도 같은 상품을 다시 제거해, 결제 대기 중 사용자가 재추가한 상품까지 정리한다.
5. `PAYMENT_FAILED`는 최종 실패 결과이며 즉시 보상을 시작한다.
6. 제한 시간 안에 결제 결과 이벤트가 없으면 기존 만료 Worker가 보상을 시작한다.
7. 장바구니 구매와 바로 구매를 구분하지 않고 실패 주문의 모든 상품을 복구 대상으로 사용한다.
8. 복구 대상 상품이 이미 장바구니에 있으면 기존 항목을 유지하고 중복 추가하지 않는다.
9. 구매자의 장바구니가 없으면 새 장바구니를 생성한다.
10. 보상 후 늦은 `PAYMENT_APPROVED`가 도착하면 실제 결제 승인을 우선한다.
11. 늦은 승인으로 주문이 완료되면 보상으로 추가했던 상품을 다시 장바구니에서 제거한다.
12. 보상 일부가 실패하면 전체 DB 트랜잭션을 롤백한다.

여기서 최종 실패는 사용자가 같은 결제 흐름을 의도적으로 재시도하지 않는다는 뜻이다. Payment Service가 실제 승인 사실을 늦게 전달하는 예외 상황에서는 금전 정합성을 위해 승인 결과를 반영한다.

## 5. 상태 모델

이 설계는 v2 주문 상태를 기준으로 한다.

### 5.1 주문 상태

```text
CREATED            -> COMPLETED | FAILED
FAILED             -> COMPLETED
COMPLETED          -> PARTIAL_REFUNDED | ALL_REFUNDED
PARTIAL_REFUNDED   -> PARTIAL_REFUNDED | ALL_REFUNDED
ALL_REFUNDED       -> 전이 없음
```

### 5.2 주문 상품 상태

```text
PENDING   -> PAID | FAILED
FAILED    -> PAID
PAID      -> REFUNDED
REFUNDED  -> 전이 없음
```

### 5.3 결제 결과별 처리

| 현재 주문 상태 | 실패 이벤트·타임아웃 | 결제 승인 |
|---|---|---|
| `CREATED` | `FAILED` 전환 및 장바구니 복구 | `COMPLETED` 전환 및 장바구니 제거 |
| `FAILED` | 멱등 no-op | `COMPLETED` 전환 및 장바구니 제거 |
| `COMPLETED` | 늦은 실패 no-op | 중복 승인 no-op |
| `PARTIAL_REFUNDED` | 늦은 실패 no-op | 늦은 승인 no-op |
| `ALL_REFUNDED` | 늦은 실패 no-op | 늦은 승인 no-op |

실패 처리가 먼저 커밋됐더라도 늦은 승인이 오면 `FAILED -> COMPLETED`, 주문 상품은 `FAILED -> PAID`로 전환한다. 실제 결제 금전 사실을 주문 실패 결과보다 우선한다.

## 6. 아키텍처

### 6.1 진입점

```text
PaymentFailedEventHandler
  -> PaymentFailedProcessor
  -> OrderFailureCompensationService.compensatePaymentFailure(...)

OrderExpirationWorker
  -> OrderFailureCompensationService.compensateTimeout(...)
```

결제 실패 이벤트와 만료 Worker는 진입 조건만 다르며 같은 보상 서비스를 사용한다. 별도의 미수신 이벤트나 추가 Scheduler를 만들지 않는다.

### 6.2 컴포넌트 책임

#### `PaymentFailedEventHandler`

- `EventMessage` payload를 `PaymentFailedPayload`로 변환한다.
- 필수 envelope와 payload 검증 실패를 예외로 전달한다.
- `PaymentFailedProcessor`로 위임한다.

#### `PaymentFailedProcessor`

- 결제 실패 이벤트 metadata와 주문 ID를 공통 보상 서비스에 전달한다.
- 장바구니 복구 규칙을 직접 구현하지 않는다.
- DB 트랜잭션 경계를 소유하지 않는다.

#### `OrderExpirationWorker`

- 기존 Redis 만료 ZSet에서 기한이 지난 주문을 조회한다.
- 별도 만료 취소 로직 대신 공통 보상 서비스를 호출한다.
- 성공 결과에 따라 만료·재시도 정보를 제거한다.
- 실패 시 기존 Redis 재시도와 DLQ 정책을 유지한다.

#### `OrderFailureCompensationService`

- 보상 처리의 유일한 DB 트랜잭션 경계를 소유한다.
- Kafka 이벤트 경로와 타임아웃 경로를 위한 명시적인 public 메서드를 제공한다.
- 두 public 메서드는 동일한 내부 상태 전이와 장바구니 복구 규칙을 사용한다.
- 주문, 주문상품, 장바구니, 처리 이력을 원자적으로 변경한다.

#### `OrderExpirationCleanupListener`

- 보상 트랜잭션 커밋 후 Redis 만료 대상과 재시도 횟수를 제거한다.
- Redis 정리 실패를 로그로 남기되 이미 커밋된 DB 보상을 롤백하지 않는다.

### 6.3 결제 실패 이벤트 계약

한 결제에 주문 한 건만 연결하므로 `PAYMENT_FAILED` payload는 단일 `orderId`를 사용한다. 기존 단건 계약 형태를 유지하고 v2 실패 metadata를 명시하되, 다건 `orderIds` 계약으로 확장하지 않는다.

```text
paymentId
orderId
buyerId
failureCode
failureReason
failedAt
```

복구 대상 구매자는 주문에 저장된 `buyerId`를 기준으로 한다. payload에 포함된 `buyerId`가 주문의 구매자와 다르면 잘못된 이벤트로 판단해 예외를 발생시키고 Kafka 재시도·DLT 흐름에 맡긴다.

## 7. 보상 트랜잭션

### 7.1 Kafka 실패 이벤트 경로

`compensatePaymentFailure`는 다음 순서로 실행한다.

1. envelope와 payload 필수값을 검증한다.
2. 같은 트랜잭션 안에서 `eventId + consumerGroup` 처리 여부를 확인한다.
3. 이미 처리한 event이면 Redis 정리 애플리케이션 이벤트를 발행하고 성공 반환한다.
4. 주문과 주문상품을 `PESSIMISTIC_WRITE`로 조회한다.
5. 잠금을 획득한 뒤 처리 여부를 다시 확인해 동시 중복 이벤트를 방어한다.
6. 주문이 `CREATED`면 장바구니 보상을 수행한다.
7. 주문이 `FAILED`거나 결제·환불 완료 상태면 상태와 장바구니를 변경하지 않는다.
8. 주문이 `CREATED`였으면 주문을 `FAILED`, 소속 `PENDING` 상품을 `FAILED`로 변경한다.
9. 처리 이력을 기록한다.
10. Redis 정리 애플리케이션 이벤트를 발행한다.
11. 트랜잭션을 커밋한다.

늦은 실패가 `COMPLETED`, `PARTIAL_REFUNDED`, `ALL_REFUNDED` 주문에 도착해도 처리 이력은 기록하고 ACK할 수 있도록 성공 no-op으로 간주한다.

### 7.2 타임아웃 경로

`compensateTimeout`은 다음 순서로 실행한다.

1. 주문과 주문상품을 `PESSIMISTIC_WRITE`로 조회한다.
2. 주문이 없으면 Redis에 남은 오래된 항목으로 보고 완료 처리한다.
3. 주문이 `CREATED`인지 확인한다.
4. `CREATED`가 아니면 상태와 장바구니를 변경하지 않고 완료 처리한다.
5. `CREATED`이면 DB의 `createdAt`과 현재 결제 제한 시간을 기준으로 실제 만료 여부를 다시 확인한다.
6. 아직 만료되지 않았으면 `false`를 반환하고 상태·장바구니·Redis 만료 정보를 유지한다.
7. 실제 만료됐으면 Kafka 경로와 동일한 장바구니 보상과 `FAILED` 전이를 수행한다.
8. Redis 정리 애플리케이션 이벤트를 발행한다.
9. 트랜잭션을 커밋한다.

타임아웃 경로에는 Kafka `eventId`가 없으므로 주문 상태와 장바구니 유일 제약을 멱등성 기준으로 사용한다. Redis ZSet 조회 결과만 신뢰하지 않고 Order 잠금 뒤 실제 만료 여부를 재검증해 조기 보상을 방지한다. 별도 보상 실행 테이블은 추가하지 않는다.

### 7.3 장바구니 복구

1. 주문에 저장된 `buyerId`를 복구 대상 구매자의 기준으로 사용한다.
2. 구매자의 장바구니를 `PESSIMISTIC_WRITE`로 조회한다.
3. 장바구니가 없으면 새 장바구니를 생성한다.
4. 주문 상품의 `productId`를 중복 제거한다.
5. 장바구니에 없는 상품만 추가한다.
6. 장바구니를 저장한다.

보상 중 Product Service를 호출하지 않는다. 주문에 저장된 상품 ID가 보상 기준이며, 상품 판매 가능 여부 표시는 이후 장바구니 조회 정책에 맡긴다.

## 8. 승인 처리와의 정합성

`PaymentApprovedProcessor`도 보상 서비스와 같은 잠금 순서를 사용한다.

1. 주문과 주문상품을 `PESSIMISTIC_WRITE`로 조회한다.
2. `CREATED` 또는 `FAILED` 주문만 `COMPLETED`로 전환한다.
3. 소속 `PENDING` 또는 `FAILED` 상품을 `PAID`로 전환한다.
4. 주문 상품의 product ID를 구매자 장바구니에서 제거한다.
5. Outbox와 처리 이력을 같은 트랜잭션에 저장한다.
6. Redis 정리 애플리케이션 이벤트를 발행하고 커밋 후 만료·재시도 정보를 제거한다.

이미 처리한 동일 승인 `eventId`가 재수신되면 Order·Cart·Outbox·처리 이력은 변경하지 않고 Redis 정리 이벤트만 다시 발행한다. 최초 승인 커밋 뒤 Redis 장애가 발생했을 때 중복 이벤트가 cleanup을 재시도할 수 있게 한다.

승인 처리와 보상 처리가 경합할 때 결과는 다음과 같다.

- 실패가 먼저 잠금을 얻으면 `FAILED + 복구`가 커밋되고, 승인이 기다린 뒤 `COMPLETED + 제거`를 커밋한다.
- 승인이 먼저 잠금을 얻으면 `COMPLETED + 제거`가 커밋되고, 실패 처리는 기다린 뒤 no-op한다.
- 두 실행 순서 모두 최종 결과는 `COMPLETED`이고 주문 상품은 장바구니에 남지 않는다.

## 9. 잠금과 데이터베이스 제약

모든 주문 상태 변경 경로는 잠금 순서를 다음과 같이 통일한다.

```text
Order -> Cart
```

Repository는 다음 잠금 조회를 제공한다.

- 주문 ID로 Order 루트 행을 잠그는 `PESSIMISTIC_WRITE` 메서드
- 구매자 ID로 Cart 루트 행을 잠그는 `PESSIMISTIC_WRITE` 메서드

Order와 Cart 루트 행을 먼저 잠근 뒤 같은 트랜잭션에서 각 자식 컬렉션을 조회한다. 모든 상태 변경 경로가 루트 잠금을 먼저 획득하므로 주문상품과 장바구니 상품의 변경도 해당 루트 잠금으로 직렬화한다. 이 방식은 PostgreSQL에서 nullable collection fetch join에 `FOR UPDATE`를 적용할 때 발생할 수 있는 제약을 피한다.

사용자 장바구니 상품 추가·삭제처럼 Cart를 변경하는 다른 애플리케이션 서비스도 같은 잠금 장바구니 조회를 사용한다. 그래야 사용자 요청과 보상 트랜잭션이 같은 Cart 행을 기준으로 직렬화된다.

다음 데이터베이스 제약을 추가한다.

```text
cart: UNIQUE (buyer_id)
cart_product: UNIQUE (cart_id, product_id)
```

첫 번째 제약은 동시 신규 장바구니 생성 시 구매자당 하나만 존재하게 한다. 두 번째 제약은 동시 보상 또는 사용자 추가가 경합해도 같은 상품이 중복 저장되지 않게 한다.

제약 추가 전 운영 데이터에서 중복 구매자 장바구니와 중복 장바구니 상품을 조회해야 한다. 중복 데이터가 있으면 마이그레이션 적용 전에 별도의 데이터 정리가 필요하다.

제약은 `ddl-auto`가 아니라 새 Flyway 마이그레이션으로 추가한다. 신규 장바구니 생성이 경합해 unique 제약 위반이 발생하면 해당 트랜잭션을 롤백하고 기존 Kafka 또는 만료 Worker 재시도 흐름으로 다시 실행한다.

## 10. 멱등성

### 10.1 Kafka 이벤트

- `eventId + consumerGroup` unique 제약을 유지한다.
- 처리 여부를 주문 잠금 전후로 확인한다.
- DB 커밋 후 ACK만 실패해 재수신돼도 주문·장바구니를 다시 변경하지 않는다.

### 10.2 타임아웃 Worker

- `CREATED` 주문에만 보상을 적용한다.
- Order 잠금 뒤 `createdAt + paymentTimeoutMinutes`를 다시 확인하고 아직 만료되지 않은 주문은 변경하지 않는다.
- `FAILED` 주문 재실행은 성공 no-op이다.
- 장바구니의 `cartId + productId` unique 제약으로 중복 insert를 방어한다.

### 10.3 장바구니 연산

- 복구는 없는 상품만 추가하는 add-if-absent 연산이다.
- 승인은 주문 상품 ID를 모두 제거하는 멱등 연산이다.
- 복구와 제거 모두 장바구니의 무관한 상품은 변경하지 않는다.

## 11. Redis 처리

기존 Key를 유지한다.

- 만료 ZSet: `order:expiration`
- 재시도 Hash: `order:expiration:retry`
- 실패 목록: `order:expiration:dlq`

주문 생성 트랜잭션 커밋 후 기존 `AFTER_COMMIT` 흐름으로 만료를 등록한다.

보상 트랜잭션이 커밋되면 내부 애플리케이션 이벤트를 통해 Redis 만료와 재시도 정보를 제거한다. Redis 제거 실패는 DB 트랜잭션을 실패시키지 않는다. 만료 정보가 남아 Worker가 다시 실행되면 DB 상태는 no-op이고, Worker가 Redis 정리를 다시 시도한다.

## 12. Kafka 처리

- `PAYMENT_FAILED`는 기존 `payment-events` topic과 manual ACK를 유지한다.
- 성공적으로 DB 트랜잭션이 커밋된 뒤에만 ACK한다.
- 비즈니스 no-op도 처리 이력 기록 후 정상 ACK한다.
- 처리 실패는 예외를 Consumer까지 전파한다.
- 기존 `DefaultErrorHandler`가 1초 간격으로 최대 3회 재시도한다.
- 한도 초과 시 원본 topic의 같은 partition에 해당하는 `.DLT`로 이동한다.

## 13. 오류 처리

### 13.1 DB 보상 실패

다음 중 하나라도 실패하면 전체 트랜잭션을 롤백한다.

- 주문·주문상품 상태 변경
- 장바구니 조회·생성·저장
- 장바구니 상품 복구
- Kafka 처리 이력 기록

롤백 후 주문은 `CREATED`, 장바구니는 보상 전 상태로 유지된다. 부분 보상은 허용하지 않는다.

### 13.2 Kafka 재시도 초과

- 실패 메시지를 `.DLT`로 이동한다.
- 주문은 `CREATED`, 장바구니는 복구 전 상태일 수 있다.
- `orderId`, `eventId`, `eventType`, 실패 원인, 재시도 횟수를 로그에 남긴다.
- 기존 DLT 재처리 운영 절차의 대상이 된다.

### 13.3 만료 Worker 재시도 초과

- 기존 Redis 재시도 횟수를 증가시킨다.
- 한도를 초과하면 `order:expiration:dlq`로 이동한다.
- 만료 ZSet과 재시도 Hash에서 제거한다.
- `orderId`, 실패 원인, 재시도 횟수를 로그에 남긴다.

### 13.4 Redis 정리 실패

- 경고 로그를 남긴다.
- DB 보상 결과를 되돌리지 않는다.
- 남아 있는 만료 항목의 후속 Worker 실행으로 다시 정리한다.

## 14. 관측성

결제 실패 이벤트 로그에는 다음을 포함한다.

- `eventId`
- `eventType`
- `paymentId`
- `orderId`
- `failureCode`
- `failureReason`
- `failedAt`
- 처리 전·후 주문 상태
- 복구 대상 수와 실제 추가 수
- `consumerGroup`

타임아웃 로그에는 다음을 포함한다.

- `orderId`
- `buyerId`
- 만료 시각
- 처리 전·후 주문 상태
- 복구 대상 수와 실제 추가 수
- 재시도 횟수

개인정보, 토큰, 결제 비밀값은 로그에 남기지 않는다.

## 15. 테스트 전략

### 15.1 도메인·서비스 단위 테스트

- `CREATED` 주문 실패 시 주문·주문상품이 `FAILED`가 된다.
- 장바구니에서 제거됐던 4개 상품을 모두 복구한다.
- 바로 구매 단건 실패 시 해당 상품을 장바구니에 추가한다.
- 같은 상품이 이미 있으면 중복 추가하지 않는다.
- 장바구니가 없으면 신규 생성한다.
- 중복 실패 이벤트는 no-op한다.
- `COMPLETED`, `PARTIAL_REFUNDED`, `ALL_REFUNDED`에 늦은 실패가 오면 no-op한다.
- 처리 이력은 최초 Kafka 이벤트에만 저장한다.
- Cart 또는 처리 이력 저장 실패를 호출자까지 전파한다.

### 15.2 트랜잭션 통합 테스트

- 주문·주문상품 상태, 장바구니, 처리 이력이 함께 커밋된다.
- 장바구니 복구 실패 시 모든 DB 변경이 롤백된다.
- 처리 이력 저장 실패 시 모든 DB 변경이 롤백된다.
- 구매자당 장바구니 unique 제약을 검증한다.
- 장바구니 상품 unique 제약을 검증한다.

### 15.3 PostgreSQL 동시성 테스트

H2와 PostgreSQL의 잠금 의미 차이를 고려해 PostgreSQL Testcontainers 동시성 테스트를 추가한다.

- 실패와 승인 동시 실행의 최종 상태는 `COMPLETED`다.
- 실패 이벤트와 만료 Worker 동시 실행에서도 한 번만 복구한다.
- 사용자 장바구니 추가와 보상 동시 실행에서도 상품이 중복되지 않는다.

### 15.4 Kafka·Redis 테스트

- `PAYMENT_FAILED`가 공통 보상 서비스로 전달된다.
- 성공한 처리만 ACK한다.
- 실패 시 재시도 후 `.DLT`로 이동한다.
- 만료 Worker가 공통 보상 서비스를 호출한다.
- 보상 실패 시 Redis 재시도 횟수가 증가한다.
- 한도 초과 시 `order:expiration:dlq`로 이동한다.
- 커밋 후 Redis 만료·재시도 정보를 제거한다.
- Redis 정리 실패 후 Worker 재실행으로 정리한다.

## 16. 인수 기준

### 16.1 장바구니 주문 실패

```text
장바구니 4개
-> 주문 생성 및 대상 상품 제거
-> PAYMENT_FAILED 또는 타임아웃
-> 주문 FAILED
-> 주문 상품 FAILED
-> 장바구니에 대상 상품 4개 복구
```

### 16.2 바로 구매 실패

```text
바로 구매 1개
-> 주문 생성
-> PAYMENT_FAILED 또는 타임아웃
-> 주문 FAILED
-> 해당 상품을 장바구니에 추가
```

### 16.3 늦은 승인

```text
실패 보상 완료
-> 늦은 PAYMENT_APPROVED
-> 주문 COMPLETED
-> 주문 상품 PAID
-> 복구 상품을 장바구니에서 제거
```

### 16.4 원자성

```text
장바구니 복구 또는 처리 이력 저장 실패
-> 주문·주문상품 상태 변경 롤백
-> 장바구니 변경 롤백
-> Kafka 또는 만료 Worker 재시도
```

## 17. 구현 영향 범위

모든 구현 변경은 `order-service/**`와 `order-service/docs/**` 안에서만 수행한다. 주요 변경 대상은 다음과 같다.

- `PaymentFailedProcessor`
- 신규 `OrderFailureCompensationService`
- 기존 `OrderExpirationService`의 독립 취소·복구 로직 제거
- `OrderExpirationWorker`에서 공통 보상 서비스로 직접 위임
- 신규 `OrderExpirationCleanupListener`
- `PaymentApprovedProcessor`
- `OrderRepository`와 JPA 잠금 조회
- `CartRepository`와 JPA 잠금 조회
- `Cart`의 중복 없는 복구 연산
- 장바구니 unique 제약 마이그레이션
- 관련 단위·JPA·Kafka·Redis·동시성 테스트

환불 요청·완료·실패 처리의 계약과 상태 계산은 변경하지 않는다.
