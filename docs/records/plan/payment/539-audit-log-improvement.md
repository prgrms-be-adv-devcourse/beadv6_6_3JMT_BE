# 감사로그 캡처 확대 구현 계획

CS 문의·장애 비즈니스 분석·시간순 흐름 확인·감사/분쟁 대응 4가지 목적에 맞춰, 기존 write-only `audit_log`(#484)에 빠져있던 `order_id`·실패 코드·결제 시도 이벤트를 채우고 이벤트 타입 명명을 결제/환불 대칭 구조로 정리한다. 조회 API는 이번 스코프 밖.

---

## 배경 및 목표

`audit_log`는 #484에서 결제/환불의 **종결 상태 전이 4종**(`PAYMENT_APPROVED`/`PAYMENT_FAILED`/`PAYMENT_REFUNDED`/`PAYMENT_REFUND_FAILED`)만 write-only로 저장하도록 만들어졌다. 조회 API는 당시 스코프 밖으로 명시적으로 남겨뒀고, 이번에도 마찬가지다.

실제 활용 목적(CS 문의, 장애의 비즈니스 관점 분석, 시간순 처리 흐름 확인, 감사/분쟁 대응)을 놓고 현재 구조를 다시 보면 아래 갭이 있다.

- `entity_id`가 `payment.id`/`refund.id`뿐이라 CS가 보통 들고 오는 `order_id`로 바로 조회할 방법이 없다. 같은 주문에 결제 재시도가 있으면 payment row가 여러 개 생겨 order 단위 타임라인을 보려면 조인이 필요하다.
- `Payment.failureCode`(PG 실패 코드)가 엔티티엔 있는데 `audit_log.detail`엔 실리지 않는다. 실패 사유가 자유 텍스트뿐이라 장애 유형별 집계(비즈니스 관점 분석)가 어렵다.
- 결제 시도 시작(READY→REQUESTED) 이벤트가 없어 시도 자체가 감사로그에 안 남는다. 재시도 횟수나 "요청은 갔는데 응답을 못 받은" 상황을 시간순으로 보기 어렵다.

이번 작업은 조회 API 없이도 **나중에 조회 API를 붙일 때 필요한 데이터가 지금부터 빠짐없이 쌓이도록** 캡처 범위와 스키마만 확장한다.

## 설계 결정

**`audit_log`에 `order_id` 컬럼을 추가한다.** 모든 팩토리 호출 시점에 `Payment` 인스턴스가 항상 있어 `payment.getOrderId()`로 구할 수 있으므로 `NOT NULL`로 둔다. CS 문의는 거의 항상 `order_id` 기준으로 들어오는데, 지금은 `payment`/`refund` 테이블을 먼저 조인해야 알 수 있다. 이 컬럼을 두면 조회 API가 생기기 전에도 DB를 직접 볼 때 order 단위로 바로 필터링할 수 있고, 조회 API를 만들 때도 조인 없이 바로 쓸 수 있다.

**`audit_log`에 `failure_code` 컬럼을 추가하고, `Refund` 엔티티에도 `failure_code` 컬럼을 새로 추가한다.** `Payment.failureCode`는 이미 있으니 감사로그 팩토리가 그대로 읽으면 된다. `Refund`는 실패 사유(`failureReason`) 텍스트만 있고 코드 개념이 없는데, PG 환불 실패 시 `TossPaymentGateway.refund()`가 이미 `TossErrorResponse.code()`를 `PaymentGatewayException.getFailureCode()`로 넘겨주고 있음에도 `RefundService`가 이를 버리고 있었다. 이 값을 살려 `Refund` 엔티티에 저장하면, PG사 실패 코드별 빈도 집계 같은 비즈니스 관점 장애 분석이 텍스트 매칭 없이 가능해진다. 잔액 초과처럼 PG 호출 없는 내부 사유는 `"REFUND_LIMIT_EXCEEDED"` 같은 내부 코드를 부여한다 — PG 코드와 같은 컬럼에 자유 문자열로 섞여 들어가는 값이라 별도 enum을 새로 만드는 실익은 없다고 판단했다(스코프 최소화).

**결제 시도 시작 시점에 `PaymentRequestedEvent`를 신설해 감사로그에 남긴다.** `ConfirmPaymentService`의 TX1(READY 생성 후 즉시 REQUESTED로 전이, 커밋)에서 `markRequested()` 직후 발행한다. 별도로 "결제 생성(READY)" 이벤트는 만들지 않는다 — 코드를 확인해보니 TX1 안에서 READY 생성과 REQUESTED 전이가 연속으로 일어나고 중간에 실패하면 트랜잭션 전체가 롤백되므로, READY 상태만 단독으로 커밋되는 경우가 존재하지 않는다. 커밋 시점 기준 최초로 확정되는 상태가 이미 REQUESTED이기 때문에 `PAYMENT_REQUESTED` 하나로 "시도 시작"을 정확히 표현할 수 있다. 이 이벤트는 `AuditLogEventListener`만 구독하고 `KafkaPaymentEventPublisher`는 구독하지 않는다 — 외부 이벤트 계약(Kafka 토픽/스키마) 확장은 실제 소비 니즈가 확인되지 않은 상태에서 임의로 하지 않는다는 원칙(`CLAUDE.md`)에 따른 것이고, 이번 목적은 payment-service 내부 감사 이력 확보이지 타 서비스로의 이벤트 전파가 아니다.

**`trace_id`(또는 request_id) 컬럼은 이번 스코프에서 제외한다.** 모노레포 전체에 분산 추적 인프라(Micrometer Tracing 등)나 게이트웨이의 request-id 전달 자체가 없어, 붙이더라도 타 서비스 로그와 상관관계를 맺을 수 없다. 이 서비스는 HTTP 호출 1건이 엔티티 1건과 1:1로 대응하는 구조(confirm 1회 = payment 1건, refund 1회 = refund 1건)라 `entity_id` 자체가 이미 "그 요청"을 유일하게 식별하므로 별도 식별자가 주는 한계효용이 낮다. 또한 목적 2("기술적 관점이 아닌 비즈니스 관점")와도 어긋난다 — trace_id의 효용은 결국 감사로그 한 줄에서 그 시점 애플리케이션 로그로 건너뛰는 기술적 상관관계 추적이다. 분산 추적 도입은 서비스 하나가 단독으로 결정할 사안이 아니라 별도 이슈로 다룬다.

**환불 이벤트 타입 명명을 결제와 대칭 구조로 바꾼다.** 기존 `PAYMENT_REFUNDED`/`PAYMENT_REFUND_FAILED`를 각각 `REFUND_COMPLETED`/`REFUND_FAILED`로 바꾸고 `REFUND_REQUESTED`를 신설해, 최종 `AuditEventType`은 `PAYMENT_REQUESTED`/`PAYMENT_APPROVED`/`PAYMENT_FAILED`/`REFUND_REQUESTED`/`REFUND_COMPLETED`/`REFUND_FAILED` 6종이 된다. 이 변경은 **`AuditEventType`(감사로그 전용 분류값) 안에서만** 일어난다 — 도메인 이벤트 Java 클래스명(`PaymentRefundedEvent`, `PaymentRefundFailedEvent`)과 `infrastructure.messaging.PaymentEventType`(Kafka 메시지 타입 코드, 외부 서비스와의 계약)은 그대로 둔다. 두 enum은 이름이 우연히 비슷할 뿐 원래도 독립된 개념이라는 것이 #484의 설계 결정이었고, 이번에도 그 경계를 유지한다.

**`REFUND_REQUESTED`는 별도 도메인 이벤트를 만들지 않고, 기존 리스너가 감사로그 2건을 쓰는 방식으로 구현한다.** `PaymentRefundedEvent`/`PaymentRefundFailedEvent`를 받는 리스너가 `REFUND_REQUESTED`(occurred_at = `refund.getRequestedAt()`)와 터미널 이벤트(`REFUND_COMPLETED`/`REFUND_FAILED`)를 함께 저장한다. **단, 이는 이름만 대칭일 뿐 실제 중간 상태 커밋은 아니다.** `RefundService.refund()`는 클래스 레벨 `@Transactional` 하나로 묶여 있고, `Refund`는 PG 호출(네트워크 I/O) 이후 최종 상태로 확정될 때 딱 한 번만 저장된다 — `ConfirmPaymentService`처럼 REQUESTED 상태를 먼저 커밋하고 PG 호출은 트랜잭션 밖에서 하는 구조가 아니다. 따라서 지금 구조에서 `REFUND_REQUESTED`는 항상 터미널 이벤트와 같은 트랜잭션 커밋 시점에 함께 기록되며, PG 호출 도중 크래시/타임아웃으로 멈춘 건은 여전히 감사로그에 안 남는다(그 경우 DB에 `Refund` row 자체가 안 생긴다). 이를 실제로 해결하려면 `RefundService`도 TX1(REQUESTED 커밋)/TX2(PG 호출 후 확정) 구조로 재구성해야 하는데, 이는 결제 승인 흐름에 이미 있는 것과 같은 패턴이지만 환불의 동시성(현재 `FOR UPDATE` 락을 PG 호출 전체 동안 물고 있음)과 트랜잭션 경계를 바꾸는 **구조 변경**이라 회귀 리스크가 있다. 감사로그 개선과는 다른 성격의 작업이라 별도 이슈로 분리한다.

**`PaymentRefundFailedEvent`에서 `failureReason` 파라미터를 제거하고 `(Payment payment, Refund refund)`로 단순화한다.** #484 설계 당시엔 `Refund`에 `failureReason` 컬럼이 없어 이벤트가 값을 직접 실어 날라야 했지만, 이후 V9(`add_refund_failure_reason_and_failed_at`)로 `Refund.failureReason` 컬럼이 생겼다. `RefundService`가 이벤트를 발행하는 시점엔 이미 `refund.fail(...)`이 호출된 뒤라 `refund.getFailureReason()`/`refund.getFailureCode()`로 직접 읽을 수 있어 이벤트가 값을 중복으로 들고 다닐 이유가 없다. `KafkaPaymentEventPublisher.onPaymentRefundFailed`를 확인한 결과 이 필드를 쓰지 않고 있어(payload엔 orderId·금액·시각만 포함) 파라미터 제거가 Kafka 발행에 영향을 주지 않는다. `PaymentFailedEvent`가 `Payment` 하나만 들고 다니는 기존 패턴과도 통일된다.

## 데이터 모델 변경 (V10)

`audit_log`:

| 컬럼 | 변경 | 비고 |
|---|---|---|
| `order_id` | 신규, `UUID NOT NULL` | `entity_type`에 따라 `payment.order_id` 또는 `refund.payment_id`→`payment.order_id`에서 백필 |
| `failure_code` | 신규, `VARCHAR(50)` nullable | `PAYMENT_FAILED`/`REFUND_FAILED`만 값 존재, 나머진 NULL (`detail`과 동일 패턴) |
| `event_type` CHECK | 값 집합 교체 | `PAYMENT_REQUESTED`/`PAYMENT_APPROVED`/`PAYMENT_FAILED`/`REFUND_REQUESTED`/`REFUND_COMPLETED`/`REFUND_FAILED` |

기존 값 마이그레이션(운영 데이터가 있을 경우 대비): `UPDATE audit_log SET event_type = 'REFUND_COMPLETED' WHERE event_type = 'PAYMENT_REFUNDED'`, `... 'REFUND_FAILED' WHERE event_type = 'PAYMENT_REFUND_FAILED'` 실행 후 CHECK 제약 교체. `order_id`는 `entity_type = 'PAYMENT'`인 행은 `payment` 테이블 조인으로, `entity_type = 'REFUND'`인 행은 `refund` → `payment` 2단 조인으로 백필한 뒤 `NOT NULL` 제약을 건다.

인덱스: 기존 `idx_audit_log_entity` 유지, `order_id` 조회 대비 `idx_audit_log_order` 신규 추가(CS 문의가 주로 order_id 기준이므로).

`refund`:

| 컬럼 | 변경 | 비고 |
|---|---|---|
| `failure_code` | 신규, `VARCHAR(50)` nullable | PG 실패 코드 또는 내부 사유 코드(`REFUND_LIMIT_EXCEEDED`) |

## 이벤트 캡처 흐름

```
ConfirmPaymentService.confirm()
  TX1: Payment.create() → markRequested() → commit
       └─ publishEvent(PaymentRequestedEvent)      → AuditLog(PAYMENT_REQUESTED)   [AuditLogEventListener만 구독]
  (Toss confirm 호출, 트랜잭션 밖)
  TX2: approve() → commit
       └─ publishEvent(PaymentApprovedEvent)       → AuditLog(PAYMENT_APPROVED) + Kafka
  TX3(실패 시): fail() → commit
       └─ publishEvent(PaymentFailedEvent)         → AuditLog(PAYMENT_FAILED, failure_code 포함) + Kafka

RefundService.refund()  (단일 트랜잭션)
  Refund.create() (in-memory)
  (Toss refund 호출, 트랜잭션 안 — 기존 구조 유지, 이번 스코프 아님)
  성공: complete() → save → commit
       └─ publishEvent(PaymentRefundedEvent)
            → AuditLog(REFUND_REQUESTED, occurred_at=refund.requestedAt)
            → AuditLog(REFUND_COMPLETED) + Kafka
  실패: fail(failureCode, failureReason) → save → commit
       └─ publishEvent(PaymentRefundFailedEvent)
            → AuditLog(REFUND_REQUESTED, occurred_at=refund.requestedAt)
            → AuditLog(REFUND_FAILED, failure_code 포함) + Kafka
```

## 코드 변경 지점

- `domain.event.PaymentRequestedEvent` 신규 (`record PaymentRequestedEvent(Payment payment)`)
- `domain.event.PaymentRefundFailedEvent` — `failureReason` 파라미터 제거, `(Payment payment, Refund refund)`로 축소
- `domain.model.AuditEventType` — 6종으로 재정의
- `domain.model.AuditLog`
  - 필드 추가: `orderId`, `failureCode`
  - `forPaymentRequested(Payment)` 신규
  - `forPaymentApproved(Payment)` / `forPaymentFailed(Payment)` — `orderId` 반영, `forPaymentFailed`는 `failureCode`도 반영
  - `forPaymentRefunded` → `forRefundRequested(Payment, Refund)` + `forRefundCompleted(Payment, Refund)`로 분리
  - `forPaymentRefundFailed` → `forRefundRequested(Payment, Refund)` + `forRefundFailed(Payment, Refund)`로 분리(파라미터에서 `failureReason` 제거, `refund.getFailureCode()`/`getFailureReason()` 직접 사용)
- `domain.model.Refund`
  - `failureCode` 필드 추가
  - `fail(String failureCode, String failureReason, OffsetDateTime failedAt)`로 시그니처 변경
- `application.service.ConfirmPaymentService` — TX1에서 `markRequested()` 직후 `PaymentRequestedEvent` 발행 추가
- `application.service.RefundService`
  - `failByExceedingLimit`: `refund.fail("REFUND_LIMIT_EXCEEDED", "...", now)`
  - `executeGatewayRefund` catch절: `refund.fail(e.getFailureCode(), e.getFailureReason(), now)` — 지금 버려지던 `getFailureCode()` 활용
  - `PaymentRefundFailedEvent` 생성자 호출부 파라미터 축소 반영
- `infrastructure.persistence.AuditLogEventListener`
  - `onPaymentRequested` 리스너 추가
  - `onPaymentRefunded`/`onPaymentRefundFailed` — 각각 감사로그 2건(REQUESTED + 터미널) 저장하도록 변경
- `src/main/resources/db/migration/V10__extend_audit_log_and_refund_failure_code.sql` 신규

## 테스트 계획

- `AuditLogJpaRepositoryTest`: `order_id`/`failure_code` 포함 라운드트립 검증 갱신
- `AuditLogEventListenerTest`: 6개 이벤트(신규 `PaymentRequestedEvent` 포함) 각각 올바른 필드값으로 저장되는지 검증, `onPaymentRefunded`/`onPaymentRefundFailed`가 2건씩 저장하는지 검증 추가
- `RefundServiceTest`: 잔액초과/PG실패 양쪽 다 `failureCode`가 `Refund`/이벤트에 반영되는지 검증 추가
- `ConfirmPaymentServiceTest`: `PaymentRequestedEvent` 발행 검증 추가
- 기존 테스트 중 `Refund.fail(...)` 시그니처, `PaymentRefundFailedEvent` 생성자, `AuditLog` 팩토리 메서드명이 바뀌는 지점 전부 컴파일 갱신 필요(`RefundServiceTest`, `KafkaPaymentEventPublisherTest` 등)

## 트레이드오프 / 향후 과제

- **조회 API는 이번 스코프 밖.** 이번 작업으로 쌓이는 `order_id`/`failure_code`/`PAYMENT_REQUESTED`/`REFUND_REQUESTED` 데이터를 실제로 CS·장애분석·타임라인·분쟁대응에 쓰려면 별도 조회 API 이슈가 필요하다. 인가 모델(운영자 조회를 누가 어떤 조건으로 볼지)도 그때 같이 정한다.
- **`RefundService` 트랜잭션 구조 개선은 별도 이슈.** `REFUND_REQUESTED`가 실제 PG 호출 전 커밋 시점을 반영하게 하려면 `ConfirmPaymentService`와 같은 TX1/TX2 분리가 필요하고, 이는 환불 동시성 락 유지 시간을 바꾸는 구조 변경이라 감사로그 작업과 분리한다.
- **`trace_id`/분산 추적은 이번 스코프 밖.** 모노레포 전체의 추적 인프라 부재가 근본 원인이라 payment-service 단독으로 결정할 사안이 아니다.

## 문서 갱신

- `.claude/docs/db-schema.md`의 `audit_log`/`refund` 테이블 섹션을 변경된 컬럼 기준으로 갱신
