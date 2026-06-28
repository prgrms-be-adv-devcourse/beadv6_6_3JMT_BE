# Order Service Payment Event 멱등 처리 점검 결과

## 1. 점검 개요

이 문서는 Order Service의 Payment Event 처리 구현을 첨부 결정안 기준으로 정적 점검한 결과다.
코드 수정이나 테스트 실행 없이 현재 소스 기준으로만 확인했다.

핵심 점검 기준은 다음과 같다.

- `processed_event` 테이블 없이 `paymentId` 기준으로 멱등 처리한다.
- `paymentId` 존재 여부만으로 이벤트를 무시하지 않고, 현재 상태가 이벤트의 목표 상태인지 확인한다.
- 결제 승인, 취소, 환불, 취소 실패, 환불 실패 이벤트의 상태 전환과 outbox 저장 정책이 명확해야 한다.
- `is_download`는 결제 가능 여부가 아니라 실제 콘텐츠 열람 여부로 유지한다.
- 정산 차감 이벤트와 outbox relay 동작이 누락되지 않아야 한다.

## 2. 현재 구현 요약

### Payment Event Consumer

- `PaymentEventConsumer`는 `payment.approved`, `payment.failed`, `payment.canceled`, `payment.refunded` 토픽을 구독한다.
- 정상 처리 후 `Acknowledgment.acknowledge()`를 호출한다.
- 처리 중 예외가 발생하면 ack하지 않고 예외를 전파해 재시도 가능성을 남긴다.
- `payment.cancel_failed`, `payment.refund_failed`는 현재 구독 및 분기 대상에 없다.

### Application Service

- `OrderPaymentEventService`가 결제 이벤트별 상태 전환을 담당한다.
- `payment.approved` 처리 시 Order와 OrderProduct를 `PAID`로 변경하고 `OrderPayment`를 저장하며 `ORDER_PAID` outbox를 저장한다.
- `payment.canceled` 처리 시 Order와 OrderProduct를 `CANCELED`로 변경한다.
- `payment.refunded` 처리 시 Order와 OrderProduct를 `REFUNDED`로 변경하고 `ORDER_REFUND` outbox를 저장한다.
- `payment.failed` 처리 시 PENDING 주문을 `FAILED`로 변경한다.

### OrderPayment

- `OrderPayment` 엔티티에는 `payment_id` unique 제약이 선언되어 있다.
- `order_id`, `payment_id`, `pg_tx_id`가 각각 unique 제약을 가진다.
- 현재 `OrderPayment`에는 결제 상태 컬럼이 없다.
- Repository는 `existsByOrderId(UUID orderId)`만 제공하고, `paymentId` 조회 메서드는 없다.

### Outbox

- `OutboxEventAppender`는 `ORDER_PAID`, `ORDER_REFUND` 이벤트 payload를 JSON 문자열로 직렬화해 저장한다.
- `OutboxEvent`의 `aggregateType`은 `ORDER`, `topic`은 `order-events`, 기본 상태는 `PENDING`이다.
- `OutboxRelay`는 PENDING 이벤트를 조회해 Kafka로 발행하고 성공 시 `PUBLISHED`, 실패 시 `retryCount` 증가 및 최대 재시도 도달 시 `FAILED`로 변경한다.

### isDownload

- `OrderProduct.download`는 생성 시 `false`다.
- 결제 승인 처리에서는 `download` 값을 true로 바꾸지 않는다.
- `OrderProduct.markDownloaded()`가 호출될 때만 true로 변경된다.
- Order API 문서의 `isDownload`와 `contentAvailable` 설명은 실제 열람 여부와 결제 후 콘텐츠 접근 가능 여부를 분리하고 있다.

## 3. 항목별 판정

| 점검 항목 | 판정 | 근거 |
| --- | --- | --- |
| `processed_event` 미사용 | 충족 | 검색된 Order Service 구현에서 processed event 저장소나 엔티티가 없다. |
| `order_payment.payment_id` unique 제약 | 충족 | `OrderPayment`의 `@Table(uniqueConstraints)`에 `uk_order_payment_payment_id`가 선언되어 있다. |
| `paymentId` 기준 조회 | 미충족 | `OrderPaymentRepository`는 `existsByOrderId`만 제공하며 `paymentId` 조회 기반 멱등 처리가 없다. |
| `paymentId` 존재 여부만으로 무시하지 않음 | 부분 충족 | 단순 paymentId 존재 여부로 무시하지는 않지만, 현재 기준은 `orderId` 결제내역 존재 여부와 `OrderStatus` 조합이다. |
| `payment.approved` 목표 상태 멱등 처리 | 부분 충족 | Order가 `PAID`이고 결제내역이 있으면 무시한다. 다만 `paymentId` 동일성 검증은 없다. |
| `payment.canceled` 목표 상태 멱등 처리 | 부분 충족 | 이미 `CANCELED`이면 무시한다. 다만 `paymentId` 기준 검증과 OrderPayment 상태 갱신은 없다. |
| `payment.cancel_failed` 처리 | 미충족 | Consumer, EventType, Handler, Service에 해당 이벤트 처리가 없다. |
| `payment.refunded` 목표 상태 멱등 처리 | 부분 충족 | 이미 `REFUNDED`이면 무시하고 `ORDER_REFUND` 중복 저장을 막는다. 다만 `paymentId` 기준 검증은 없다. |
| `payment.refund_failed` 처리 | 미충족 | Consumer, EventType, Handler, Service에 해당 이벤트 처리가 없다. |
| OrderPayment 상태 갱신 | 미충족 | `order_payment`에 상태 컬럼이 없어 취소, 환불, 실패 상태를 갱신할 수 없다. |
| 승인 처리와 `ORDER_PAID` outbox 저장의 트랜잭션 | 충족 | `OrderPaymentEventService`가 `@Transactional`이며 상태 변경, 결제내역 저장, outbox 저장을 한 서비스 메서드에서 수행한다. |
| 환불 처리와 `ORDER_REFUND` outbox 저장의 트랜잭션 | 충족 | `handlePaymentRefunded`가 같은 트랜잭션 안에서 상태 변경과 outbox 저장을 수행한다. |
| 취소 시 정산 차감 이벤트 발행 | 결정 필요 | `payment.canceled` 처리에서 outbox 이벤트를 저장하지 않는다. 취소를 정산 차감 대상으로 볼지 정책 결정이 필요하다. |
| `is_download` 의미 | 충족 | 실제 구현은 결제 승인 시 true로 바꾸지 않고, 콘텐츠 최초 열람 시 true로 바꾼다. |
| Payment API 문서의 `is_download` 설명 | 미충족 | `docs/api-spec/payment.md`의 Kafka 이벤트 표에는 `payment.approved` 처리 내용이 `is_download = true`로 남아 있다. |
| Application Service의 KafkaTemplate 직접 호출 금지 | 충족 | `OrderPaymentEventService`는 KafkaTemplate을 직접 호출하지 않고 outbox 저장만 수행한다. |
| OutboxRelay 발행 처리 | 충족 | `OutboxRelay`가 `event.topic`으로 발행하고 성공/실패 상태를 갱신한다. |
| 중복 승인 시 `ORDER_PAID` 중복 저장 방지 | 부분 충족 | 이미 `PAID`이고 결제내역이 있으면 outbox 저장을 생략한다. 다만 `paymentId` 기준 중복 검증은 없다. |
| 중복 환불 시 `ORDER_REFUND` 중복 저장 방지 | 충족 | 이미 `REFUNDED`이면 outbox 저장 없이 반환한다. |
| 실패 이벤트 reason 기록 | 미충족 | `payment.cancel_failed`, `payment.refund_failed`가 없고, 실패 사유 이력 저장 구조도 없다. |

## 4. 주요 리스크

### 4.1 멱등 기준이 최종 결정안과 다름

최종 결정안은 `paymentId` 기준 조회 후 현재 상태가 이벤트 목표 상태인지 확인하는 방식이다.
현재 구현은 `paymentId`가 아니라 `orderId` 결제내역 존재 여부와 `OrderStatus`를 기준으로 한다.

이 구조에서는 같은 주문에 대해 다른 `paymentId`가 들어오는 비정상 이벤트, 또는 같은 `paymentId`의 후속 취소/환불 이벤트를 엄밀하게 검증하기 어렵다.
`OrderPaymentRepository.findByPaymentId(...)` 또는 동등한 조회 경로가 필요하다.

### 4.2 OrderPayment 상태 모델이 없음

결정안은 `OrderPayment 상태 → PAID/CANCELED/REFUNDED` 저장 또는 갱신을 전제로 한다.
현재 `OrderPayment`는 승인 결제내역 성격의 컬럼만 가지고 있고 상태 컬럼이 없다.

Order 상태만으로 충분한지, 아니면 Payment 이벤트 처리 이력을 OrderPayment 또는 별도 이력 모델에 남길지 결정해야 한다.

### 4.3 취소와 환불의 정산 차감 정책이 미확정

현재 `payment.refunded`는 `ORDER_REFUND` outbox를 저장하지만 `payment.canceled`는 outbox를 저장하지 않는다.
이미 `ORDER_PAID`가 발행된 주문이 취소될 수 있다면 정산 차감 이벤트가 필요할 수 있다.

전체 취소와 전체 환불을 같은 정산 차감으로 처리할지, `ORDER_CANCEL`과 `ORDER_REFUND`를 분리할지 결정해야 한다.

### 4.4 Order 이벤트명 불일치 가능성

Order Service는 환불 outbox eventType으로 `ORDER_REFUND`를 사용한다.
반면 Settlement Service의 `OrderEventType`은 `ORDER_REFUNDED`를 기대한다.

이 상태라면 환불 정산 이벤트가 Settlement Service에서 정상 처리되지 않을 수 있다.
Order, Product, Settlement 서비스 간 이벤트 카탈로그를 하나로 맞춰야 한다.

### 4.5 Payment API 문서와 실제 구현의 `is_download` 의미가 다름

실제 Order 구현은 `isDownload`를 최초 열람 여부로 사용한다.
하지만 `docs/api-spec/payment.md`에는 `payment.approved` 수신 시 `is_download = true`라고 되어 있다.

결제 완료 시점에는 `OrderProduct.status = PAID`, `contentAvailable = true`, `isDownload = false`가 맞다.
Payment API 문서의 Kafka 이벤트 설명을 수정해야 한다.

## 5. 권장 후속 조치

1. `OrderPaymentRepository`에 `findByPaymentId(UUID paymentId)`를 추가하고, 이벤트 멱등 판단을 `paymentId + 현재 목표 상태` 기준으로 재정렬한다.
2. `OrderPayment`에 상태 컬럼을 둘지, Order 상태만으로 이벤트 처리를 끝낼지 정책을 확정한다.
3. `payment.cancel_failed`, `payment.refund_failed` 이벤트를 Order Service가 처리해야 하는지 확정하고, 처리한다면 payload와 실패 사유 기록 위치를 정의한다.
4. `payment.canceled` 발생 시 정산 차감 이벤트를 발행할지 결정한다.
5. Order Service의 `ORDER_REFUND`와 Settlement Service의 `ORDER_REFUNDED` 중 하나로 이벤트명을 통일한다.
6. `docs/api-spec/payment.md`의 `payment.approved` 구독자 처리 내용을 `Order PAID 전환 + contentAvailable = true + isDownload = false 유지`로 수정한다.
7. 중복 이벤트 테스트를 `paymentId` 기준으로 보강한다.

## 6. 결론

현재 구현은 기본적인 중복 승인, 중복 취소, 중복 환불 방지는 일부 갖추고 있다.
하지만 최종 결정안의 핵심인 `paymentId` 기준 멱등 처리에는 아직 맞지 않는다.

특히 다음 항목은 구현 또는 정책 결정이 필요하다.

- `paymentId` 조회 기반 멱등 처리
- `OrderPayment` 상태 모델링 여부
- `payment.cancel_failed`, `payment.refund_failed` 처리 여부
- `payment.canceled`의 정산 차감 이벤트 발행 여부
- `ORDER_REFUND`와 `ORDER_REFUNDED` 이벤트명 통일
- Payment API 문서의 `is_download` 설명 정정
