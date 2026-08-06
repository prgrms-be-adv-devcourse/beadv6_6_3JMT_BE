# 주문 생성 Kafka 이벤트 제거 설계

## 배경

PR #397의 주문 생성 흐름은 주문과 주문 상품을 저장한 뒤 두 종류의 이벤트를 발행한다.

- 외부 통합 이벤트: Outbox에 `ORDER_CREATED`를 저장하고 Kafka `order-events` 토픽으로 발행한다.
- 내부 애플리케이션 이벤트: `OrderCreatedEvent`를 발행하고 트랜잭션 커밋 후 Redis에 미결제 주문 만료 시각을 등록한다.

결제 생성의 진입점이 Kafka `ORDER_CREATED` 소비에서 프론트엔드의 결제 API 직접 호출로 변경되었다. 따라서 외부 이벤트를 유지하면 Kafka와 HTTP라는 두 경로가 같은 결제 생성을 시작할 수 있다.

## 목표

- 주문 생성 성공 시 외부 Kafka `ORDER_CREATED` 이벤트를 만들지 않는다.
- 프론트엔드는 주문 생성 응답의 `orderId`와 결제 정보를 사용해 결제 API를 호출한다.
- 주문 생성 트랜잭션이 커밋된 뒤 Redis 만료 예약은 기존대로 한 번 등록한다.
- 결제 완료와 환불 뒤 발행되는 `ORDER_PAID`, `ORDER_REFUND` 및 공용 Outbox 인프라는 유지한다.

## 비목표

- 프론트엔드 또는 Payment Service의 결제 API를 이 작업에서 구현하지 않는다.
- 주문 생성 API의 요청 또는 응답 계약을 변경하지 않는다.
- 결제 승인·실패·취소·환불 Kafka 소비 흐름을 변경하지 않는다.
- Outbox Relay나 Outbox 테이블을 제거하지 않는다.
- Redis 주문 만료 정책이나 제한 시간을 변경하지 않는다.

## 검토한 접근

### 1. 외부 `ORDER_CREATED`만 제거하고 내부 만료 이벤트 유지 — 채택

주문 생성에서 `OrderCreatedPayload`, `OrderEventMessageFactory.createOrderCreatedMessage`와 Outbox 저장을 제거한다. `OrderCreatedEvent`와 `OrderExpirationRegistrar`는 유지한다.

장점은 결제 생성 진입점이 프론트엔드 API 하나로 명확해지고, 주문 만료의 `AFTER_COMMIT` 보장이 유지된다는 점이다. 결제 완료와 환불 이벤트가 사용하는 Outbox 인프라에도 영향을 주지 않는다.

### 2. Kafka 이벤트와 프론트엔드 API를 모두 유지 — 기각

전환 기간의 호환성은 높지만 동일한 결제 생성을 두 경로가 시작할 수 있다. Payment Service에 추가 멱등성과 순서 제어가 필요하며 최종 계약도 불명확해진다.

### 3. 외부 및 내부 주문 생성 이벤트를 모두 제거 — 기각

내부 이벤트까지 제거하고 주문 생성 코드에서 Redis를 직접 호출할 수 있다. 그러나 DB 커밋 전 Redis 예약이 만들어질 위험이 있고, 주문 생성 트랜잭션이 롤백되어도 만료 대상이 남을 수 있다. 현재의 `AFTER_COMMIT` 경계를 훼손하므로 적용하지 않는다.

## 목표 데이터 흐름

```text
Frontend
  -> POST /api/v2/orders
  -> OrderCommandHandler
  -> OrderCreator @Transactional
       - Order 1건과 OrderProduct N건 저장
       - 내부 OrderCreatedEvent 발행
  -> DB commit
  -> OrderExpirationRegistrar AFTER_COMMIT
  -> Redis order:expiration 등록

Frontend
  <- orderId와 결제 금액을 포함한 주문 생성 응답
  -> Payment Service 결제 API 호출
```

주문 생성 트랜잭션은 `ORDER_CREATED` Outbox를 저장하지 않으며 Kafka 발행을 기다리지 않는다.

## 코드 변경 범위

### 주문 생성

`OrderCreator`에서 다음 의존성과 처리를 제거한다.

- `OrderEventMessageFactory`
- `OutboxEventAppender`
- `OrderCreatedPayload`
- `EventMessage<OrderCreatedPayload>` 생성
- 주문 생성용 Outbox 저장

다음 처리는 유지한다.

- 주문 및 주문 상품 저장
- `ApplicationEventPublisher.publishEvent(OrderCreatedEvent.from(savedOrder))`
- `CreateOrderResult` 반환

### 외부 이벤트 계약

전체 참조를 확인한 뒤 주문 생성에만 사용되는 요소를 제거한다.

- `OrderCreatedPayload`
- `OrderEventMessageFactory.createOrderCreatedMessage`
- `OrderEventType.ORDER_CREATED`
- `OutboxEvent.orderCreated` 팩토리 메서드

`OrderEventMessageFactory`, `OutboxEventAppender`, `OutboxRelay`, `OutboxEvent` 자체는 `ORDER_PAID`와 `ORDER_REFUND` 때문에 유지한다.

### 내부 만료 이벤트

다음 요소는 변경하지 않는다.

- `OrderCreatedEvent`
- `OrderExpirationRegistrar`
- `@TransactionalEventListener(phase = AFTER_COMMIT)`
- `OrderExpirationStore.registerExpiration`
- Redis Key와 주문 만료 설정

## 실패 처리와 일관성

- 주문 DB 트랜잭션이 롤백되면 내부 `OrderCreatedEvent`의 `AFTER_COMMIT` 리스너는 실행되지 않는다.
- 주문 생성 성공 후 프론트엔드의 결제 API 호출이 실패하거나 사용자가 이탈하면 주문은 미결제 상태로 남고 기존 Redis 만료 처리 대상이 된다.
- 결제 API 재시도의 멱등성은 Payment Service가 `orderId`를 기준으로 보장해야 하며 이번 Order Service 변경 범위에는 포함하지 않는다.
- 주문 생성은 더 이상 Outbox 직렬화 또는 저장 실패 때문에 롤백되지 않는다.

## 테스트 설계

### 단위 테스트

- `OrderCreatorTest`는 단일 주문과 주문 상품이 저장되는지 검증한다.
- 주문 생성 시 외부 이벤트 Factory와 Outbox 의존성이 존재하지 않음을 컴파일 단계에서 고정한다.
- 내부 `OrderCreatedEvent`가 생성된 주문 ID와 생성 시각으로 한 번 발행되는지 검증한다.
- 기존 금액 overflow와 0원 검증에서 주문 저장 및 내부 이벤트 부수효과가 없는지 유지한다.

### 통합 테스트

- 주문 생성 성공 후 Order 1건, OrderProduct N건, 주문 생성 Outbox 0건을 검증한다.
- 기존 `ORDER_CREATED` Outbox 저장 실패 롤백 테스트는 더 이상 성립하지 않으므로 제거한다.
- 주문 번호 충돌 시 주문·주문 상품이 롤백되고 Redis 만료 예약이 생기지 않는 검증은 유지한다.
- 주문 커밋 후 Redis 만료 예약이 한 번 등록되는 기존 통합 테스트를 유지한다.

### Outbox 및 Kafka 회귀 테스트

- `ORDER_CREATED` 전용 Factory와 payload 직렬화 테스트를 제거한다.
- Outbox Appender와 Relay의 일반 동작 검증은 `ORDER_PAID` 또는 `ORDER_REFUND` 이벤트로 전환한다.
- 결제 완료와 환불 처리에서 Outbox가 계속 저장·발행되는지 기존 테스트로 검증한다.

### 검증 명령

```bash
../gradlew :order-service:test
../gradlew :order-service:build
git diff --check
```

## 배포 및 호환성

- DB 스키마 변경은 없다.
- 새 주문은 `ORDER_CREATED` Outbox를 만들지 않는다.
- 이미 저장된 PENDING `ORDER_CREATED` Outbox 행은 이 코드 변경으로 삭제하거나 변경하지 않는다. 배포 대상 환경에 해당 행이 존재한다면 프론트엔드 결제 API 전환 전에 잔여 이벤트 처리 정책을 별도로 확인해야 한다.
- Payment Service와 프론트엔드의 직접 API 계약 및 배포 순서는 해당 서비스 변경에서 확인한다.

## 완료 기준

- 주문 생성 코드와 테스트에 외부 `ORDER_CREATED` payload·Factory·Outbox 저장 참조가 없다.
- 주문 생성 성공 시 주문 생성 Outbox가 0건이다.
- 주문 생성 트랜잭션 커밋 후 Redis 만료 예약은 한 번 등록된다.
- `ORDER_PAID`와 `ORDER_REFUND` Outbox 발행 흐름이 유지된다.
- Order Service 전체 테스트와 빌드가 통과한다.
