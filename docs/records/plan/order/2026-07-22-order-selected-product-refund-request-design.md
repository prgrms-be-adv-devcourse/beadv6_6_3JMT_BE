# 주문 내역 상품 선택 환불 접수 설계

## 1. 목적

구매자가 주문 내역에서 환불 가능한 주문상품을 하나 이상 선택해 환불을 접수할 수 있도록 Order Service의 조회 계약과 접수 흐름을 보완한다.

Order Service의 책임은 다음으로 한정한다.

- 주문 생성 시 저장한 주문상품 금액 스냅샷을 주문 목록에 제공한다.
- 구매자, 주문, 결제, 주문상품의 관계와 환불 가능 조건을 검증한다.
- 선택한 주문상품만 `REFUND_REQUESTED`로 전이한다.
- Payment Service가 상품별 환불을 처리할 수 있도록 상품 단위 Outbox 이벤트를 저장한다.
- 상태 변경과 Outbox 저장을 하나의 트랜잭션으로 보장한다.

Payment Service가 소유하는 PG 환불 실행, 누적 환불액 검증, 결제 상태, 환불 완료·실패 처리는 이번 변경 범위에서 제외한다.

## 2. 현재 구현과 변경 필요성

현재 구현에는 선택 환불의 기반이 일부 존재한다.

- `order_product.product_amount_snapshot`에 주문 생성 당시 금액이 저장된다.
- `OrderProductStatus.REFUND_REQUESTED`와 `PAID -> REFUND_REQUESTED` 전이가 존재한다.
- `OrderRepository.findByIdWithOrderProductsForUpdate`가 주문과 주문상품을 비관적 잠금으로 조회한다.
- `POST /api/v2/orders/{orderId}/refund`가 주문상품 ID 목록을 받는다.
- 상태 변경과 `ORDER_REFUND_REQUESTED` Outbox 저장이 단일 트랜잭션에서 수행된다.

다음 차이를 보완해야 한다.

- 주문 목록이 주문상품 한 건당 응답 한 건인 평탄 구조라 한 주문의 상품이 여러 페이지에 나뉠 수 있고, `productAmount`도 응답에서 노출하지 않는다.
- 환불 요청 DTO에 `paymentId`가 없다.
- `Order`가 결제 승인 이벤트의 `paymentId`를 저장하지 않아 요청의 결제 매핑을 검증할 수 없다.
- 성공 응답이 빈 body가 아니라 `ApiResult<RefundResult>`를 반환한다.
- 하나의 합산 환불 이벤트만 저장하므로 상품별 추적 키가 없다.
- `Order.requestRefund`가 주문 상태를 `REFUND_REQUESTED`로 바꿔 같은 주문의 남은 `PAID` 상품을 후속 접수하지 못하게 한다.
- 환불 불가 사유별 HTTP 상태와 오류 코드가 요구 계약을 충분히 구분하지 못한다.

## 3. 확정 범위

### 3.1 포함

- `GET /api/v2/orders`의 주문 단위 페이지와 주문별 `products[]`
- 주문의 `totalAmount`와 주문상품별 `amount`
- `POST /api/v2/orders/{orderId}/refund` 요청 DTO와 빈 성공 응답
- 결제 승인 시 `Order.paymentId` 저장
- 선택 목록 전체의 원자적 검증
- 선택 주문상품의 `REFUND_REQUESTED` 전이
- 상품별 `ORDER_REFUND_REQUESTED` Outbox 저장
- 동일 상품 중복·동시 요청 충돌 처리
- OpenAPI와 Order Service 테스트
- Order Service 스키마 마이그레이션과 과거 결제 ID 보강 운영 조건

### 3.2 제외

- Payment Service의 PG 부분 환불 구현
- Payment Service의 결제 상태와 결제 내역 API
- Payment Service 환불 결과 이벤트의 생성·변경
- Order Service의 `PAYMENT_REFUNDED`, `PAYMENT_REFUND_FAILED` 소비 로직 변경
- Gateway 라우팅과 인증 정책 변경
- Frontend 변경

기존 환불 결과 Consumer는 이번 작업에서 수정하지 않는다. 환불 완료·실패 이후의 최종 표시와 상태 소유권은 Payment Service 계약을 따른다.

## 4. 선택한 접근법

선택한 주문상품마다 독립적인 `ORDER_REFUND_REQUESTED` Outbox 이벤트를 한 건씩 저장한다.

배치 이벤트 한 건에 상품 배열을 담는 방식은 Outbox 수를 줄일 수 있지만 Payment Service가 배열을 분해하고 부분 실패 및 상품별 멱등성을 추가로 관리해야 한다. Order Service에 별도 환불 요청·상세 테이블을 만드는 방식은 감사 이력과 재처리에 유리하지만 Payment Service가 환불을 전부 소유하는 경계에서는 중복 모델이 된다.

상품 단위 Outbox는 기존 Outbox 인프라를 재사용하면서 `paymentId + orderProductId`를 상품별 추적 키로 제공한다. 여러 Outbox가 하나의 DB 트랜잭션에 저장되므로 선택 목록 전체의 접수 원자성도 유지된다.

## 5. HTTP API 계약

### 5.1 주문 목록

```http
GET /api/v2/orders?page=1&size=20
X-User-Id: {buyerId}
```

페이지 항목 하나를 주문 한 건으로 구성하고 해당 주문의 모든 주문상품을 `products`에 포함한다. 페이지 크기와 `meta.total`은 주문 수를 기준으로 계산한다.

```json
{
  "orderId": "5ae4ad23-a910-4ad8-a406-ef8198682531",
  "orderNumber": "ORD-20260722-000001",
  "orderStatus": "COMPLETED",
  "totalAmount": 6000,
  "products": [
    {
      "orderProductId": "f7f8a8e9-bc24-4ca0-aed2-2a57228c2322",
      "productId": "d46491a9-4b4c-4bb7-a57f-7660e8926a36",
      "orderProductStatus": "PAID",
      "amount": 6000,
      "isRefundable": true,
      "downloaded": false
    }
  ]
}
```

금액과 페이지는 다음 계약을 갖는다.

- `totalAmount`는 `order.total_order_amount`, `products[].amount`는 `order_product.product_amount_snapshot`에서 조회한다.
- 두 값의 Java 타입은 `int`이며 JSON에서는 음수가 아닌 정수다.
- 무료 상품은 `0`이다.
- 현재 상품가 조회나 주문 총액 분할 계산을 사용하지 않는다.
- 누락과 `null`을 허용하지 않는다.
- 주문 페이지 조회 후 해당 페이지 주문들의 상품을 한 번에 조회해 N+1과 페이지 경계에서의 상품 누락을 방지한다.

### 5.2 선택 환불 접수

```http
POST /api/v2/orders/{orderId}/refund
X-User-Id: {buyerId}
Content-Type: application/json

{
  "paymentId": "f305e6e0-c85d-4369-bbe6-7255b202a6ec",
  "orderProductIds": [
    "f7f8a8e9-bc24-4ca0-aed2-2a57228c2322",
    "c43f3323-f6e8-4017-b9a8-e89e6cfc4868"
  ]
}
```

요청 DTO는 다음 형태로 변경한다.

```java
public record RefundOrderRequest(
    @NotNull UUID paymentId,
    @NotEmpty List<@NotNull UUID> orderProductIds
) {
    @AssertTrue(message = "orderProductIds must not contain duplicates")
    public boolean isOrderProductIdsUnique() {
        return orderProductIds == null
            || new HashSet<>(orderProductIds).size() == orderProductIds.size();
    }
}
```

성공 시 HTTP `202 Accepted`와 빈 body를 반환한다. 성공 응답은 환불 완료가 아니라 Order Service가 접수 상태와 Outbox를 원자적으로 저장했다는 뜻이다.

## 6. 결제 ID 매핑

`Order`에 nullable `paymentId`를 추가한다. 무료 주문은 Payment Service 승인 과정이 없고 환불 대상도 아니므로 `paymentId`가 `null`일 수 있다.

유료 주문의 `PAYMENT_APPROVED` 이벤트 처리 시 다음 규칙으로 결제 ID를 등록한다.

- `paymentId == null`이면 이벤트의 `paymentId`를 저장한다.
- 저장된 값과 이벤트 값이 같으면 멱등 재처리로 허용한다.
- 저장된 값과 이벤트 값이 다르면 결제 매핑 충돌로 거절하고 주문 상태를 변경하지 않는다.

DB에는 `order.payment_id uuid`와 null을 허용하는 unique index를 추가한다. 환불 요청 시 Path의 `orderId`로 주문을 잠근 뒤 `request.paymentId == order.paymentId`를 확인한다.

기존 유료 주문은 과거 `PAYMENT_APPROVED` 이벤트 재처리 또는 Payment Service가 제공한 검증된 `orderId -> paymentId` 자료를 통해 Order Service DB에 보강해야 한다. Order Service가 Payment Service DB를 직접 조회하는 방식은 사용하지 않는다. 과거 주문의 결제 ID가 보강되지 않은 상태에서는 결제 매핑을 검증할 수 없으므로 해당 주문의 환불 접수를 허용하지 않는다.

## 7. 애플리케이션 흐름

`OrderRefundService.requestRefund`는 하나의 트랜잭션에서 다음 순서로 동작한다.

1. `paymentId`, `orderProductIds`의 null, 빈 목록, null 원소, 중복을 검증한다.
2. Path의 `orderId`로 주문과 주문상품을 비관적 잠금 조회한다.
3. 주문이 없으면 `404`를 반환한다.
4. 주문 구매자와 `X-User-Id`가 다르면 `403`을 반환한다.
5. 주문의 `paymentId`가 없거나 요청 `paymentId`와 다르면 `400`을 반환한다.
6. 모든 요청 ID가 잠근 주문의 주문상품인지 확인한다. 하나라도 없으면 전체 요청을 거절한다.
7. 각 상품이 `PAID`, 미다운로드, `amount > 0`인지 확인한다.
8. 선택 상품만 `REFUND_REQUESTED`로 전이한다.
9. 선택 상품마다 상품 단위 Outbox 메시지를 생성하고 저장한다.
10. 정상적으로 commit되면 Controller가 `202 Accepted`를 반환한다.

검증 또는 Outbox 저장이 하나라도 실패하면 트랜잭션을 rollback해 어떤 주문상품 상태와 Outbox도 남기지 않는다.

## 8. 도메인 상태 정책

접수 시 주문상품 상태만 변경한다.

```text
PAID -> REFUND_REQUESTED
```

`Order.orderStatus`는 접수 시 변경하지 않는다. 주문 전체를 `REFUND_REQUESTED`로 바꾸면 같은 주문의 선택하지 않은 `PAID` 상품에 대한 후속 요청이 막히기 때문이다.

환불 가능 여부는 다음 조건을 모두 만족할 때만 `true`다.

- 주문이 결제 완료 계열 상태다.
- 주문상품 상태가 `PAID`다.
- 주문상품 스냅샷 금액이 `0`보다 크다.
- 다운로드되지 않았다.

같은 주문의 다른 상품이 `REFUND_REQUESTED`여도 남은 `PAID` 상품의 환불 가능 여부에는 영향을 주지 않는다.

## 9. Outbox 이벤트 계약

선택 상품마다 다음 payload를 가진 이벤트를 한 건씩 저장한다.

```json
{
  "refundRequestId": "0aca95cb-96b1-4527-899e-62bfa98f086f",
  "paymentId": "f305e6e0-c85d-4369-bbe6-7255b202a6ec",
  "orderId": "5ae4ad23-a910-4ad8-a406-ef8198682531",
  "orderProductId": "f7f8a8e9-bc24-4ca0-aed2-2a57228c2322",
  "buyerId": "0f265908-983f-4a60-96e7-7752c63e274f",
  "refundAmount": 6000,
  "requestedAt": "2026-07-22T10:30:00"
}
```

- topic: 기존 `order-events`
- event type: 기존 `ORDER_REFUND_REQUESTED`
- envelope `eventId`: 상품별로 새 UUID 생성
- payload `refundRequestId`: 상품별로 새 UUID 생성
- envelope `aggregateType`: 기존 `ORDER`
- envelope `aggregateId`: 기존 `orderId`
- Kafka key: 기존 `orderId`. 같은 주문의 상품별 환불 순서를 동일 partition에서 보존한다.
- `refundAmount`: `OrderProduct.productAmount` 스냅샷

상품별 메시지는 `paymentId + orderProductId`를 안정적인 비즈니스 멱등 키로 제공하고, 기존 Payment Service 소비 계약과 호환되도록 `refundRequestId`도 유지한다. 이벤트 payload에는 JPA Entity나 API DTO를 직접 사용하지 않고 전용 record를 사용한다.

## 10. 동시성 및 원자성

기존 `findByIdWithOrderProductsForUpdate`를 사용해 주문 행과 해당 주문상품 행을 잠근다.

- 동일 상품에 두 요청이 동시에 들어오면 먼저 잠금을 획득한 요청만 `PAID -> REFUND_REQUESTED`로 성공한다.
- 뒤 요청은 잠금 획득 후 `REFUND_REQUESTED`를 확인하고 `409 Conflict`를 반환한다.
- 첫 요청에서 선택하지 않은 `PAID` 상품은 잠금 해제 후 후속 요청으로 접수할 수 있다.
- 서로 다른 주문은 독립적으로 처리된다.
- 상태 변경과 모든 상품별 Outbox insert는 같은 DB 트랜잭션에 포함된다.

다운로드 확정도 동일한 주문·주문상품 잠금 흐름을 사용하므로 다운로드와 환불 요청이 경쟁하면 먼저 commit된 상태를 기준으로 뒤 요청이 거절된다.

## 11. 오류 계약

| HTTP | 오류 코드 | 조건 |
| --- | --- | --- |
| `400` | `V001` | 빈 목록, null 원소, 중복 ID |
| `400` | 신규 결제 매핑 오류 | `paymentId` 누락, 주문 결제 ID 미보강, 주문과 payment 불일치 |
| `400` | 신규 주문상품 혼합 오류 | 요청 상품 중 Path 주문에 속하지 않은 ID 존재 |
| `401` | `A003` | `X-User-Id` 누락 |
| `403` | `O008` | 다른 구매자의 주문 |
| `404` | `O001` | 주문 없음 |
| `404` | `O012` | 주문상품 없음 |
| `409` | `O017` | 다운로드됨, 무료 상품, `PAID`가 아닌 상태 |

서로 다른 주문의 상품 ID와 존재하지 않는 주문상품을 구분하려면 Path 주문 내부 검색만으로는 부족하다. 요구 계약에 따라 혼합 요청은 `400`, 실제 미존재 상품은 `404`여야 하므로 Repository에 요청 ID 전체의 존재 여부를 조회하는 읽기 포트를 추가한다. 모든 ID가 전역으로 존재하지만 Path 주문에 속하지 않으면 혼합 요청으로 판단하고, 전역 조회에서도 누락되면 `O012`를 반환한다. 이 조회는 상태 변경 전에 수행하며 실제 상태 검증과 변경은 잠근 주문 aggregate에서 처리한다.

## 12. 파일 구조

### 수정

- `src/main/java/com/prompthub/order/domain/model/Order.java`
  - `paymentId` 저장과 멱등 매핑 등록
  - 주문 전체 상태를 바꾸지 않는 선택 상품 환불 접수
- `src/main/java/com/prompthub/order/domain/model/OrderProduct.java`
  - 기존 `requestRefund` 규칙 유지 및 테스트 보강
- `src/main/java/com/prompthub/order/domain/repository/OrderRepository.java`
  - 주문상품 ID 전체 존재 여부 확인 포트
- `src/main/java/com/prompthub/order/infra/persistence/order/OrderAdapter.java`
  - 신규 존재 여부 포트 구현
- `src/main/java/com/prompthub/order/infra/persistence/order/OrderProductPersistence.java`
  - 요청 ID 존재 조회
- `src/main/java/com/prompthub/order/application/service/event/PaymentApprovedProcessor.java`
  - 승인 이벤트의 `paymentId` 등록
- `src/main/java/com/prompthub/order/application/service/refund/OrderRefundService.java`
  - 전체 검증, 선택 상태 전이, 상품별 Outbox 조율
- `src/main/java/com/prompthub/order/application/service/event/OrderEventMessageFactory.java`
  - 상품 단위 환불 요청 메시지 생성
- `src/main/java/com/prompthub/order/presentation/OrderController.java`
  - 기존 경로 유지, 빈 `202` 응답
- `src/main/java/com/prompthub/order/presentation/dto/RefundOrderRequest.java`
  - `paymentId` 추가
- `src/main/java/com/prompthub/order/application/dto/OrderListProjection.java`
  - 주문 페이지용 주문 단위 projection으로 변경
- `src/main/java/com/prompthub/order/application/dto/OrderListProductProjection.java`
  - 페이지 주문들의 주문상품 일괄 조회 projection
- `src/main/java/com/prompthub/order/presentation/dto/response/OrderListResponse.java`
  - 주문 번호, 총액, `products`를 갖는 주문 단위 응답으로 변경
- `src/main/java/com/prompthub/order/presentation/dto/response/OrderListProductResponse.java`
  - 주문상품 상태, 스냅샷 금액, 환불 가능 여부를 포함하는 목록 전용 응답
- `src/main/java/com/prompthub/order/application/service/order/OrderQueryService.java`
  - 주문과 주문상품 projection을 주문 ID로 그룹화해 응답에 매핑
- `src/main/java/com/prompthub/order/global/exception/ErrorCode.java`
  - 결제 매핑과 혼합 주문상품 오류 구분
- `src/main/resources/db/migration/V7__add_order_payment_id.sql`
  - nullable `payment_id`와 unique index

### 신규

- `src/main/java/com/prompthub/order/infra/messaging/kafka/event/OrderRefundRequestedPayload.java`
  - Payment Service로 전달할 상품 단위 이벤트 payload

### 테스트

- `src/test/java/com/prompthub/order/domain/model/OrderTest.java`
- `src/test/java/com/prompthub/order/application/service/event/PaymentApprovedProcessorTest.java`
- `src/test/java/com/prompthub/order/application/service/refund/OrderRefundServiceTest.java`
- `src/test/java/com/prompthub/order/application/service/order/DownloadRefundConcurrencyIntegrationTest.java`
- `src/test/java/com/prompthub/order/application/service/order/OrderQueryServiceTest.java`
- `src/test/java/com/prompthub/order/infra/persistence/OrderLockPersistenceTest.java`
- `src/test/java/com/prompthub/order/infra/messaging/kafka/OutboxRelayIntegrationTest.java`
- `src/test/java/com/prompthub/order/presentation/OrderControllerTest.java`
- `src/test/java/com/prompthub/order/LocalFlywayConfigurationTest.java`

## 13. 테스트 전략

### 도메인

- 승인 결제 ID 최초 등록, 같은 ID 재등록 허용, 다른 ID 재등록 거절
- 선택 상품만 `REFUND_REQUESTED` 전이
- 주문 전체 상태 유지
- 다른 상품이 접수 중이어도 남은 `PAID` 상품 후속 접수 허용
- 무료, 다운로드, `REFUND_REQUESTED`, `REFUNDED` 상품 거절

### 애플리케이션 서비스

- 단일·다중 선택 접수 시 상품별 금액의 Outbox 생성
- 요청 `paymentId`와 주문 매핑 검증
- 구매자 불일치, 혼합 주문, 미존재 상품, 중복 ID 검증
- 잘못된 상품 하나가 포함되면 상태와 Outbox 전체 rollback
- Outbox 두 번째 저장 실패 시 첫 Outbox와 모든 상태 rollback

### 웹 계약

- 정확한 Path와 body로 `202` 및 빈 body 반환
- `paymentId`, 상품 목록, 사용자 헤더 검증
- 오류 조건별 `400`, `401`, `403`, `404`, `409`
- 구 요구사항의 `/api/v2/orders/refunds`가 노출되지 않음

### 조회

- 페이지의 각 항목이 주문 한 건이고 주문별 모든 상품이 `products`에 포함됨
- `meta.total`이 주문상품 수가 아닌 주문 수를 반환함
- 유료·무료 상품 모두 `products[].amount`가 null 없이 반환됨
- 상품 현재 가격과 무관하게 스냅샷 금액 반환
- 한 상품이 접수 중이어도 같은 주문의 다른 `PAID` 상품은 `isRefundable=true`

### 동시성·영속성·Outbox

- 동일 상품 동시 요청 중 하나만 성공
- 서로 다른 상품의 순차 후속 접수 성공
- 다운로드와 환불 경쟁에서 하나만 성공
- Flyway V7 적용 및 nullable unique 결제 ID 제약
- Relay가 상품별 key와 payload를 발행

## 14. 배포 및 운영 순서

1. Order Service DB에 nullable `payment_id` 컬럼과 unique index를 배포한다.
2. 결제 승인 Processor가 신규 유료 주문에 `paymentId`를 저장하도록 배포한다.
3. 기존 유료 주문의 `orderId -> paymentId`를 신뢰 가능한 자료나 승인 이벤트 재처리로 보강한다.
4. 보강 결과에서 결제 ID 누락·중복·주문 불일치가 없는지 검증한다.
5. 주문 목록 `amount`와 선택 환불 API, 상품별 Outbox를 활성화한다.
6. Payment Service가 새 상품 단위 이벤트를 소비할 준비가 된 뒤 Frontend 선택 환불 UI를 배포한다.

과거 결제 ID를 신뢰할 수 있게 보강하지 못하면 기존 주문에 대한 선택 환불 기능 배포를 차단한다. 현재 상품 가격으로 결제 또는 환불 정보를 추정하지 않는다.

## 15. 완료 기준

- 모든 주문 목록 항목이 주문 한 건을 나타내고 해당 주문의 전체 주문상품을 `products`로 반환한다.
- 주문은 `totalAmount`, 각 상품은 주문 당시 스냅샷 `amount`를 반환한다.
- `POST /api/v2/orders/{orderId}/refund`가 `paymentId + orderProductIds`를 원자적으로 접수한다.
- 잘못된 항목이 하나라도 있으면 어떤 상태와 Outbox도 변경되지 않는다.
- 선택 상품마다 독립적인 Outbox 이벤트가 저장된다.
- 동일 상품 동시 요청은 하나만 성공한다.
- 선택하지 않은 `PAID` 상품은 후속 접수할 수 있다.
- 성공 응답은 `202 Accepted`와 빈 body다.
- Order Service의 환불 실행·결제 상태·환불 결과 처리 범위가 확장되지 않는다.
