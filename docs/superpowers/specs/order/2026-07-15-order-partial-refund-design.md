# 주문 상품 단건 부분 환불 설계

## 1. 목적

`order-service`에 다음 API를 추가한다.

```http
POST /api/v2/orders/{orderId}/refund
```

Frontend는 주문 상품별 환불 버튼에서 한 번에 하나의 상품만 환불 요청한다. 현재 frontend 변경이 어렵고 payment-service도 `orderProductId` 단건 Kafka·gRPC 계약을 이미 제공하므로, Order Service 역시 환불 요청 한 건에 주문 상품 한 건만 처리한다.

이 문서는 Order Service 구현 범위와 기존 payment-service 계약을 사용하는 방법을 정의한다. 다른 서비스 코드는 수정하지 않는다.

## 2. 확정된 정책

### 2.1 HTTP API

- 요청 본문은 `paymentId`, `orderProductId`를 받는다.
- 환불 금액은 클라이언트에서 받지 않고 선택 상품의 금액 스냅샷으로 계산한다.
- 정상 접수 시 `202 Accepted`와 빈 ResponseBody를 반환한다.
- Gateway가 전달하는 `X-User-Id`와 구매자 역할 정책을 적용한다.

요청 예시:

```json
{
  "paymentId": "550e8400-e29b-41d4-a716-446655440000",
  "orderProductId": "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa"
}
```

### 2.2 환불 단위

- 하나의 API 요청은 하나의 내부 `refundRequestId`와 하나의 `orderProductId`를 갖는다.
- Payment Service에는 주문 상품 한 건의 금액으로 PG 환불을 한 번 요청한다.
- 한 요청 안의 상품별 부분 성공 개념은 없다.
- 주문에 여러 환불 가능 상품이 있으면 각 상품을 순차적으로 별도 요청한다.
- `PARTIAL_REFUNDED` 주문에서 남은 `PAID` 상품을 다시 선택해 추가 환불할 수 있다.
- 같은 주문에는 동시에 하나의 환불 요청만 진행할 수 있다.
- 진행 중 요청이 완료되기 전에 같은 주문의 다른 상품을 요청하면 `409 Conflict`로 거절한다.

### 2.3 환불 가능 조건

다음 조건을 모두 만족해야 한다.

- 주문이 존재하고 요청 구매자의 주문이다.
- 로컬 `order_payment`의 `paymentId`, `orderId`, `buyerId`가 일치한다.
- `order_payment.approvedAmount`와 주문 총액이 일치한다.
- 주문 상태가 `PAID` 또는 `PARTIAL_REFUNDED`다.
- `orderProductId`가 해당 주문에 속한다.
- 선택 상품 상태가 `PAID`다.
- 선택 상품이 다운로드되지 않았다.
- 같은 주문에 `REQUESTED` 상태 환불 요청이 없다.
- 같은 주문 상품의 기존 환불 요청이 없다.

검증 하나라도 실패하면 주문 상태, 상품 상태, 환불 요청, Outbox를 모두 변경하지 않는다. Frontend의 버튼 제한과 별개로 backend에서 전체 정책을 다시 검증한다.

### 2.4 여러 상품의 순차 환불

A, B, C, D, E를 구매한 뒤 A, C, E를 환불하려면 다음처럼 요청한다.

```text
A 요청 및 완료 -> 주문 PARTIAL_REFUNDED
C 요청 및 완료 -> 주문 PARTIAL_REFUNDED
E 요청 및 완료 -> B, D가 PAID이므로 주문 PARTIAL_REFUNDED
```

각 요청은 독립적인 `order_refund` 이력으로 저장한다. 모든 주문 상품이 `REFUNDED`가 되는 마지막 요청에서만 주문을 `REFUNDED`로 변경한다.

### 2.5 콘텐츠 접근

콘텐츠 접근과 다운로드 가능 여부는 주문 전체 상태가 아니라 대상 주문 상품 상태로 판단한다.

A 환불 요청 중:

- A: `REFUND_REQUESTED`이므로 열람·다운로드 불가
- B, C, D, E: `PAID`를 유지하므로 열람·다운로드 가능
- A 완료 후: A는 `REFUNDED`이므로 계속 접근 불가

주문이 `REFUND_REQUESTED` 또는 `PARTIAL_REFUNDED`여도 대상 상품이 `PAID`면 이용할 수 있다.

### 2.6 실패 정책

- Order Service는 최초 `ORDER_REFUND_REQUESTED` Outbox 이후 요청 이벤트를 재발행하지 않는다.
- Payment Service의 현재 `PAYMENT_REFUND_FAILED` 이벤트를 최종 실패 결과로 취급한다.
- 최종 실패 시 `order_refund.status=FAILED`로 변경한다.
- 최종 실패 시 주문과 선택 상품은 `REFUND_REQUESTED`를 유지한다.
- 실패한 요청 때문에 주문과 상품을 이전 상태로 복원하지 않는다.
- 실패 후 같은 상품을 새 `refundRequestId`로 다시 요청하지 않는다.
- 실패한 상품이 포함된 주문은 추가 환불 요청도 허용하지 않는다.

현재 payment-service에 별도 PG 재처리 Worker가 없다는 점은 운영상 알려진 제약이다. Payment Service 재처리 정책 추가는 이 설계 범위가 아니다.

## 3. 서비스 경계

### 3.1 Order Service 책임

- HTTP 요청 인증과 입력 검증
- 로컬 `order_payment` 기반 결제 정합성 검증
- 단건 환불 요청과 대상 상품의 영속화
- 주문·상품 상태 전이
- `ORDER_REFUND_REQUESTED` Outbox 저장·발행
- Payment Service 결과 이벤트 수신
- 결과 이벤트 멱등성과 저장 요청 일치 검증
- Kafka 결과 유실에 대비한 기존 `GetRefund` gRPC 조회
- 완료 후 Product Service용 `ORDER_REFUND` Outbox 발행
- 부분 환불 조회와 관리자 거래 통계 반영

### 3.2 Payment Service 책임

현재 구현을 변경하지 않고 그대로 사용한다.

- 단건 `ORDER_REFUND_REQUESTED` 소비
- `orderProductId` 기준 PG 부분 환불
- 결제 잔액과 중복 상품 환불 검증
- 단건 `PAYMENT_REFUNDED`, `PAYMENT_REFUND_FAILED` 발행
- `paymentId + orderProductId` 기준 `GetRefund` gRPC 제공

Order Service는 Toss Payments를 직접 호출하지 않는다.

## 4. 애플리케이션 구조

### 4.1 Presentation

`OrderRefundController`

- 기본 경로: `/api/v2/orders`
- 단건 요청을 `OrderRefundUseCase`에 위임한다.
- Repository, Kafka, Redis, gRPC를 직접 참조하지 않는다.

`RefundOrderRequest`

```java
public record RefundOrderRequest(
    @NotNull UUID paymentId,
    @NotNull UUID orderProductId
) {}
```

### 4.2 Application

`OrderRefundUseCase`

```java
void requestRefund(
    UUID buyerId,
    UUID orderId,
    UUID paymentId,
    UUID orderProductId
);
```

`OrderRefundService`

- 단건 환불 접수
- Kafka 성공·실패 결과 반영
- gRPC 조회 결과 반영
- 저장 요청과 외부 결과 일치 검증

기능을 여러 Command Handler로 선제 분리하지 않고 하나의 서비스에서 시작한다. 책임이 커질 때 요청·결과·정합성 서비스로 분리한다.

### 4.3 Kafka

`PaymentRefundedEventHandler`, `PaymentRefundedProcessor`

- 기존 단건 payment-service payload를 typed DTO로 변환한다.
- `paymentId + orderProductId`로 저장 요청을 찾아 성공을 반영한다.
- processed event 멱등성을 유지한다.

`PaymentRefundFailedEventHandler`, `PaymentRefundFailedProcessor`

- 기존 `PAYMENT_REFUND_FAILED` 단건 payload를 처리한다.
- 최종 실패와 processed event 기록을 같은 트랜잭션으로 처리한다.

### 4.4 Reconciliation

`OrderRefundReconciliationWorker`

- Kafka 결과가 제시간에 도착하지 않은 `REQUESTED` 환불을 찾는다.
- `paymentId + orderProductId`로 Payment Service의 기존 `GetRefund`를 호출한다.
- 원격 호출은 DB 트랜잭션 밖에서 수행한다.
- 결과는 `OrderRefundService`에 전달한다.

## 5. 데이터 모델

앞서 확정한 `order_refund`, `order_refund_product` 테이블 명칭을 유지한다. 단건 계약을 DB에서도 명확히 하기 위해 요청과 상세의 관계는 1:1로 제한한다.

### 5.1 `order_refund`

| 컬럼 | 타입 | 제약/설명 |
|---|---|---|
| `id` | UUID | PK, Order Service 내부 `refundRequestId` |
| `order_id` | UUID | NOT NULL |
| `payment_id` | UUID | NOT NULL |
| `buyer_id` | UUID | NOT NULL |
| `status` | VARCHAR(20) | NOT NULL |
| `total_refund_amount` | INTEGER | NOT NULL, 선택 상품 금액과 동일 |
| `check_count` | INTEGER | NOT NULL, 기본값 0 |
| `next_check_at` | TIMESTAMP | 다음 gRPC 조회 시각 |
| `requested_at` | TIMESTAMP | NOT NULL |
| `completed_at` | TIMESTAMP | 성공 완료 시각 |
| `failed_at` | TIMESTAMP | 최종 실패 시각 |
| `failure_code` | VARCHAR(100) | Order Service 표준화 코드 |
| `failure_reason` | TEXT | Payment 실패 사유 또는 gRPC 확인 사유 |
| `version` | BIGINT | NOT NULL, 낙관적 잠금 |
| `created_at` | TIMESTAMP | 생성 시각 |
| `updated_at` | TIMESTAMP | 수정 시각 |

상태:

- `REQUESTED`: 요청과 Outbox 저장 완료, 결과 대기
- `COMPLETED`: 선택 상품 환불 완료
- `FAILED`: `PAYMENT_REFUND_FAILED` 또는 gRPC `FAILED` 확인
- `DLQ`: 6회 정합성 조회 후에도 결과 미확정

허용 전이:

```text
REQUESTED -> COMPLETED | FAILED | DLQ
DLQ       -> COMPLETED | FAILED
```

`COMPLETED`, `FAILED`는 비즈니스 최종 상태다. `DLQ`는 자동 조회 중단 상태이므로 늦은 권위 있는 Kafka 결과를 반영할 수 있다.

### 5.2 `order_refund_product`

| 컬럼 | 타입 | 제약/설명 |
|---|---|---|
| `id` | UUID | PK |
| `order_refund_id` | UUID | NOT NULL, `order_refund.id` FK, UNIQUE |
| `order_product_id` | UUID | NOT NULL, `order_product.id` FK, UNIQUE |
| `refund_amount` | INTEGER | NOT NULL, 양수 |
| `created_at` | TIMESTAMP | 생성 시각 |

- 하나의 `order_refund`에는 정확히 하나의 `order_refund_product`만 존재한다.
- 하나의 `order_product`는 최초 요청 이후 다시 환불 요청할 수 없다.
- `order_refund.total_refund_amount`와 `order_refund_product.refund_amount`는 같아야 하며 Application 테스트로 검증한다.

### 5.3 버전 컬럼

다음 Entity에 `@Version`과 `version BIGINT NOT NULL DEFAULT 0`을 추가한다.

- `Order`
- `OrderProduct`
- `OrderRefund`

`Order` 버전은 같은 주문의 동시 환불을 충돌 처리한다. `OrderProduct` 버전은 동일 상품의 다운로드와 환불 요청 경쟁을 충돌 처리한다.

### 5.4 인덱스

- `order_refund(status, next_check_at)`: gRPC 정합성 조회 대상 탐색
- `order_refund(order_id, status)`: 진행 중 요청 확인
- `order_refund(payment_id)`: 결제 연관 조회
- `order_refund_product(order_product_id)`: 결과 이벤트·gRPC 상관관계 조회

## 6. 상태 전이

### 6.1 주문

```text
PAID             -> REFUND_REQUESTED -> PARTIAL_REFUNDED | REFUNDED
PARTIAL_REFUNDED -> REFUND_REQUESTED -> PARTIAL_REFUNDED | REFUNDED
```

최종 실패 시 `REFUND_REQUESTED`를 유지한다.

### 6.2 주문 상품

```text
PAID -> REFUND_REQUESTED -> REFUNDED
```

최종 실패 시 `REFUND_REQUESTED`를 유지한다. 선택하지 않은 상품은 `PAID`를 유지한다.

### 6.3 환불 완료 시각

- `order_refund.completed_at`: 단건 요청 완료 시각
- `order_product.refunded_at`: 선택 상품 환불 완료 시각
- `order.refunded_at`: 모든 주문 상품이 `REFUNDED`가 된 시점에만 기록
- `PARTIAL_REFUNDED` 주문의 `order.refunded_at`: `null`

## 7. Kafka 계약

Payment Service의 현재 단건 계약을 그대로 사용한다. 이벤트 이름과 payload를 변경하지 않는다.

### 7.1 Order Service → Payment Service

토픽: `order-events`

이벤트 타입: `ORDER_REFUND_REQUESTED`

Kafka key와 envelope aggregate ID는 `orderId`다.

```json
{
  "orderId": "UUID",
  "orderProductId": "UUID",
  "buyerId": "UUID",
  "refundAmount": 10000,
  "requestedAt": "2026-07-15T12:00:00"
}
```

`paymentId`와 내부 `refundRequestId`는 이 payload에 포함하지 않는다. Payment Service는 `orderId`로 결제를 찾고 `orderProductId`로 단건 환불을 식별한다.

### 7.2 Payment Service → Order Service 성공

토픽: `payment-events`

이벤트 타입: `PAYMENT_REFUNDED`

```json
{
  "paymentId": "UUID",
  "orderId": "UUID",
  "userId": "UUID",
  "orderProductId": "UUID",
  "amount": 10000,
  "paymentStatus": "PARTIAL_REFUNDED",
  "refundedAt": "2026-07-15T12:00:05+09:00"
}
```

### 7.3 Payment Service → Order Service 실패

이벤트 타입: `PAYMENT_REFUND_FAILED`

```json
{
  "paymentId": "UUID",
  "orderId": "UUID",
  "userId": "UUID",
  "orderProductId": "UUID",
  "refundAmount": 10000,
  "paymentStatus": "PAID",
  "failureReason": "PG 환불 실패",
  "failedAt": "2026-07-15T12:00:05+09:00"
}
```

### 7.4 결과 검증과 멱등성

Order Service는 `paymentId + orderProductId`로 `order_refund`와 상세를 조회하고 다음 값을 비교한다.

- `paymentId`
- `orderId`
- `userId == buyerId`
- `orderProductId`
- 성공 `amount` 또는 실패 `refundAmount`

불일치하면 상태를 변경하지 않고 `O020 REFUND_EVENT_MISMATCH`를 던진다. Consumer 재시도 후 기존 DLT 정책을 적용한다.

- 같은 `eventId + consumerGroup`은 한 번만 처리한다.
- 같은 `paymentId + orderProductId`가 이미 `COMPLETED`면 다른 eventId의 동일 성공 결과도 상태와 Outbox를 중복 반영하지 않는다.
- gRPC가 먼저 완료를 반영한 뒤 Kafka 성공이 늦게 도착해도 processed event만 기록한다.

### 7.5 Product Service용 후속 이벤트

성공 시 기존 `ORDER_REFUND` Outbox를 저장한다. 다른 서비스 수정 없이 기존 소비 계약을 사용하기 위해 `products` 배열 구조는 유지하되 원소는 정확히 하나만 담는다.

```json
{
  "orderId": "UUID",
  "paymentId": "UUID",
  "buyerId": "UUID",
  "totalRefundAmount": 10000,
  "totalProductCount": 1,
  "refundedAt": "2026-07-15T12:00:05",
  "products": [
    {
      "orderProductId": "UUID",
      "productId": "UUID",
      "sellerId": "UUID",
      "refundAmount": 10000
    }
  ]
}
```

## 8. gRPC 계약

Payment Service의 현재 `grpc/payment/payment_query.proto`를 그대로 사용한다.

```protobuf
rpc GetRefund(GetRefundRequest) returns (GetRefundResponse);

message GetRefundRequest {
  string payment_id = 1;
  string order_product_id = 2;
}

message GetRefundResponse {
  string payment_id = 1;
  string order_id = 2;
  string user_id = 3;
  string order_product_id = 4;
  int32 amount = 5;
  string payment_status = 6;
  string refund_status = 7;
  string refunded_at = 8;
}
```

`refund_status` 값은 `REQUESTED`, `COMPLETED`, `FAILED`다.

- `COMPLETED`: 성공 이벤트와 같은 로직으로 반영한다.
- `FAILED`: 실패 시각과 실패 사유가 응답에 없으므로 조회 시각을 `failedAt`으로 사용하고 `failureCode=PAYMENT_REFUND_FAILED_CONFIRMED_BY_GRPC`를 저장한다.
- `REQUESTED`: 다음 조회를 예약한다.
- `NOT_FOUND`, `UNAVAILABLE`, `DEADLINE_EXCEEDED`: 비즈니스 실패로 확정하지 않고 다음 조회를 예약한다.

Order Service의 dev/prod Adapter는 deadline을 적용하고 테스트에서는 in-process gRPC를 사용한다.

## 9. 상세 처리 흐름

### 9.1 환불 요청 트랜잭션

1. 주문과 주문 상품을 조회한다.
2. 주문 소유권을 확인한다.
3. `order_payment`를 `paymentId`로 조회해 주문·구매자·승인 금액을 확인한다.
4. `orderProductId`의 소속·상태·다운로드 여부를 확인한다.
5. 진행 중 주문 환불과 기존 상품 환불 이력을 확인한다.
6. 상품 스냅샷 금액으로 `refundAmount`를 계산한다.
7. `OrderRefund`와 단일 `OrderRefundProduct`를 생성한다.
8. 최초 `nextCheckAt`을 요청 시각으로부터 10분 뒤로 설정한다.
9. 주문과 선택 상품을 `REFUND_REQUESTED`로 변경한다.
10. 단건 `ORDER_REFUND_REQUESTED` EventMessage를 Outbox에 저장한다.
11. 트랜잭션을 커밋한다.
12. Controller가 `202 Accepted`와 빈 body를 반환한다.

Payment Service 원격 호출은 이 트랜잭션에 포함하지 않는다.

### 9.2 성공 이벤트 트랜잭션

1. `eventId + consumerGroup` 멱등성을 확인한다.
2. `paymentId + orderProductId`로 요청과 상세를 조회한다.
3. 결과 payload를 저장 요청과 비교한다.
4. 선택 상품을 `REFUNDED`로 변경한다.
5. 남은 `PAID` 상품이 있으면 주문을 `PARTIAL_REFUNDED`로 변경한다.
6. 모든 상품이 환불됐으면 주문을 `REFUNDED`로 변경한다.
7. `OrderRefund`를 `COMPLETED`로 변경한다.
8. 단일 상품을 포함한 `ORDER_REFUND` Outbox를 저장한다.
9. processed event를 저장한다.

### 9.3 실패 이벤트 트랜잭션

1. processed event 멱등성을 확인한다.
2. `paymentId + orderProductId`로 요청을 조회한다.
3. 결과 payload를 저장 요청과 비교한다.
4. `OrderRefund`를 `FAILED`로 변경한다.
5. `failureCode=PAYMENT_REFUND_FAILED`, 실패 사유·시각을 기록한다.
6. `nextCheckAt`을 제거한다.
7. 주문과 선택 상품은 `REFUND_REQUESTED`를 유지한다.
8. processed event를 저장한다.

### 9.4 정합성 조회

기본 설정:

```text
initialDelayMinutes=10
retryIntervalMinutes=10
maxChecks=6
fixedDelayMs=5000
batchSize=100
```

Worker 처리:

1. 짧은 트랜잭션에서 `REQUESTED`이고 `nextCheckAt <= now`인 요청을 선점한다.
2. `checkCount`를 증가시키고 `nextCheckAt`을 10분 뒤로 이동한다.
3. 선점 트랜잭션을 커밋한다.
4. DB 트랜잭션 밖에서 `GetRefund(paymentId, orderProductId)`를 호출한다.
5. `COMPLETED/FAILED`는 Kafka와 같은 내부 로직으로 반영한다.
6. `REQUESTED`, 통신 실패, `NOT_FOUND`는 다음 조회까지 유지한다.
7. 확인 한도 초과 시 `DLQ`로 변경하고 자동 조회를 중단한다.

다중 인스턴스에서는 `FOR UPDATE SKIP LOCKED` 또는 동등한 원자 쿼리를 사용한다. gRPC 호출 중 DB 커넥션과 행 잠금을 유지하지 않는다.

## 10. 동시성

### 10.1 같은 주문의 동시 환불

두 요청이 같은 `Order.version`을 읽고 변경하면 먼저 커밋한 요청만 성공한다. 나중 요청은 낙관적 잠금 충돌로 전체 롤백하고 `409 Conflict`를 반환한다.

이미 `REQUESTED` 요청이 보이는 일반 경로에서는 `ORDER_REFUND_IN_PROGRESS`로 즉시 거절한다.

### 10.2 다운로드와 환불 요청

- 다운로드가 먼저 커밋되면 환불 요청은 버전 충돌 또는 다운로드 검증 실패로 롤백한다.
- 환불 요청이 먼저 커밋되면 같은 상품의 다운로드는 상태 검증 또는 버전 충돌로 실패한다.
- 환불 대상이 아닌 `PAID` 상품 다운로드는 허용한다.

낙관적 잠금 예외는 `O019 ORDER_CONCURRENT_MODIFICATION`, HTTP `409`로 변환한다.

## 11. 조회와 통계

- 새 주문 상태 `REFUND_REQUESTED`, `PARTIAL_REFUNDED`를 API DTO, Swagger, 검색 조건에 반영한다.
- 콘텐츠·다운로드·리뷰 권한은 대상 `OrderProduct.PAID`를 기준으로 판단한다.
- 환불 가능 표시는 주문 `PAID/PARTIAL_REFUNDED`, 상품 `PAID`, 미다운로드, 진행 중 요청 없음 조건을 사용한다.
- 순거래액과 환불 통계는 `order_refund.status=COMPLETED`의 `total_refund_amount` 합계를 기준으로 계산한다.
- 같은 주문에서 순차 완료된 여러 단건 환불은 각각 한 번씩 차감한다.

## 12. 오류 처리

### 기존 코드 재사용

- `V001 INVALID_INPUT_VALUE`
- `A003 INVALID_AUTHENTICATION`
- `A004 FORBIDDEN`
- `O001 ORDER_NOT_FOUND`
- `O008 ORDER_ACCESS_DENIED`
- `O012 ORDER_PRODUCT_NOT_FOUND`
- `O014 ORDER_PAYMENT_AMOUNT_MISMATCH`

### 신규 오류

| 코드 | HTTP | 이름 | 사용 조건 |
|---|---:|---|---|
| `O016` | 404 | `ORDER_PAYMENT_NOT_FOUND` | 로컬 주문 결제 정보 없음 |
| `O017` | 409 | `ORDER_REFUND_NOT_ALLOWED` | 주문·상품 상태 또는 다운로드 정책 위반 |
| `O018` | 409 | `ORDER_REFUND_IN_PROGRESS` | 같은 주문의 진행 중 환불 존재 |
| `O019` | 409 | `ORDER_CONCURRENT_MODIFICATION` | 낙관적 잠금 충돌 |
| `O020` | 500 | `REFUND_EVENT_MISMATCH` | Payment 결과와 저장 요청 불일치 |

Kafka payload 불일치는 예외를 삼키지 않고 기존 재시도·DLT 정책을 적용한다.

## 13. Flyway 변경

후속 migration에서 다음을 수행한다.

- `order_refund` 생성
- `order_refund_product` 생성
- `order_refund_product.order_refund_id` unique로 요청당 상세 1건 보장
- `order_refund_product.order_product_id` unique로 상품 재요청 차단
- FK, 금액 check, 조회 index 추가
- `order`, `order_product`에 version 추가
- 상태 CHECK에 `REFUND_REQUESTED`, `PARTIAL_REFUNDED` 추가

기존 baseline 파일은 수정하지 않는다.

## 14. 테스트 계획

### Domain

- `PAID/PARTIAL_REFUNDED -> REFUND_REQUESTED`
- 단일 선택 상품만 `REFUND_REQUESTED`
- 부분 완료와 전체 완료 주문 상태 계산
- 최종 실패 시 주문·상품 상태 유지
- `OrderRefund` 상태 전이와 확인 횟수

### Application

- 정상 단건 환불 요청
- 부분 환불 완료 후 다른 상품 추가 요청
- 결제·주문·구매자 불일치
- 다른 주문 상품, 다운로드 상품, 이미 요청한 상품
- 같은 주문 진행 중 요청 거절
- 성공·실패 payload 일치와 불일치
- Kafka와 gRPC 결과 중복 반영 방지

### Controller

- `202 Accepted`와 빈 body
- `paymentId`, `orderProductId` validation
- 인증·역할 검증
- `400`, `401`, `403`, `404`, `409`

### Persistence

- `OrderRefund`와 단일 `OrderRefundProduct` 매핑
- 요청당 상세 1건 unique
- 상품당 요청 1건 unique
- 낙관적 잠금
- due request 선점
- Flyway migration

### Kafka

- 단건 `ORDER_REFUND_REQUESTED` Outbox payload
- 현재 `PAYMENT_REFUNDED`, `PAYMENT_REFUND_FAILED` payload 역직렬화
- processed event 멱등성
- payload 불일치 DLT
- 단일 상품 `ORDER_REFUND` 발행

### gRPC와 Worker

- 기존 `GetRefund` in-process 테스트
- `REQUESTED`, `COMPLETED`, `FAILED`, `NOT_FOUND` 매핑
- 10분 간격 최대 6회
- DLQ 이후 늦은 Kafka 결과
- gRPC 호출 중 DB 트랜잭션 비활성

### 회귀

```bash
../gradlew :order-service:test
../gradlew :order-service:build
git diff --check
```

## 15. 배포 조건

Payment Service의 다음 기존 계약이 배포돼 있어야 한다.

- 단건 `ORDER_REFUND_REQUESTED` 소비
- 단건 `PAYMENT_REFUNDED`, `PAYMENT_REFUND_FAILED` 발행
- `GetRefund(paymentId, orderProductId)` gRPC

계약 변경이나 Payment Service 선행 개발은 요구하지 않는다.

## 16. 범위 제외

- 한 요청에서 여러 `orderProductId` 환불
- Frontend 다중 선택 UI
- Payment Service 코드 또는 계약 변경
- 다른 서비스 DB·Redis 직접 접근
- Toss Payments 직접 호출
- 환불 요청 이벤트 재발행
- Redis 기반 환불 요청 저장
- 별도 관리자 재처리 API
- 과거와 다른 신규 Kafka payload 도입

다건 환불은 Frontend 변경 여건과 별도 요구가 생길 때 후속 설계한다.
