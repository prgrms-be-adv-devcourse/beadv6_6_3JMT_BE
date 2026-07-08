# 작업 계획: 부분 환불 API 구현

## 결정 배경 (그릴링 세션 결과)

| 항목 | 결정 |
|---|---|
| 진입점 | **order-service** — 환불 정책(다운로드 여부·상품 상태)과 상품별 금액의 소유자가 검증 후 실행 요청 |
| 환불 단위 | **orderProduct 1개씩** — 요청당 상품 1개, `Refund.orderProductId` 단수 필드와 일치 |
| 전체 환불 | **부분 환불 API로 통일** — 전체 환불 = 상품마다 개별 호출. payment-service의 기존 전체 환불 API는 제거 |
| 할인 처리 | **할인 기능 미사용 전제** — 환불액 = `OrderProduct.productAmount` 스냅샷 그대로. 할인 도입 시 재설계 필요(후속 과제) |
| 상태 모델 | **새 최종 상태 없음** — `OrderStatus`에 중간 상태 `REFUND_REQUESTED`만 추가. Order/Payment 레벨은 PAID 유지, 전 상품 환불 완료 시 REFUNDED로 파생 전이 |
| 실행 요청 채널 | **Kafka + outbox** — order-service가 `order-events`에 환불 요청 이벤트 발행, payment-service가 소비해 PG 취소 실행, 결과를 `payment.events` 토픽에 eventType `payment.refunded` / `payment.refund-failed`로 회신 |
| 환불 사유(reason) | 이번 범위 제외 (현행대로 null 유지) |
| 관리자 환불 | 이번 범위 제외 — 정책 검증 로직만 분리해 두어 후속 확장 대비 |
| 선행 작업 관계 | `payment.refund-failed` 이벤트는 **이 작업의 필수 구성요소로 포함** (구 failure-event-publishing.md의 환불 실패 부분을 흡수 — 결제 실패 파트는 flow-redesign(작업 2)에 흡수되어 해당 문서는 해체, 00-execution-order.md D2·D5). **unify-payment-topic(작업 1)·flow-redesign(작업 2) 선행 완료 전제** — 발행 토픽은 `payment.events`, order-events 컨슈머는 작업 2가 신설한 것을 확장 |

---

## 전체 흐름

```
[구매자]
  POST /api/v1/orders/{orderId}/products/{orderProductId}/refund   (order-service)
    → 검증: 본인 주문, Order=PAID, OrderProduct.isRefundable() (PAID && !downloaded)
    → OrderProduct: PAID → REFUND_REQUESTED
    → Outbox 기록: ORDER_REFUND_REQUESTED → order-events 토픽 (5초 폴링 릴레이)
    → 202 ACCEPTED

[payment-service]  order-events 컨슈머 (신규)
    → Payment 조회 + 검증: 상태, 과환불 방어(누적 환불액 + 요청액 ≤ approvedAmount),
      동일 orderProductId 활성 Refund 부재
    → Refund 생성 (orderProductId, refundAmount, REQUESTED)
    → Toss POST /payments/{paymentKey}/cancel  (cancelAmount = refundAmount,
      Idempotency-Key = refund-{paymentId}-{orderProductId})
    ├─ 성공: Refund → COMPLETED.
    │        누적 환불액 == 승인액이면 Payment → REFUNDED(refundedAt).
    │        발행: payment.refunded (orderProductId, refundId 포함)
    └─ 실패: Refund → FAILED.
             발행: payment.refund-failed (orderProductId, refundId 포함)

[order-service]  payment 이벤트 컨슈머 (확장)
    ├─ payment.refunded: OrderProduct REFUND_REQUESTED → REFUNDED(refundedAt).
    │   전 상품 REFUNDED이면 Order → REFUNDED(refundedAt).
    │   Outbox 기록: ORDER_REFUND (해당 상품 1건 payload)
    └─ payment.refund-failed: OrderProduct REFUND_REQUESTED → PAID 복구 (재시도 가능)
```

---

## 상태 모델

### OrderStatus (order-service) — `REFUND_REQUESTED` 추가

| 전이 | 트리거 | 적용 레벨 |
|---|---|---|
| PAID → REFUND_REQUESTED | 환불 API 요청 접수 | OrderProduct만 |
| REFUND_REQUESTED → REFUNDED | `payment.refunded` 수신 | OrderProduct |
| REFUND_REQUESTED → PAID | `payment.refund-failed` 수신 | OrderProduct (복구) |
| PAID → REFUNDED | 마지막 상품 REFUNDED 확정 시 | Order (파생 전이) |

- Order 레벨은 부분 환불 진행 중에도 **PAID 유지**. `PARTIALLY_REFUNDED` 같은 신규 최종 상태는 만들지 않는다.
- `REFUND_REQUESTED` 상태의 상품은 `isRefundable() = false`가 자동으로 성립(PAID가 아니므로) → **중복 환불 요청 차단**.
- **다운로드 경합 차단**: 다운로드 확정 API(`PATCH .../download`)에 `orderStatus == PAID` 가드를 추가한다. REFUND_REQUESTED 상태에서 다운로드가 확정되면 "환불 진행 중인데 다운로드됨"이라는 모순 상태가 되므로 반드시 막는다.

### PaymentStatus (payment-service) — 변경 없음, 의미 재정의

- 부분 환불 진행 중 Payment는 **PAID 유지** (`startRefunding()` 경유 안 함).
- 누적 환불액(`SUM(refund.refund_amount) WHERE status = COMPLETED`)이 `approvedAmount`와 같아지는 순간 Payment → REFUNDED.
- `REFUNDING` 상태는 기존 전체 환불 플로우 제거와 함께 **신규 유입이 사라진다**. enum 값 자체는 남기되(기존 데이터 호환), 재시도 스케줄러의 조회 기준을 Payment.REFUNDING → **Refund.REQUESTED + stale**로 교체한다.

---

## 이벤트 계약

### 신규: `ORDER_REFUND_REQUESTED` (order-events 토픽, order-service 발행)

기존 `OrderEventEnvelope`(version 1) 형식을 따른다.

```json
{
  "orderId": "UUID",
  "orderProductId": "UUID",
  "paymentId": "UUID",
  "buyerId": "UUID",
  "refundAmount": 15000,
  "requestedAt": "2026-07-05T12:00:00"
}
```

- `paymentId`는 `OrderPayment`에서 조회(PAID 주문이므로 반드시 존재). payment-service의 조회 비용을 줄이고 검증 기준을 명확히 한다.
- `refundAmount`는 order-service가 `productAmount` 스냅샷으로 산정한 값. payment-service는 이 값을 신뢰하되 **과환불 방어(누적 ≤ approvedAmount)를 독립적으로 재검증**한다 — confirm의 금액 위변조 방어와 같은 원칙(금액의 최종 검증은 돈을 만지는 쪽이 한다).

### 확장: `payment.refunded` (PaymentRefundedMessage)

```json
{
  "eventType": "payment.refunded",
  "paymentId": "UUID",
  "orderId": "UUID",
  "userId": "UUID",
  "amount": 15000,
  "refundedAt": "...",
  "orderProductId": "UUID",   // 신규
  "refundId": "UUID"          // 신규
}
```

### 신규: `payment.refund-failed` (PaymentRefundFailedMessage)

이 문서가 스키마 확정본이다 (failure-event-publishing.md의 환불 실패 파트를 흡수했으므로 이 이벤트의 발행 로직·스키마 모두 여기서 신규 구현).

```json
{
  "eventType": "payment.refund-failed",
  "paymentId": "UUID",
  "orderId": "UUID",
  "userId": "UUID",
  "refundId": "UUID",
  "orderProductId": "UUID"    // 신규 (해당 문서 대비 추가)
}
```

> **구 failure-event-publishing.md와의 조정 (반영 완료)**: 그 문서의 원안은 환불 실패 시 Order를 `PAID → REFUND_FAILED`로 전이시키는 전제였으나, 이 설계의 **상품 단위 PAID 복구**가 이를 대체한다. Order 레벨 `REFUND_FAILED` 상태는 도입하지 않는다. 결제 실패(`payment.failed`)는 flow-redesign(작업 2)에 흡수되어 진행한다(D5).

> **토픽 단일화**: unify-payment-topic(작업 1)이 선행 완료되므로 이 문서의 발행 이벤트는 모두 `payment.events` 토픽 + eventType 분기 기준으로 구현한다 (`PaymentTopic.PAYMENT_EVENTS` 상수 사용).

---

## order-service 변경 사항

| # | 파일 | 변경 |
|---|---|---|
| 1 | `domain/enums/OrderStatus.java` | `REFUND_REQUESTED` 추가 |
| 2 | `domain/model/OrderProduct.java` | `requestRefund()` (PAID → REFUND_REQUESTED), `refund()` 전이 조건을 REFUND_REQUESTED → REFUNDED로 수정, `failRefund()` (REFUND_REQUESTED → PAID) 추가 |
| 3 | `domain/model/Order.java` | `refundProduct(orderProductId, refundedAt)` — 상품 확정 후 전 상품 REFUNDED 검사 시 Order 전이. 기존 `refund()`(일괄)는 신규 흐름에서 미사용 처리 |
| 4 | `presentation/OrderController.java` | `POST /api/v1/orders/{orderId}/products/{orderProductId}/refund` 추가 (X-User-Id 본인 검증, 202 응답) |
| 5 | `application/service/...` | `RefundOrderProductService` (신규 UseCase): 검증 → 상태 전이 → outbox 기록 (단일 트랜잭션) |
| 6 | `application/service/order/OrderPolicyService.java` | `isRefundable` 로직 유지 — 관리자 확장 대비 검증 진입점을 이 서비스로 일원화 |
| 7 | `.../outbox/OutboxEventAppender.java` | `appendOrderRefundRequested()` 추가 (eventType `ORDER_REFUND_REQUESTED`) |
| 8 | `.../consumer/payment/PaymentEventType.java` | `PAYMENT_REFUND_FAILED("payment.refund-failed")` 추가 |
| 9 | `.../event/OrderPaymentEventService.java` | `handlePaymentRefunded()`: `orderProductId != null`이면 상품 단위 처리, null이면 기존 전체 처리(하위 호환). `handlePaymentRefundFailed()` 신규 |
| 10 | 다운로드 확정 흐름 | **기존 구현으로 충족 — 확인만** — `confirmDownload`가 이미 `order.isPaid() && orderProduct.isPaid()`를 요구하므로 REFUND_REQUESTED 상품은 자동 차단. 회귀 테스트(REFUND_REQUESTED 중 다운로드 시도 → 거부)만 추가 |

- **멱등 소비**: `payment.refunded` 중복 수신 시 이미 REFUNDED인 상품이면 ack 후 무시(기존 중복 체크 패턴 유지).
- **응답 DTO 파급**: `OrderStatus` enum이 프론트에 노출되므로 `REFUND_REQUESTED` 값 추가를 프론트에 공지해야 한다. `isRefundable`은 자동으로 false 처리되므로 추가 작업 없음.

## payment-service 변경 사항

| # | 파일 | 변경 |
|---|---|---|
| 1 | `infrastructure/messaging/` + `config/` | **order-events 컨슈머 확장** — flow-redesign(작업 2)이 신설한 `OrderEventConsumer`에 `ORDER_REFUND_REQUESTED` eventType 분기 추가 (manual ack, groupId `payment-service-group` — 기존 yaml 기본값과 통일) |
| 2 | `application/service/RefundPaymentService.java` | 재작성: command에 `orderProductId`, `refundAmount` 추가. 검증(Payment 존재·상태, 과환불 방어, 동일 상품 활성 Refund 부재) → Refund 생성 → `PaymentRefundRequestedEvent` 발행 |
| 3 | `application/gateway/external/PaymentGateway.java` | `refund(pgTxId, paymentId, orderProductId, amount)` — 멱등성 키 구성용 orderProductId 전달 |
| 4 | `infrastructure/external/toss/TossPaymentGateway.java` | `TossRefundRequest.cancelAmount = refundAmount` (부분 취소), `Idempotency-Key: refund-{paymentId}-{orderProductId}` |
| 5 | `domain/model/Payment.java` | `completePartialRefund(누적환불액)` — 누적 == approvedAmount일 때만 REFUNDED 전이. 기존 `startRefunding()` 경로 신규 흐름에서 미사용 |
| 6 | `infrastructure/messaging/RefundEventHandler.java` | 부분 환불 대응: 성공 시 누적액 판정 후 Payment 전이 여부 결정, `payment.refunded`에 orderProductId·refundId 포함. 실패 시 Refund FAILED + `payment.refund-failed` 발행 |
| 7 | `infrastructure/scheduling/PaymentRefundRetryScheduler.java` | 조회 기준 교체: `Refund.status = REQUESTED AND updated_at < NOW() - 30분`. Toss 멱등성 키가 결정적이므로 재호출 안전 |
| 8 | `domain/repository/RefundRepository.java` | `sumCompletedAmountByPaymentId()`, `existsActiveByPaymentIdAndOrderProductId()` 추가 |
| 9 | `presentation/PaymentController.java` | **기존 `POST /api/v1/payments/{paymentId}/refund` 제거** (breaking change — 프론트 조율 필수) |
| 10 | DDL / `db-schema.md` | refund 테이블에 부분 유니크 인덱스 추가: `UNIQUE (payment_id, order_product_id) WHERE status <> 'FAILED'` — 동시 중복 요청의 최종 방어선 |
| 11 | `.claude/docs/events.md` | 신규/확장 이벤트 계약 반영 |

### 멱등성 키 변경 이유

현재 키 `refund-{paymentId}`는 결제당 환불 1회 전제다. 부분 환불이 2회 발생하면 두 번째 Toss 호출이 첫 번째와 같은 키로 나가 **PG가 중복으로 간주해 무시**한다. `refund-{paymentId}-{orderProductId}`는 상품당 1회 환불 정책과 정확히 일치하는 결정적 키로, 스케줄러 재시도 시에도 같은 요청은 같은 키를 갖는다.

### 동시성 방어 계층

1. order-service: `REFUND_REQUESTED` 상태 → API 레벨 중복 요청 차단
2. Kafka 파티션 키 = orderId → 같은 주문의 환불 요청은 순차 소비
3. payment-service: 활성 Refund 존재 검사 + 부분 유니크 인덱스 → 컨슈머 중복 소비 방어
4. Toss Idempotency-Key → PG 레벨 최종 방어

---

## 구현 순서 (마일스톤)

| 단계 | 내용 | 비고 |
|---|---|---|
| 1 | payment-service: `payment.refund-failed` 발행 기반 + 이벤트 DTO 확장(orderProductId, refundId) | failure-event-publishing.md 환불 실패 부분 흡수 |
| 2 | order-service: `REFUND_REQUESTED` enum·도메인 전이·환불 API·outbox 이벤트·다운로드 가드 | 이 시점부터 요청 접수 가능(소비자 없으면 outbox 대기) |
| 3 | payment-service: order-events 컨슈머 + Toss 부분 취소 + Refund 검증 로직 + 스케줄러 교체 | 핵심 구간 |
| 4 | order-service: `payment.refunded`/`refund-failed` 컨슈머 확장 (상품 단위 확정/복구) | |
| 5 | payment-service: 기존 전체 환불 API 제거 + 프론트 공지 | breaking change, 프론트 전환 완료 후 |
| 6 | 통합 테스트: 양 서비스 Testcontainers(PostgreSQL + Kafka) 기반 E2E 시나리오 | 아래 표 |

### 필수 테스트 시나리오

| 시나리오 | 검증 |
|---|---|
| 정상 부분 환불 | 상품 REFUNDED, Order·Payment PAID 유지, Refund COMPLETED, Toss cancelAmount 정확 |
| 마지막 상품 환불 | Order → REFUNDED, Payment → REFUNDED 파생 전이 |
| PG 취소 실패 | Refund FAILED, `refund-failed` 발행, 상품 PAID 복구, 재요청 가능 |
| 중복 환불 요청 | 두 번째 요청 400 (REFUND_REQUESTED 상태) |
| 과환불 시도 | payment-service 검증에서 거부 (이벤트 위변조 가정) |
| 환불 중 다운로드 확정 | 거부 |
| 다운로드된 상품 환불 | 400 (isRefundable=false) |
| 컨슈머 중복 소비 | Refund 1건만 생성 (부분 유니크 인덱스) |
| 스케줄러 재시도 | stale REQUESTED Refund 재처리, 동일 멱등성 키 |

---

## 리스크 및 열린 이슈

| 항목 | 내용 |
|---|---|
| **Breaking change** | 기존 `POST /payments/{paymentId}/refund` 제거 — 프론트엔드가 order-service의 신규 API로 전환 완료된 후 제거 (마일스톤 5) |
| **Toss 부분 취소 제약 확인** | 카드 결제는 부분 취소 지원이 일반적이나 결제수단(가상계좌 등)에 따라 제약이 있을 수 있음. 구현 전 Toss 개발자 문서에서 사용 중인 결제수단의 부분 취소 지원 여부 확인 필요 |
| **비동기 UX** | 202 응답 후 결과는 주문 조회로 확인(outbox 5초 폴링 + PG 지연). 프론트는 REFUND_REQUESTED 상태를 "환불 처리 중"으로 표시 필요 |
| **failure-event-publishing.md** | **해체 완료(D2·D5)** — 결제 실패는 flow-redesign(작업 2)에 흡수, 환불 실패는 이 문서가 확정본 |
| **REFUNDING enum 잔존** | 신규 유입은 없으나 기존 데이터 호환을 위해 값은 유지. 완전 제거는 데이터 정리 후 후속 과제 |

## 후속 과제 (이번 범위 제외)

- 관리자 강제 환불 API (`OrderPolicyService` 분리로 정책만 갈아끼우면 되는 구조 확보)
- 환불 사유(reason) 수집 및 Toss cancelReason 전달
- 할인(discountAmount) 도입 시 부분 환불 금액 배분 규칙 재설계
- REFUNDING enum 값 정리
