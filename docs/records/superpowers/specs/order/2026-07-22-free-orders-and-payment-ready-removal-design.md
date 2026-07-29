# 0원 주문 지원과 Payment Ready API 제거 설계

## 배경과 목표

요구사항 원문인 `Backend - 0원 상품 주문 생성 및 즉시 구매 완료 처리 지원.md`를 기준으로 다음 두 변경을 하나의 주문 흐름으로 통합한다.

1. 기존 `POST /api/v2/orders`가 0원 상품을 허용하고, 주문 총액이 0원이면 결제 없이 즉시 구매 완료한다.
2. 사용하지 않는 `POST /api/v2/orders/{orderId}/payment-ready` 계약과 전용 내부 코드를 제거한다.

현재 도메인의 실제 상태명은 주문 `CREATED/COMPLETED`, 주문상품 `PENDING/PAID`다. 이 설계는 요구사항 문서의 상태 의미를 해당 enum 이름에 맞춰 구현한다.

## 확정 정책

- 상품 금액은 0 이상이어야 한다. 0원은 허용하고 음수는 `V001 INVALID_INPUT_VALUE`로 거부한다.
- 주문상품 합계가 정확히 0원이면 주문 생성 트랜잭션 안에서 주문을 `COMPLETED`, 모든 주문상품을 `PAID`로 전환하고 완료 시각을 기록한다.
- 무료·유료 상품이 섞여 총액이 양수면 주문 전체를 기존과 같이 `CREATED/PENDING`으로 유지하고 결제 승인 후 함께 완료한다.
- 이미 접근 가능한 무료 상품이 요청에 하나라도 포함되면 새 주문을 만들지 않고 `O018 ORDER_PRODUCT_ALREADY_OWNED`, HTTP 409를 반환한다.
- 무료 주문도 기존 `ORDER_PAID` Outbox를 발행해 Product Service의 판매 횟수 증가를 유지한다.
- 무료 주문상품은 환불 불가다. 금액 0의 환불 요청 이벤트를 Payment Service로 발행하지 않는다.
- 무료 주문에는 Toss 승인, 결제 승인 이벤트, Redis 결제 만료 예약을 요구하지 않는다.
- `payment-ready` API는 복구하거나 대체하지 않고 완전히 제거한다.

## 접근 방법 비교

### 1. 주문 생성 트랜잭션에서 즉시 완료 — 채택

`OrderCreator`가 총액을 계산한 뒤 무료 주문을 도메인 메서드로 완료하고, 주문·장바구니·`ORDER_PAID` Outbox를 한 트랜잭션에 저장한다. 기존 주문 생성 API와 응답을 유지하며 결제 시스템에 가짜 데이터를 만들지 않는다.

### 2. 가상 PAYMENT_APPROVED 이벤트 생성

기존 `PaymentApprovedProcessor`를 재사용할 수 있지만 실제로 존재하지 않는 paymentId, eventId, 승인 시각과 멱등 이력을 만들어야 한다. 무료 구매를 Payment Service 계약에 결합하므로 사용하지 않는다.

### 3. 무료 주문 전용 API 또는 주문 분리

무료 전용 흐름은 명시적이지만 프런트가 기존 주문 API 하나만 호출한다는 요구와 맞지 않는다. 혼합 주문을 둘로 분리하면 응답 계약과 원자성이 달라지므로 사용하지 않는다.

## 컴포넌트 설계

### 금액 검증

`OrderAmountCalculator.sum`의 개별 금액 검증을 `itemAmount <= 0`에서 `itemAmount < 0`으로 바꾼다.

- `null` 또는 빈 목록 거부 유지
- `null` item 거부 유지
- `Math.addExact`를 이용한 정수 overflow 거부 유지
- 합계 0 허용
- 개별 음수와 음수 합계 가능성 차단

`ProductOrderSnapshot.amount`와 `OrderItem.amount`는 primitive `int`이므로 금액 자체의 null 상태는 현재 계약에서 표현되지 않는다. snapshot 객체와 필수 ID의 null 검증은 기존대로 유지하고, gRPC `int32` 기본값 0은 정상적인 무료 금액으로 처리한다.

### 무료 주문 도메인 전이

`Order`에 의도가 드러나는 무료 주문 메서드를 둔다.

- `isFree()`는 `totalOrderAmount == 0`을 반환한다.
- `completeFreeOrder()`는 총액이 0이 아니면 `INVALID_ORDER_STATUS_TRANSITION`을 던지고, 0원이면 기존 `markCompleted()`를 사용한다.
- 기존 `markCompleted()`가 주문 상태를 `COMPLETED`, 주문상품 상태를 `PAID`로 바꾸고 `completedAt`을 기록하는 책임은 유지한다.

유료 주문과 결제 승인 처리에서는 `completeFreeOrder()`를 호출하지 않는다.

### 주문 생성 트랜잭션

`OrderCreator.create`의 트랜잭션 순서는 다음과 같다.

1. 상품 목록과 개별 금액을 검증하며 합계를 계산한다.
2. 요청에 0원 상품이 있을 때 구매자의 접근 가능한 상품 ID를 조회한다.
3. 접근 가능한 무료 상품이 하나라도 겹치면 `O018`로 중단한다.
4. 주문과 주문상품을 생성한다.
5. 합계가 0원이면 `completeFreeOrder()`를 호출한다.
6. 주문과 주문상품을 저장한다.
7. 선택한 상품을 장바구니에서 제거한다.
8. 무료 주문이면 `ORDER_PAID` Outbox를 저장하고, 유료 주문이면 `OrderCreatedEvent`를 발행한다.
9. 기존 `CreateOrderResult`를 반환한다.

주문 저장, 장바구니 변경, Outbox 저장 중 하나라도 실패하면 트랜잭션 전체가 rollback된다. `OrderCreatedEvent`는 유료 주문에만 발행되므로 `OrderExpirationRegistrar`가 무료 주문을 Redis에 등록하지 않는다.

중복 무료 구매 검사는 기존 `findAccessiblePaidProductIdsByBuyerId` 조회 결과와 요청의 0원 상품 ID를 비교한다. 이 정책은 일반적인 순차 재요청을 409로 거부한다. 현재 스키마에는 구매자·상품 단위 무료 entitlement unique 제약이나 idempotency key가 없으므로 서로 다른 인스턴스의 완전 동시 요청까지 단일 주문으로 강제하는 것은 이번 범위에 포함하지 않는다.

### ORDER_PAID Outbox 재사용

무료 주문과 결제 승인 주문이 동일한 이벤트 조립 코드를 사용하도록 `OrderPaidOutboxAppender`를 `application/service/event`에 추가한다.

- 입력: 완료된 `Order`
- 처리: `OrderPaidPayload.from(order)` → `OrderEventMessageFactory.createOrderPaidMessage(...)` → `OutboxEventAppender.append(...)`
- 출력: 없음

`OrderCreator`는 무료 주문 완료 후 이 컴포넌트를 호출한다. `PaymentApprovedProcessor`도 기존 Factory와 Appender 직접 조립 대신 같은 컴포넌트를 호출한다. 이 변경은 payload, topic, key, `eventType=ORDER_PAID` 계약을 바꾸지 않는다.

### 후속 서비스 영향

- Product Service는 `ORDER_PAID.payload.products[].productId`만 사용해 판매 횟수를 증가시킨다. 0원 payload를 동일하게 처리할 수 있어 변경이 필요 없다.
- Payment Service는 `ORDER_PAID`를 무시하고 `ORDER_REFUND_REQUESTED`만 처리한다. 무료 주문에 결제 데이터가 생성되지 않는다.
- Settlement Service는 현재 `ORDER_PAID` Kafka consumer를 사용하지 않고 주문 데이터를 조회하므로 0원 정산 이벤트가 새로 유입되지 않는다.
- 판매자 통계와 구매 내역은 order-service의 `COMPLETED/PAID` 데이터에서 조회되며 금액 0으로 자연스럽게 반영된다.

### 구매 권한

무료 주문 생성 응답은 기존 `CreateOrderResponse`를 그대로 사용한다.

- `totalAmount = 0`
- `order.orderStatus = COMPLETED`
- `order.products[].orderProductStatus = PAID`

저장 직후 기존 조회 조건을 만족하므로 다음 기능이 별도 승인 없이 동작한다.

- `GET /api/v2/orders/users` 구매 상품 ID 목록
- `GET /api/v2/orders/product/{productId}/paid` 구매 여부
- 주문 상세와 구매 내역
- 구매 콘텐츠 조회
- 다운로드 확정 및 콘텐츠 접근 권한

응답 필드는 추가하거나 삭제하지 않는다. 완료 시각은 DB에 기록되며 기존 주문 조회 응답의 결제 완료 시각으로 노출된다.

### 무료 상품 환불 차단

`OrderProduct.isRefundable()`은 기존 상태·다운로드 조건에 `productAmount > 0`을 추가한다. 주문 목록 projection에도 상품 금액을 전달해 `OrderPolicyService.isRefundable(...)`가 같은 규칙을 적용하게 한다.

무료 주문상품을 환불 요청하면 기존 `O017 ORDER_REFUND_NOT_ALLOWED`를 반환하고, 주문 상태 변경이나 `ORDER_REFUND_REQUESTED` Outbox 저장은 발생하지 않는다. 혼합 주문에서도 0원 상품만 환불 불가이며 양수 상품은 기존 정책을 따른다.

## Payment Ready API 제거

다음 전용 요소를 제거한다.

- `OrderController.validatePaymentReady` HTTP 매핑과 Swagger 설명
- `OrderPaymentValidationRequest`
- `OrderPaymentValidationResponse`
- `OrderQueryUseCase.validatePaymentReady`
- `OrderQueryService.validatePaymentReady`
- 전용 테스트와 `OrderQueryService`의 불필요한 `OrderExpirationPolicy` 의존성
- 이 API에서만 사용하는 `ORDER_EXPIRED(O015)`

`ORDER_PAYMENT_AMOUNT_MISMATCH(O014)`는 `PaymentApprovedProcessor`의 실제 결제 승인 금액 검증에서 사용하므로 유지한다. 구 URL은 404를 반환하는 회귀 테스트로 비노출 상태를 고정한다.

## 오류 처리

- 음수 금액, 빈 상품 목록, 잘못된 snapshot: `V001`, HTTP 400
- 접근 가능한 무료 상품 중복 구매: `O018`, HTTP 409
- 무료 주문상품 환불 요청: 기존 `O017`, HTTP 409
- 주문·장바구니·Outbox 저장 실패: 예외 전파와 트랜잭션 rollback
- `ORDER_PAID` 발행 실패: 기존 Outbox Relay 재시도와 FAILED 상태 정책 유지

## 테스트 설계

### 단위 테스트

- `OrderAmountCalculator`: 0원 단건·다건 성공, 음수 실패, 빈 목록과 null item 실패, overflow 실패
- `Order`: 0원 주문 즉시 완료와 완료 시각·상품 PAID, 양수 주문의 무료 완료 거부
- `OrderProduct`와 `OrderPolicyService`: 0원 상품 환불 불가, 유료 상품 기존 규칙 유지
- `OrderCreator`: 무료 주문 완료·장바구니 제거·ORDER_PAID Outbox·만료 이벤트 미발행, 유료 주문 기존 CREATED·OrderCreatedEvent 유지, 혼합 주문 CREATED 유지
- `OrderCreator`: 접근 가능한 무료 상품 중복 시 O018과 저장·장바구니·Outbox 부작용 없음
- `PaymentApprovedProcessor`: 공용 `OrderPaidOutboxAppender` 호출과 기존 금액 검증 유지
- `OrderController`: 무료 주문 응답 계약과 제거된 payment-ready URL 404

### 영속성·통합 테스트

- 무료 단건·다건 주문의 `COMPLETED/PAID`, `completedAt`, total 0 저장
- 무료·유료 혼합 주문의 `CREATED/PENDING`, 양수 합계 저장
- 무료 주문과 장바구니 제거와 ORDER_PAID Outbox의 단일 트랜잭션 원자성
- Outbox 실패 시 주문·장바구니 rollback
- 무료 주문 생성 직후 구매 목록·구매 여부·콘텐츠 접근
- `ORDER_PAID` Relay payload가 Product Service의 기존 소비 계약과 호환
- 정산 pull 조회가 무료 구매를 `PAID`, 금액 0인 라인으로 반환
- 무료 상품 환불 요청이 O017이며 refund Outbox가 생기지 않음
- payment-ready 전용 심볼 제거와 구 URL 404
- 전체 `../gradlew :order-service:test`

## 문서 변경

- `docs/api-spec/order.md`에서 payment-ready 절을 제거한다.
- 주문 생성 API에 0원 즉시 완료, 혼합 주문, 중복 무료 구매 O018 응답을 명시한다.
- 주문 조회 문서의 상태 예시는 실제 enum인 `CREATED/COMPLETED`와 `PENDING/PAID`에 맞춘다.
- `order-service/AGENTS.md`의 책임에서 결제 준비 상태 검증을 제거하고 0원 주문 즉시 완료 책임을 반영한다.
- `order-service/report.md`의 payment-ready 현재 상태 설명을 제거한다. 기존의 무관한 충돌 표식은 수정하지 않는다.
- Kafka 문서에는 무료 주문도 기존 `ORDER_PAID` 계약을 사용하고 settlement용 별도 0원 이벤트는 만들지 않는다고 기록한다.

## 비변경 범위

- 무료 주문 전용 HTTP API를 추가하지 않는다.
- Toss 승인·취소 API를 order-service에서 호출하지 않는다.
- 유료 주문의 결제 승인·실패·보상 흐름을 변경하지 않는다.
- Kafka topic, key, `ORDER_PAID` payload 필드를 변경하지 않는다.
- Product Service, Payment Service, Settlement Service 코드를 수정하지 않는다.
- 무료 entitlement 전용 테이블, idempotency key, DB migration을 추가하지 않는다.
- Frontend 코드를 수정하지 않는다.

## 완료 조건

- 0원 금액은 주문 검증을 통과하고 음수는 거부된다.
- 무료 단건·다건 주문은 HTTP 200과 기존 응답 계약을 사용한다.
- 무료 주문은 즉시 `COMPLETED`, 상품은 `PAID`, 완료 시각은 non-null이다.
- 혼합·유료 주문은 총액이 양수이면 기존 `CREATED/PENDING` 결제 흐름을 유지한다.
- 무료 주문 직후 구매 내역·구매 여부·콘텐츠·다운로드 접근이 가능하다.
- 장바구니 제거와 ORDER_PAID Outbox가 주문 저장과 원자적으로 처리된다.
- 무료 주문은 Redis 만료 예약과 Payment Service 승인·환불 요청을 만들지 않는다.
- 중복 무료 구매는 O018, HTTP 409를 반환한다.
- 무료 주문상품은 환불 불가로 표시되고 O017로 거부된다.
- Product Service는 기존 ORDER_PAID로 판매 횟수를 반영한다.
- payment-ready API와 전용 코드는 제거되고 구 URL은 404다.
- `ORDER_PAYMENT_AMOUNT_MISMATCH` 결제 승인 검증은 유지된다.
- order-service 전체 테스트와 `git diff --check`가 통과한다.
