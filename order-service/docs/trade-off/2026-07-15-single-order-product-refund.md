# 주문 상품 단건 환불 계약 유지 결정

- 상태: Accepted
- 결정일: 2026-07-15
- 대상: Order Service 부분 환불
- 관련 API: `POST /api/v2/orders/{orderId}/refund`

## 1. 배경

초기 설계에서는 한 번의 환불 요청에 여러 `orderProductId`와 상품별 환불 금액을 담아 payment-service에 전달하려고 했다. 선택한 여러 상품을 하나의 비즈니스 요청으로 처리하면 전체 성공 또는 전체 실패를 명확하게 보장할 수 있기 때문이다.

구현 전 Frontend 동작을 다시 확인한 결과, 현재 UI는 주문 상품별 단건 환불만 지원한다. 한 번의 HTTP 요청에서 하나의 주문 상품만 전달하며, 현재 일정에서는 Frontend 수정이 어렵다.

```http
POST /api/v2/orders/{orderId}/refund
```

```json
{
  "paymentId": "UUID",
  "orderProductId": "UUID"
}
```

payment-service 역시 다음 단건 계약을 이미 구현하고 있다.

- `ORDER_REFUND_REQUESTED`: `orderProductId`, `refundAmount` 한 건
- `PAYMENT_REFUNDED`: `orderProductId`, `amount` 한 건
- `PAYMENT_REFUND_FAILED`: `orderProductId`, `refundAmount` 한 건
- `GetRefund(paymentId, orderProductId)` gRPC

Order Service만 수정할 수 있는 제약에서 다건 계약을 유지하면 Frontend와 payment-service 모두 추가 변경이 필요해 현재 기능을 실제로 연결할 수 없다.

## 2. 결정

현재 릴리스에서는 주문 상품 단건 환불 계약을 유지한다.

- HTTP body는 `paymentId`, `orderProductId`만 받는다.
- 하나의 요청은 하나의 주문 상품과 하나의 PG 환불을 나타낸다.
- Order Service는 payment-service의 현재 단건 Kafka 이벤트와 gRPC를 그대로 사용한다.
- 주문에 여러 환불 가능 상품이 있으면 앞선 요청이 완료된 뒤 상품별로 순차 요청한다.
- 같은 주문에는 동시에 하나의 환불 요청만 허용한다.
- 완료 후 `PARTIAL_REFUNDED` 주문의 남은 `PAID` 상품은 추가 환불할 수 있다.
- Frontend와 payment-service 변경은 요구하지 않는다.

기존에 확정한 테이블 이름 `order_refund`, `order_refund_product`는 유지한다. 다만 단건 계약을 DB에도 명확히 표현하기 위해 요청과 상세를 1:1로 제한한다.

- `order_refund_product.order_refund_id` unique
- `order_refund_product.order_product_id` unique
- 한 요청의 `total_refund_amount`는 상세 한 건의 `refund_amount`와 동일

## 3. 검토한 대안

### 3.1 다건 계약으로 전체 계층 변경

HTTP에서 `orderProductIds`를 받고 payment-service에는 상품·금액 목록을 한 이벤트로 전달한다.

장점:

- 사용자가 선택한 여러 상품을 하나의 비즈니스 요청으로 표현할 수 있다.
- PG가 지원하면 여러 상품의 전체 성공 또는 전체 실패를 한 번에 보장할 수 있다.
- 여러 상품 환불의 진행 상태를 하나의 요청 ID로 추적하기 쉽다.

단점:

- 현재 Frontend 요청 형식과 호환되지 않는다.
- payment-service Kafka payload, 멱등성 키, PG 호출과 gRPC 조회 계약을 모두 변경해야 한다.
- 서비스 배포 순서와 호환성 전환이 필요하다.
- Order Service만 수정할 수 있다는 현재 범위를 벗어난다.

현재는 채택하지 않는다.

### 3.2 단건과 다건 API를 동시에 지원

기존 단건 body와 신규 목록 body를 모두 수용해 내부에서 공통 모델로 변환한다.

장점:

- 기존 Frontend를 유지하면서 향후 다건 UI를 먼저 준비할 수 있다.
- 점진적으로 계약을 전환할 수 있다.

단점:

- payment-service가 단건만 처리하므로 Order Service가 다건 요청을 여러 단건 이벤트로 분해해야 한다.
- 일부 상품만 성공하는 상태와 보상 정책이 다시 생겨 전체 성공·전체 실패 요구를 만족하지 못한다.
- API validation, 저장 모델, 멱등성과 테스트가 현재 필요 이상으로 복잡해진다.
- 사용되지 않는 다건 경로를 선제 구현하게 된다.

현재는 채택하지 않는다.

### 3.3 현재 단건 계약을 전체 계층에서 유지

Frontend, Order Service, payment-service를 주문 상품 한 건 단위로 맞춘다.

장점:

- 현재 Frontend를 수정하지 않고 연결할 수 있다.
- payment-service의 구현된 Kafka·gRPC 계약을 그대로 사용한다.
- 서비스 간 계약 변경과 배포 순서 위험이 없다.
- 이번 Order Service 구현 범위가 작고 테스트 경계가 명확하다.
- 한 요청에는 한 상품만 있으므로 요청 내부의 부분 성공 문제가 없다.

단점:

- 여러 상품 환불에는 사용자 요청과 PG 처리가 여러 번 필요하다.
- 앞선 요청이 진행 중이면 같은 주문의 다음 상품 환불을 기다려야 한다.
- 여러 상품을 하나의 원자적 환불 요청으로 묶을 수 없다.
- 상품 수만큼 환불 이력, Kafka 이벤트, PG 호출이 늘어난다.

현재 조건에서 이 대안을 채택한다.

## 4. 결과와 영향

### 4.1 긍정적 영향

- Frontend의 실제 요청과 API 문서가 일치한다.
- payment-service 개발자에게 신규 기능 요청이 필요 없다.
- 이미 구현된 `GetRefund(paymentId, orderProductId)`를 정합성 조회에 재사용한다.
- 이벤트 결과는 `paymentId + orderProductId`로 찾을 수 있다.
- 환불 대상 상품만 `REFUND_REQUESTED`로 바꿔 같은 주문의 다른 `PAID` 상품 이용을 유지할 수 있다.

### 4.2 감수하는 제약

- A, C, E 상품을 환불하려면 A 완료 후 C, C 완료 후 E 순으로 요청해야 한다.
- 각 단건 요청은 독립적인 환불이므로 여러 요청 전체를 한 번에 롤백하지 않는다.
- payment-service의 현재 실패 이벤트를 최종 실패로 취급한다.
- 실패한 상품과 주문은 정책상 `REFUND_REQUESTED`를 유지하고, 같은 주문의 후속 환불도 차단된다.
- 현재 payment-service에 PG 재처리 Worker가 없으므로 실패 후 운영 복구 절차가 필요할 수 있다.

## 5. 향후 다건 환불 전환 조건

다음 요구가 실제로 생기면 별도 설계로 다건 환불을 재검토한다.

- Frontend에서 여러 주문 상품 선택을 지원한다.
- 선택 상품 전체 성공 또는 전체 실패를 하나의 PG 요청으로 보장해야 한다.
- payment-service가 요청 목록, 비즈니스 멱등성 ID와 요청 전체 상태 조회를 제공한다.
- 여러 서비스의 계약 변경과 배포 순서를 조율할 수 있다.

전환 시 최소 변경 대상:

- HTTP body의 `orderProductIds` 또는 상품·금액 목록
- `ORDER_REFUND_REQUESTED`, 성공·실패 이벤트 payload
- 요청 전체를 식별하는 멱등성·상관관계 ID
- payment-service gRPC 요청 전체 상태 조회
- `order_refund_product.order_refund_id` unique 제약 해제
- 다건 전체 성공·실패 및 보상 정책
- Frontend 다중 선택 UX와 진행 상태 표현

단건 계약은 제거하지 말고 버전이 다른 API 또는 명시적인 호환 기간을 두고 전환한다.

## 6. 관련 문서와 이슈

- 설계: `docs/superpowers/specs/2026-07-15-order-partial-refund-design.md`
- 구현 계획: `docs/superpowers/plans/2026-07-15-order-partial-refund.md`
- Payment 연동: `docs/integration/payment-service-single-refund-contract.md`
- Epic: #296
- 구현 이슈: #346, #347, #348, #349, #350, #351
