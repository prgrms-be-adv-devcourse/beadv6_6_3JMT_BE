# Order Service 단일 주문·다중 판매자 Checkout 개발 기획서

> 작성일: 2026-07-17
>
> 구현 기준선: `133ada88`
>
> 구현 브랜치: `feat/order-service-single-order-multi-seller`
>
> 핵심 목표: 상품 4개를 한 번에 주문하면 판매자 수와 무관하게 Payment 1개, Order 1개, OrderProduct 4개가 생성되는 구조로 전환한다.

## 1. 결정 요약

이번 변경은 다음 원칙을 적용한다.

- 한 Checkout 요청은 정확히 하나의 `Order`를 생성한다.
- 요청 상품마다 하나의 `OrderProduct`를 생성한다.
- 서로 다른 판매자의 상품도 같은 `Order`에 포함할 수 있다.
- `seller_id`의 소유 위치를 `order`에서 `order_product`로 옮긴다.
- 한 `Order`는 Payment Service의 `Payment.order_id` 한 건과 대응한다.
- 주문 생성 HTTP 응답과 주문·결제 Kafka 이벤트는 단건 주문 계약을 사용한다.
- 주문 완료·실패는 Order 단위로 처리하고, 환불·정산·판매량 반영은 OrderProduct 단위로 처리한다.
- Order Service는 Payment Entity를 만들거나 결제 테이블을 복제하지 않는다. Payment 생성과 PG 처리는 Payment Service가 계속 소유한다.

목표 데이터 수는 다음과 같다.

`ORDER_CREATED` 수신 시 Payment Service는 주문 snapshot만 저장한다. 실제 Payment row는 사용자가 결제 승인 API를 호출할 때 생성한다. 아래 Payment 수는 결제 승인 요청이 시작된 이후의 목표이며, 주문 생성 transaction에서 Payment를 함께 만든다는 의미가 아니다.

| Checkout 입력 | Payment, 결제 요청 후 | Order | OrderProduct |
|---|---:|---:|---:|
| 상품 1개 | 1 | 1 | 1 |
| 상품 4개, 판매자 1명 | 1 | 1 | 4 |
| 상품 4개, 판매자 3명 | 1 | 1 | 4 |

## 2. 변경 전 구조와 변경 이유

기준선 `133ada88`의 `OrderCreator`는 `OrderItem.sellerId`로 상품을 그룹화했다. A 판매자 상품 2개, B 판매자 상품 1개, C 판매자 상품 1개를 주문하면 Order 3개와 OrderProduct 4개를 만들고, 하나의 `ORDER_CREATED` payload에 Order 3개를 넣어 Payment 결과도 다건 주문 배열로 처리했다.

이 구조는 `order.seller_id`를 유지하기에는 자연스럽지만 다음 요구와 맞지 않는다.

- 사용자 관점에서 한 번의 주문을 하나의 주문 번호로 조회해야 한다.
- 한 번의 PG 승인과 하나의 Payment가 하나의 Order에 대응해야 한다.
- 부분 환불과 정산은 같은 Order 안에서 상품과 판매자별로 분리되어야 한다.
- 현재 Payment Service는 이미 `Payment.order_id` 단건 모델과 단건 주문·결제 이벤트를 사용한다.

따라서 판매자는 Order의 속성이 아니라 구매 시점 OrderProduct snapshot의 속성으로 본다.

## 3. 목표와 제외 범위

### 3.1 포함 범위

- `order.seller_id` 제거와 `order_product.seller_id` 추가
- 기존 데이터의 seller ID 역채움 migration
- 판매자 그룹화 제거와 단일 Order 생성
- 단건 주문 생성 결과와 `/api/v2/orders` 응답
- 단건 `ORDER_CREATED`, `PAYMENT_APPROVED`, `PAYMENT_FAILED` 계약
- 단건 결제 결과의 상태 변경, Outbox, 장바구니, processed event 원자성
- OrderProduct 단위 환불 반영과 `ORDER_REFUND` 발행
- 구매자 조회, 관리자 조회, 통계, 정산 조회의 seller ID 출처 변경
- Redis 주문 만료 등록·제거의 단건화
- 단위, JPA, 트랜잭션, Kafka, gRPC 계약 테스트

### 3.2 제외 범위

- Order Service 내부에 Payment 테이블이나 Payment FK 추가
- Toss Payments 직접 호출
- Product, User, Seller Service DB 직접 참조
- 한 Checkout에서 여러 Order를 만드는 호환 모드
- `PAYMENT_CANCELED` 지원 복구
- Product Service의 `ORDER_PAID`·`ORDER_REFUND` 소비 동작 변경
- 정산 gRPC protobuf 필드 변경
- 무중단 rolling migration 구현

스키마 소유 위치가 바뀌므로 배포는 DB migration과 Order Service를 조율한 단일 전환으로 계획한다. 무중단 배포가 필요하면 expand-contract migration을 별도 기획해야 한다.

## 4. 목표 도메인 모델

### 4.1 Order

`Order`는 Checkout 전체의 구매·결제 상태를 소유한다.

```text
Order
  id
  buyerId
  orderNumber
  totalOrderAmount
  orderStatus
  completedAt
  refundedAt
  orderProducts[]
```

변경 사항:

- `sellerId` 필드와 `idx_order_seller_created_at` index를 제거한다.
- 생성 메서드는 `buyerId`, `orderNumber`, `totalOrderAmount`만 받는다.
- `totalOrderAmount`는 모든 OrderProduct의 구매 당시 금액 합계다.
- Order 상태는 모든 자식 상품의 결제·환불 상태를 요약한다.

### 4.2 OrderProduct

`OrderProduct`는 상품과 판매자 snapshot 및 상품별 후속 상태를 소유한다.

```text
OrderProduct
  id
  orderId
  productId
  sellerId
  productTitleSnapshot
  productAmountSnapshot
  orderProductStatus
  downloaded
  createdAt
  updatedAt
  refundedAt
```

변경 사항:

- `sellerId`를 UUID, NOT NULL로 저장한다.
- `OrderProduct.create`는 `productId`, `sellerId`, 제목, 금액을 필수로 받는다.
- `getSellerId()`는 부모 Order가 아니라 자신의 영속 필드를 반환한다.
- Seller Service에 대한 물리 FK는 생성하지 않는다.

### 4.3 불변식

- Order에는 OrderProduct가 한 개 이상 존재한다.
- OrderProduct의 seller ID, product ID, 제목, 금액은 모두 유효해야 한다.
- Order 총액은 OrderProduct snapshot 금액 합계와 일치해야 한다.
- 하나의 OrderProduct는 정확히 하나의 Order에 속한다.
- OrderProduct seller ID는 주문 이후 Product Service의 판매자 변경과 무관한 snapshot이다.
- `CREATED` Order의 자식은 모두 `PENDING`으로 시작한다.
- `COMPLETED` Order의 미환불 자식은 `PAID`다.
- 일부 자식만 환불되면 Order는 `PARTIAL_REFUNDED`, 모두 환불되면 `ALL_REFUNDED`다.

## 5. DB migration

schema 변경은 `V2__move_seller_id_to_order_product.sql`, `V3__add_settlement_query_indexes.sql`, `V4__create_order_payment_table.sql`로 나눈다. V2 적용 순서는 다음과 같다.

1. `order_product.seller_id uuid` nullable column을 추가한다.
2. `order_product.order_id = order.id`를 기준으로 기존 `order.seller_id`를 역채움한다.
3. null seller ID가 남아 있지 않은지 검증한다.
4. `order_product.seller_id`에 NOT NULL을 적용한다.
5. `idx_order_product_seller_created_at(seller_id, created_at desc)`를 추가한다.
6. `idx_order_seller_created_at`를 제거한다.
7. `order.seller_id`를 제거한다.

V3는 V2 다음에 적용하며 정산 조회 경로를 위해 다음 index를 추가한다.

1. PAID line 조회에서 Order와 OrderProduct join을 지원하는 `idx_order_product_order_id(order_id)`
2. REFUND line 기간 조회를 지원하는 `idx_order_product_refunded_at(refunded_at)`

V4는 active `OrderPayment` mapping과 V1 baseline 사이의 누락을 전진형으로 복구한다. `order_payment` 11개 column, PK, order/payment/PG transaction ID unique 제약을 만들며, 과거 baseline에 table이 이미 존재하는 환경을 위해 `CREATE TABLE IF NOT EXISTS`를 사용한다. V1/V2/V3의 migration 파일과 checksum은 변경하지 않는다.

기존 Order가 한 판매자만 소유하므로 역채움 값은 모든 기존 OrderProduct에 동일하게 복사된다. migration은 PostgreSQL transaction 안에서 V1 → V2 → V3 → V4 순으로 실행하며, 적용 중 주문 생성 트래픽을 중지한다.

JPA 모델과 migration 완료 후 다음을 확인한다.

- Hibernate schema validation과 Flyway history가 일치한다.
- H2 PostgreSQL mode에서 `seller_id` NOT NULL mapping이 동작한다.
- QueryDSL Q-type은 build로 재생성하며 생성 파일을 직접 수정하지 않는다.

## 6. 주문 생성 흐름

### 6.1 처리 순서

`OrderCommandHandler`는 기존처럼 요청과 Product Service snapshot을 product ID로 결합한다. `OrderCreator`만 판매자 그룹화를 제거하고 다음을 하나의 DB transaction에서 수행한다.

1. 요청 상품 ID 중복, 제목, snapshot 수와 ID 집합을 검증한다.
2. Product Service snapshot의 `sellerId`와 금액을 신뢰 가능한 값으로 사용한다.
3. 모든 OrderItem 금액을 합산한다.
4. 주문 번호를 한 번 발급한다.
5. Order 한 개를 `CREATED`로 생성한다.
6. 각 OrderItem을 seller ID가 포함된 OrderProduct로 변환한다.
7. Order 한 건을 저장한다. cascade로 OrderProduct N건을 저장한다.
8. 단건 `ORDER_CREATED` Outbox 한 건을 저장한다.
9. Order 한 건을 Redis 만료 등록 내부 이벤트로 발행한다.
10. 단건 `CreateOrderResult`를 반환한다.

상품 4개 요청에서 `OrderNumberGenerator`, `OrderRepository.save`, `OutboxEventAppender`는 각각 한 번 호출되어야 한다.

### 6.2 트랜잭션

다음 DB 변경은 하나의 transaction으로 유지한다.

- Order 1건
- OrderProduct N건
- `ORDER_CREATED` Outbox 1건

Order 또는 OrderProduct 저장, 주문 번호 unique 제약, Outbox 직렬화·저장 중 하나라도 실패하면 모두 rollback한다. Redis 등록은 기존처럼 `AFTER_COMMIT`에서 실행한다.

## 7. HTTP API 계약

`POST /api/v2/orders`는 배열 대신 단건 Order를 반환한다.

```json
{
  "success": true,
  "data": {
    "totalAmount": 45000,
    "order": {
      "orderId": "UUID",
      "orderNumber": "ORD-20260717-...",
      "buyerId": "UUID",
      "orderStatus": "CREATED",
      "orderAmount": 45000,
      "products": [
        {
          "orderProductId": "UUID",
          "productId": "UUID",
          "sellerId": "UUID",
          "productTitle": "상품 제목",
          "productAmount": 10000,
          "orderProductStatus": "PENDING"
        }
      ],
      "createdAt": "2026-07-17T10:00:00"
    }
  }
}
```

변경 사항:

- `CreateOrderResult.orders`를 `CreateOrderResult.order`로 변경한다.
- `CreateOrderResponse.orders`를 `CreateOrderResponse.order`로 변경한다.
- Order 수준 `sellerId`를 제거한다.
- Product 응답에 `sellerId`를 추가한다.
- Swagger 설명에서 “판매자별 주문 목록” 표현을 제거한다.

이 변경은 v2 API의 비호환 변경이다. 프론트엔드는 `data.orders[0]` 대신 `data.order`를 사용해야 한다.

## 8. Order → Payment 이벤트 계약

### 8.1 ORDER_CREATED

Payment Service가 실제 소비하는 최소 단건 계약에 맞춘다.

```json
{
  "eventId": "UUID",
  "eventType": "ORDER_CREATED",
  "occurredAt": "2026-07-17T10:00:00",
  "aggregateType": "ORDER",
  "aggregateId": "ORDER_ID",
  "payload": {
    "orderId": "ORDER_ID",
    "buyerId": "BUYER_ID",
    "totalAmount": 45000,
    "createdAt": "2026-07-17T10:00:00"
  }
}
```

- event key, `aggregateId`와 payload `orderId`를 같은 Order ID로 사용한다.
- 임의의 `ORDER_GROUP` ID와 `orders` 배열을 제거한다.
- 상품과 seller 정보는 Payment 생성에 필요하지 않으므로 payload에 중복하지 않는다.
- Order Service Outbox Relay의 topic과 재시도 정책은 유지한다.

Payment Service의 현재 `OrderCreatedMessage(orderId, buyerId, totalAmount, createdAt)`와 일치하므로 Payment Service 소비 코드는 변경하지 않는다.

`ORDER_CREATED` 소비 단계에서는 OrderSnapshot만 저장한다. Payment 생성은 이후 결제 승인 요청에서 `Payment.create(orderId, ...)`로 한 번 수행되며, 같은 Order의 진행 중·완료 Payment 중복 생성은 Payment Service의 기존 중복 검증을 유지한다.

## 9. Payment → Order 이벤트 계약

현재 Payment Service가 발행하는 단건 payload를 기준 계약으로 사용한다.

### 9.1 PAYMENT_APPROVED

```json
{
  "paymentId": "UUID",
  "orderId": "UUID",
  "userId": "UUID",
  "amount": 45000,
  "approvedAt": "2026-07-17T10:00:05+09:00"
}
```

### 9.2 PAYMENT_FAILED

```json
{
  "paymentId": "UUID",
  "orderId": "UUID",
  "userId": "UUID"
}
```

실패 시각은 envelope `occurredAt`을 사용한다. 현재 Payment Service가 발행하지 않는 `orderIds`, `orders`, `failureCode`, `failureReason`은 Order Service 필수 계약에서 제거한다.

### 9.3 PAYMENT_REFUNDED

```json
{
  "paymentId": "UUID",
  "orderId": "UUID",
  "userId": "UUID",
  "orderProductId": "UUID",
  "amount": 10000,
  "paymentStatus": "PARTIAL_REFUNDED",
  "refundedAt": "2026-07-17T11:00:00+09:00"
}
```

변경 전 Order Service의 `PaymentRefundedPayload`에 빠졌던 `orderProductId`와 `paymentStatus`를 반영하고 필드명을 Payment Service와 일치시켰다.

### 9.4 지원하지 않는 이벤트

- `PAYMENT_CANCELED`는 계속 미지원으로 ACK한다.
- `PAYMENT_REFUND_FAILED`는 주문 상태를 변경하지 않는다. 별도 실패 이력 API 요구가 생기기 전까지 기존 미지원 ACK 정책을 유지한다.

## 10. 결제 승인·실패 처리

### 10.1 공통 처리

- payload와 envelope 필수 필드를 먼저 검증한다.
- `orderId`로 Order와 OrderProduct를 비관적 잠금 조회한다.
- 처리 전과 잠금 후 `eventId + consumerGroup` processed event를 확인한다.
- payload `userId`와 Order `buyerId`가 일치해야 한다.
- processed event, 상태, 장바구니, Outbox는 같은 DB transaction에 둔다.

다건 주문 잠금 포트는 제거하거나 단건 잠금 포트로 대체한다.

```java
Optional<Order> findByIdWithOrderProductsForUpdate(UUID orderId);
```

### 10.2 승인

승인 처리 순서는 다음과 같다.

1. Order가 존재하는지 확인한다.
2. 구매자와 승인 금액을 검증한다.
3. payload `amount`와 `order.totalOrderAmount`가 다르면 거절한다.
4. `CREATED` 또는 `FAILED` Order를 `COMPLETED`로 변경한다.
5. `PENDING` 또는 `FAILED` OrderProduct를 모두 `PAID`로 변경한다.
6. Order 한 건의 `ORDER_PAID` Outbox 한 건을 저장한다.
7. 해당 Order의 product ID를 장바구니에서 제거한다.
8. processed event를 저장한다.
9. commit 후 Redis 만료 예약과 retry count를 제거한다.

이미 `COMPLETED`, `PARTIAL_REFUNDED`, `ALL_REFUNDED`인 주문은 상태, Outbox, 장바구니, Redis를 다시 변경하지 않고 processed event만 기록한다. 다른 event ID의 늦은 중복 승인이 사용자가 다시 담은 장바구니 상품을 제거하지 않게 한다.

### 10.3 실패

- `CREATED` Order만 `FAILED`로 변경한다.
- 자식 `PENDING` 상품을 모두 `FAILED`로 변경한다.
- 이미 `FAILED`, `COMPLETED`, `PARTIAL_REFUNDED`, `ALL_REFUNDED`인 주문은 정상 no-op으로 처리한다.
- 실패 이벤트는 장바구니와 Outbox를 변경하지 않는다.
- processed event 저장 실패 시 상태 변경도 rollback한다.

## 11. 환불 처리

다중 판매자 단일 Order에서 환불의 최소 단위는 OrderProduct다.

### 11.1 PAYMENT_REFUNDED 반영

1. Order와 모든 OrderProduct를 잠근다.
2. `orderId`, `orderProductId`, `userId`의 소유 관계를 검증한다.
3. 환불 대상 상품이 `PAID`인지 확인한다.
4. payload `amount`와 OrderProduct snapshot 금액을 비교한다.
5. 대상 OrderProduct 하나만 `REFUNDED`로 변경한다.
6. 자식 상태를 기준으로 Order 상태를 재계산한다.
7. 일부 환불이면 `PARTIAL_REFUNDED`, 모두 환불이면 `ALL_REFUNDED`로 전환한다.
8. 방금 환불된 상품 하나만 포함하는 `ORDER_REFUND` Outbox를 저장한다.
9. processed event를 저장한다.

동일 환불이 다른 event ID로 재수신되어도 이미 환불된 OrderProduct에는 상태 변경과 Outbox를 다시 만들지 않는다.

### 11.2 ORDER_REFUND 계약

`ORDER_REFUND`의 최상위 계약은 유지하되 `products`에는 이번 이벤트에서 실제 환불된 상품만 포함한다. 각 상품은 자신의 영속 `sellerId`를 사용한다.

이렇게 해야 Product Service가 환불되지 않은 다른 판매자 상품의 판매량까지 감소시키지 않는다.

## 12. 조회와 관리자 API

### 12.1 구매자 주문 조회

- Order 상세·목록의 Order 수준 seller ID 의존성을 제거한다.
- 상품 응답의 seller ID는 `order_product.seller_id`에서 제공한다.
- 상품별 다운로드·환불 가능 여부는 기존 OrderProduct 상태 기준을 유지한다.
- 주문 단위 상태와 총액은 Order에서 제공한다.

### 12.2 관리자 주문 목록

현재 관리자 목록의 단일 `sellerNickname`은 다중 판매자 Order를 표현할 수 없다. 다음 구조로 변경한다.

```json
{
  "orderId": "UUID",
  "sellerCount": 3,
  "sellers": [
    {
      "sellerId": "UUID",
      "sellerNickname": "seller-a",
      "productCount": 2,
      "orderAmount": 20000
    }
  ],
  "productTitle": "상품 A 외 3건",
  "totalOrderCount": 4,
  "totalOrderAmount": 45000,
  "orderStatus": "COMPLETED",
  "createdAt": "2026-07-17T10:00:00"
}
```

QueryDSL 조회는 `orderProduct.sellerId`, 상품 수, 상품 금액을 Order별·Seller별로 모은다. Application Service는 모든 seller ID를 한 번에 `SellerClient`로 조회하고 seller 요약에 nickname을 결합한다.

### 12.3 관리자 거래 통계

- 결제 한 건은 Order 한 건이므로 완료 거래 건수도 한 건으로 집계한다.
- gross 금액은 Order 완료 시 `order.totalOrderAmount`를 한 번 더한다.
- 환불 금액은 OrderProduct `refundedAt`과 snapshot 금액으로 차감한다.
- seller별 통계가 필요하면 Order가 아니라 OrderProduct seller ID로 그룹화한다.

## 13. 정산과 gRPC

정산의 protobuf 계약과 Settlement Service 클라이언트는 이미 OrderProduct line 단위로 정의되어 있고 `SettleableLine.seller_id`를 제공한다. 기준선의 Order Service에는 `GetSettleableLines` 서버 메서드가 없었으므로, protobuf 형태를 유지한 채 조회 포트·QueryDSL projection·gRPC 서버 구현을 추가했다.

- 판매 line: `orderProduct.sellerId`, `orderProduct.productAmount`, Order `completedAt`
- 환불 line: `orderProduct.sellerId`, 환불 금액, `orderProduct.refundedAt`
- 멱등키: 기존 `orderProductId + lineType`
- Order ID가 같아도 seller ID별 SettlementSourceLine을 독립 생성한다.

새 Order gRPC 조회 구현은 seller ID를 `OrderProduct`에서 읽는다. Payment용 `GetOrder`는 Order ID, buyer ID, 전체 금액만 반환하므로 계약을 유지한다.

## 14. 하위 주문 이벤트

### 14.1 ORDER_PAID

Order 한 건의 모든 상품을 한 payload에 포함한다. 상품마다 다음 snapshot을 제공한다.

- `orderProductId`
- `productId`
- `sellerId`
- 제목
- 상품 금액

현재 payload 구조는 이미 상품별 seller ID를 포함하므로 필드 형태를 바꾸지 않고 값의 출처만 OrderProduct 영속 필드로 바꾼다.

### 14.2 ORDER_REFUND

환불 이벤트는 실제로 환불된 OrderProduct만 포함한다. Product Service는 기존처럼 `products[].productId`를 읽을 수 있으므로 Consumer 변경은 필요하지 않다.

## 15. Redis 주문 만료

- 주문 생성 후 Order ID 한 개만 `order:expiration`에 등록한다.
- 결제 승인 commit 후 해당 Order의 만료 예약과 retry count를 제거한다.
- 만료 Worker는 `CREATED` Order 한 건과 모든 `PENDING` OrderProduct를 `FAILED`로 변경한다.
- 생성 시 장바구니를 비우지 않으므로 만료 시 장바구니 복원은 수행하지 않는다.
- Redis 정리 실패는 경고 로그 후 Worker의 상태 확인으로 수렴한다.

## 16. 서비스별 영향

### 16.1 Order Service

도메인, DB, 주문 생성 API, Outbox, 결제 결과 Consumer, 환불, 관리자 조회, 정산 projection을 변경한다.

### 16.2 Payment Service

현재 구조가 이미 Payment 1건과 Order ID 1건을 연결한다. `ORDER_CREATED`, `PAYMENT_APPROVED`, `PAYMENT_FAILED`, `PAYMENT_REFUNDED`를 현재 Payment Service 계약에 맞추므로 기능 코드 변경은 필요하지 않다. `ORDER_CREATED`에서는 OrderSnapshot만 기록하고 결제 승인 API 호출 시 Payment 한 건을 생성하는 현재 책임도 유지한다.

다만 배포 전 Payment Service 통합 테스트로 실제 JSON 호환성을 검증한다.

이번 구현에서는 Payment Service source/test/설정 파일을 수정하지 않았다. 계약 source를 읽고 지정 test와 Task 10 전체 test만 실행했다.

### 16.3 Product Service

`ORDER_PAID`, `ORDER_REFUND`의 `products[].productId` 형태를 유지하므로 Consumer 변경은 필요하지 않다. 환불 이벤트가 대상 상품만 포함하는지 회귀 테스트한다.

이번 구현에서는 Product Service 파일을 수정하지 않고 전체 test로 소비 계약을 검증했다.

### 16.4 Settlement Service

protobuf와 Settlement Service 클라이언트는 변경하지 않았다. Order Service에 `GetSettleableLines` 서버 구현을 추가하고, 상품별 seller ID를 정확히 반환하는 계약 테스트를 보강했다.

이번 구현에서는 Settlement Service와 root protobuf 파일을 수정하지 않고 지정/전체 test로 기존 line 역직렬화 계약을 검증했다.

### 16.5 Frontend·Gateway

Gateway 경로는 유지한다. Frontend는 주문 생성 응답의 `orders` 배열을 `order` 단건 객체로 변경해야 한다.

## 17. 구현 작업 단위

### 작업 1. 도메인과 schema

- Order seller ID 제거
- OrderProduct seller ID 영속화
- 생성 메서드와 fixture 수정
- Flyway V2 migration 추가
- Flyway V3 정산 조회 index migration 추가
- Flyway V4 결제 이력 table 복구 migration 추가
- Domain·JPA mapping 테스트

### 작업 2. 단일 Order 생성

- seller grouping 제거
- Order 1건, OrderProduct N건 저장
- 단건 결과와 HTTP 응답
- 단건 만료 내부 이벤트
- 생성 transaction 통합 테스트

### 작업 3. 단건 ORDER_CREATED

- payload와 EventMessage aggregate 단건화
- Outbox 직렬화 계약 수정
- Payment Service 소비 계약 fixture와 일치 검증

### 작업 4. 단건 결제 승인·실패

- payload를 Payment Service 필드와 일치
- 단건 비관적 잠금 repository
- 구매자·금액·상태 검증
- Outbox·장바구니·processed event 원자성
- Kafka retry·DLT 회귀

### 작업 5. OrderProduct 환불

- PaymentRefunded payload 정합화
- 상품 한 건 환불과 Order 상태 재계산
- 환불 대상만 ORDER_REFUND 발행
- 중복·롤백·다른 판매자 상품 비영향 테스트

### 작업 6. 조회·관리자·정산

- 구매자 DTO seller 출처 변경
- 관리자 multi-seller summary
- QueryDSL 통계 수정
- 정산 line seller 출처 변경
- gRPC 계약 테스트

### 작업 7. 문서와 전체 회귀

- Swagger 예시와 이벤트 문서 갱신
- API·Kafka 비호환 변경과 배포 순서 기록
- Order, Payment, Product, Settlement Service 관련 테스트 실행

## 18. 테스트 계획

### 18.1 주문 생성

- 판매자 3명의 상품 4개 → Order 1, OrderProduct 4, Outbox 1
- 동일 판매자 상품 4개 → Order 1, OrderProduct 4, Outbox 1
- 주문 번호 생성 1회
- seller ID와 금액은 Product Service snapshot 사용
- 중복 상품, 누락 snapshot, 잘못된 금액 거절
- Order/Outbox 실패 시 전체 rollback
- commit 이후 Redis 등록 1회

### 18.2 API와 직렬화

- `data.order` 단건 응답과 상품별 seller ID
- `data.orders` 필드가 존재하지 않음
- 단건 ORDER_CREATED JSON이 Payment Service DTO와 일치
- aggregate type, ID, Kafka key가 Order ID와 일치

### 18.3 결제 결과

- 승인 시 Order 1건과 상품 4건 완료
- 승인 금액 불일치 거절
- 실패 후 승인 복구
- 승인 후 늦은 실패 무시
- 동일 event ID 중복 무시
- 다른 event ID 중복 승인에서 Outbox·장바구니 재변경 없음
- 상태, 장바구니, Outbox, processed event rollback 원자성
- Redis는 commit 이후에만 정리

### 18.4 환불

- 판매자 A 상품 1개 환불 시 그 상품만 REFUNDED
- 다른 판매자 상품 상태와 판매량 이벤트 비영향
- 일부 환불 시 PARTIAL_REFUNDED
- 마지막 상품 환불 시 ALL_REFUNDED
- 금액·소유권·상품 소속 불일치 거절
- 동일·다른 event ID 중복 환불의 Outbox 멱등성

### 18.5 관리자·정산

- 관리자 한 Order에 seller summary 3개
- seller별 상품 수와 금액 합계가 Order 총액과 일치
- Payment 1건·Order 1건을 거래 1건으로 집계
- 환불 금액은 환불된 OrderProduct만 차감
- 정산 line에 각 OrderProduct seller ID가 정확히 포함

### 18.6 Kafka

- PAYMENT_APPROVED/FAILED/REFUNDED 단건 라우팅
- payload 필수 필드 누락 시 retry 후 DLT
- Processor 예외 시 ACK하지 않음
- `PAYMENT_CANCELED` 미지원 ACK 유지
- Product Service가 ORDER_PAID 전체 상품과 ORDER_REFUND 대상 상품만 소비

## 19. 검증 명령

가까운 테스트부터 실행한 뒤 계약 영향 범위를 넓힌다.

```bash
../gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderCreatorTest"
../gradlew :order-service:test --tests "com.prompthub.order.application.service.event.*"
../gradlew :order-service:test
../gradlew :order-service:build
```

서비스 간 계약 확인이 필요하므로 저장소 루트에서 다음도 실행한다.

```bash
./gradlew :payment-service:test
./gradlew :product-service:test
./gradlew :settlement-service:test
```

마지막으로 `git diff --check`, migration SQL, 최종 diff와 민감정보 포함 여부를 확인한다.

### 19.1 실제 검증 결과

- PostgreSQL 18.4-alpine disposable DB에서 Order Service runtime Flyway API로 V1 → sample 2 Order/3 OrderProduct → V2 → V3 → V4를 순서대로 적용했다. seller backfill, parent seller column 제거, child NOT NULL, 최종 index 3개, `order_payment` 11개 column과 PK/unique 제약 3개를 확인했다.
- `flyway_schema_history`는 V1 `1943769770`, V2 `731148972`, V3 `-2109978892`, V4 `-844890900` checksum과 success=true를 기록했다. 같은 DB에서 실제 Order Service context가 Flyway `validate-on-migrate`와 Hibernate `ddl-auto=validate`를 통과해 active 상태가 된 뒤 정상 close됐으며, 임시 harness와 container를 제거했다.
- `./gradlew :order-service:test --rerun-tasks`: `BUILD SUCCESSFUL in 1m 25s`, 428 tests, skip/failure/error 0.
- `./gradlew :order-service:build --rerun-tasks`: sandbox wrapper-lock 실패 1회 후 승인된 동일 명령의 최종 HEAD 실행은 `BUILD SUCCESSFUL in 1m 30s`, 16 tasks executed. 기존 checkstyle/deprecation 및 Embedded Kafka 종료 warning은 non-failing이다.
- `./gradlew :payment-service:test :product-service:test :settlement-service:test --rerun-tasks`: `BUILD SUCCESSFUL in 49s`. 각 66/90/87 tests이며 skip/failure/error 0이다.
- Task 9 지정 계약 tests도 Payment 29초, Product 전체 19초, Settlement 7초에 각각 성공했다.
- 기준선 `133ada88` 이후 source 변경은 `order-service/**`에만 있고 세 sibling service와 protobuf는 읽기/검증만 수행했다.

## 20. 배포 순서

1. Order/Payment Kafka exact JSON fixture와 관련 모듈 tests를 모두 통과시킨다.
2. 운영 `flyway_schema_history`의 version, description, checksum, success를 승인된 값과 대조한다. checksum mismatch나 실패 이력이 하나라도 있으면 배포를 즉시 중단하며, 원인 규명과 별도 검토 없이 `repair`하지 않는다.
3. 주문 생성 ingress를 중지한다.
4. 수신 완료된 in-flight 주문이 모두 종료되었는지 확인한다.
5. 구 Order Service writer instance를 모두 중지하고 DB writer가 남아 있지 않은지 확인한다.
6. 이 시점의 cutover snapshot과 PITR 복구 위치를 생성·기록한다.
7. Flyway V2를 적용하고 null count 0, child NOT NULL, parent column 제거를 확인한다.
8. Flyway V3를 적용하고 정산 조회 index 2개를 catalog에서 확인한다.
9. Flyway V4를 적용하고 `order_payment` mapping의 column, PK, unique 제약을 확인한다.
10. V1–V4 schema history/checksum/success와 Flyway validate, Hibernate `ddl-auto=validate`를 확인한다.
11. 새 Order Service를 배포한다.
12. 4상품·3판매자 Checkout smoke test로 Order 1, OrderProduct 4, Outbox 1을 확인한다.
13. `ORDER_CREATED`가 Payment snapshot 1건을 만들고 결제 승인 요청이 Payment 1건만 만드는지 확인한다.
14. 승인 후 Order COMPLETED, 상품 4건 PAID, `ORDER_PAID` 1건, Redis 만료 제거를 확인한다.
15. seller A 상품 1개를 환불해 PARTIAL_REFUNDED와 `ORDER_REFUND.products=1`을 확인하고, 마지막 상품까지 환불해 ALL_REFUNDED를 확인한다.
16. 관리자 응답 seller summary 3개와 대상 월 정산 PAID/REFUND line seller ID를 확인한다.
17. 이상이 없으면 주문 생성 트래픽을 재개한다.

주문 생성 트래픽 재개 전 rollback은 cutover snapshot/PITR 복원과 구 application 재배포를 반드시 동시에 수행한다. application만 이전 버전으로 내리거나 seller column 역 migration만 적용하지 않는다.

다중 seller 쓰기가 시작된 뒤에는 새 데이터를 구 단일 seller schema로 무손실 표현할 수 없으므로 reverse migration을 금지한다. forward-fix를 우선하며, snapshot rollback도 운영 DB에 직접 수행하지 않는다. snapshot이 필요하면 별도 복구 환경에서 변경분을 식별·reconcile하고 명시적인 데이터 손실 승인까지 받은 복구 계획으로만 진행한다.

## 21. 완료 조건

- 판매자 수와 무관하게 한 Checkout이 Order 한 건을 생성한다.
- 상품 N개가 OrderProduct N개로 저장되고 각 seller ID가 보존된다.
- 주문 번호, ORDER_CREATED Outbox, Redis 만료 등록이 각각 한 번만 생성된다.
- `/api/v2/orders`가 단건 Order를 반환한다.
- Payment Service가 결제 승인 요청 시 Order ID 하나로 Payment 한 건만 생성한다.
- 승인·실패 이벤트가 Order 한 건과 모든 상품에 원자적으로 반영된다.
- 환불은 지정된 OrderProduct만 변경하고 주문 상태를 정확히 재계산한다.
- 관리자 조회는 다중 판매자를 손실 없이 표시한다.
- 정산 line은 상품별 seller ID를 사용한다.
- PostgreSQL runtime Flyway V1 → V2 → V3 → V4 rehearsal에서 seller backfill, NOT NULL, 최종 정산 index, 결제 이력 table과 Hibernate schema validation을 확인한다.
- Order, Payment, Product, Settlement 관련 회귀 테스트와 build가 통과한다.
- Payment, Product, Settlement Service와 protobuf를 수정하지 않고 기존 소비 계약을 유지한다.
- API·Kafka·DB 비호환 변경과 배포 순서가 문서화된다.
