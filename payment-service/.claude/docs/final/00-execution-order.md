# 파이널 작업 실행 순서 및 충돌 해소 결정 (그릴링 세션 결과)

> 2026-07-05 확정. `final/` 폴더 7개 작업 문서 간 충돌을 코드베이스(order-service, payment-service) 교차 검증으로 확인하고 해소한 결과.
> 각 문서 착수 전 이 문서의 "문서별 조정 사항"을 먼저 반영한다.

## 확정 결정 4건

| # | 쟁점 | 결정 |
|---|---|---|
| D1 | 재결제 정책 상충 — flow-redesign "FAILED 후 재결제 허용" vs failure-event "PAYMENT_FAILED 재결제 불가". 현재 `handlePaymentApproved`는 PENDING만 허용하므로 Order를 실패 상태로 전이 후 재결제가 성공하면 승인 이벤트가 거부됨("결제됐는데 주문 미결제") | **`payment.failed` 수신 시 Order 기존 `FAILED` 상태로 전이(신규 `PAYMENT_FAILED` 미도입) + `handlePaymentApproved`가 PENDING·FAILED 양쪽에서 PAID 전이 허용(복귀 경로)**. 서킷 OPEN발 `payment.failed`로 Order가 FAILED 되어도 재결제로 복귀 가능 |
| D2 | failure-event-publishing의 환불 실패 파트가 partial-refund와 중복(구모델 기준) | **failure-event 문서는 `payment.failed` 발행만 남기고 축소**. `payment.refund-failed`는 partial-refund 마일스톤 1에서 신모델 기준으로 구현 |
| D3 | circuit-breaker 환불 파트가 구모델(REFUNDING 유지 + 스케줄러 skip) 기준 | **partial-refund 이후 배치** — 환불 파트를 최종 환불 모델 기준으로 한 번만 작성 |
| D4 | unify-payment-topic 시점 + order-service 변경 누락 | **1순위 선행**. order-service 컨슈머 전환(구독 토픽·DLT)을 범위에 추가하고 "구독자 먼저 전환" 순서로 배포 |
| D5 | (추가 조정) failure-event-publishing 잔여 파트(`payment.failed`)의 배치 | **flow-redesign(작업 2)에 흡수** — confirm 트랜잭션 구조(`noRollbackFor`)를 최종형으로 한 번에 작성하고, D1의 발행(FAILED 전이)과 복귀(재결제)가 같은 작업에서 배포되어 어긋난 중간 상태 없음. 환불 실패는 D2대로 partial-refund에 흡수 유지. **failure-event-publishing.md는 해체(스텁만 유지)** → 작업 7개 → **6개** |

## 파생 확정 (세션에서 정리)

- **`PAY008` 이중 점유 해소**: circuit-breaker(`PG_UNAVAILABLE`) = `PAY008`, rate-limiting(`TOO_MANY_REQUESTS`) = **`PAY009`로 변경** (구현 순서 기준).
- **order-events 컨슈머는 1개**: flow-redesign(작업 2)에서 신설, partial-refund(작업 3)에서 eventType 분기 확장. groupId는 application.yaml 기존 기본값 **`payment-service-group`으로 통일** (partial-refund 문서의 `payment-service` 표기는 오기).
- **다운로드 경합 가드는 이미 구현됨**: `confirmDownload`가 `order.isPaid() && orderProduct.isPaid()`를 요구하므로 상품이 REFUND_REQUESTED면 자동 차단. partial-refund의 해당 항목은 "확인 테스트 추가"로 축소.
- **`REFUND_FAILED` Order 상태 미도입**: partial-refund의 상품 단위 PAID 복구가 대체 (D2에 포함).

## 실행 순서 (D5 반영 — 6개 체계)

| 순서 | 작업 | 규모 | 선행 의존 |
|---|---|---|---|
| 1 | **unify-payment-topic** (+ order-service 컨슈머 전환) | 소 | — |
| 2 | **order-payment-flow-redesign** (payment.failed 발행·소비 포함) | 대 | 1 (이벤트 작업 기준 토픽 확정) |
| 3 | **partial-refund-api** (refund-failed 발행 포함) | 대 | 2 (order-events 컨슈머 인프라 재사용) |
| 4 | **circuit-breaker** | 중 | 3 (환불 최종 모델 기준으로 작성), 2 (payment.failed 발행 경로 선행) |
| 5 | **rate-limiting** | 소 | 2 (amount 제거된 컨트롤러 기준) |
| 6 | **audit-log** | 중 | 2·3·4 (최종 코드에 계측, doConfirm/doRefund는 4가 생성) |

- 2→3은 대형 구조 변경 연속이므로 **각 작업 완료 시 통합 테스트 그린 확인 후 다음 착수**.
- 보호 장치(4·5)가 후순위인 대신 그 사이 PG 장애 대응력은 현행 수준 — 인지된 트레이드오프.
- ~~작업 4 failure-event-publishing(축소판)~~ → D5로 flow-redesign(작업 2)에 흡수, 문서 해체.

## 문서별 조정 사항 (착수 전 반영)

### 1. unify-payment-topic.md
- **범위 추가**: order-service `PaymentEventConsumer` 구독 토픽을 `payment.approved`/`payment.refunded` → `payment.events`로 전환, DLT 토픽명 파생(`payment.events.DLT`) 확인, 관련 통합 테스트 수정.
- **배포 순서 명시**: 구독자(order) 먼저 신토픽 구독 추가 → 발행자(payment) 전환 → 구토픽 정리.

### 2. order-payment-flow-redesign.md
- 원안 유효. 컨슈머 groupId `payment-service-group` 명시.
- **(D5) payment.failed 발행·소비 흡수** — payment-service: `PaymentFailedEvent`/`PaymentFailedMessage` 신규, confirm 트랜잭션 최종형(`noRollbackFor`) + catch 발행, `onPaymentFailed()` 리스너. order-service: `PAYMENT_FAILED` 소비(`handlePaymentFailed`, PENDING → FAILED) + `handlePaymentApproved` 완화(PENDING·FAILED → PAID, D1).
- 참고: seller gRPC 포트 "9081 관례" 근거는 코드와 불일치(Config 기본값 9091 vs yaml 9081)하나 신규 order gRPC 9083 결정에는 영향 없음.

### 3. partial-refund-api.md
- 토픽 참조를 `payment.events` 기준으로 치환(1번 선행 완료 전제).
- groupId `payment-service` → `payment-service-group`.
- order-service 변경 #10(다운로드 가드)을 "기존 구현 확인 + 회귀 테스트"로 축소.
- `payment.refund-failed` 스키마(orderProductId 포함)는 이 문서가 확정본.

### 4. failure-event-publishing.md — **해체됨(D5)**
- ~~축소판(payment.failed만)으로 유지~~ → **D5로 문서 자체를 해체**: 결제 실패 파트는 flow-redesign(위 §2)에, 환불 실패 파트는 partial-refund(아래 §3 문서)에 흡수. 파일은 링크 안정성용 스텁만 잔존(팀 확인 후 삭제 가능).
- (이력) D1 반영: "PENDING → PAYMENT_FAILED (재결제 불가)" → "PENDING → FAILED (기존 상태 재사용, 재결제 시 FAILED → PAID 복귀)" — 이 결정은 flow-redesign에 승계됨.

### 5. circuit-breaker.md
- confirm 파트(서킷 래핑, doConfirm 추출, ConfirmPaymentService catch)는 원안 유효 — 단, flow-redesign 이후의 confirm 코드 기준으로 적용.
- **환불 파트 재작성**: 대상이 RefundEventHandler(구모델) → 신규 order-events 환불 컨슈머 흐름. OPEN 시 "Payment REFUNDING 유지" → "Refund REQUESTED 유지(트랜잭션 롤백)", 스케줄러 skip 로직은 신스케줄러(Refund.REQUESTED stale 조회) 기준.
- `PG_UNAVAILABLE` = `PAY008` 확정.

### 6. rate-limiting.md
- `TOO_MANY_REQUESTS` = **`PAY009`**로 변경.
- 컨트롤러 변경 스니펫에서 `request.amount()` 제거(flow-redesign 이후 계약 `{paymentKey, orderId}` 기준으로 리베이스).

### 7. audit-log.md
- 계측 지점 재산정: `RefundPaymentService.startRefunding()` 지점 삭제(신규 유입 소멸), RefundEventHandler·스케줄러 지점을 부분 환불 전이(`completePartialRefund`, Refund 단위 전이) 기준으로 갱신.
- `doConfirm()`/`doRefund()` 계측은 5번(circuit-breaker) 완료 전제.

## 코드 검증에서 확인된 기타 사실 (착수 시 참고)

- `PaymentErrorCode` 현황: `V001`, `PAY002`~`PAY007`, `PAY_FAILED` — PAY008·PAY009 미할당.
- payment-service 컨슈머 0개, 환불 멱등키는 `TossPaymentGateway` 레벨에서 `refund-{paymentId}`.
- order-service `OrderStatus`: PENDING / PAID / FAILED / CANCELED / REFUNDED (`markFailed()` 기구현).
- order-service 테스트는 H2 + outbox 릴레이 비활성화 패턴, payment-service는 Testcontainers 정책 — partial-refund 마일스톤 6의 양 서비스 E2E는 각 서비스 테스트 체계 안에서 분리 검증.
