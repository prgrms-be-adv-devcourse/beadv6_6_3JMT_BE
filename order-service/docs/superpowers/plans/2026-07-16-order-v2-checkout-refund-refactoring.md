# Order Service v2 Checkout·다건 환불 리팩터링 계획

> 작성일: 2026-07-16
>
> 구현 범위: `order-service`
>
> 상태: 구현 전 확정 계획
>
> 대체 문서: `2026-07-15-order-partial-refund.md`, `2026-07-15-order-partial-refund-design.md`, `payment-service-single-refund-contract.md`

## 1. 목적

현재 Order Service는 하나의 주문에 여러 판매자의 상품을 담고, `order_payment`에 결제 정보를 복제하며, 주문과 주문 상품이 같은 `OrderStatus`를 사용한다.

v2에서는 다음 구조로 전환한다.

- 한 번의 Checkout 요청에서 상품을 판매자별로 묶어 여러 주문을 생성한다.
- 주문과 결제를 별도 Aggregate로 유지한다.
- Order Service는 주문 묶음을 `CHECKOUT_CREATED` 이벤트로 Payment Service에 전달한다.
- Payment Service의 결제 결과 이벤트로 여러 주문을 한 트랜잭션에서 완료 또는 실패 처리한다.
- 환불 요청은 Order Service가 접수하지만 결제·잔액·중복 검증과 PG 환불은 Payment Service가 소유한다.
- Order Service는 환불 성공 이벤트를 받은 뒤 주문 상품과 주문 상태만 반영한다.
- `order_payment`, `order_refund`, `order_refund_product`를 Order Service에서 사용하지 않는다.
- 모든 HTTP API를 `/api/v2`로 전환하고 v1 API를 제거한다.

## 2. 범위와 서비스 경계

### 2.1 이번 구현 범위

- Order Service DB 스키마와 JPA 모델
- 주문·주문 상품 상태 모델
- 판매자별 다중 주문 생성
- Checkout Outbox 이벤트
- 다건 결제 성공·실패 이벤트 처리
- 결제 단위 다건 환불 요청 API와 Outbox 이벤트
- 다건 환불 성공·실패 이벤트 처리
- 장바구니, 콘텐츠 접근, 주문 만료 정책 조정
- 구매자·관리자 조회와 거래 통계 재작성
- 기존 Product Service용 `ORDER_PAID`, `ORDER_REFUND` 이벤트 유지
- v1 Controller·DTO·Use Case 제거 및 v2 API 전환
- 테스트와 Order Service 문서 갱신

### 2.2 이번 구현에서 제외

- Payment Service의 `payment`, `payment_order`, `refund` 구현
- Payment Service의 PG 재시도 정책
- Gateway의 v2 라우팅 구현
- Seller Service의 판매자 페이지와 조회 API
- Product Service Consumer 수정
- 엄격한 결제 결과 상관관계·총액 검증 강화
- 기존 데이터 보존과 무중단 스키마 마이그레이션

제외 항목 중 Payment Service 계약과 Gateway 라우팅은 통합 배포의 선행 조건으로 관리한다.

## 3. 최종 데이터 모델

### 3.1 관계

```text
Seller 1 ── N Order 1 ── N OrderProduct

Payment 1 ── N Order
└─ 실제 관계는 Payment Service의 payment_order가 소유
```

- Order Service에는 Payment FK나 결제 복제 테이블을 두지 않는다.
- `seller_id`와 `payment_id`는 다른 서비스 DB를 참조하는 물리 FK로 연결하지 않는다.
- 하나의 `order`에는 정확히 한 판매자의 상품만 포함한다.

### 3.2 `order`

| 컬럼 | 제약 | 설명 |
|---|---|---|
| `id` | PK, UUID | 주문 ID |
| `buyer_id` | NOT NULL | 구매자 ID |
| `seller_id` | NOT NULL | 주문 판매자 ID |
| `order_number` | NOT NULL, UNIQUE | 판매자별 주문마다 독립 발급 |
| `total_order_amount` | NOT NULL | 구매 당시 주문 상품 금액 합계, 환불 후에도 변경하지 않음 |
| `order_status` | NOT NULL | `CREATED` 기본값 |
| `completed_at` | NULL 허용 | `PAYMENT_APPROVED` 반영 시각 |
| `refunded_at` | NULL 허용 | 모든 주문 상품이 환불된 시각 |
| `created_at` | NOT NULL | 생성 시각 |
| `updated_at` | NOT NULL | 수정 시각 |

제거 컬럼:

- `total_product_count`: `orderProducts.size()` 또는 조회 집계로 계산
- `paid_at`: `completed_at`으로 변경
- `canceled_at`: 새 상태 모델에서 사용하지 않음

권장 인덱스:

- `order(buyer_id, created_at desc)`
- `order(seller_id, created_at desc)`
- `order(order_status, created_at desc)`
- `order(completed_at)`
- `order(refunded_at)`

### 3.3 `order_product`

| 컬럼 | 제약 | 설명 |
|---|---|---|
| `id` | PK, UUID | 주문 상품 ID |
| `order_id` | NOT NULL, FK | 소속 주문 |
| `product_id` | NOT NULL | 상품 ID |
| `product_title_snapshot` | NOT NULL | 프론트엔드가 제공한 표시용 제목 snapshot |
| `product_amount_snapshot` | NOT NULL | Product Service가 제공한 구매 당시 금액 |
| `order_product_status` | NOT NULL | `PENDING` 기본값 |
| `downloaded` | NOT NULL | 콘텐츠 다운로드 여부 |
| `refunded_at` | NULL 허용 | 해당 상품 환불 완료 시각 |
| `created_at` | NOT NULL | 생성 시각 |
| `updated_at` | NOT NULL | 수정 시각 |

제거 컬럼:

- `seller_id`: `order.seller_id`로 단일화
- `product_type_snapshot`
- `product_model_snapshot`
- `canceled_at`

`product_title_snapshot`은 표시 전용으로만 사용한다. 판매자 분리, 주문 금액, 결제 금액, 환불 금액은 프론트엔드 값을 신뢰하지 않고 Product Service 응답을 기준으로 계산한다.

### 3.4 제거 테이블과 타입

- `order_payment` 테이블과 `OrderPayment` Entity·Repository·QueryDSL 구현 제거
- `PaymentStatus`와 결제 내역 전용 Projection·Response 제거
- `order_refund`, `order_refund_product`는 생성하지 않으며 관련 설계 문서를 폐기
- Payment Service의 `refund` 테이블은 이번 범위가 아님

### 3.5 Outbox 일반화

현재 `order_outbox_event.order_id`는 `EventMessage.aggregateId`를 저장하면서 이름만 주문에 종속돼 있다.

다음과 같이 일반화한다.

- DB 컬럼: `order_id` → `aggregate_id`
- Entity 필드: `orderId` → `aggregateId`
- 인덱스: `idx_order_outbox_event_aggregate_id`
- Kafka key: `aggregateId`
- `OutboxEventAppender`와 `OutboxRelay`는 Aggregate 종류에 무관하게 처리

## 4. 상태 모델

### 4.1 주문 상태

```java
public enum OrderStatus {
    CREATED,
    COMPLETED,
    FAILED,
    PARTIAL_REFUNDED,
    ALL_REFUNDED
}
```

허용 전이:

```text
CREATED            -> COMPLETED | FAILED
FAILED             -> COMPLETED
COMPLETED          -> PARTIAL_REFUNDED | ALL_REFUNDED
PARTIAL_REFUNDED   -> PARTIAL_REFUNDED | ALL_REFUNDED
ALL_REFUNDED       -> 전이 없음
```

- 결제 재시도 성공을 위해 `FAILED → COMPLETED`를 허용한다.
- 결제 성공 후 늦게 도착한 실패 이벤트는 상태를 되돌리지 않는다.
- `refunded_at`은 `ALL_REFUNDED`에 도달할 때만 설정한다.

### 4.2 주문 상품 상태

```java
public enum OrderProductStatus {
    PENDING,
    PAID,
    FAILED,
    REFUNDED
}
```

허용 전이:

```text
PENDING   -> PAID | FAILED
FAILED    -> PAID
PAID      -> REFUNDED
REFUNDED  -> 전이 없음
```

### 4.3 환불 후 주문 상태 계산

별도 상품 수·환불 금액 카운터를 저장하지 않는다.

```text
모든 주문 상품이 REFUNDED
-> ALL_REFUNDED

REFUNDED 상품과 PAID 상품이 함께 존재
-> PARTIAL_REFUNDED
```

- `total_order_amount`는 최초 구매 금액으로 유지한다.
- 환불 이벤트 적용 후 실제 자식 상태를 순회해 주문 상태를 다시 계산한다.
- 중복 성공 이벤트에서도 카운터 증가나 금액 차감이 발생하지 않는다.

## 5. v2 HTTP API

### 5.1 공통 전환

- `/api/v1/orders/**` 제거 → `/api/v2/orders/**`
- `/api/v1/cart/**` 제거 → `/api/v2/cart/**`
- `/api/v1/admin/orders/**` 제거 → `/api/v2/admin/orders/**`
- `WebConfig`와 구매자·관리자 Interceptor 경로를 v2로 변경
- Swagger의 상태값, 요청·응답, 상태 코드를 실제 동작과 함께 갱신
- Seller 전용 HTTP API와 SELLER Interceptor는 추가하지 않음

### 5.2 주문 생성

```http
POST /api/v2/orders
```

요청:

```json
{
  "products": [
    {
      "productId": "UUID",
      "productTitle": "상품 제목"
    }
  ]
}
```

검증:

- 상품 목록이 비어 있지 않음
- `productId`가 null이 아니며 중복되지 않음
- 제목이 공백이 아니고 DB 길이 제한 이내
- Product Service snapshot 수와 요청 ID 집합이 정확히 일치
- Product Service의 `sellerId`, 금액만 주문 계산에 사용

응답 방향:

```json
{
  "totalAmount": 45000,
  "orders": [
    {
      "orderId": "UUID",
      "orderNumber": "ORDER-NUMBER",
      "buyerId": "UUID",
      "sellerId": "UUID",
      "orderStatus": "CREATED",
      "orderAmount": 20000,
      "products": [
        {
          "orderProductId": "UUID",
          "productId": "UUID",
          "productTitle": "상품 제목",
          "productAmount": 10000,
          "orderProductStatus": "PENDING"
        }
      ],
      "createdAt": "2026-07-16T10:00:00"
    }
  ]
}
```

### 5.3 구매자 조회·콘텐츠·다운로드

- 목록·상세 DTO에서 상품 유형·모델과 결제 상태를 제거
- 주문 상품 상태에는 `OrderProductStatus`를 사용
- 콘텐츠 접근은 주문 전체 상태보다 개별 상품 상태를 우선한다.
  - `COMPLETED`/`PARTIAL_REFUNDED` 주문의 `PAID` 상품: 접근 가능
  - `REFUNDED`, `PENDING`, `FAILED` 상품: 접근 불가
- 다운로드 확정은 `PAID` 상품에만 허용
- 목록의 환불 가능 여부는 주문 상태 `COMPLETED/PARTIAL_REFUNDED`, 상품 상태 `PAID`, 미다운로드 조건으로 계산

### 5.4 제거 API

- `GET /api/v1/orders/payments`와 v2 대체 API를 모두 제거
- `POST /api/v1/orders/{orderId}/payment-ready` 제거
- 결제 내역과 결제 준비·승인 책임은 Payment Service 계약으로 이동

### 5.5 다건 환불 요청

```http
POST /api/v2/orders/refunds
```

요청:

```json
{
  "paymentId": "UUID",
  "orderProductIds": ["UUID", "UUID"]
}
```

응답:

```text
202 Accepted
ResponseBody 없음
```

Order Service 검증:

- 요청 구매자와 모든 대상 주문의 구매자가 일치
- 모든 주문 상품이 존재
- `orderProductIds`에 중복이 없음
- 각 주문 상태가 `COMPLETED` 또는 `PARTIAL_REFUNDED`
- 각 주문 상품 상태가 `PAID`
- 다운로드되지 않은 상품

Order Service는 `order_payment`가 없으므로 해당 상품이 `paymentId`에 속하는지는 검증하지 않는다. 이 검증은 Payment Service 책임이다.

### 5.6 관리자 API와 통계

- 관리자 API 경로를 `/api/v2/admin/orders`로 변경
- 판매자 ID는 첫 주문 상품이 아니라 `order.seller_id`에서 조회
- 상품 수는 저장 컬럼 대신 QueryDSL count 또는 조회된 컬렉션 크기로 계산
- 상태 필터를 새 `OrderStatus`로 변경

순거래액:

```text
completed_at이 존재하는 모든 주문의 total_order_amount 합계
- REFUNDED 주문 상품의 product_amount_snapshot 합계
```

- 현재 주문 상태가 `PARTIAL_REFUNDED`나 `ALL_REFUNDED`여도 최초 완료 금액은 gross에 포함한다.
- 환불 금액은 주문 상품의 `refunded_at` 기준 일자에 차감한다.
- `transactionCount`는 Payment 건수가 아니라 완료된 Order 건수다.
- 결제 한 건이 판매자별 주문 세 건이면 거래 건수는 세 건으로 집계한다.

## 6. Checkout 생성과 결제 결과 처리

### 6.1 주문 생성 트랜잭션

1. 구매자와 요청 상품을 검증한다.
2. Product Service에서 상품별 `sellerId`, 금액을 조회한다.
3. 요청 상품을 `sellerId`로 그룹화한다.
4. 판매자 그룹마다 `CREATED` 주문을 만들고 독립 주문 번호를 발급한다.
5. 각 주문 상품을 `PENDING`으로 생성한다.
6. 모든 주문을 저장한다.
7. 주문 묶음 하나를 나타내는 `CHECKOUT_CREATED` EventMessage를 만든다.
8. Outbox 한 건을 같은 트랜잭션에 저장한다.
9. 한 주문 또는 Outbox 저장이라도 실패하면 전체를 롤백한다.
10. 커밋 후 각 주문의 만료 시각을 Redis에 등록한다.

주문 생성 시 장바구니 상품을 제거하지 않는다.

### 6.2 `CHECKOUT_CREATED`

Envelope:

```text
topic: order-events
eventType: CHECKOUT_CREATED
aggregateType: CHECKOUT
eventId: 새 UUID
aggregateId: eventId와 같은 UUID
Kafka key: aggregateId
```

Payload:

```json
{
  "buyerId": "UUID",
  "totalAmount": 45000,
  "orders": [
    {
      "orderId": "UUID",
      "orderAmount": 20000,
      "orderProducts": [
        {
          "orderProductId": "UUID",
          "amount": 10000
        }
      ]
    }
  ]
}
```

Payment Service는 주문·주문 상품 ID와 금액을 보존하고 `payment_order`로 한 Payment와 여러 Order를 연결해야 한다.

### 6.3 `PAYMENT_APPROVED`

```json
{
  "paymentId": "UUID",
  "buyerId": "UUID",
  "totalAmount": 45000,
  "orders": [
    {
      "orderId": "UUID",
      "orderProductIds": ["UUID", "UUID"]
    }
  ],
  "approvedAt": "2026-07-16T10:00:05"
}
```

처리 트랜잭션:

1. `eventId + consumerGroup` 중복을 확인한다.
2. 모든 주문과 주문 상품을 한 번에 조회하고 결정적인 UUID 순서로 잠근다.
3. `CREATED/FAILED` 주문을 `COMPLETED`로 변경한다.
4. `PENDING/FAILED` 상품을 `PAID`로 변경한다.
5. 각 주문의 `completed_at`을 설정한다.
6. 구매자의 해당 장바구니 상품을 제거한다.
7. 주문별 `ORDER_PAID` Outbox를 저장한다.
8. 처리 이력을 기록한다.

모든 주문, 주문 상품, 장바구니, Outbox, 처리 이력은 한 트랜잭션에서 반영한다.

### 6.4 `PAYMENT_FAILED`

```json
{
  "paymentId": "UUID",
  "orderIds": ["UUID", "UUID"],
  "failureCode": "PAY_FAILED",
  "failureReason": "PG 결제 실패",
  "failedAt": "2026-07-16T10:00:05"
}
```

처리:

- `CREATED` 주문을 `FAILED`로 변경
- 소속 `PENDING` 상품을 `FAILED`로 변경
- 장바구니는 변경하지 않음
- 실패 상세는 DB에 저장하지 않음
- `eventId`, `paymentId`, `orderIds`, 실패 코드·사유·시각을 구조화 로그로 기록
- 처리 이력을 기록하고 ACK
- 이미 `COMPLETED/PARTIAL_REFUNDED/ALL_REFUNDED`인 주문에는 늦은 실패를 적용하지 않음
- 이후 새로운 성공 이벤트가 오면 `FAILED → COMPLETED`, `FAILED → PAID` 허용

새 Checkout 실패는 `PAYMENT_FAILED`로 단일화한다. 주문 상태에 `CANCELED`가 없으므로 기존 `PAYMENT_CANCELED` Handler·Processor·payload와 지원 event type은 제거한다.

## 7. 주문 만료

- Redis 만료 등록은 주문 트랜잭션 `AFTER_COMMIT` 흐름을 유지한다.
- 판매자별 주문을 현재 Redis 구조에 각각 등록한다.
- Checkout에 속한 주문 사이에 짧은 만료 처리 시차를 허용한다.
- 만료된 `CREATED` 주문은 `FAILED`, 소속 `PENDING` 상품은 `FAILED`로 변경한다.
- 주문 생성 시 장바구니를 비우지 않으므로 만료 시 장바구니 복원을 수행하지 않는다.
- 늦은 `PAYMENT_APPROVED`가 오면 실제 결제 성공을 우선하여 다시 `COMPLETED/PAID`로 전환한다.
- `OrderExpirationService`, Worker, 테스트에서 `PENDING/CANCELED` 전제를 제거한다.

## 8. 다건 환불 처리

### 8.1 환불 요청 Outbox

Envelope:

```text
topic: order-events
eventType: ORDER_REFUND_REQUESTED
aggregateType: PAYMENT
aggregateId: paymentId
Kafka key: paymentId
eventId: 환불 요청 멱등 식별자
```

Payload:

```json
{
  "paymentId": "UUID",
  "buyerId": "UUID",
  "orders": [
    {
      "orderId": "UUID",
      "products": [
        {
          "orderProductId": "UUID",
          "refundAmount": 10000
        }
      ]
    }
  ],
  "totalRefundAmount": 20000,
  "requestedAt": "2026-07-16T11:00:00"
}
```

- 요청 상품은 하나의 Payment에 속하지만 여러 Order에 걸칠 수 있다.
- Order Service가 저장된 상품 금액으로 `refundAmount`와 합계를 계산한다.
- 로컬 환불 요청·진행 상태 테이블은 만들지 않는다.
- 같은 상품의 동시·중복 요청, Payment 소유권, `payment_order` 관계, 잔여 환불 가능 금액은 Payment Service가 검증한다.
- Payment Service는 다건을 하나의 원자적 환불로 처리하고 전부 성공 또는 전부 실패 이벤트를 발행한다.

### 8.2 `PAYMENT_REFUNDED`

```json
{
  "paymentId": "UUID",
  "products": [
    {
      "orderProductId": "UUID",
      "amount": 10000
    }
  ],
  "totalRefundAmount": 20000,
  "refundedAt": "2026-07-16T11:00:05"
}
```

처리 트랜잭션:

1. 처리 이력으로 동일 `eventId`를 차단한다.
2. 모든 대상 주문 상품과 소속 주문을 조회하고 결정적인 순서로 잠근다.
3. 아직 `PAID`인 상품만 `REFUNDED`로 변경하고 `refunded_at`을 기록한다.
4. 영향받은 각 주문의 전체 상품 상태를 다시 계산한다.
5. 일부 환불이면 `PARTIAL_REFUNDED`, 전부 환불이면 `ALL_REFUNDED`로 변경한다.
6. `ALL_REFUNDED` 주문에만 `order.refunded_at`을 기록한다.
7. 주문별로 이번에 새로 환불된 상품만 담은 `ORDER_REFUND` Outbox를 저장한다.
8. 처리 이력을 기록한다.

중복 방어:

- 같은 `eventId`: 처리 이력에서 즉시 종료
- 다른 `eventId`로 같은 성공 결과 재수신: 이미 `REFUNDED`인 상품은 다시 변경하지 않음
- 새 상태 전이가 없는 주문에는 `ORDER_REFUND`를 다시 발행하지 않음

### 8.3 `PAYMENT_REFUND_FAILED`

```json
{
  "paymentId": "UUID",
  "products": [
    {
      "orderProductId": "UUID",
      "amount": 10000
    }
  ],
  "totalRefundAmount": 20000,
  "failureCode": "REFUND_FAILED",
  "failureReason": "PG 환불 실패",
  "failedAt": "2026-07-16T11:00:05"
}
```

처리:

- 주문·주문 상품 상태를 변경하지 않음
- 로컬 실패 이력을 별도 테이블에 저장하지 않음
- 구조화 로그와 처리 이력만 기록하고 ACK
- 이후 `PAYMENT_REFUNDED`가 도착하면 정상 반영
- 이미 환불 성공한 상품에 늦은 실패 이벤트가 와도 상태를 되돌리지 않음
- 필수 ID 누락·역직렬화 실패처럼 기술적으로 처리할 수 없는 메시지만 재시도 후 DLT

## 9. 하위 서비스용 주문 이벤트

Product Service의 판매량 반영을 위해 이벤트 이름을 유지한다.

### 9.1 `ORDER_PAID`

- `PAYMENT_APPROVED` 한 건을 처리하면서 완료된 주문마다 한 건 발행
- Product Service는 `products[].productId`로 판매 완료를 반영

### 9.2 `ORDER_REFUND`

- 환불 성공 이벤트에서 영향받은 주문마다 한 건 발행
- 해당 성공 이벤트에서 새로 환불된 상품만 포함
- Product Service는 `products[].productId`로 판매량을 되돌림

공통 상품 payload:

```json
{
  "orderProductId": "UUID",
  "productId": "UUID",
  "sellerId": "UUID",
  "productTitle": "상품 제목",
  "productAmount": 10000
}
```

- 제거된 `productType`, `productModel`은 이벤트에서도 제거한다.
- `sellerId`는 `order.sellerId`에서 가져온다.

## 10. Persistence와 조회 리팩터링

### 10.1 Repository 계약

`OrderRepository`에 다음 목적의 메서드를 추가·조정한다.

- 판매자별 다건 주문 저장
- 주문 ID 목록과 주문 상품을 함께 조회
- 주문 상품 ID 목록으로 소속 주문까지 fetch
- 다건 상태 변경 시 잠금 가능한 조회
- 구매자 주문 목록 v2 Projection
- 완료 주문 상품 구매 여부 확인

다건 이벤트 처리 잠금은 UUID 정렬 순서를 고정해 교착 위험을 줄인다.

### 10.2 OrderPayment 제거 영향

삭제:

- `OrderPayment.java`
- `OrderPaymentRepository.java`
- `OrderPaymentAdapter.java`
- `OrderPaymentPersistence*.java`
- `OrderPaymentListProjection.java`
- `OrderPaymentListResponse.java`
- 관련 QueryDSL 테스트와 Controller 테스트

`PaymentApprovedProcessor`는 더 이상 결제 복제 Entity를 저장하지 않는다.

### 10.3 구매자 조회

- 목록 Projection의 주문 상품 상태 타입을 `OrderProductStatus`로 변경
- 상품 유형·모델 제거
- 저장된 제목 snapshot 사용
- 상품별 환불 가능 여부와 콘텐츠 접근 여부를 새 상태로 계산
- 주문 상세의 판매자 ID는 `order.sellerId`에서 제공

### 10.4 관리자 조회

- `AdminOrderQueryRepositoryImpl`의 `orderProduct.sellerId` 사용 제거
- `order.sellerId`로 SellerClient batch 조회
- `orderPayment` 기반 승인·취소·환불 통계 제거
- 완료 주문과 환불 주문 상품을 각각 일자별 집계
- 상품 개수는 QueryDSL count로 계산

### 10.5 gRPC와 정산

- 기존 Order gRPC 계약은 HTTP v1 제거와 별개로 유지한다.
- 결제용 단건 `GetOrder`는 즉시 제거하지 않고 호환성을 보존하되 신규 Checkout 결제의 원본은 `CHECKOUT_CREATED`다.
- 정산용 라인 생성 시 판매자는 `order.sellerId`, 금액은 `orderProduct.productAmountSnapshot`을 사용한다.
- `COMPLETED/PARTIAL_REFUNDED/ALL_REFUNDED`와 상품 `PAID/REFUNDED` 조합에 맞춰 매출·환불 라인을 제공한다.

## 11. 구조화 로그

추후 ELK·Filebeat 도입을 고려해 메시지에 다음 key-value를 일관되게 포함한다.

- `eventId`
- `eventType`
- `consumerGroup`
- `paymentId`
- `aggregateId`
- `orderIds`
- `orderProductIds`
- `failureCode`
- 처리 대상·현재·목표 상태

기록하지 않는 값:

- 토큰, PG 비밀키, 결제 원문 payload
- 개인정보
- 외부 서비스가 제공한 민감한 실패 상세

자유 형식 `failureReason`은 운영 진단에 필요한 범위만 기록하고 사용자 응답이나 Order DB에는 저장하지 않는다.

## 12. 구현 작업 순서

### Task 1. 스키마와 상태 모델 재작성

주요 파일:

- `src/main/resources/db/migration/V1__baseline.sql`
- `domain/enums/OrderStatus.java`
- 신규 `domain/enums/OrderProductStatus.java`
- `domain/model/Order.java`
- `domain/model/OrderProduct.java`
- `domain/model/OutboxEvent.java`

작업:

1. baseline을 새 스키마로 재작성한다.
2. Order·OrderProduct 상태 Enum을 분리한다.
3. 도메인 생성·완료·실패·환불 상태 메서드를 구현한다.
4. `total_product_count`와 환불 카운터 없이 자식 상태로 환불 상태를 계산한다.
5. Outbox Aggregate 컬럼과 API를 일반화한다.
6. OrderPayment 모델을 제거한다.

### Task 2. v2 주문 생성과 Checkout Outbox

주요 파일:

- `presentation/OrderController.java`
- `presentation/dto/request/CreateOrderRequest.java`
- 주문 생성 응답 DTO
- `application/usecase/CreateOrderUseCase.java`
- `application/service/order/CreateOrderCommandHandler.java`
- `application/service/order/OrderPolicyService.java`
- `application/service/event/OrderEventMessageFactory.java`
- 신규 Checkout event payload
- `application/service/event/outbox/OutboxEventAppender.java`
- `infra/messaging/kafka/producer/OutboxRelay.java`

작업:

1. 프론트엔드 제목 snapshot 요청을 검증한다.
2. Product Service 응답으로 seller와 금액을 검증한다.
3. 판매자별 주문을 한 트랜잭션에서 생성한다.
4. 단일 `CHECKOUT_CREATED` Outbox를 저장한다.
5. 다건 주문 응답을 반환한다.
6. 생성 시 장바구니 제거를 삭제한다.
7. 단건 `ORDER_CREATED` 생성 흐름과 event type을 `CHECKOUT_CREATED`로 대체한다.

### Task 3. 다건 결제 결과 처리

주요 파일:

- `PaymentApprovedPayload.java`
- `PaymentFailedPayload.java`
- `PaymentApprovedProcessor.java`
- `PaymentFailedProcessor.java`
- Payment Router·Handler·Consumer
- `OrderRepository`와 persistence adapter

작업:

1. 다건 payload로 변경한다.
2. 모든 주문·상품을 원자적으로 완료 또는 실패 처리한다.
3. 실패 후 성공과 성공 후 늦은 실패 우선순위를 구현한다.
4. 성공 시 장바구니 상품을 제거한다.
5. 주문별 `ORDER_PAID` Outbox를 저장한다.
6. OrderPayment 저장 흐름을 제거한다.
7. 기존 `PAYMENT_CANCELED` 처리 코드를 제거하고 실패 계약을 `PAYMENT_FAILED`로 단일화한다.

### Task 4. 만료·콘텐츠·다운로드 정책 변경

주요 파일:

- `OrderExpirationService.java`
- `OrderExpirationRegistrar.java`
- `OrderExpirationWorker.java`
- `OrderQueryService.java`
- `ConfirmDownloadCommandHandler.java`

작업:

1. 만료 상태를 `FAILED`로 변경한다.
2. 만료 시 장바구니 복원을 제거한다.
3. 늦은 성공 이벤트를 허용한다.
4. 콘텐츠 접근과 다운로드를 상품 `PAID` 기준으로 변경한다.

### Task 5. 결제 단위 다건 환불 요청

신규·변경 파일:

- 환불 요청 DTO
- `OrderRefundUseCase`
- `OrderRefundService`
- 환불 요청 event payload
- `OrderController`
- `ErrorCode`

작업:

1. `/api/v2/orders/refunds`를 추가한다.
2. 구매자·상품 상태·다운로드·중복 ID를 검증한다.
3. 대상 상품을 주문별로 그룹화한다.
4. 저장 금액으로 상품별·총 환불 금액을 계산한다.
5. Payment Aggregate 기준 Outbox 한 건을 저장한다.
6. `202 Accepted`를 반환한다.

### Task 6. 다건 환불 결과 처리

주요 파일:

- `PaymentRefundedPayload.java`
- 신규 `PaymentRefundFailedPayload.java`
- `PaymentRefundedProcessor.java`
- 신규 환불 실패 Handler·Processor
- Payment Router·Consumer event type
- `OrderRefundPayload.java`

작업:

1. 성공·실패 다건 payload를 역직렬화한다.
2. 성공 시 여러 주문을 한 트랜잭션에서 갱신한다.
3. 자식 상태로 주문 환불 상태를 재계산한다.
4. 신규 전이 상품만 주문별 `ORDER_REFUND`에 담는다.
5. 실패 이벤트는 상태를 바꾸지 않고 로그·처리 이력만 기록한다.

### Task 7. v2 조회·통계·이벤트 정리

주요 파일:

- `OrderQueryUseCase.java`
- `OrderQueryService.java`
- 구매자 응답 DTO·Projection
- `AdminOrderQueryRepositoryImpl.java`
- `AdminOrderService.java`
- `OrderPaidPayload.java`
- `OrderPaidProductPayload.java`
- `OrderRefundPayload.java`
- Controller·Swagger

작업:

1. v2 상태와 snapshot 필드로 조회를 재작성한다.
2. 결제 내역 API와 타입을 삭제한다.
3. 관리자 gross·refund·net 집계를 주문 데이터로 재작성한다.
4. 하위 주문 이벤트에서 유형·모델 필드를 제거한다.
5. 판매자 ID를 Order에서 가져오도록 변경한다.

### Task 8. v1 제거와 문서·회귀 정리

작업:

1. Order·Cart·Admin Controller 경로를 v2로 변경한다.
2. WebConfig·Interceptor 테스트를 v2로 변경한다.
3. v1 전용 DTO·Swagger 설명·테스트를 제거한다.
4. 기존 부분 환불 설계·계획·trade-off 문서를 새 계약으로 대체한다.
5. 루트 Order API·ERD·도메인 용어 문서의 갱신 필요성을 별도 변경 요청으로 기록한다.
6. 로컬 DB 초기화 절차를 문서화한다.

## 13. 테스트 계획

### 13.1 Domain

- Order 기본 상태 `CREATED`
- OrderProduct 기본 상태 `PENDING`
- `CREATED → COMPLETED/FAILED`
- `FAILED → COMPLETED`
- 상품 `PENDING/FAILED → PAID`
- 상품 `PAID → REFUNDED`
- 일부 상품 환불 시 `PARTIAL_REFUNDED`
- 전체 상품 환불 시 `ALL_REFUNDED`와 `refunded_at`
- 중복 환불 적용 시 상태·Outbox 중복 없음
- 환불 금액과 무관하게 자식 상태로 전체 환불 판정

### 13.2 주문 생성

- A 판매자 a1·a2, B 판매자 b1, C 판매자 c1 요청 시 주문 세 건 생성
- 판매자별 금액과 전체 금액 계산
- 판매자별 독립 주문 번호
- 제목은 요청 snapshot, seller와 금액은 Product Service snapshot 사용
- 상품 ID 중복·snapshot 누락·외부 서비스 실패 시 전체 롤백
- 주문 세 건과 Checkout Outbox 한 건의 원자성
- 생성 시 장바구니 유지

### 13.3 결제 이벤트

- 다건 성공 시 모든 주문·상품 완료
- 한 대상이라도 처리 실패하면 전체 롤백
- 성공 시 장바구니 대상 상품 제거
- 성공 시 주문별 `ORDER_PAID` 생성
- 결제 실패 시 모든 주문·상품 실패, 장바구니 유지
- 실패 후 새 성공 이벤트 반영
- 성공 후 늦은 실패 이벤트 무시
- 동일 `eventId` 중복 무시
- Embedded Kafka 역직렬화·ACK·DLT

### 13.4 주문 만료

- `CREATED/PENDING → FAILED/FAILED`
- 장바구니 복원 호출 없음
- 완료·환불 주문은 만료 Worker no-op
- 만료 후 결제 성공 허용
- 주문별 독립 만료 처리

### 13.5 환불 요청

- 하나의 Payment에 속한 여러 OrderProduct 요청
- 여러 Order에 걸친 상품 그룹화
- 주문 소유권, 상태, 상품 상태, 다운로드 검증
- 클라이언트 금액을 받지 않고 저장 금액으로 계산
- `ORDER_REFUND_REQUESTED` payload와 Payment Aggregate key
- Outbox 저장 후 `202` 반환
- 동시·중복 차단은 Payment Service 책임이라는 계약 검증

### 13.6 환불 결과

- 여러 OrderProduct 성공 반영
- 주문별 `PARTIAL_REFUNDED/ALL_REFUNDED` 독립 계산
- 주문별 신규 환불 상품만 `ORDER_REFUND` 발행
- 동일 eventId 중복 무시
- 다른 eventId로 같은 성공 재수신 시 추가 상태 변경·Outbox 없음
- 실패 이벤트 수신 시 상태 불변, 로그·처리 이력 저장
- 성공 후 늦은 실패 상태 불변
- malformed payload 재시도·DLT

### 13.7 조회·관리자·Persistence

- v2 구매자 목록·상세 상태와 필드
- 환불된 상품 콘텐츠 접근 거부
- `order.seller_id` 기반 관리자 조회
- 완료 주문 gross 집계
- 환불 상품 금액 차감
- 결제 한 건·주문 세 건을 거래 건수 세 건으로 집계
- `order_payment` 없이 QueryDSL 조회 성공
- H2 PostgreSQL mode에서 새 CHECK·컬럼·인덱스 검증

### 13.8 회귀 명령

가까운 테스트부터 실행한다.

```bash
../gradlew :order-service:test --tests "com.prompthub.order.domain.model.OrderTest"
../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.CreateOrderCommandHandlerTest"
../gradlew :order-service:test --tests "com.prompthub.order.application.service.event.PaymentApprovedProcessorTest"
../gradlew :order-service:test --tests "com.prompthub.order.application.service.event.PaymentRefundedProcessorTest"
../gradlew :order-service:test --tests "com.prompthub.order.infra.messaging.kafka.PaymentEventConsumerIntegrationTest"
../gradlew :order-service:test
../gradlew :order-service:build
git diff --check
```

## 14. 외부 선행 계약과 롤아웃

### 14.1 Payment Service 선행 조건

Payment Service는 Order Service 배포 전에 다음 계약을 지원해야 한다.

- 다건 `CHECKOUT_CREATED` 소비
- `eventId/aggregateId` 기반 Checkout 멱등 처리
- 주문 상품 ID와 금액 보존
- `payment_order`로 Payment 1:N Order 관계 저장
- 다건 `PAYMENT_APPROVED`, `PAYMENT_FAILED` 발행
- 다건 `ORDER_REFUND_REQUESTED` 소비
- Payment 소유권·주문 관계·잔액·중복·진행 중 요청 검증
- 다건 원자적 PG 환불
- 다건 `PAYMENT_REFUNDED`, `PAYMENT_REFUND_FAILED` 발행

이 항목들은 이번 Order Service 구현 작업에 포함하지 않는다.

### 14.2 Gateway·Frontend 조건

- Gateway는 `/api/v2/orders/**`, `/api/v2/cart/**`, `/api/v2/admin/**`를 라우팅한다.
- v1 라우팅은 제거한다.
- Frontend는 주문 생성 시 `productId + productTitle` 목록을 보낸다.
- Frontend는 다건 주문 생성 응답을 처리한다.
- Frontend는 Order Service 환불 API에 `paymentId + orderProductIds`를 보낸다.
- 환불 요청의 `202`는 완료가 아닌 비동기 접수임을 처리한다.

### 14.3 Product Service 조건

- 기존 `ORDER_PAID`, `ORDER_REFUND` eventType을 유지한다.
- `products[].productId` 구조를 유지한다.
- 상품 유형·모델 필드 제거가 Consumer 역직렬화에 영향을 주지 않는지 통합 전에 확인한다.

### 14.4 DB 초기화

기존 데이터와 로컬 DB를 보존하지 않는다.

- `V1__baseline.sql`을 새 최종 스키마로 재작성한다.
- 기존 Flyway history와 서비스 스키마를 제거한 뒤 재생성한다.
- 혼합 판매자 기존 주문을 분리하거나 역채움하는 마이그레이션은 작성하지 않는다.
- 운영 데이터가 존재하는 환경에는 이 계획을 그대로 적용하지 않는다.

### 14.5 배포 순서

1. Payment Service 계약 지원과 통합 테스트 완료
2. Gateway v2 라우팅 준비
3. Order Service DB 초기화
4. Order Service v2 배포
5. Frontend v2 전환
6. Checkout 생성 → 결제 성공·실패 → 다건 환불 end-to-end 검증
7. v1 트래픽과 이전 계약 제거 확인

## 15. 후속 과제

이번 계획에서는 다음을 후순위로 둔다.

- `PAYMENT_APPROVED`의 주문·상품 목록과 Checkout 원본의 완전 일치 검증
- 승인 총액과 주문 금액 합계의 엄격한 상관관계 검증
- 환불 성공 금액과 OrderProduct snapshot 금액의 엄격한 비교
- Payment 결과 이벤트 유실 시 gRPC reconciliation
- Order Service의 환불 진행 상태 조회
- Seller Service용 주문 조회 계약
- Outbox Relay 다중 인스턴스 claim/locking 강화
- 구조화 JSON 로그 포맷과 ELK·Filebeat 운영 설정

후속 검증을 추가하기 전에도 필수 ID, payload 구조, 소유권, 로컬 상태 전이는 검증한다.

## 16. 완료 조건

- 한 Checkout 요청이 판매자 수만큼 주문을 생성한다.
- 주문 생성과 단일 `CHECKOUT_CREATED` Outbox가 원자적이다.
- 주문은 `CREATED`, 상품은 `PENDING`으로 시작한다.
- 한 결제 결과가 여러 주문을 원자적으로 완료 또는 실패 처리한다.
- 결제 실패 후 성공 재시도가 반영되고, 성공 후 늦은 실패는 상태를 되돌리지 않는다.
- 결제 성공 전에는 장바구니가 유지되고 성공 후에만 제거된다.
- 주문 만료는 `FAILED`로 처리하며 장바구니 복원을 수행하지 않는다.
- 환불 요청은 Payment 단위로 여러 주문 상품을 접수하고 `202`를 반환한다.
- Order Service에 결제·환불 요청 테이블이 존재하지 않는다.
- 환불 성공 후 각 주문 상태가 자식 상품 상태로 정확히 계산된다.
- 환불 실패 이벤트는 주문 상태를 변경하지 않는다.
- Product Service용 `ORDER_PAID`, `ORDER_REFUND`가 주문별로 유지된다.
- 구매자·장바구니·관리자 HTTP API는 v2만 노출한다.
- 결제 내역 API가 제거된다.
- 관리자 순거래액이 완료 주문 금액에서 환불 상품 금액을 차감한다.
- Order Service 전체 테스트와 build가 통과한다.
