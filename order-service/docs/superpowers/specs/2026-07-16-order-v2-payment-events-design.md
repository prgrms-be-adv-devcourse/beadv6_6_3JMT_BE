# Order Service 다건 결제 성공·실패 이벤트 처리 설계

## 1. 목적

GitHub 이슈 `#369`의 범위로 Payment Service가 한 번 발행한 결제 결과를 여러 판매자 주문에 원자적으로 반영한다. 결제 성공·실패의 도착 순서와 중복 수신에도 주문 상태가 역전되지 않게 하고, 성공 시 주문별 `ORDER_PAID` Outbox와 장바구니 변경을 같은 DB 트랜잭션으로 처리한다.

## 2. 범위

변경 대상은 `order-service/**`와 해당 모듈 내부 문서로 제한한다.

포함 범위:

- 다건 `PAYMENT_APPROVED`, `PAYMENT_FAILED` payload
- 주문과 주문상품의 결정적 비관적 잠금
- 다건 결제 성공·실패 Processor
- processed event, 주문 상태, 장바구니, Outbox의 트랜잭션 원자성
- 성공 후 늦은 실패 무시와 실패 후 성공 복구
- 성공 주문별 `ORDER_PAID` Outbox
- `PaymentApprovedProcessor`의 `OrderPayment` 저장 제거
- `PAYMENT_CANCELED` Handler, Processor, payload, event type 제거
- Redis 주문 만료 정보의 커밋 이후 정리
- 단위, JPA, 트랜잭션, Embedded Kafka 테스트

제외 범위:

- `payment-service`, `product-service`, `common-module`, Gateway 등 다른 모듈 변경
- Payment Service의 다건 이벤트 발행 구현
- `OrderPayment` Entity·Repository·Projection·결제 내역 API 전체 제거
- Checkout 전체 주문 누락 여부와 승인 금액 합계의 엄격한 대조
- 다건 환불 처리

`OrderPayment` 관련 코드 전체 제거와 결제 내역 API 정리는 후속 이슈 `#373`에서 수행한다. 이번 이슈에서는 새로운 결제 승인 처리기가 `OrderPayment`를 저장하지 않도록 쓰기 흐름만 제거한다.

## 3. 외부 계약

### 3.1 PAYMENT_APPROVED

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

Order Service의 typed payload는 다음 구조를 사용한다.

```java
public record PaymentApprovedPayload(
    UUID paymentId,
    UUID buyerId,
    int totalAmount,
    List<PaymentApprovedOrderPayload> orders,
    LocalDateTime approvedAt
) {
}

public record PaymentApprovedOrderPayload(
    UUID orderId,
    List<UUID> orderProductIds
) {
}
```

### 3.2 PAYMENT_FAILED

```json
{
  "paymentId": "UUID",
  "orderIds": ["UUID", "UUID"],
  "failureCode": "PAY_FAILED",
  "failureReason": "PG 결제 실패",
  "failedAt": "2026-07-16T10:00:05"
}
```

```java
public record PaymentFailedPayload(
    UUID paymentId,
    List<UUID> orderIds,
    String failureCode,
    String failureReason,
    LocalDateTime failedAt
) {
}
```

Payment Service가 이 계약으로 다건 이벤트를 발행하는 것은 Order Service 배포의 외부 선행 조건이며 이번 구현에 포함하지 않는다.

## 4. 컴포넌트 경계

기존 Kafka 수신 구조를 유지한다.

```text
PaymentEventConsumer
  -> PaymentEventRouter
    -> PaymentApprovedEventHandler / PaymentFailedEventHandler
      -> PaymentApprovedProcessor / PaymentFailedProcessor
        -> OrderRepository
        -> CartRepository
        -> OutboxEventAppender
        -> ProcessedEventService
```

각 컴포넌트의 책임은 다음과 같다.

- `PaymentEventConsumer`: envelope 역직렬화, 지원 event type 판별, 수동 ACK
- `PaymentEventRouter`: event type에 맞는 Handler 선택
- Handler: `JsonNode`를 typed payload로 변환하고 Processor에 위임
- Processor: 트랜잭션 경계, 검증 순서, 상태 변경, 장바구니와 Outbox 조율
- `Order`, `OrderProduct`: 허용된 상태 전이 보장
- `OrderRepository`와 persistence adapter: 결정적인 순서로 주문·상품 잠금
- `ProcessedEventService`: `eventId + consumerGroup` 처리 이력 저장
- `OutboxEventAppender`: 주문별 `ORDER_PAID` Outbox 저장
- Redis listener: DB 커밋 이후 만료·재시도 정보 정리

Controller, HTTP API, gRPC 계약은 변경하지 않는다.

## 5. 결정적 잠금 설계

`OrderRepository`에 다건 잠금 조회 계약을 추가한다.

```java
List<Order> findAllByIdsWithOrderProductsForUpdate(List<UUID> orderIds);
```

JPA 잠금 구현은 `OrderAdapter` 내부에 감춘다. Adapter는 다음 순서를 지킨다.

1. Processor 검증을 통과한 주문 ID를 방어적으로 중복 제거한 후 UUID 자연 순서로 정렬한다.
2. 정렬된 주문 ID 순서대로 `Order` Root를 `PESSIMISTIC_WRITE`로 잠근다.
3. 각 주문의 `OrderProduct`를 상품 UUID 순서로 `PESSIMISTIC_WRITE`로 잠근다.
4. 잠긴 주문과 상품을 초기화된 Aggregate로 반환한다.

주문과 컬렉션을 한 번에 Fetch Join하는 단일 잠금 쿼리는 사용하지 않는다. 컬렉션 Fetch Join과 비관적 잠금 조합의 Hibernate·DB별 동작 차이를 피하고 실제 잠금 순서를 코드로 명시하기 위함이다.

Processor는 요청한 고유 주문 ID 수와 조회된 주문 수를 비교한다. 하나라도 없으면 상태 변경 전에 `ORDER_NOT_FOUND`로 실패한다. 잠금 획득 도중 실패해도 같은 트랜잭션이 종료되면서 이미 획득한 잠금이 해제된다.

## 6. 공통 payload 검증

DB 상태를 변경하기 전에 다음을 검증한다.

- `paymentId`와 이벤트별 필수 시각이 존재한다.
- 주문 목록이 `null` 또는 빈 목록이 아니다.
- 주문 ID가 모두 존재하고 중복되지 않는다.
- 승인 이벤트의 `buyerId`가 존재한다.
- 승인 이벤트의 각 주문상품 목록이 `null` 또는 빈 목록이 아니다.
- 승인 이벤트 전체에서 주문상품 ID가 `null`이거나 중복되지 않는다.
- 실패 이벤트의 `failureCode`, `failureReason`이 존재한다.

잠금 조회 이후에는 다음을 검증한다.

- 승인 대상 주문의 `buyerId`가 payload의 `buyerId`와 모두 일치한다.
- payload의 각 주문상품 ID가 지정된 주문에 실제로 속한다.

Order Service에는 Checkout Group을 보존하는 별도 Aggregate가 없으므로 Payment Service가 원본 Checkout 주문을 빠뜨렸는지 판별하지 않는다. `totalAmount`와 조회된 주문 금액 합계의 엄격한 상관관계도 기존 계획대로 후속 과제로 남긴다. 상태 전이는 payload 금액이 아니라 Order Service가 보관한 주문 Aggregate를 기준으로 수행한다.

payload의 `orderProductIds`는 주문상품 소속 검증에 사용한다. 승인 대상 주문 하나가 선택되면 부분 상품만 완료하지 않고, 로컬 주문 Aggregate에 속한 모든 `PENDING/FAILED` 상품을 함께 `PAID`로 전환해 주문과 자식 상태의 일관성을 유지한다.

## 7. 결제 성공 처리

`PaymentApprovedProcessor.process`는 하나의 DB 트랜잭션에서 다음 순서로 실행한다.

1. `eventId + order-service` 처리 이력이 있으면 즉시 종료한다.
2. payload 구조를 검증한다.
3. 모든 주문과 주문상품을 결정적 순서로 잠근다.
4. 잠금 대기 중 같은 이벤트가 먼저 완료됐을 가능성을 고려해 처리 이력을 다시 확인한다.
5. 주문 존재 여부, 구매자, 주문상품 소속을 검증한다.
6. 각 주문의 현재 상태를 분류한다.
7. `CREATED`, `FAILED` 주문을 `COMPLETED`로 전환하고 `completedAt`을 `approvedAt`으로 설정한다.
8. 전이 대상 주문의 `PENDING`, `FAILED` 상품을 `PAID`로 전환한다.
9. 새로 완료된 주문마다 현재 `ORDER_PAID` 계약으로 Outbox 한 건을 저장한다.
10. 구매자 장바구니가 존재하면 대상 주문 Aggregate의 실제 `productId` 합집합을 제거한다.
11. processed event를 저장한다.
12. 새로 완료된 주문 ID를 담은 내부 애플리케이션 이벤트를 발행한다.

`COMPLETED`, `PARTIAL_REFUNDED`, `ALL_REFUNDED` 주문은 다시 상태 전이하지 않으며 `ORDER_PAID` Outbox도 생성하지 않는다. 따라서 동일 결제 결과가 다른 `eventId`로 다시 도착하더라도 후속 Outbox가 중복 생성되지 않는다.

장바구니가 없거나 일부 상품이 이미 장바구니에서 제거된 경우는 멱등 no-op으로 처리한다. 장바구니 저장소나 DB 작업 자체가 실패하면 전체 트랜잭션을 롤백한다.

`PaymentApprovedProcessor`는 `OrderPaymentRepository`를 의존하거나 `OrderPayment`를 저장하지 않는다.

## 8. 결제 실패 처리

`PaymentFailedProcessor.process`도 하나의 DB 트랜잭션에서 실행한다.

1. 처리 이력을 확인한다.
2. payload 구조를 검증한다.
3. 모든 주문과 주문상품을 결정적 순서로 잠근다.
4. 처리 이력을 다시 확인한다.
5. 모든 주문이 존재하는지 확인한다.
6. `CREATED` 주문을 `FAILED`로 전환한다.
7. 전이 대상 주문의 `PENDING` 상품을 `FAILED`로 전환한다.
8. processed event를 저장한다.
9. `eventId`, `paymentId`, `orderIds`, 실패 코드·사유·시각, 최종 상태를 구조화 로그로 남긴다.

이미 `FAILED`인 주문은 변경하지 않는다. `COMPLETED`, `PARTIAL_REFUNDED`, `ALL_REFUNDED` 주문에 도착한 실패는 늦은 실패로 판단해 변경하지 않는다. 이러한 상태 기반 no-op은 정상 처리이므로 processed event를 기록하고 ACK한다.

실패 처리에서는 장바구니와 Outbox를 변경하지 않고 실패 상세를 별도 DB Entity로 저장하지 않는다.

## 9. 상태 우선순위

상태 우선순위는 기존 도메인 전이 규칙을 사용한다.

```text
CREATED -> COMPLETED | FAILED
FAILED -> COMPLETED
COMPLETED -> PARTIAL_REFUNDED | ALL_REFUNDED
PARTIAL_REFUNDED -> PARTIAL_REFUNDED | ALL_REFUNDED
ALL_REFUNDED -> 전이 없음
```

- 실패 후 성공: `FAILED -> COMPLETED`, `FAILED -> PAID`를 허용한다.
- 성공 후 실패: 완료·환불 상태를 유지한다.
- 동일 상태 재처리: 상태와 Outbox를 변경하지 않는다.
- 환불 이후 늦은 성공: 환불 상태를 되돌리거나 `ORDER_PAID`를 다시 생성하지 않는다.

## 10. 트랜잭션과 멱등성

다음 DB 변경은 Processor의 같은 트랜잭션에 포함한다.

- 주문 상태와 `completedAt`
- 주문상품 상태
- 장바구니 상품 제거
- 주문별 Outbox
- processed event

Outbox 직렬화·저장, 장바구니 변경, processed event 저장 중 하나라도 실패하면 모든 DB 변경을 롤백한다. Kafka Consumer는 Processor가 정상 반환한 뒤에만 ACK한다.

멱등성은 다음 세 단계로 방어한다.

1. 트랜잭션 시작 시 처리 이력 확인
2. 주문 잠금 획득 후 처리 이력 재확인
3. `event_id + consumer_group` DB unique 제약

동일 이벤트가 동시에 들어와 첫 조회에서 모두 미처리로 판단하더라도 잠금 이후 재조회 또는 unique 제약이 중복 커밋을 막는다. unique 제약 충돌이 발생하면 해당 시도의 상태·장바구니·Outbox도 같은 트랜잭션에서 롤백된다.

## 11. ACK·재시도·DLT 정책

다음 오류는 processed event를 기록하지 않고 예외를 전파한다.

- envelope 또는 typed payload 역직렬화 실패
- 필수 값 누락, 빈 목록, 중복 ID
- 일부 주문 누락
- 구매자 불일치
- 주문상품 소속 불일치
- 잠금 또는 DB 상태 변경 실패
- 장바구니, Outbox, processed event 저장 실패

Consumer는 ACK하지 않으며 기존 `DefaultErrorHandler` 정책으로 1초 간격 최대 3회 재시도한 뒤 같은 partition의 `payment-events.DLT`로 전달한다.

상태 우선순위에 따른 no-op과 동일 event 재수신은 정상 처리로 ACK한다.

## 12. PAYMENT_CANCELED 제거

다음 코드를 제거한다.

- `PaymentCanceledEventHandler`
- `PaymentCanceledProcessor`
- `PaymentCanceledPayload`
- 두 `PaymentEventType` Enum의 `PAYMENT_CANCELED`
- Router의 canceled Handler 의존성과 분기
- canceled 전용 단위 테스트

제거 이후 `PAYMENT_CANCELED`가 수신되면 `PaymentEventConsumer`가 미지원 event type으로 판단한다. 경고 로그를 남기고 ACK하며 DLT로 보내지 않는다. `PAYMENT_REFUNDED` 기존 라우팅은 이번 이슈에서 변경하지 않는다.

## 13. Redis 만료 정리

Redis 변경은 DB 트랜잭션에 포함할 수 없으므로 현재 Processor 내부의 즉시 삭제를 커밋 이후 처리로 전환한다.

1. 성공 Processor가 새로 완료된 주문 ID 목록을 내부 애플리케이션 이벤트로 발행한다.
2. Redis adapter의 listener가 `AFTER_COMMIT`에서 각 주문의 만료 예약과 재시도 횟수를 제거한다.
3. 주문 하나의 Redis 정리가 실패해도 나머지 주문 정리를 계속하고 주문 ID와 예외를 경고 로그로 남긴다.

DB 트랜잭션이 롤백되면 listener가 실행되지 않으므로 아직 `CREATED/FAILED` 상태인 주문의 만료 정보가 잘못 제거되지 않는다. 커밋 후 Redis 정리가 실패해 남은 만료 항목은 Worker가 DB의 완료 상태를 확인하고 no-op 처리할 수 있다.

## 14. 로그 정책

성공·실패 처리 로그에는 다음 식별자를 포함한다.

- `eventId`
- `eventType`
- `paymentId`
- 정렬된 `orderIds`
- 주문별 처리 전·후 상태 또는 최종 상태
- `consumerGroup`
- 실패 이벤트의 `failureCode`, `failureReason`, `failedAt`

PG 인증정보, 토큰, 결제 비밀값과 개인정보는 로그에 기록하지 않는다.

## 15. 테스트 설계

### 15.1 Payload와 Handler

- 다건 성공 payload와 주문별 상품 목록 매핑
- 다건 실패 payload와 실패 정보 매핑
- 필수 필드, 빈 목록, 중복 ID, 잘못된 UUID 거절
- 매핑·검증 실패 시 Processor 미호출

### 15.2 잠금 저장소

- 입력 순서와 무관한 주문 UUID 잠금 순서
- 주문별 상품 UUID 잠금 순서
- 초기화된 주문 Aggregate 반환
- 일부 주문 누락 감지
- H2 PostgreSQL mode에서 비관적 잠금 조회 실행

### 15.3 성공 Processor

- 여러 `CREATED/PENDING` 주문·상품 완료
- `FAILED` 주문·상품의 성공 복구
- 새로 완료된 주문별 `ORDER_PAID` 한 건
- 장바구니 대상 상품 합집합 제거
- 동일 eventId 중복 무시
- 다른 eventId의 중복 성공 시 Outbox 미생성
- 구매자·주문상품 소속 불일치 거절
- 주문 누락과 Outbox 저장 실패 시 전체 롤백
- DB 커밋 이후에만 Redis 정리

### 15.4 실패 Processor

- 여러 `CREATED/PENDING` 주문·상품 실패
- 기존 `FAILED` 멱등 no-op
- 완료·부분 환불·전체 환불 주문의 늦은 실패 무시
- 장바구니·Outbox 미변경
- 실패 후 성공 복구
- 일부 처리 실패 시 전체 롤백

### 15.5 Router와 Embedded Kafka

- 성공·실패 이벤트 Handler 라우팅과 ACK
- Handler 예외 시 ACK 미수행과 재시도
- 재시도 소진 후 `payment-events.DLT` 전달
- 잘못된 JSON과 payload 누락 DLT 전달
- `PAYMENT_CANCELED` 미지원 ACK와 DLT 미전달
- 기존 `PAYMENT_REFUNDED` 라우팅 회귀 검증

## 16. 완료 조건

- 결제 성공·실패가 모든 대상 주문과 상품에 하나의 DB 트랜잭션으로 반영된다.
- 한 대상 또는 협력 객체 처리 실패 시 상태·장바구니·Outbox·처리 이력이 모두 롤백된다.
- 동일 eventId와 다른 eventId의 중복 성공이 Outbox를 중복 생성하지 않는다.
- 실패 후 성공과 성공 후 늦은 실패가 정의한 우선순위를 따른다.
- 성공 이벤트에서만 장바구니 상품이 제거된다.
- 새로 완료된 주문마다 `ORDER_PAID` Outbox가 한 건 생성된다.
- `OrderPayment` 저장과 `PAYMENT_CANCELED` 지원이 제거된다.
- 단위 테스트, JPA 테스트, 트랜잭션 통합 테스트, Embedded Kafka 테스트가 통과한다.
- `../gradlew :order-service:test`, `../gradlew :order-service:build`, `git diff --check`가 통과한다.
- 다른 서비스와 `common-module`은 변경되지 않는다.
