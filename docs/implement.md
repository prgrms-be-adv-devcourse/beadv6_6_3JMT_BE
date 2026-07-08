# 주문-결제 이벤트 흐름 구현 계획

현재 주문 생성 이후 결제 진행을 위해 payment-service가 주문 정보를 안정적으로 확인할 수 있는 이벤트/동기 조회 흐름이 필요하다. 본 문서는 요구사항에 맞춰 `order-service` 내부의 상태 전이 방어, Outbox 이벤트 저장/발행, gRPC 주문 조회 API 구현, 그리고 결제 이벤트 처리기 활성화를 위한 상세 계획을 기술한다.

## User Review Required

> [!IMPORTANT]
> **상태 전이 정책의 엄격성**
> - `FAILED -> PAID` 및 `CANCELED -> PAID`와 같이 결제가 한 번 실패/취소된 주문을 복구하는 전이는 엄격히 차단됩니다.
> - PG 결제 최종 결과를 확인한 뒤에만 payment-service가 단일 최종 이벤트를 발행하므로, `order-service`에서도 이러한 상태 전이 규칙을 도메인 및 애플리케이션 레벨에서 철저히 방어합니다.

> [!NOTE]
> **주문 취소 상태 전이 규칙 변경**
> - 기존 `Order.cancel()`은 `PAID -> CANCELED`만 허용했습니다. (사용자 직접 취소/환불 시나리오)
> - 신규 요구사항으로 결제 실패/취소 이벤트 수신 시 `PENDING -> CANCELED` 및 `PENDING -> FAILED` 상태 전이가 필요하므로, 도메인 엔티티에 결제 전 취소/실패 처리를 위한 비즈니스 메서드를 추가합니다.

---

## Proposed Changes

### 1. 주문 (Order) 도메인 영역

#### [MODIFY] [OrderStatus.java](file:///Users/chan/Desktop/gongbu/programmers/beadv6_6_3JMT_BE/order-service/src/main/java/com/prompthub/order/domain/enums/OrderStatus.java)
- 상태 전이 검증 비즈니스 로직 추가 (`canTransitionTo(OrderStatus target)`).
- 허용 상태 전이:
  - `PENDING -> PAID`
  - `PENDING -> FAILED`
  - `PENDING -> CANCELED`
  - `PAID -> REFUNDED`
- 그 외 모든 전이는 차단 (`FAILED -> PAID`, `CANCELED -> PAID` 등 금지).

#### [MODIFY] [Order.java](file:///Users/chan/Desktop/gongbu/programmers/beadv6_6_3JMT_BE/order-service/src/main/java/com/prompthub/order/domain/model/Order.java)
- `validateTransition(OrderStatus target)` 메서드를 구현하여 `OrderStatus.canTransitionTo`를 통한 상태 전이 검증을 수행하고, 위반 시 `INVALID_ORDER_STATUS_TRANSITION` 예외를 던집니다.
- 결제 실패 시 호출할 `markFailed()` 메서드의 상태 검증 로직을 `validateTransition(OrderStatus.FAILED)`로 교체합니다.
- 결제 취소 시 호출할 `markCanceled(LocalDateTime canceledAt)` 및 `markCanceled()` 메서드를 신규 정의합니다. (기존 사용자 취소용 `cancel()`과 별개로 `PENDING -> CANCELED` 전이 지원)

#### [MODIFY] [OutboxEvent.java](file:///Users/chan/Desktop/gongbu/programmers/beadv6_6_3JMT_BE/order-service/src/main/java/com/prompthub/order/domain/model/OutboxEvent.java)
- `ORDER_CREATED` 이벤트를 생성하기 위한 팩토리 메서드 `orderCreated(UUID orderId, String payload, LocalDateTime occurredAt)` 추가.

#### [MODIFY] [OrderEventType.java](file:///Users/chan/Desktop/gongbu/programmers/beadv6_6_3JMT_BE/order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderEventType.java)
- `ORDER_CREATED` 이벤트 타입 상수 추가.

---

### 2. 주문 생성 시 OutboxEvent 저장 및 발행

#### [NEW] [OrderCreatedPayload.java](file:///Users/chan/Desktop/gongbu/programmers/beadv6_6_3JMT_BE/order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderCreatedPayload.java)
- `ORDER_CREATED` 이벤트의 Payload 레코드 정의. 상품 목록은 포함하지 않음.
- 포함 필드: `orderId` (UUID), `buyerId` (UUID), `orderNumber` (String), `totalAmount` (int), `orderStatus` (String), `createdAt` (LocalDateTime).

#### [MODIFY] [OrderEventMessageFactory.java](file:///Users/chan/Desktop/gongbu/programmers/beadv6_6_3JMT_BE/order-service/src/main/java/com/prompthub/order/application/service/event/OrderEventMessageFactory.java)
- `EventMessage<OrderCreatedPayload>`를 생성하기 위한 팩토리 메서드 `createOrderCreatedMessage(UUID orderId, OrderCreatedPayload payload)` 추가.

#### [MODIFY] [OrderService.java](file:///Users/chan/Desktop/gongbu/programmers/beadv6_6_3JMT_BE/order-service/src/main/java/com/prompthub/order/application/service/order/OrderService.java)
- `createOrder` 비즈니스 메서드 내에 주문 생성(`orderRepository.save`) 완료 후, 같은 DB 트랜잭션 안에서 `ORDER_CREATED` OutboxEvent를 영속화하는 로직 추가 (`outboxEventAppender.append`).
- DB 저장 성공 시 즉시 응답을 리턴하며, 실제 Kafka 발행은 기존 `OutboxRelay`에 의해 비동기로 처리됨.

---

### 3. 결제 실패 및 취소 이벤트 처리 활성화 (Idempotency 보장)

#### [NEW] [PaymentFailedPayload.java](file:///Users/chan/Desktop/gongbu/programmers/beadv6_6_3JMT_BE/order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentFailedPayload.java)
- `PAYMENT_FAILED` 이벤트 수신용 페이로드 정의.

#### [NEW] [PaymentCanceledPayload.java](file:///Users/chan/Desktop/gongbu/programmers/beadv6_6_3JMT_BE/order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/event/PaymentCanceledPayload.java)
- `PAYMENT_CANCELED` 이벤트 수신용 페이로드 정의.

#### [NEW] [PaymentFailedEventHandler.java](file:///Users/chan/Desktop/gongbu/programmers/beadv6_6_3JMT_BE/order-service/src/main/java/com/prompthub/order/application/service/event/PaymentFailedEventHandler.java)
- `EventMessage<JsonNode>` 수신 시 `PaymentFailedPayload`로 변환하여 Processor에 위임.

#### [NEW] [PaymentCanceledEventHandler.java](file:///Users/chan/Desktop/gongbu/programmers/beadv6_6_3JMT_BE/order-service/src/main/java/com/prompthub/order/application/service/event/PaymentCanceledEventHandler.java)
- `EventMessage<JsonNode>` 수신 시 `PaymentCanceledPayload`로 변환하여 Processor에 위임.

#### [NEW] [PaymentFailedProcessor.java](file:///Users/chan/Desktop/gongbu/programmers/beadv6_6_3JMT_BE/order-service/src/main/java/com/prompthub/order/application/service/event/PaymentFailedProcessor.java)
- `@Transactional` 하에서 멱등성 검사(`ProcessedEventService.isProcessed`) 수행.
- 주문 조회 후 이미 `FAILED` 상태인 경우 멱등 처리(중복 이벤트 무시 및 `markProcessed` 기록).
- 주문이 `PENDING`인 경우 `order.markFailed()`를 호출하여 실패 상태로 전이.
- 최종적으로 이벤트를 처리 완료 상태로 기록.

#### [NEW] [PaymentCanceledProcessor.java](file:///Users/chan/Desktop/gongbu/programmers/beadv6_6_3JMT_BE/order-service/src/main/java/com/prompthub/order/application/service/event/PaymentCanceledProcessor.java)
- `@Transactional` 하에서 멱등성 검사 수행.
- 주문 조회 후 이미 `CANCELED` 상태인 경우 멱등 처리.
- 주문이 `PENDING`인 경우 `order.markCanceled(occurredAt)`를 호출하여 취소 상태로 전이.
- 최종적으로 이벤트를 처리 완료 상태로 기록.

#### [MODIFY] [PaymentEventRouter.java](file:///Users/chan/Desktop/gongbu/programmers/beadv6_6_3JMT_BE/order-service/src/main/java/com/prompthub/order/infra/messaging/kafka/router/PaymentEventRouter.java)
- 기존에 `PAYMENT_FAILED`와 `PAYMENT_CANCELED` 발생 시 단순히 로그만 남기던 `switch` 분기문을 신규 등록한 `failedHandler` 및 `canceledHandler` 호출로 대체하여 상태 변경 흐름을 활성화.

---

### 4. gRPC 주문 조회 API 제공 (payment-service 연동용)

#### [NEW] [order_payment.proto](file:///Users/chan/Desktop/gongbu/programmers/beadv6_6_3JMT_BE/order-service/src/main/proto/order_payment.proto)
- payment-service가 주문 상태 스냅샷 조회를 위해 동기적으로 호출할 gRPC 서비스 정의.
- RPC 메서드: `GetOrderForPayment(GetOrderForPaymentRequest) returns (GetOrderForPaymentResponse)`
- 응답 내 `status` 필드는 `FOUND` / `NOT_FOUND` 값으로 채워짐.

#### [NEW] [OrderPaymentGrpcService.java](file:///Users/chan/Desktop/gongbu/programmers/beadv6_6_3JMT_BE/order-service/src/main/java/com/prompthub/order/infra/grpc/server/OrderPaymentGrpcService.java)
- `OrderPaymentServiceGrpc.OrderPaymentServiceImplBase`를 구현.
- 주문 ID를 바탕으로 데이터베이스 조회 후, 존재하면 `FOUND` 상태와 함께 주문 정보(`orderId`, `buyerId`, `orderNumber`, `totalAmount`, `orderStatus`, `createdAt`)를 리턴.
- 존재하지 않으면 `NOT_FOUND` 상태를 리턴 (상태 코드 에러를 발생시키지 않고 응답 구조체 내부 필드로 표현).

#### [NEW] [GrpcServerConfig.java](file:///Users/chan/Desktop/gongbu/programmers/beadv6_6_3JMT_BE/order-service/src/main/java/com/prompthub/order/infra/grpc/server/GrpcServerConfig.java)
- Spring ContextRefreshedEvent를 구독하여 지정된 gRPC 포트(기본값 `9083`)로 gRPC 서버를 시작하고 `OrderPaymentGrpcService`를 등록하는 Configuration 클래스 구현. (기존 product-service 내 GrpcServerConfig 구조를 준수함)

---

## Verification Plan

### Automated Tests

#### 1. Order 도메인 & 상태 전이 테스트
- **[NEW] OrderStatusTest.java**: 각 상태 쌍에 대한 `canTransitionTo` 로직 동작 검증.
- **[MODIFY] OrderTest.java**: `markCanceled` 동작 검증 및 금지된 전이 시도 시 예외 발생 검증.

#### 2. 주문 생성 및 OutboxEvent 검증
- **[MODIFY] OrderServiceTest.java**: 주문 저장 성공 시 `ORDER_CREATED` 이벤트 규격의 아웃박스 데이터가 올바르게 영속화되는지 검증 (상품 목록 배제 여부 확인 포함).

#### 3. 결제 이벤트 컨슈머 및 멱등성 검증
- **[NEW] PaymentFailedProcessorTest.java**: 결제 실패 이벤트 수신 시 `PENDING -> FAILED` 상태 전이 검증, 이미 `FAILED`인 경우 멱등 처리 동작 검증, 금지된 상태(`PAID`)에서 전이 시도 시 예외 검증.
- **[NEW] PaymentCanceledProcessorTest.java**: 결제 취소 이벤트 수신 시 `PENDING -> CANCELED` 상태 전이 검증 및 멱등 처리 동작 검증.
- **[MODIFY] PaymentApprovedProcessorTest.java**: `FAILED -> PAID` 및 `CANCELED -> PAID`와 같이 완료된 상태에서 복구가 불가능함을 테스트 코드로 강제 검증.

#### 4. gRPC 주문 조회 API 검증
- **[NEW] OrderPaymentGrpcServiceTest.java**: `grpc-inprocess` 채널을 이용하여 테스트 환경에서 gRPC 서버를 가상 기동하고, 존재하는 주문 조회 시 `FOUND` 데이터 응답 검증, 존재하지 않을 시 `NOT_FOUND` 검증, 상태가 `FAILED`, `CANCELED`, `PAID`여도 정상적으로 데이터를 조회할 수 있는지 검증.

---

### 실행 테스트 커맨드
```bash
./gradlew :order-service:test
```
