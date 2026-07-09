# 파이널 작업 전체 개요

> **이 문서의 역할**: `final/` 폴더에 처음 온 사람이 전체 그림을 잡는 온보딩 진입점.
> 결정의 근거·문서별 조정 이력은 [00-execution-order.md](00-execution-order.md)(결정 기록)가 담당한다.
> **작업 순서나 결정이 바뀌면 두 문서를 함께 갱신할 것.**

## 1. 무엇을 하는 작업인가

order-service와 payment-service에 걸친 **결제·환불 시스템 개편 6개 작업**이다. 목표는 다섯 가지:

1. **금액의 진실 공급원 교정** — 현재 결제 금액은 클라이언트가 confirm 요청에 실어 보내는 값을 그대로 Toss에 전달한다(위변조 가능). 주문 스냅샷 기반 서버 검증으로 전환.
2. **환불 단위 세분화** — 현재는 결제 전체 환불만 가능. 주문상품 단위 부분 환불로 전환하고, 환불 진입점을 정책 소유자인 order-service로 이동.
3. **실패의 가시화** — 결제·환불 실패가 order-service에 전달되지 않아 주문 상태가 실제와 어긋나는 문제 해소.
4. **장애 대응** — Toss PG 장애 시 서킷 브레이커, 대량 요청 시 유량 제어.
5. **관측성** — 상태 전환·PG 호출의 구조화 감사 로그.

## 2. 문서 지도

| 순서 | 문서 | 한 줄 요약 | 규모 |
|---|---|---|---|
| — | [README.md](README.md) | 이 문서 — 전체 개요·온보딩 진입점 | — |
| — | [00-execution-order.md](00-execution-order.md) | 충돌 해소 결정 기록(D1~D5)·문서별 조정 이력 | — |
| 1 | [unify-payment-topic.md](unify-payment-topic.md) | payment 발행 토픽을 `payment.events` 단일 토픽으로 통합 (+order 컨슈머 전환) | 소 |
| 2 | [order-payment-flow-redesign.md](order-payment-flow-redesign.md) | ORDER_CREATED 이벤트 + 주문 스냅샷 기반 confirm 재설계 + gRPC 폴백 + `payment.failed` 발행·소비(재결제 복귀) | 대 |
| 3 | [partial-refund-api.md](partial-refund-api.md) | 주문상품 단위 부분 환불 (order 진입 → Kafka → payment 실행) + `payment.refund-failed` | 대 |
| 4 | [circuit-breaker.md](circuit-breaker.md) | Toss 호출 서킷 브레이커 (confirm/refund 독립 서킷) | 중 |
| 5 | [rate-limiting.md](rate-limiting.md) | confirm 동시 요청 유량 제어 (Semaphore, 429) | 소 |
| 6 | [audit-log.md](audit-log.md) | 결제 감사 로그 (상태 전환 + PG 호출, JSON) | 중 |

> `failure-event-publishing.md`는 **해체됨(D5)** — 결제 실패 파트는 작업 2에, 환불 실패 파트는 작업 3에 흡수. 파일은 스텁만 잔존.

**순서의 핵심 근거** (상세는 00-execution-order.md):

- 1이 먼저 → 이후 모든 이벤트 작업이 `payment.events` 기준으로 한 번에 작성됨
- 2가 3~6보다 먼저 → confirm 계약·컨슈머 인프라·재결제 정책(D1)을 나머지가 전제함
- 3이 4·6보다 먼저 → 환불 모델(REFUNDING 폐기)이 확정돼야 서킷·계측을 최종형으로 한 번만 작성
- 6이 마지막 → 계측은 움직이지 않는 코드 위에
- 2→3 연속 대형 변경 구간은 **각 작업 완료 시 통합 테스트 그린 확인 후 다음 착수**

## 3. 결제(confirm) 흐름 — 전/후

**현재**: 금액을 클라이언트가 제시하고, 검증은 결제 이후 order가 사후에 한다.

```
client ── POST /orders ──▶ order (PENDING, totalAmount 응답)
client ── Toss UI 결제
client ── POST /payments/confirm {paymentKey, orderId, amount} ──▶ payment
              payment ── Toss confirm(클라이언트 amount 그대로) ──▶ PAID
              payment ── payment.approved 발행
order  ◀─ 소비: 사후 금액 검증 → PAID (불일치면 이미 결제된 뒤에야 발견)
```

**최종**: 금액의 진실 공급원이 주문 스냅샷. 평상시 이벤트, 유실 시 gRPC 폴백.

```
client ── POST /orders ──▶ order: 주문 저장 + outbox(ORDER_CREATED) [같은 트랜잭션]
                            └─ relay ──▶ Kafka order-events ──▶ payment: order_snapshot 저장
client ── Toss UI 결제
client ── POST /payments/confirm {paymentKey, orderId} ──▶ payment   ※ amount 없음
              1. 스냅샷 확보 (없으면 order gRPC 폴백 :9083 → 그것도 실패면 503, Payment 미생성)
              2. 본인 검증 (snapshot.buyerId ≠ X-User-Id → 403)
              3. 중복 판정 (진행·완료 상태만 409 — FAILED뿐이면 재결제 허용)
              4. Toss confirm(스냅샷 금액) ── 성공 ──▶ PAID → payment.events(approved)
                                          └─ 실패 ──▶ FAILED → payment.events(failed)
order  ◀─ approved 소비: PENDING·FAILED → PAID (재결제 복귀 허용)
order  ◀─ failed  소비: PENDING → FAILED (PAID엔 무시 — 늦은 이벤트 방어)
```

## 4. 환불 흐름 — 전/후

**현재**: payment가 진입점, 결제 전액 환불만.

```
client ── POST /payments/{paymentId}/refund ──▶ payment (202)
              PAID → REFUNDING, Refund 생성
              [AFTER_COMMIT] Toss 전액 취소 ── 성공: REFUNDED → payment.refunded
                                            └─ 실패: PAID 복원 (order는 모름)
scheduler: Payment REFUNDING 30분 stale → 재시도
order  ◀─ payment.refunded → Order REFUNDED
```

**최종**: order가 진입점(정책 소유), 상품 1건 단위, 실패도 이벤트로 회신.

```
client ── POST /orders/{orderId}/products/{opId}/refund ──▶ order (202)
              검증: 본인·Order PAID·isRefundable(PAID && !downloaded)
              OrderProduct: PAID → REFUND_REQUESTED (중복 요청 자동 차단)
              outbox(ORDER_REFUND_REQUESTED) ──▶ Kafka order-events
payment ◀─ 소비: 과환불 방어(누적+요청 ≤ 승인액) → Refund 생성(REQUESTED)
              Toss 부분 취소 (cancelAmount, 멱등키 refund-{paymentId}-{orderProductId})
              ├─ 성공: Refund COMPLETED. 누적==승인액이면 Payment PAID → REFUNDED
              │        payment.events(refunded, orderProductId 포함)
              └─ 실패: Refund FAILED → payment.events(refund-failed)
order  ◀─ refunded 소비: 상품 REFUNDED. 전 상품 REFUNDED면 Order → REFUNDED
order  ◀─ refund-failed 소비: 상품 PAID 복구 (재요청 가능)
scheduler: Refund REQUESTED 30분 stale → 재시도 (Payment REFUNDING 경로는 폐기)
```

## 5. 이벤트·통신 구조 — 전/후

| 구분 | 현재 | 최종 |
|---|---|---|
| payment 발행 | 토픽 2개 (`payment.approved`, `payment.refunded`) | **단일 토픽 `payment.events`**, eventType 4종 (`payment.approved` / `payment.failed` / `payment.refunded` / `payment.refund-failed`) |
| order 발행 (`order-events`) | `ORDER_PAID`, `ORDER_REFUND` | + `ORDER_CREATED`, `ORDER_REFUND_REQUESTED` |
| payment 소비 | 없음 | `order-events` (`ORDER_CREATED`, `ORDER_REFUND_REQUESTED` — 컨슈머 1개, groupId `payment-service-group`) |
| order 소비 | `payment.approved`, `payment.refunded` | `payment.events` eventType 4종 분기 |
| gRPC | order → product(:9082), order → seller | + **payment → order(:9083)** `GetOrderPaymentInfo` — 스냅샷 유실 시 폴백 전용 |

## 6. 상태 모델·계약 변화 요약

**상태 모델**

- `OrderStatus`: `REFUND_REQUESTED` 추가(OrderProduct 레벨 중간 상태). 기존 `FAILED`가 "결제 실패" 의미로 활용되며 **FAILED → PAID 복귀 허용**(재결제). 신규 `PAYMENT_FAILED`/`REFUND_FAILED`는 도입하지 않음.
- `PaymentStatus`: `REFUNDING` 신규 유입 소멸(enum 값은 데이터 호환 위해 잔존). 부분 환불 중 Payment는 PAID 유지, 완납 시에만 REFUNDED.
- Payment 멱등키: `pay-{orderId}` → **paymentKey** (FAILED 후 재결제 시 시도마다 새 행).

**클라이언트 breaking changes (FE 공유 필수)**

| 항목 | 변경 |
|---|---|
| `POST /payments/confirm` | body에서 `amount` 제거. 403(타인 주문)·404(주문 없음)·503(주문 정보 확보 불가, 재시도 대상)·429(혼잡) 신규. 409는 "진행·완료 상태 존재 시만"으로 의미 변화 |
| 환불 API | `POST /payments/{paymentId}/refund` **제거** → `POST /orders/{orderId}/products/{orderProductId}/refund` (202, 결과는 주문 조회로 확인) |
| `OrderStatus` 노출 값 | `REFUND_REQUESTED` 추가 — "환불 처리 중" 표시 필요 |

**에러 코드 신설**: `PAY008` PG_UNAVAILABLE(503, 서킷 OPEN) · `PAY009` TOO_MANY_REQUESTS(429) · `ORDER_INFO_UNAVAILABLE`(503) · `ORDER_NOT_FOUND`(404)

## 7. 횡단 원칙 (모든 작업 공통)

- **outbox 우선**: order 측 이벤트 발행은 기존 outbox 패턴 재사용 (주문 트랜잭션과 원자성).
- **커밋 후 발행**: payment 측 Kafka 발행은 모든 경로에서 트랜잭션 커밋 이후 (`@TransactionalEventListener(AFTER_COMMIT)` 또는 `TransactionSynchronization.afterCommit()`).
- **멱등성 다층 방어**: 상태 기반 중복 판정 → DB 부분 유니크 인덱스(수동 DDL) → Kafka 파티션 키(orderId) 순차 소비 → Toss Idempotency-Key.
- **돈을 만지는 쪽이 최종 검증**: 이벤트 페이로드의 금액을 신뢰하되 payment가 독립 재검증(과환불 방어 등).
- **아키텍처 경계**: 외부 기술 예외(Resilience4j 등)는 infrastructure에서 변환, application 레이어에 미노출.
- **테스트**: payment는 Testcontainers(PostgreSQL+Kafka) 정책, order는 자체 테스트 체계 — E2E는 각 서비스 테스트로 분리 검증.

## 8. 처음 읽는 사람의 권장 경로

1. 이 문서 (전체 그림)
2. [00-execution-order.md](00-execution-order.md) (왜 이 순서·결정인지)
3. 착수할 작업의 개별 문서 — 각 문서 상단의 조정 주석(`> 2026-07-05 조정`)이 반영된 최신 상태다
