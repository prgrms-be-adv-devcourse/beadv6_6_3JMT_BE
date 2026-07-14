# 부분 환불(OrderProduct 단위) 구현 계획

기존 REST 기반 전체환불(202+REFUNDING+폴링)을 완전히 제거하고, order-service가 발행하는 Kafka 이벤트로 트리거되는 OrderProduct 단위 환불로 전면 교체한다. "전체 환불" 개념 자체가 없어지고, 주문 전체를 환불하려면 order-service가 상품 수만큼 이벤트를 여러 번 발행해 누적으로 완료 처리한다.

---

## 배경 및 목표

- api-design.md는 현재 "전체 환불만 지원(부분 환불은 세미 MVP 범위 외)"으로 명시하고 있음 — 이번 작업으로 이 제약을 없앤다.
- `refund.order_product_id` 컬럼은 이미 스키마에 존재("부분 환불 대상 OrderProduct ID")하지만 실제로 채워진 적 없음(항상 NULL).
- order-service 쪽 gRPC(`order_payment.proto`)/order_snapshot 테이블 모두 상품별 금액을 갖고 있지 않음 — payment-service가 상품별 가격을 직접 검증할 방법이 없음. 따라서 **금액 검증은 하지 않고 order-service가 이벤트에 실어 보내는 `refundAmount`를 그대로 신뢰**한다(브레인스토밍 중 확정 사항).
- 이 계약(토픽 재사용/eventType/payload)은 payment-service 쪽 컨슈머 구현과 문서화까지가 이번 작업 범위다. order-service 쪽 프로듀서 구현은 다른 팀 소관 — 계약만 합의한다.

---

## 확정 사항 (브레인스토밍 결론)

1. **환불 단위**: OrderProduct 단위만 존재. orderProductId는 항상 필수(null 불가). "주문 전체 한번에 환불"이라는 별도 이벤트/커맨드는 없음.
2. **트리거**: order-service가 `order-events` 토픽에 신규 eventType `ORDER_REFUND_REQUESTED`를 발행. payment-service는 이를 소비해서 처리한다. 기존 REST `POST /payments/{id}/refund`는 **완전히 제거**한다.
3. **금액 검증**: order-service가 보낸 `refundAmount`를 그대로 신뢰. payment-service는 "누적 환불액이 결제 총액을 넘지 않는지"만 내부 정합성 차원에서 확인한다(상품 가격 진위 검증 아님).
4. **REFUNDING 상태 제거**: 기존 202-비동기-폴링 패턴은 HTTP 호출자가 없는 이벤트 소비 구조에서 더 이상 의미가 없다. PG 호출을 트랜잭션 안에서 동기로 수행하고, `PaymentStatus.REFUNDING` 자체를 없앤다.
5. **PaymentStatus 개명/추가**: `REFUNDED` → `ALL_REFUNDED`로 개명, `PARTIAL_REFUNDED` 신규 추가. `ALL_REFUNDED`는 누적 환불액이 `total_amount`에 도달했을 때만 전이.
6. **PG 실패 시 재시도 없음**: `Refund.status = FAILED`로만 남긴다. 재시도가 필요하면 order-service가 이벤트를 재발행하거나 운영자가 수동 처리한다. 기존 `PaymentRefundRetryScheduler`/`SchedulingConfig`는 완전히 제거한다(다른 용도로 쓰이지 않음을 확인함).
7. **기존 버그 수정 포함(필수)**: `TossPaymentGateway.refund()`가 `cancelAmount`를 항상 `null`로 보내 PG사가 전액취소로 처리하던 버그, `Idempotency-Key`가 `paymentId` 고정이라 같은 결제에 대한 두 번째 부분환불이 Toss의 캐시된 첫 응답을 돌려받는 버그. 둘 다 이번 작업에서 고친다.
8. **환불 실패 시 order-service에 알림**: PG 환불 실패 시 `Refund.FAILED` 저장뿐 아니라 `payment-events` 토픽에 신규 eventType `PAYMENT_REFUND_FAILED`를 발행한다. order-service가 자기 쪽 상태(반품 대기 등)를 실패로 되돌리거나 재시도 판단에 쓸 수 있도록.

---

## 이벤트 계약 (신규 — order-service와 합의 필요)

```
topic: order-events  (기존 토픽 재사용, eventType으로 멀티플렉싱)
eventType: ORDER_REFUND_REQUESTED
payload: {
  orderId: UUID,
  orderProductId: UUID,      // 필수, null 불가
  buyerId: UUID,             // 참고/로그용, 별도 거부 로직 없음(order-service가 이미 소유권 검증 완료로 간주)
  refundAmount: Int,
  requestedAt: LocalDateTime // 존 없음, ORDER_CREATED.createdAt과 동일 관례(소비 시 KST 부여)
}
```

## PAYMENT_REFUNDED 발행 페이로드 변경

기존 payload에 `orderProductId`(필수) 필드를 추가한다. `amount`는 이번 환불 건(해당 상품)의 금액이다. order-service가 상품별로 자기 쪽 상태(반품 완료 등)를 반영할 수 있도록.

## PAYMENT_REFUND_FAILED (신규 발행 이벤트)

PG 환불 실패 시 `payment-events` 토픽에 발행. `PaymentEventType`에 추가.

```
payload: {
  paymentId: UUID,
  orderId: UUID,
  userId: UUID,
  orderProductId: UUID,
  refundAmount: Int,       // 시도했던 환불 금액
  failureReason: String | null,
  failedAt: ISO 8601 (KST)
}
```

`KafkaPaymentEventPublisher`에 `onPaymentRefundFailed(PaymentRefundFailedEvent)` 추가(기존 `onPaymentApproved`/`onPaymentFailed`와 동일한 `@TransactionalEventListener(AFTER_COMMIT)` 표준 패턴).

---

## 도메인 모델 변경

### PaymentStatus

```
READY, REQUESTED, PAID, FAILED, PARTIAL_REFUNDED, ALL_REFUNDED, UNKNOWN
```

`REFUNDING` 제거, `REFUNDED` → `ALL_REFUNDED` 개명.

### Payment

`startRefunding()` / `completeRefund()` / `restoreToRefundFailed()` 3개 메서드 제거. 아래 1개로 대체:

```java
public void applyRefund(OffsetDateTime refundedAt, boolean isFullyRefunded) {
    if (status != PaymentStatus.PAID && status != PaymentStatus.PARTIAL_REFUNDED) {
        throw new IllegalStateException("PAID/PARTIAL_REFUNDED 상태에서만 환불을 적용할 수 있습니다.");
    }
    this.status = isFullyRefunded ? PaymentStatus.ALL_REFUNDED : PaymentStatus.PARTIAL_REFUNDED;
    this.refundedAt = refundedAt;
}
```

`isFullyRefunded` 판정은 서비스 레이어가 계산(기존 COMPLETED 환불 누적액 + 이번 금액 >= totalAmount).

### Refund

변경 없음. `create(paymentId, userId, refundAmount, reason, orderProductId)` 호출부만 `orderProductId`를 항상 채워서 넘긴다.

---

## 처리 흐름

```
OrderEventConsumer (order-events, eventType=ORDER_REFUND_REQUESTED)
  → ProcessRefundUseCase.process(command)   ← @Transactional (동기, PG 호출까지 같은 트랜잭션)
      1. payment = findByOrderIdAndStatusInForUpdate(orderId, [PAID, PARTIAL_REFUNDED])
         없으면 예외 → Kafka 재시도(3회) → order-events.DLT
      2. remaining = payment.totalAmount - sum(해당 payment의 COMPLETED Refund.refundAmount)
      3. command.refundAmount > remaining → 예외(InvalidRefundStateException) → DLT
      4. refund = Refund.create(paymentId, buyerId, refundAmount, null, orderProductId)  // REQUESTED로 저장
      5. paymentGateway.refund(pgTxId, refund.getId(), refundAmount) ← 동기 PG 호출
         성공: refund.complete(refundedAt)
               payment.applyRefund(refundedAt, remaining == refundAmount)
               ApplicationEventPublisher.publishEvent(PaymentRefundedEvent)
                 AFTER_COMMIT → KafkaPaymentEventPublisher → payment-events(PAYMENT_REFUNDED)
         실패(PaymentGatewayException): refund.fail() 저장. Payment 상태 불변. 커밋(롤백 아님).
               ApplicationEventPublisher.publishEvent(PaymentRefundFailedEvent)
                 AFTER_COMMIT → KafkaPaymentEventPublisher → payment-events(PAYMENT_REFUND_FAILED)
  → ack
```

### 동시성

`REFUNDING` 마커 없이도 안전 — PG 호출이 트랜잭션 안에 있어 row lock이 호출 종료까지 유지된다. 같은 `orderId`는 Kafka 파티션 키(`aggregateId`)라 순차 처리되므로 같은 Payment에 대한 동시 이벤트 경합이 없다. (트레이드오프: PG 네트워크 호출 동안 DB 커넥션 + row lock을 점유 — HTTP 대기자가 없는 컨슈머 구조라 허용 가능하다고 판단.)

---

## PaymentGateway / TossPaymentGateway 변경 (버그 수정 포함)

```java
public interface PaymentGateway {
    ConfirmResult confirm(String paymentKey, UUID orderId, int amount);
    RefundResult refund(String pgTxId, UUID refundId, int amount);  // paymentId → refundId
}
```

- `TossRefundRequest`의 `cancelAmount`에 실제 `amount`를 전달(기존 `null` 고정 버그 수정 — PG사가 전액취소로 처리하던 문제).
- `Idempotency-Key`를 `"refund-" + refundId`로 변경(기존 `"refund-" + paymentId` 고정 버그 수정 — 같은 결제의 두 번째 부분환불이 Toss의 첫 응답 캐시를 돌려받던 문제).

---

## DB 변경

`schema.sql`에 부분 유니크 인덱스 추가:

```sql
-- 같은 결제의 같은 상품에 대한 중복 환불 방지(이벤트 재전송 idempotency 방어)
CREATE UNIQUE INDEX IF NOT EXISTS uk_refund_payment_order_product
  ON refund (payment_id, order_product_id);
```

`order_product_id`가 항상 필수가 되므로 `NOT NULL` 제약으로 컬럼 정의도 변경(`Refund.java` `@Column(nullable = false)`).

---

## 삭제 대상

| 파일 | 이유 |
|---|---|
| `application/usecase/RefundPaymentUseCase.java` | REST 환불 제거 |
| `application/service/RefundPaymentService.java` | REST 환불 제거 |
| `application/dto/command/RefundPaymentCommand.java` | REST 환불 제거 |
| `domain/event/PaymentRefundRequestedEvent.java` | REFUNDING 비동기 분리 불필요 |
| `infrastructure/messaging/RefundEventHandler.java` | 동기 처리로 대체 |
| `infrastructure/scheduling/PaymentRefundRetryScheduler.java` | 재시도 없음(확정) |
| `infrastructure/scheduling/SchedulingConfig.java` | 위 스케줄러 전용, 다른 용도 없음 확인 |
| `PaymentController.refund()` 및 관련 Swagger 문서 | REST 환불 제거 |
| `domain/repository/PaymentRepository.findByStatusAndUpdatedAtBefore` (및 구현체) | 스케줄러 전용, 재시도 제거로 불필요 |
| `RefundPaymentServiceTest.java`, `RefundPaymentIntegrationTest.java` | 신규 흐름으로 재작성 |

## 신규/수정 대상

| 파일 | 내용 |
|---|---|
| `domain/model/PaymentStatus.java` | REFUNDING 제거, REFUNDED→ALL_REFUNDED, PARTIAL_REFUNDED 추가 |
| `domain/model/Payment.java` | `applyRefund()`로 전이 메서드 통합 |
| `domain/model/Refund.java` | `orderProductId` NOT NULL |
| `domain/repository/PaymentRepository.java` | `findByOrderIdAndStatusInForUpdate(orderId, statuses)` 추가, `findByStatusAndUpdatedAtBefore` 제거 |
| `domain/repository/RefundRepository.java` | 누적 COMPLETED 금액 합산용 메서드 추가(예: `findByPaymentIdAndStatus`) |
| `application/dto/command/ProcessRefundCommand.java` (신규) | orderId, orderProductId, buyerId, refundAmount, requestedAt |
| `application/usecase/ProcessRefundUseCase.java` (신규) | Input Boundary |
| `application/service/ProcessRefundService.java` (신규) | 위 처리 흐름 구현체 |
| `application/gateway/external/PaymentGateway.java` | `refund()` 시그니처 `refundId`로 변경 |
| `infrastructure/external/toss/TossPaymentGateway.java` | cancelAmount 전달, Idempotency-Key refundId 기준 |
| `infrastructure/messaging/consumer/OrderEventConsumer.java` | `ORDER_REFUND_REQUESTED` 라우팅 추가 |
| `infrastructure/messaging/dto/OrderRefundRequestedMessage.java` (신규) | 이벤트 payload DTO |
| `domain/event/PaymentRefundedEvent.java` (신규) | `PaymentRefundedEvent(Payment, Refund)` — 기존엔 존재하지 않았고 `KafkaPaymentEventPublisher.publishRefunded()` 직접 호출이었음. 이제 일반 `@Transactional` 서비스 흐름이라 표준 AFTER_COMMIT 패턴 적용 가능 |
| `domain/event/PaymentRefundFailedEvent.java` (신규) | `PaymentRefundFailedEvent(Payment, Refund, String failureReason)` |
| `infrastructure/messaging/PaymentEventType.java` | `PAYMENT_REFUND_FAILED` 추가 |
| `infrastructure/messaging/dto/PaymentRefundFailedMessage.java` (신규) | 실패 이벤트 payload DTO |
| `infrastructure/messaging/KafkaPaymentEventPublisher.java` | `publishRefunded()` 직접호출 메서드 제거 → `onPaymentRefunded(PaymentRefundedEvent)` 표준 AFTER_COMMIT 리스너로 전환(payload에 orderProductId 추가), `onPaymentRefundFailed(PaymentRefundFailedEvent)` 신규 추가 |
| `infrastructure/persistence/PaymentJpaRepository.java` / `PaymentRepositoryAdapter.java` | 신규 조회 메서드 위임, 구 메서드 제거 |
| `infrastructure/persistence/RefundJpaRepository.java` / `RefundRepositoryAdapter.java` | 신규 조회 메서드 위임 |
| `schema.sql` | 부분 유니크 인덱스 추가 |
| `.claude/docs/api-design.md` | 환불 API 섹션 제거, 이벤트 기반 흐름으로 대체 서술 |
| `.claude/docs/events.md` | 구독 `ORDER_REFUND_REQUESTED` 추가, `PAYMENT_REFUNDED` payload에 orderProductId 반영, 발행 `PAYMENT_REFUND_FAILED` 신규 추가 |
| `.claude/docs/db-schema.md` | PaymentStatus enum 표, refund 인덱스 반영 |

---

## 테스트 케이스 (신규/재작성)

### `ProcessRefundServiceTest` (단위, Mockito)

| 테스트 메서드명 | 검증 내용 |
|---|---|
| `결제_건_없으면_예외` | findByOrderIdAndStatusInForUpdate → empty → 예외 |
| `누적_환불액_초과_시_예외` | remaining < refundAmount → InvalidRefundStateException |
| `부분_환불_성공_시_PARTIAL_REFUNDED_전이` | remaining > refundAmount → Payment.PARTIAL_REFUNDED, Refund.COMPLETED |
| `누적_환불액_totalAmount_도달_시_ALL_REFUNDED_전이` | remaining == refundAmount → Payment.ALL_REFUNDED |
| `PG_실패_시_Refund_FAILED_Payment_상태_불변` | PaymentGatewayException → refund.fail()만, Payment 상태 그대로 |
| `PG_실패_시_PaymentRefundFailedEvent_발행` | PaymentGatewayException → `PaymentRefundFailedEvent` publish 검증(Mockito ArgumentCaptor) |
| `같은_상품_중복_환불_요청_시_예외_또는_무시` | (payment_id, order_product_id) 유니크 위반 처리 확인 |

### `PartialRefundIntegrationTest` (Testcontainers, 루트 패키지)

| 테스트 메서드명 | 검증 내용 |
|---|---|
| `ORDER_REFUND_REQUESTED_수신_시_부분환불_완료_Kafka_발행` | Kafka로 이벤트 발행 → DB PARTIAL_REFUNDED 확인 → `payment-events`(PAYMENT_REFUNDED) 수신 확인 |
| `누적환불로_ALL_REFUNDED_도달` | 두 번의 이벤트(서로 다른 orderProductId) → 두 번째에서 ALL_REFUNDED |
| `PG_환불_실패_시_상태_불변_및_실패_이벤트_발행` | PG Mock 예외 → Payment 상태 그대로, Refund.FAILED, `payment-events`(PAYMENT_REFUND_FAILED) 수신 확인 |

### `RefundJpaRepositoryTest` 수정

- `orderProductId` NOT NULL 반영, 유니크 인덱스 위반 케이스 추가.

---

## 트레이드오프

- **동기 PG 호출(트랜잭션 내)**: 커넥션/락 점유 시간이 늘지만, HTTP 대기자가 없는 이벤트 소비 구조이므로 지연 자체는 사용자에게 영향 없음. 단, Kafka 컨슈머 처리량은 PG 응답 속도에 종속된다(단일 파티션 기준 순차 처리이므로 상한 존재).
- **재시도 없음**: 운영 초기 실패 건수가 적다는 전제. 실패가 늘면 order-service 쪽 재발행 정책이나 별도 재시도 장치를 다시 논의해야 한다.
- **금액 무검증**: order-service가 잘못된 금액을 보내도 payment-service는 (누적 초과가 아닌 이상) 그대로 PG에 요청한다. 상품별 가격 정보가 order-service에만 있다는 현재 구조상 불가피한 신뢰 경계.
