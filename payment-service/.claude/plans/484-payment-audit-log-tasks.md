# 결제/환불 감사로그 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 결제·환불 종결 상태 전이 4종(승인/실패/환불완료/환불실패)이 발생할 때마다 `audit_log` 테이블에 행위자·시각·사유를 append-only로 저장한다.

**Architecture:** 기존 `Payment`/`Refund`와 동일한 domain 모델 + repository 인터페이스 + infrastructure 구현체 패턴. 기존 도메인 이벤트 4종(`PaymentApprovedEvent`, `PaymentFailedEvent`, `PaymentRefundedEvent`, `PaymentRefundFailedEvent`)을 `KafkaPaymentEventPublisher`와는 별도인 새 `@TransactionalEventListener`(`AuditLogEventListener`, AFTER_COMMIT)가 구독해 `AuditLog` 엔티티로 저장한다.

**Tech Stack:** Spring Boot 4.1 / Java 21, Spring Data JPA, PostgreSQL(Flyway V8), JUnit5 + AssertJ + Mockito, Testcontainers(PostgreSQL).

## Global Constraints

- 모든 명령은 `payment-service` 디렉터리에서 `../gradlew` 로 실행 (모노레포 루트에 wrapper 위치)
- 테스트 메서드명은 한국어, 클래스/필드/메서드 식별자는 영어
- 단언은 AssertJ(`assertThat`), 영속성 테스트는 Testcontainers(PostgreSQL) 기반 `AbstractJpaTest` 사용, H2 금지
- `@Entity` 추가 시 대응하는 `V{n}` Flyway SQL을 같은 작업에 동반 (다음 버전: `V8`)
- NOT NULL 제약은 `V8` SQL과 `@Entity` 양쪽에 동일하게 반영
- 도메인 레이어(`domain.*`)는 infrastructure에 의존 금지 — `AuditEventType`은 기존 `infrastructure.messaging.PaymentEventType`을 재사용하지 않고 domain 자체 enum으로 신설
- 이벤트 발행/구독 패턴은 `KafkaPaymentEventPublisher`(AFTER_COMMIT, 예외를 잡아 로그만 남기고 전파하지 않음)와 동일하게 따름
- 커밋 메시지는 `type: 한국어 설명` 형식, AI 트레일러 `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>` 포함 (git-conventions.md)

---

### Task 1: `audit_log` 영속성 계층 (Flyway V8 + AuditLog 엔티티 + Repository)

**Files:**
- Create: `src/main/resources/db/migration/V8__create_audit_log.sql`
- Create: `src/main/java/com/prompthub/payment/domain/model/AuditEntityType.java`
- Create: `src/main/java/com/prompthub/payment/domain/model/AuditEventType.java`
- Create: `src/main/java/com/prompthub/payment/domain/model/AuditLog.java`
- Create: `src/main/java/com/prompthub/payment/domain/repository/AuditLogRepository.java`
- Create: `src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogJpaRepository.java`
- Create: `src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogRepositoryAdapter.java`
- Modify: `.claude/docs/db-schema.md` (audit_log 섹션 추가)
- Test: `src/test/java/com/prompthub/payment/infrastructure/persistence/AuditLogJpaRepositoryTest.java`

**Interfaces:**
- Produces: `AuditLog.forPaymentApproved(Payment payment): AuditLog`, `AuditLog.forPaymentFailed(Payment payment): AuditLog`, `AuditLog.forPaymentRefunded(Payment payment, Refund refund): AuditLog`, `AuditLog.forPaymentRefundFailed(Payment payment, Refund refund, String failureReason): AuditLog` — Task 2가 사용
- Produces: `AuditLogRepository.save(AuditLog auditLog): AuditLog` — Task 2가 사용
- Produces: `AuditLog` getter — `getId()`, `getEntityType()`, `getEntityId()`, `getEventType()`, `getActorId()`, `getNewStatus()`, `getDetail()`, `getOccurredAt()`, `getCreatedAt()` (Lombok `@Getter`)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/prompthub/payment/infrastructure/persistence/AuditLogJpaRepositoryTest.java`:

```java
package com.prompthub.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.payment.domain.model.AuditEntityType;
import com.prompthub.payment.domain.model.AuditEventType;
import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.support.AbstractJpaTest;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AuditLogJpaRepositoryTest extends AbstractJpaTest {

    @Autowired
    AuditLogJpaRepository auditLogJpaRepository;

    @Test
    void 감사로그_저장_조회_라운드트립() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pg-tx-audit", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());

        AuditLog auditLog = AuditLog.forPaymentApproved(payment);
        AuditLog saved = auditLogJpaRepository.saveAndFlush(auditLog);

        AuditLog found = auditLogJpaRepository.findById(saved.getId())
            .orElseThrow(() -> new AssertionError("AuditLog not found"));

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getEntityType()).isEqualTo(AuditEntityType.PAYMENT);
        assertThat(found.getEntityId()).isEqualTo(payment.getId());
        assertThat(found.getEventType()).isEqualTo(AuditEventType.PAYMENT_APPROVED);
        assertThat(found.getActorId()).isEqualTo(payment.getUserId());
        assertThat(found.getNewStatus()).isEqualTo("PAID");
        assertThat(found.getDetail()).isNull();
        assertThat(found.getOccurredAt()).isNotNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.persistence.AuditLogJpaRepositoryTest"`
Expected: FAIL — `AuditEntityType`, `AuditEventType`, `AuditLog`, `AuditLogJpaRepository` 심볼을 찾을 수 없어 컴파일 자체가 실패한다.

- [ ] **Step 3: enum 두 개 작성**

`src/main/java/com/prompthub/payment/domain/model/AuditEntityType.java`:

```java
package com.prompthub.payment.domain.model;

public enum AuditEntityType {
    PAYMENT,
    REFUND
}
```

`src/main/java/com/prompthub/payment/domain/model/AuditEventType.java`:

```java
package com.prompthub.payment.domain.model;

public enum AuditEventType {
    PAYMENT_APPROVED,
    PAYMENT_FAILED,
    PAYMENT_REFUNDED,
    PAYMENT_REFUND_FAILED
}
```

- [ ] **Step 4: `AuditLog` 도메인 엔티티 작성**

`src/main/java/com/prompthub/payment/domain/model/AuditLog.java`:

```java
package com.prompthub.payment.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import static jakarta.persistence.EnumType.STRING;
import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "audit_log")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = PROTECTED)
public class AuditLog {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Enumerated(STRING)
    @Column(name = "entity_type", columnDefinition = "varchar(20)", nullable = false)
    private AuditEntityType entityType;

    @Column(name = "entity_id", columnDefinition = "uuid", nullable = false)
    private UUID entityId;

    @Enumerated(STRING)
    @Column(name = "event_type", columnDefinition = "varchar(30)", nullable = false)
    private AuditEventType eventType;

    @Column(name = "actor_id", columnDefinition = "uuid", nullable = false)
    private UUID actorId;

    @Column(name = "new_status", length = 20, nullable = false)
    private String newStatus;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    private AuditLog(
        UUID id, AuditEntityType entityType, UUID entityId, AuditEventType eventType,
        UUID actorId, String newStatus, String detail, OffsetDateTime occurredAt
    ) {
        this.id = id;
        this.entityType = entityType;
        this.entityId = entityId;
        this.eventType = eventType;
        this.actorId = actorId;
        this.newStatus = newStatus;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public static AuditLog forPaymentApproved(Payment payment) {
        return new AuditLog(
            UUID.randomUUID(), AuditEntityType.PAYMENT, payment.getId(), AuditEventType.PAYMENT_APPROVED,
            payment.getUserId(), payment.getStatus().name(), null, payment.getApprovedAt()
        );
    }

    public static AuditLog forPaymentFailed(Payment payment) {
        return new AuditLog(
            UUID.randomUUID(), AuditEntityType.PAYMENT, payment.getId(), AuditEventType.PAYMENT_FAILED,
            payment.getUserId(), payment.getStatus().name(), payment.getFailureReason(), payment.getFailedAt()
        );
    }

    public static AuditLog forPaymentRefunded(Payment payment, Refund refund) {
        return new AuditLog(
            UUID.randomUUID(), AuditEntityType.REFUND, refund.getId(), AuditEventType.PAYMENT_REFUNDED,
            payment.getUserId(), refund.getStatus().name(), null, refund.getCompletedAt()
        );
    }

    public static AuditLog forPaymentRefundFailed(Payment payment, Refund refund, String failureReason) {
        return new AuditLog(
            UUID.randomUUID(), AuditEntityType.REFUND, refund.getId(), AuditEventType.PAYMENT_REFUND_FAILED,
            payment.getUserId(), refund.getStatus().name(), failureReason, OffsetDateTime.now()
        );
    }
}
```

`occurred_at`의 소스는 이벤트별로 다르다 — `PAYMENT_APPROVED`는 `payment.getApprovedAt()`, `PAYMENT_FAILED`는 `payment.getFailedAt()`, `PAYMENT_REFUNDED`는 `refund.getCompletedAt()`(모두 엔티티에 이미 존재하는 정확한 값). `PAYMENT_REFUND_FAILED`만 `Refund`에 `failedAt` 컬럼이 없어 `OffsetDateTime.now()` 근사치를 쓴다 — 이 근사치를 정확한 값으로 교체하는 후속 작업은 `.claude/plans/TBD-refund-reason-split.md`에 별도로 정리되어 있다.

- [ ] **Step 5: domain repository 인터페이스 작성**

`src/main/java/com/prompthub/payment/domain/repository/AuditLogRepository.java`:

```java
package com.prompthub.payment.domain.repository;

import com.prompthub.payment.domain.model.AuditLog;

public interface AuditLogRepository {
    AuditLog save(AuditLog auditLog);
}
```

- [ ] **Step 6: JPA repository + adapter 작성**

`src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogJpaRepository.java`:

```java
package com.prompthub.payment.infrastructure.persistence;

import com.prompthub.payment.domain.model.AuditLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogJpaRepository extends JpaRepository<AuditLog, UUID> {
}
```

`src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogRepositoryAdapter.java`:

```java
package com.prompthub.payment.infrastructure.persistence;

import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuditLogRepositoryAdapter implements AuditLogRepository {

    private final AuditLogJpaRepository jpaRepository;

    @Override
    public AuditLog save(AuditLog auditLog) {
        return jpaRepository.save(auditLog);
    }
}
```

- [ ] **Step 7: Flyway V8 마이그레이션 작성**

`src/main/resources/db/migration/V8__create_audit_log.sql`:

```sql
-- audit_log: 결제/환불 종결 상태 전이(승인/실패/환불완료/환불실패) 이력을 append-only로 보존 (#484)

CREATE TABLE audit_log (
    id uuid NOT NULL,
    entity_type varchar(20) NOT NULL,
    entity_id uuid NOT NULL,
    event_type varchar(30) NOT NULL,
    actor_id uuid NOT NULL,
    new_status varchar(20) NOT NULL,
    detail text,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT audit_log_pkey PRIMARY KEY (id),
    CONSTRAINT audit_log_entity_type_check CHECK (entity_type IN ('PAYMENT', 'REFUND')),
    CONSTRAINT audit_log_event_type_check CHECK (event_type IN ('PAYMENT_APPROVED', 'PAYMENT_FAILED', 'PAYMENT_REFUNDED', 'PAYMENT_REFUND_FAILED'))
);

CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id);
```

- [ ] **Step 8: 테스트 통과 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.persistence.AuditLogJpaRepositoryTest"`
Expected: PASS

- [ ] **Step 9: `db-schema.md`에 `audit_log` 테이블 문서 추가**

`.claude/docs/db-schema.md` 파일 끝(86행, `refund` 테이블 섹션 뒤)에 아래 섹션을 추가:

```markdown

---

## audit_log 테이블

결제·환불 종결 상태 전이(승인/실패/환불완료/환불실패) 4종에 한해 행위자·시각·사유를 append-only로 기록하는 이력 테이블(#484). 조회 API는 아직 없다 — 저장까지만 구현됨.

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|---|---|---|---|---|
| `id` | UUID | ✅ | — | PK |
| `entity_type` | VARCHAR(20) | ✅ | — | `PAYMENT` / `REFUND` |
| `entity_id` | UUID | ✅ | — | `payment.id` 또는 `refund.id`. FK 없음 — entity_type에 따라 대상 테이블이 갈리는 폴리모픽 참조 |
| `event_type` | VARCHAR(30) | ✅ | — | `PAYMENT_APPROVED` / `PAYMENT_FAILED` / `PAYMENT_REFUNDED` / `PAYMENT_REFUND_FAILED` |
| `actor_id` | UUID | ✅ | — | 행위자 user_id. `Payment.userId`에서 획득(Refund엔 user_id 컬럼 없음 — #398로 제거됨) |
| `new_status` | VARCHAR(20) | ✅ | — | 전이 후 상태 스냅샷 |
| `detail` | TEXT | — | NULL | 실패 사유. `PAYMENT_FAILED`/`PAYMENT_REFUND_FAILED`만 값 있음 |
| `occurred_at` | TIMESTAMPTZ | ✅ | — | 엔티티 기준 실제 발생 시각. 환불 실패만 근사치(`OffsetDateTime.now()`) 사용 |
| `created_at` | TIMESTAMPTZ | ✅ | — | 감사로그 레코드 삽입 시각 |

`updated_at` 없음 — append-only, 절대 수정하지 않는다.

**인덱스** (Flyway):

| 인덱스 | 대상 | 목적 |
|---|---|---|
| `idx_audit_log_entity` | (`entity_type`, `entity_id`) | 엔티티별 이력 조회 |
```

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/prompthub/payment/domain/model/AuditEntityType.java \
    src/main/java/com/prompthub/payment/domain/model/AuditEventType.java \
    src/main/java/com/prompthub/payment/domain/model/AuditLog.java \
    src/main/java/com/prompthub/payment/domain/repository/AuditLogRepository.java \
    src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogJpaRepository.java \
    src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogRepositoryAdapter.java \
    src/main/resources/db/migration/V8__create_audit_log.sql \
    src/test/java/com/prompthub/payment/infrastructure/persistence/AuditLogJpaRepositoryTest.java \
    .claude/docs/db-schema.md
git commit -m "$(cat <<'EOF'
feat: 감사로그 audit_log 테이블 및 영속성 계층 추가

- AuditLog 도메인 엔티티, AuditLogRepository 인터페이스, JPA 구현체 추가
- V8 마이그레이션으로 audit_log 테이블 생성
- Payment/Refund 종결 상태별 AuditLog 정적 팩토리(forPaymentApproved 등) 제공

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `AuditLogEventListener` — 도메인 이벤트 구독 및 감사로그 저장

**Files:**
- Create: `src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListener.java`
- Test: `src/test/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListenerTest.java`

**Interfaces:**
- Consumes: `AuditLogRepository.save(AuditLog): AuditLog` (Task 1), `AuditLog.forPaymentApproved/forPaymentFailed/forPaymentRefunded/forPaymentRefundFailed` (Task 1), 기존 `PaymentApprovedEvent.payment(): Payment`, `PaymentFailedEvent.payment(): Payment`, `PaymentRefundedEvent.payment(): Payment` / `.refund(): Refund`, `PaymentRefundFailedEvent.payment(): Payment` / `.refund(): Refund` / `.failureReason(): String`
- Produces: `AuditLogEventListener(AuditLogRepository)` 생성자, `onPaymentApproved/onPaymentFailed/onPaymentRefunded/onPaymentRefundFailed(...)` public 메서드 — Spring 컨텍스트가 `@TransactionalEventListener`로 자동 구독하므로 다른 프로덕션 코드가 직접 호출하지 않는다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListenerTest.java`:

```java
package com.prompthub.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prompthub.payment.domain.event.PaymentApprovedEvent;
import com.prompthub.payment.domain.event.PaymentFailedEvent;
import com.prompthub.payment.domain.event.PaymentRefundFailedEvent;
import com.prompthub.payment.domain.event.PaymentRefundedEvent;
import com.prompthub.payment.domain.model.AuditEntityType;
import com.prompthub.payment.domain.model.AuditEventType;
import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.domain.model.Refund;
import com.prompthub.payment.domain.repository.AuditLogRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditLogEventListenerTest {

    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final AuditLogEventListener listener = new AuditLogEventListener(auditLogRepository);

    @Test
    void 결제_승인_이벤트_수신_시_감사로그를_저장한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-1", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());

        listener.onPaymentApproved(new PaymentApprovedEvent(payment));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog auditLog = captor.getValue();
        assertThat(auditLog.getEntityType()).isEqualTo(AuditEntityType.PAYMENT);
        assertThat(auditLog.getEntityId()).isEqualTo(payment.getId());
        assertThat(auditLog.getEventType()).isEqualTo(AuditEventType.PAYMENT_APPROVED);
        assertThat(auditLog.getActorId()).isEqualTo(payment.getUserId());
        assertThat(auditLog.getNewStatus()).isEqualTo("PAID");
        assertThat(auditLog.getDetail()).isNull();
    }

    @Test
    void 결제_실패_이벤트_수신_시_감사로그를_저장한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-2", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.fail("REJECT", "카드 거절", "{}", "{}", OffsetDateTime.now());

        listener.onPaymentFailed(new PaymentFailedEvent(payment));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog auditLog = captor.getValue();
        assertThat(auditLog.getEventType()).isEqualTo(AuditEventType.PAYMENT_FAILED);
        assertThat(auditLog.getNewStatus()).isEqualTo("FAILED");
        assertThat(auditLog.getDetail()).isEqualTo("카드 거절");
    }

    @Test
    void 환불_완료_이벤트_수신_시_감사로그를_저장한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-3", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        Refund refund = Refund.create(payment.getId(), UUID.randomUUID(), 4_000, "단순 변심");
        refund.complete(OffsetDateTime.now());

        listener.onPaymentRefunded(new PaymentRefundedEvent(payment, refund));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog auditLog = captor.getValue();
        assertThat(auditLog.getEntityType()).isEqualTo(AuditEntityType.REFUND);
        assertThat(auditLog.getEntityId()).isEqualTo(refund.getId());
        assertThat(auditLog.getEventType()).isEqualTo(AuditEventType.PAYMENT_REFUNDED);
        assertThat(auditLog.getActorId()).isEqualTo(payment.getUserId());
        assertThat(auditLog.getNewStatus()).isEqualTo("COMPLETED");
        assertThat(auditLog.getDetail()).isNull();
    }

    @Test
    void 환불_실패_이벤트_수신_시_감사로그를_저장한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-4", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        Refund refund = Refund.create(payment.getId(), UUID.randomUUID(), 4_000, "단순 변심");
        refund.fail("PG 오류");

        listener.onPaymentRefundFailed(new PaymentRefundFailedEvent(payment, refund, "PG 오류"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog auditLog = captor.getValue();
        assertThat(auditLog.getEntityType()).isEqualTo(AuditEntityType.REFUND);
        assertThat(auditLog.getEventType()).isEqualTo(AuditEventType.PAYMENT_REFUND_FAILED);
        assertThat(auditLog.getNewStatus()).isEqualTo("FAILED");
        assertThat(auditLog.getDetail()).isEqualTo("PG 오류");
    }

    @Test
    void 감사로그_저장_실패해도_예외를_전파하지_않는다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-5", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        when(auditLogRepository.save(any())).thenThrow(new RuntimeException("DB down"));

        assertDoesNotThrow(() -> listener.onPaymentApproved(new PaymentApprovedEvent(payment)));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.persistence.AuditLogEventListenerTest"`
Expected: FAIL — `AuditLogEventListener` 심볼을 찾을 수 없어 컴파일 실패.

- [ ] **Step 3: `AuditLogEventListener` 구현**

`src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListener.java`:

```java
package com.prompthub.payment.infrastructure.persistence;

import com.prompthub.payment.domain.event.PaymentApprovedEvent;
import com.prompthub.payment.domain.event.PaymentFailedEvent;
import com.prompthub.payment.domain.event.PaymentRefundFailedEvent;
import com.prompthub.payment.domain.event.PaymentRefundedEvent;
import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.repository.AuditLogRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogEventListener {

    private final AuditLogRepository auditLogRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentApproved(PaymentApprovedEvent event) {
        save(AuditLog.forPaymentApproved(event.payment()), event.payment().getId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentFailed(PaymentFailedEvent event) {
        save(AuditLog.forPaymentFailed(event.payment()), event.payment().getId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRefunded(PaymentRefundedEvent event) {
        save(AuditLog.forPaymentRefunded(event.payment(), event.refund()), event.refund().getId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRefundFailed(PaymentRefundFailedEvent event) {
        save(
            AuditLog.forPaymentRefundFailed(event.payment(), event.refund(), event.failureReason()),
            event.refund().getId()
        );
    }

    private void save(AuditLog auditLog, UUID entityId) {
        try {
            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("감사로그 저장 실패 — entityId={}, eventType={}, cause={}",
                entityId, auditLog.getEventType(), e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.persistence.AuditLogEventListenerTest"`
Expected: PASS

- [ ] **Step 5: 전체 테스트 실행**

Run: `../gradlew :payment-service:test`
Expected: BUILD SUCCESSFUL, 기존 테스트(`KafkaPaymentEventPublisherTest`, `ConfirmPaymentIntegrationTest` 등) 회귀 없음.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListener.java \
    src/test/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListenerTest.java
git commit -m "$(cat <<'EOF'
feat: 결제/환불 종결 이벤트 구독해 감사로그 저장하는 리스너 추가

- AuditLogEventListener가 4개 도메인 이벤트를 AFTER_COMMIT 시점에 구독
- 저장 실패 시 KafkaPaymentEventPublisher와 동일하게 예외를 흡수하고 로그만 남김

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```
