# 상품 단위 환불 정책 질문 답변

> 작성 기준: 2026-07-11 현재 프로젝트 코드
>
> 제약 조건: **Payment Service는 수정하지 않는다.**

## 1. 결론

장기적인 도메인 방향은 주문 전체 상태와 개별 주문 상품 상태를 분리하는 것이 맞다. 그러나 현재 Payment Service는 결제 전체 환불만 지원한다. 따라서 이번 범위에서 Order Service만 수정하여 **상품 단위 환불 요청, 상품별 환불 결과 처리, 환불 상태 재조회**를 완성할 수는 없다.

현재 Payment Service가 제공하는 환불 계약은 다음과 같다.

- 구매자가 `POST /api/v2/payments/{paymentId}/refund`를 호출한다.
- 환불 요청에는 `paymentId`와 Gateway가 전달한 `X-User-Id`만 사용한다.
- 환불 금액은 `Payment.totalAmount` 전체이다.
- Payment Service가 PG 환불에 성공하면 `PAYMENT_REFUNDED` 이벤트를 발행한다.
- 결과 이벤트에는 `paymentId`, `orderId`, `userId`, `amount`, `refundedAt`만 포함된다.
- Order Service는 결과 이벤트를 받으면 주문과 모든 주문 상품을 `REFUNDED`로 변경한다.

반면 다음 계약은 존재하지 않는다.

- Order Service가 발행하는 `REFUND_REQUESTED` 이벤트의 Payment Service 소비자
- `productId` 또는 `orderProductId`를 지정하는 상품 단위 환불 API
- `refundRequestId`를 전달하거나 조회하는 계약
- 환불 실패 결과 이벤트
- 환불 상태 조회용 gRPC 메서드
- 상품별 환불 완료 결과 이벤트

따라서 아래 답변은 **현재 계약으로 가능한 결정**과 **Payment Service를 수정하지 않으면 구현할 수 없는 항목**을 구분한다.

## 2. Payment Service 기존 계약

### 질문 1. Payment Service는 이미 `REFUND_REQUESTED` 이벤트를 소비하도록 구현되어 있나요?

아니다. Payment Service의 Kafka 소비자는 `order-events`의 `ORDER_CREATED`만 처리한다. 환불은 Payment Service의 HTTP API에서 시작되며, Order Service가 발행하는 `REFUND_REQUESTED` 이벤트를 소비하는 코드는 없다.

**결정:** 이번 범위에서 Order Service가 `REFUND_REQUESTED` 이벤트를 발행하는 설계는 사용하지 않는다.

**구현 가능 여부:** Payment Service를 수정하지 않으면 불가능하다.

### 질문 2. `REFUND_REQUESTED` 이벤트가 요구하는 payload 필드는 무엇인가요?

해당 이벤트 계약 자체가 없으므로 요구 필드도 정의되어 있지 않다.

상품 단위 환불을 설계한다면 `refundRequestId`, `paymentId`, `orderId`, `orderProductId`, `productId`, `userId`, `refundAmount`, `reason`, `requestedAt` 등이 필요하지만 이는 미래 계약 제안일 뿐 현재 구현으로 사용할 수 없다.

**결정:** 존재하지 않는 payload를 Order Service에서 임의로 정의하지 않는다.

**구현 가능 여부:** Payment Service를 수정하지 않으면 불가능하다.

### 질문 3. Payment Service가 발행하는 환불 결과 이벤트 타입은 무엇인가요?

현재 성공 결과인 `PAYMENT_REFUNDED`만 발행한다. `PAYMENT_REFUND_FAILED` 또는 이에 대응하는 실패 이벤트는 없다.

PG 환불 실패 시 Payment는 `PAID`로 복원되고 Refund는 `FAILED`로 변경되지만, Order Service에는 실패 사실을 전달하지 않는다.

**결정:** Order Service는 `PAYMENT_REFUNDED`만 확정적인 환불 완료 결과로 처리한다. 실패 결과를 추측해서 만들지 않는다.

**구현 가능 여부:** 성공 처리만 가능하며 실패 결과 연동은 불가능하다.

### 질문 4. 환불 결과 이벤트에 필요한 값이 모두 포함되나요?

아니다. 현재 `PAYMENT_REFUNDED` payload는 다음 값만 포함한다.

```text
paymentId
orderId
userId
amount
refundedAt
```

`refundRequestId`, `orderProductId`, `productId`, `status`, `failureCode`, `failureReason`은 포함되지 않는다. 따라서 Order Service는 이벤트만으로 어떤 상품이 환불되었는지 식별할 수 없다.

**결정:** 현재 이벤트는 주문 전체 환불 결과로만 해석한다.

**구현 가능 여부:** 상품 단위 결과 처리는 불가능하다.

### 질문 5. Payment Service에 환불 상태 조회용 gRPC 메서드가 존재하나요?

존재하지 않는다. 현재 확인되는 gRPC 연동은 Payment Service가 주문 결제 정보를 조회하기 위해 Order Service를 호출하는 방향이다. Order Service가 Payment Service에 환불 상태를 조회하는 계약은 없다.

**결정:** 2분, 5분, 10분, 20분 시점에 gRPC로 환불 상태를 확인하는 스케줄은 이번 범위에서 구현하지 않는다.

**구현 가능 여부:** Payment Service를 수정하지 않으면 불가능하다.

### 질문 6. 환불 상태 조회 기준은 무엇인가요?

조회 메서드가 없으므로 조회 기준도 없다. Payment Service 내부에는 `Refund.id`가 있지만 외부 계약에 노출되지 않으며, Order Service가 그 값을 알 방법도 없다.

**결정:** `refundRequestId`, `paymentId + productId`, `paymentId + orderProductId` 중 어느 것도 현재 서비스 간 조회 키로 채택할 수 없다.

**구현 가능 여부:** 불가능하다.

## 3. 환불 가능 조건

### 질문 7. 환불 요청자는 주문 구매자 본인만 허용하나요?

그렇다. 현재 Payment Service는 요청의 `X-User-Id`와 `Payment.userId`가 일치하는지 검증하며, `BUYER` 역할도 요구한다.

Order Service에 환불 진입점을 추가한다면 `Order.buyerId`와 `X-User-Id`도 먼저 검증해야 한다. 다만 현재 실제 환불 요청 API는 Payment Service에 있다.

**결정:** 구매자 본인만 허용한다.

**구현 가능 여부:** 현재 전체 환불 흐름에서 가능하다.

### 질문 8. `paymentId`와 주문 및 상품의 연관성을 검증해야 하나요?

전체 환불에서는 `paymentId`가 `OrderPayment.paymentId`와 일치하고 해당 결제가 주문에 연결되어 있어야 한다. 현재 `order_payment`는 `order_id`와 `payment_id`에 각각 유일성 제약을 둔다.

상품 단위 환불이라면 해당 주문에 `orderProductId` 또는 `productId`가 포함되었는지도 검증해야 한다. 그러나 Payment Service의 현재 환불 요청에는 상품 식별자가 없다.

**결정:** 현재는 결제와 주문의 일치만 유효한 검증 기준이다. 상품 포함 여부 검증은 상품 단위 계약이 생기기 전에는 실제 PG 환불과 연결할 수 없다.

**구현 가능 여부:** 주문 전체 검증은 가능하고 상품 단위 검증은 실효성 있는 연동이 불가능하다.

### 질문 9. `OrderProductStatus.PAID`인 상품만 최초 환불 요청을 허용하나요?

도메인 정책으로는 맞다. 현재 `OrderProduct.isRefundable()`도 `PAID` 상태를 요구한다.

다만 현재 프로젝트는 별도의 `OrderProductStatus` enum 없이 주문과 주문 상품이 같은 `OrderStatus` enum을 공유한다. 상품별 `REFUND_REQUESTED` 상태도 존재하지 않는다.

**결정:** 환불 가능 여부 판단은 `PAID`인 주문 상품에 한정한다. 이번 범위에서는 상품별 환불 요청 상태 전이를 추가하지 않는다.

**구현 가능 여부:** 사전 판단은 가능하지만 상품 단위 환불 실행은 불가능하다.

### 질문 10. `downloaded = true`인 상품은 환불을 차단하나요?

그렇다. 현재 `OrderProduct.isRefundable()`은 `PAID && !downloaded`일 때만 `true`를 반환한다. 프로젝트 QA 문서도 다운로드된 상품의 환불 차단을 정책으로 명시한다.

다만 실제 전체 환불 HTTP API는 Payment Service에 있고 Payment Service에는 다운로드 여부가 없다. 따라서 화면과 Order Service에서 환불 불가로 표시할 수는 있어도 사용자가 Payment Service 환불 API를 직접 호출하는 경우 이 정책을 강제할 수 없다.

**결정:** 다운로드된 상품은 환불 불가이다. 이 정책을 확실히 강제하려면 Gateway 라우팅 또는 Payment Service의 Order 조회 계약이 필요하지만, 이는 현재 제약 밖이다.

**구현 가능 여부:** Order Service 내부 판단은 가능하지만 현재 전체 환불 API에 대한 종단 간 강제는 보장할 수 없다.

### 질문 11. 무료 상품은 환불 요청 대상에서 제외하나요?

그렇다. 환불할 결제 금액이 없으므로 무료 상품은 PG 환불 대상에서 제외하는 것이 타당하다.

현재 `OrderProduct.productAmount`는 주문 시점 가격을 보관하지만, 환불 API는 상품별 금액을 받지 않고 Payment 전체 금액을 사용한다.

**결정:** `productAmount == 0`인 상품은 환불 요청 대상이 아니라 단순 구매 취소 또는 접근 권한 정책의 대상으로 다룬다.

**구현 가능 여부:** 환불 가능 여부 계산에는 반영할 수 있지만 상품 단위 PG 환불과 연결할 수는 없다.

## 4. 환불 금액

### 질문 12. 환불 금액으로 `productAmountSnapshot` 전체를 사용해도 되나요?

상품 단위 환불 계약이 있다면 현재 할인, 쿠폰, 포인트 배분 정보가 없으므로 `OrderProduct.productAmount`를 사용하는 것이 가장 일관적이다.

하지만 현재 Payment Service는 `Payment.totalAmount` 전체만 환불하며 `orderProductId`와 상품 금액을 입력받지 않는다.

**결정:** 현재 실행 가능한 환불 금액은 결제 전체 금액이다. `productAmountSnapshot`은 미래 상품 단위 환불 금액의 기준으로만 문서화한다.

**구현 가능 여부:** 전체 환불은 가능하고 상품 금액 환불은 불가능하다.

### 질문 13. 이번 범위에서 할인 배분을 제외하나요?

그렇다. 현재 주문 상품에는 할인·쿠폰·포인트의 상품별 배분 결과가 저장되지 않는다. 존재하지 않는 할인 배분 규칙을 이번 범위에서 추정하지 않는다.

**결정:** 할인 배분은 범위에서 제외한다. 미래에 할인 기능을 도입할 때는 주문 시점의 상품별 실결제 금액을 별도 스냅샷으로 저장해야 한다.

**구현 가능 여부:** 정책 결정은 가능하지만 현재 상품 단위 환불 자체는 불가능하다.

## 5. 환불 실패 후 재요청

### 질문 14. `REFUND_FAILED` 상태에서 구매자가 다시 요청할 수 있나요?

Order Service에는 `REFUND_FAILED` 상태가 없다. Payment Service는 환불 실패 시 Payment를 `PAID`로 복원하고 Refund를 `FAILED`로 변경한다. 환불 API가 `PAID` 상태의 요청을 허용하므로 사용자가 API를 다시 호출하는 것은 가능하다.

다만 재요청 정책과 횟수 제한이 명시되어 있지 않고, 요청할 때마다 새로운 Refund가 생성된다. `findByPaymentId()`는 단건 조회를 전제로 하므로 같은 Payment에 Refund가 여러 건 생기면 조회 결과의 유일성도 보장되지 않는다. 따라서 현재 동작을 안전한 재요청 지원으로 간주할 수 없다.

**결정:** Order Service에서는 자동 또는 사용자 재요청 기능을 추가하지 않는다. Payment Service API의 재호출 가능 여부와 별개로, 공식 재요청 정책이 확정된 것으로 보지 않는다.

**구현 가능 여부:** Order Service만 수정해서는 재요청할 수 없다.

### 질문 15. 재요청 시 기존 ID와 새 ID 중 무엇을 사용하나요?

현재 재요청 계약이 없고 Payment Service가 생성한 `Refund.id`도 Order Service에 전달되지 않는다.

**결정:** 이번 범위에서는 적용 대상이 아니다. 미래에 재요청을 지원한다면 각 시도를 추적할 수 있도록 새로운 `refundRequestId`를 발급하는 방식이 적절하다.

**구현 가능 여부:** 불가능하다.

## 6. 환불 결과 확인 불가 상태

### 질문 16. `REFUND_UNKNOWN`이 되면 자동 조회를 종료하나요?

현재 `REFUND_UNKNOWN` 상태와 환불 조회 기능이 모두 없다. 따라서 자동 조회를 시작하거나 종료할 수 없다.

**결정:** 이번 범위에서는 `REFUND_UNKNOWN`을 추가하지 않는다. 실제 결제 상태를 확인할 계약 없이 Order Service만 해당 상태를 만들면 영구적으로 해소할 방법이 없기 때문이다.

**구현 가능 여부:** 불가능하다.

### 질문 17. `REFUND_UNKNOWN`은 관리자 재확인 API나 스케줄러 대상으로 두나요?

현재 Payment Service의 조회 계약이 없으므로 관리자 API나 스케줄러를 만들어도 확인할 데이터 원천이 없다.

**결정:** 이번 범위에서 관리자 재확인 API와 재조정 스케줄러를 구현하지 않는다.

**구현 가능 여부:** 불가능하다.

### 질문 18. 프론트에는 `REFUND_UNKNOWN`을 어떻게 노출하나요?

상태 자체를 도입하지 않으므로 프론트 노출도 하지 않는다. 현재 환불 API의 `202 Accepted` 이후에는 별도 조회 계약이 없는 한 “환불 요청 접수” 정도만 표현할 수 있다. Order Service가 `PAYMENT_REFUNDED`를 수신한 뒤에는 `REFUNDED`를 노출할 수 있다.

**결정:** 존재하지 않는 상태를 API에 추가하지 않는다.

**구현 가능 여부:** `REFUND_UNKNOWN` 노출은 적용 대상이 아니다.

## 7. 스케줄링

### 질문 19. 환불 확인 대상은 어떤 방식으로 관리하나요?

이번 범위에서는 별도 관리하지 않는다. DB, Redis Sorted Set, 기존 주문 만료 스케줄러 중 어떤 방식을 사용하더라도 Payment Service의 환불 상태를 조회할 수 없으므로 정확한 확인이 불가능하다.

Payment Service 자체에는 30분 이상 `REFUNDING`인 결제를 10분 주기로 재시도하는 스케줄러가 이미 있다. 이 동작은 Payment Service 내부 책임이며 Order Service에서 중복 구현하지 않는다.

**결정:** Order Service 환불 확인 스케줄러를 만들지 않고 `PAYMENT_REFUNDED` 결과 이벤트를 기다린다.

**구현 가능 여부:** 상태 확인 스케줄러는 불가능하다.

## 8. 이벤트와 트랜잭션

### 질문 20. 상품 상태 변경과 `REFUND_REQUESTED` Outbox 저장을 같은 트랜잭션으로 처리하나요?

일반적인 Outbox 원칙으로는 같은 트랜잭션이 맞다. 그러나 Payment Service가 해당 이벤트를 소비하지 않으므로 이번 범위에서 이를 구현하면 처리되지 않는 이벤트와 `REFUND_REQUESTED` 상태만 남는다.

**결정:** 이번 범위에서는 상품 상태 변경과 요청 Outbox를 추가하지 않는다. 현재 사용 중인 Outbox는 환불 완료 이벤트를 반영한 뒤 `ORDER_REFUND`를 후속 발행할 때만 사용한다.

**구현 가능 여부:** 기술적으로 저장은 가능하지만 기능 완성은 불가능하므로 구현하지 않는다.

### 질문 21. 동일 요청에는 새 Outbox를 만들지 않고 기존 요청 정보를 반환하나요?

그 방식이 올바른 멱등성 정책이지만 현재 Order Service에는 환불 요청 API, `refundRequestId`, 요청 Outbox가 없다.

현재 수신 이벤트 멱등성은 `eventId + consumerGroup`으로 관리한다. 이는 API 요청 멱등성과 별개의 문제이며 이미 `PAYMENT_REFUNDED` 중복 소비 방지에 사용된다.

**결정:** 현재 이벤트 소비 멱등성은 유지한다. 상품 단위 API 멱등성은 이번 범위에서 구현하지 않는다.

**구현 가능 여부:** 수신 이벤트 멱등성만 가능하다.

## 9. 환불 완료 후 처리

### 질문 22. 환불 완료 시 콘텐츠 접근 권한을 즉시 제거하나요?

그렇다. 현재 콘텐츠 접근 가능 여부는 주문 상품이 `PAID`인지 여부를 기준으로 판단하므로 `REFUNDED`로 전환되면 접근 권한이 제거되어야 한다.

현재 `PAYMENT_REFUNDED`는 주문 전체를 환불하므로 모든 주문 상품의 접근 권한이 함께 제거된다.

**결정:** 환불 완료 이벤트를 반영하는 트랜잭션에서 상태를 변경하고, 이후 조회부터 콘텐츠 접근을 차단한다.

**구현 가능 여부:** 전체 환불 기준으로 가능하다.

### 질문 23. 환불 완료 후 `ORDER_REFUND` 이벤트를 Settlement Service에 상품 단위로 발행하나요?

현재 Order Service는 환불 완료 후 주문의 모든 상품을 포함한 `ORDER_REFUND` 이벤트를 Outbox로 발행한다. Settlement Service의 payload도 상품별 `orderProductId`, `productId`, `sellerId`, `refundAmount`를 처리할 구조를 갖고 있다.

그러나 이벤트 타입 계약이 일치하지 않는다. Order Service는 `ORDER_REFUND`를 발행하고 Settlement Service는 `ORDER_REFUNDED`만 인식한다. 또한 Settlement listener는 기본적으로 비활성화되어 있다.

**결정:** 이번 문서에서는 현재 불일치를 명시한다. Payment Service를 수정하지 않는 조건과 별개로, 정산 반영을 정상화하려면 Order와 Settlement 사이의 이벤트 타입을 하나로 통일하고 배포 설정에서 listener를 활성화해야 한다.

**구현 가능 여부:** payload 발행은 가능하지만 현재 상태로는 Settlement Service가 정상 소비하지 못한다.

### 질문 24. 부분 환불 이벤트에는 해당 상품 한 개만 포함하나요?

이상적인 상품 단위 환불에서는 환불 완료된 상품만 포함하는 것이 맞다. 그러나 현재 `PAYMENT_REFUNDED`에는 상품 식별자가 없고 Order Service의 `OrderRefundPayload.from()`은 주문의 모든 상품을 포함한다.

**결정:** 현재 이벤트는 전체 환불이므로 모든 상품을 포함한다. 상품 한 개만 포함하는 부분 환불 이벤트는 만들지 않는다.

**구현 가능 여부:** 상품 단위 이벤트는 불가능하다.

### 질문 25. 모든 상품 환불 시에만 `Order.refundedAt`을 기록하나요?

장기 설계로는 맞다. 개별 상품 환불 완료 시 `OrderProduct.refundedAt`만 기록하고, 모든 상품이 환불된 경우에만 `Order.refundedAt`을 기록해야 한다.

하지만 현재는 전체 환불만 지원하므로 `Order.refund(refundedAt)`이 주문 상태와 모든 주문 상품 상태를 한 번에 `REFUNDED`로 바꾸고 양쪽에 동일한 환불 시각을 기록한다.

**결정:** 현재 전체 환불 동작을 유지한다. 부분 환불을 전제로 한 시간 기록 규칙은 이번 범위에서 구현하지 않는다.

**구현 가능 여부:** 전체 환불 시각 기록만 가능하다.

## 10. 상태 모델에 대한 결정

장기적으로는 `OrderStatus`와 `OrderProductStatus`를 분리하는 것이 바람직하다.

```text
OrderStatus
- PENDING
- PAID
- FAILED
- CANCELED
- PARTIALLY_REFUNDED
- REFUNDED

OrderProductStatus
- PENDING
- PAID
- FAILED
- CANCELED
- REFUND_REQUESTED
- REFUNDED
- REFUND_FAILED
- REFUND_UNKNOWN
```

그러나 이번 제약에서는 위 상태 전체를 구현하지 않는다. Payment Service가 상품 단위 요청과 결과를 제공하지 않기 때문에 `REFUND_REQUESTED`, `REFUND_FAILED`, `REFUND_UNKNOWN`, `PARTIALLY_REFUNDED`를 추가해도 실제 결제 상태와 신뢰성 있게 동기화할 수 없다.

이번 범위의 상태 정책은 다음과 같다.

```text
PAYMENT_REFUNDED 수신 전
→ 기존 PAID 유지

PAYMENT_REFUNDED 수신
→ Order REFUNDED
→ 모든 OrderProduct REFUNDED

환불 실패 또는 결과 이벤트 유실
→ Order Service에서 판단하거나 상태를 변경하지 않음
```

## 11. 멱등성에 대한 결정

`paymentId + productId`는 현재 상품 단위 환불의 멱등성 키로 사용할 수 없다.

- 현재 환불 요청에 `productId`가 없다.
- Payment Service가 상품 단위 요청을 소비하지 않는다.
- Payment Service가 생성한 `Refund.id`를 Order Service에 전달하지 않는다.
- 현재 코드상 한 주문 안에서 동일 `productId`의 중복을 DB 제약으로 보장하지 않는다.

따라서 이번 범위에서 유효한 멱등성 기준은 수신한 결제 이벤트의 `eventId + consumerGroup`뿐이다. 미래 상품 단위 환불에서는 Order Service가 생성한 `refundRequestId`를 요청 단위 식별자로 사용하고, 별도의 유일성 제약과 원자적 상태 전이를 설계해야 한다.

## 12. 환불 상태 확인 시점에 대한 결정

2분, 5분, 10분, 20분 단계 조회는 적용하지 않는다. 이는 Payment Service의 상태 조회 계약이 있다는 전제에서만 유효하다.

현재 프로젝트에서는 다음 방식만 가능하다.

```text
구매자 → Payment Service 전체 환불 API 호출
Payment Service → PG 환불 처리
Payment Service → PAYMENT_REFUNDED 발행
Order Service → 전체 주문 환불 완료 반영
```

20분이 지났다는 이유만으로 `REFUND_FAILED` 또는 `REFUND_UNKNOWN`으로 변경하지 않는다. 이벤트 지연과 실제 PG 실패를 Order Service가 구분할 수 없기 때문이다.

## 13. 최종 구현 범위

### Order Service만으로 가능한 항목

- 구매자와 주문 소유 관계 검증
- 주문 상품의 `PAID` 여부 확인
- `downloaded = true`인 상품을 환불 불가로 계산
- 무료 상품을 환불 대상에서 제외하는 정책 계산
- `PAYMENT_REFUNDED` 이벤트의 중복 소비 방지
- 전체 주문과 모든 주문 상품의 `REFUNDED` 전환
- 전체 환불 완료 후 콘텐츠 접근 차단
- 전체 환불 후 정산용 Outbox 이벤트 저장

### Payment Service를 수정하지 않으면 불가능한 항목

- 상품 단위 환불 실행
- `REFUND_REQUESTED` 이벤트 기반 환불 요청
- `refundRequestId` 기반 요청 추적
- 상품별 환불 금액 전달
- 상품별 환불 성공 또는 실패 이벤트 처리
- 환불 실패 코드와 사유 수신
- 환불 상태 gRPC 조회
- 2분·5분·10분·20분 재확인 스케줄
- `REFUND_UNKNOWN` 상태의 자동 해소
- 실패 유형별 재요청
- `PARTIALLY_REFUNDED`의 신뢰성 있는 계산
- 환불 완료 상품 한 개만 포함하는 정산 이벤트

## 14. 최종 답변

이번 프로젝트에서는 **주문 전체 상태와 주문 상품 상태를 분리하는 방향은 장기 설계로 채택하되, 상품 단위 환불 상태와 부분 환불 기능은 구현하지 않는다.**

Payment Service를 수정하지 않는 조건에서는 현재의 결제 전체 환불 흐름만 완결되어 있다. Order Service는 `PAYMENT_REFUNDED` 성공 이벤트를 수신했을 때만 주문 전체와 모든 주문 상품을 `REFUNDED`로 변경한다. 실패, 처리 중, 결과 불명 상태는 확인할 계약이 없으므로 임의로 판단하지 않는다.

상품 단위 환불이 실제 요구사항이라면 향후 별도 작업에서 Payment Service의 다음 계약을 먼저 추가해야 한다.

1. 상품 단위 환불 요청 API 또는 `REFUND_REQUESTED` Kafka consumer
2. `refundRequestId`와 `orderProductId`를 포함한 요청 모델
3. 상품별 성공·실패 결과 이벤트
4. 환불 상태 조회 gRPC API
5. 재시도 가능 여부를 포함한 실패 코드 정책

이 계약이 준비되기 전에는 Order Service에 상품별 환불 진행 상태만 선행 추가하지 않는다.
