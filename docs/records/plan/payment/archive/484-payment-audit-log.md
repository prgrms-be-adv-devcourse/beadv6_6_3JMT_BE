# 결제/환불 감사로그 구현 계획

결제·환불 상태가 종결(승인/실패/완료/실패)될 때마다 행위자·시각·사유를 별도 `audit_log` 테이블에 append-only로 남겨, 분쟁·장애 조사 시 이력을 조회할 수 있게 한다.

---

## 배경 및 목표

`payment`/`refund` 테이블은 현재 상태(`status`)만 유지하는 구조라, 과거에 어떤 전이가 언제·누구에 의해 발생했는지 이력이 남지 않는다. `request_payload`/`response_payload` 컬럼이 PG 원문은 보존하지만 "누가(user_id) 언제(occurred_at) 어떤 사건(event_type)을 겪었는지"를 한 곳에서 조회할 수 있는 이력 테이블은 없다.

이번 작업의 목표는 결제/환불 도메인의 **종결 상태 전이 4종**(승인/실패/환불완료/환불실패)에 한해 감사 이력을 DB에 저장하는 것까지다. 조회 API는 스코프 밖 — 실제 조회 니즈(누가 어떤 조건으로 볼지, 인가 모델을 어떻게 할지)가 구체화되면 별도 이슈로 다룬다.

## 설계 결정

**감사 대상은 기존 도메인 이벤트 4종(`PaymentApprovedEvent`, `PaymentFailedEvent`, `PaymentRefundedEvent`, `PaymentRefundFailedEvent`)으로 한정한다.** READY→REQUESTED 같은 중간 전이는 포함하지 않는다. 분쟁·컴플라이언스 조회는 대부분 "이 결제가 승인됐는가", "환불이 처리됐는가" 같은 종결 상태를 묻지, 진행 중 상태를 묻지 않는다. 중간 전이까지 넣으려면 새 도메인 이벤트 설계가 필요해 범위가 커지는데, 지금은 그 실익이 없다. UNKNOWN 상태 조사 등으로 중간 전이 이력이 실제로 필요해지면 그때 확장한다.

**이번 작업은 저장까지만 하고 조회 API는 만들지 않는다.** 조회 API를 지금 만들면 인가 모델(운영자/어드민이 볼 물건인데 현재 서비스엔 role 기반 인가가 없음 — gateway 이관 정책)을 새로 정해야 하는데, 실제 조회 요구사항이 없는 상태에서 인가 설계까지 하는 건 낭비다. 저장 로직만으로도 완결된 작업 단위다.

**`previous_status` 컬럼을 두지 않는다.** `PaymentFailedEvent`는 READY/REQUESTED 두 상태 모두에서 발생할 수 있어, 이벤트 수신 시점(엔티티는 이미 FAILED로 변경된 후)엔 전이 전 상태를 재구성할 방법이 없다. 캡처하려면 서비스 계층에서 상태 변경 직전 값을 이벤트에 실어 보내야 하는데, 이번 스코프에서 그 정보의 실익보다 구현 비용이 크다고 판단했다. `event_type`(예: `PAYMENT_FAILED`) 자체가 무슨 일이 일어났는지 충분히 설명하므로, "이전 상태가 뭐였나"보다 "무슨 일이 언제/누가/왜 일어났나"에 집중한다.

**`AuditLogEventListener`를 `KafkaPaymentEventPublisher`와 별도 클래스로 둔다.** 하나는 Kafka 발행(`infrastructure.messaging` 책임), 하나는 감사 이력 DB 저장(`infrastructure.persistence` 책임)으로 관심사가 다르다. 같은 도메인 이벤트 4종을 두 리스너가 독립적으로 구독하며, 한쪽 실패가 다른 쪽에 영향을 주지 않는다.

**`event_type`을 domain 자체 enum(`AuditEventType`)으로 정의하고, 기존 `infrastructure.messaging.PaymentEventType`(Kafka 전용, common-module `EventType` 구현)을 재사용하지 않는다.** domain 레이어는 infrastructure에 의존할 수 없다는 아키텍처 규칙(`architecture.md`) 때문이다. 두 enum의 값 집합이 우연히 같아 보이지만, 하나는 Kafka 메시지 타입 코드이고 하나는 감사 이력 분류값으로 목적이 다르다 — 이름이 겹친다고 억지로 공유하면 나중에 한쪽만 값이 늘어날 때(예: 감사로그에만 새 이벤트 종류 추가) 부자연스러운 결합이 생긴다.

## 아키텍처 / 패키지 배치

기존 `Payment`/`Refund`와 동일한 domain 모델 + repository 인터페이스 + infrastructure 구현체 패턴을 따른다.

```
domain/model/AuditLog.java              ← JPA 엔티티, append-only(수정 메서드 없음)
domain/model/AuditEntityType.java       ← enum: PAYMENT, REFUND
domain/model/AuditEventType.java        ← enum: PAYMENT_APPROVED, PAYMENT_FAILED, PAYMENT_REFUNDED, PAYMENT_REFUND_FAILED
domain/repository/AuditLogRepository.java

infrastructure/persistence/AuditLogJpaRepository.java       ← Spring Data JPA
infrastructure/persistence/AuditLogRepositoryAdapter.java   ← AuditLogRepository 구현체
infrastructure/persistence/AuditLogEventListener.java       ← @TransactionalEventListener(AFTER_COMMIT), 도메인 이벤트 4종 구독
```

## 데이터 모델 — `audit_log` 테이블 (V8)

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|---|---|---|---|---|
| `id` | UUID | ✅ | — | PK |
| `entity_type` | VARCHAR(20) | ✅ | — | `PAYMENT` / `REFUND` |
| `entity_id` | UUID | ✅ | — | `payment.id` 또는 `refund.id`. FK 없음 — entity_type에 따라 대상 테이블이 갈리는 폴리모픽 참조 |
| `event_type` | VARCHAR(30) | ✅ | — | `PAYMENT_APPROVED` / `PAYMENT_FAILED` / `PAYMENT_REFUNDED` / `PAYMENT_REFUND_FAILED` |
| `actor_id` | UUID | ✅ | — | 행위자 user_id. `Payment.userId`에서 획득(Refund엔 user_id 컬럼 없음 — #398로 제거됨) |
| `new_status` | VARCHAR(20) | ✅ | — | 전이 후 상태 스냅샷(`PAID`/`FAILED`/`COMPLETED`/`FAILED`) |
| `detail` | TEXT | — | NULL | 실패 사유. `PAYMENT_FAILED`/`PAYMENT_REFUND_FAILED`만 값 있고 나머진 NULL |
| `occurred_at` | TIMESTAMPTZ | ✅ | — | 엔티티 기준 실제 발생 시각(`approvedAt`/`failedAt`/`completedAt`). 환불 실패는 `Refund`에 `failedAt` 컬럼이 없어 리스너가 이벤트 수신 시각(`OffsetDateTime.now()`)을 사용 — `KafkaPaymentEventPublisher.onPaymentRefundFailed`도 동일 처리 |
| `created_at` | TIMESTAMPTZ | ✅ | `NOW()` | 감사로그 레코드 삽입 시각(`@CreatedDate`) |

`updated_at` 없음 — append-only, 절대 수정하지 않는다.

**인덱스**: `idx_audit_log_entity` on (`entity_type`, `entity_id`) — 엔티티별 이력 조회 대비.

`@Entity` 추가이므로 [flyway-migration.md](../../rules/flyway-migration.md) 규칙에 따라 `V8__create_audit_log.sql`을 같은 PR에 동반한다.

## 기록 흐름

`AuditLogEventListener`가 4개 도메인 이벤트 각각을 구독, `AuditLog` 정적 팩토리로 생성 후 `AuditLogRepository.save()`.

```
onPaymentApproved(PaymentApprovedEvent)         → AuditLog.forPaymentApproved(payment)
onPaymentFailed(PaymentFailedEvent)             → AuditLog.forPaymentFailed(payment)
onPaymentRefunded(PaymentRefundedEvent)         → AuditLog.forPaymentRefunded(payment, refund)
onPaymentRefundFailed(PaymentRefundFailedEvent) → AuditLog.forPaymentRefundFailed(payment, refund, failureReason)
```

각 팩토리가 `entity_type`/`entity_id`/`actor_id`(`payment.getUserId()`)/`new_status`/`detail`/`occurred_at`을 채운다. `PaymentRefundFailedEvent`는 이미 `failureReason` 파라미터를 들고 있다 — `Refund.reason` 필드가 `fail()` 호출 시 실패 사유로 덮어써지므로, 이벤트가 이를 별도로 실어 보내는 기존 구조(`KafkaPaymentEventPublisherTest` 참고)를 그대로 활용한다.

## 에러 처리

감사로그 저장 실패가 원 트랜잭션(결제/환불 처리)에 영향을 주면 안 된다 — `AFTER_COMMIT` 시점이라 원 트랜잭션은 이미 커밋 완료 상태다. 저장 중 예외 발생 시 `KafkaPaymentEventPublisher`와 동일한 패턴으로 리스너 내부에서 예외를 잡아 `log.error`만 남기고 전파하지 않는다.

## 테스트

- `AuditLogJpaRepositoryTest`: 기존 `PaymentJpaRepositoryTest` 패턴을 따라 `create(...)` 팩토리 라운드트립 + 감사 필드(`createdAt`) 검증
- `AuditLogEventListenerTest`: 4개 이벤트 각각 발행 시 올바른 필드값으로 `AuditLog`가 저장되는지 검증(Mockito, repository mock)
- 기존 `ConfirmPaymentIntegrationTest`/`RefundIntegrationTest`류에 `audit_log` row 생성 검증을 추가할지는 구현 단계에서 판단 — 과도한 결합을 피하기 위해 필요 시 별도 통합 테스트로 분리할 수 있다

## 문서 갱신

- `.claude/docs/db-schema.md`에 `audit_log` 테이블 섹션 추가
