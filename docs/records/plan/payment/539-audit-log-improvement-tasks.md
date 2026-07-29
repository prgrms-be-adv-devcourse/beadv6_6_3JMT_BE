# 감사로그 캡처 확대 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 결제/환불 감사로그(`audit_log`)에 `order_id`·실패 코드·결제 시도(REQUESTED) 이벤트를 추가하고, 환불 이벤트 타입 명명을 결제와 대칭 구조로 정리한다.

**Architecture:** 클린 아키텍처 기존 구조(`domain`/`application`/`infrastructure`) 그대로. 새 도메인 이벤트(`PaymentRequestedEvent`) 1개 추가, 기존 이벤트(`PaymentRefundFailedEvent`) 파라미터 축소, `AuditLog` 팩토리 메서드 재구성, `AuditLogEventListener`가 환불 이벤트당 감사로그 2건(REQUESTED+터미널)을 저장하도록 확장. Flyway 마이그레이션 2개(V10: refund, V11: audit_log) 추가.

**Tech Stack:** Spring Boot 4.1 / Java 21, Spring Data JPA, Flyway, JUnit 5 + Mockito + AssertJ, Testcontainers(PostgreSQL).

## Global Constraints

- 모든 gradle 명령은 `payment-service` 디렉터리에서 `../gradlew` 로 실행하고, `JAVA_HOME=/Users/anjinpyo/.asdf/installs/java/temurin-21.0.5+11.0.LTS` 를 명시한다(shim 경로로는 실패).
- 테스트 메서드명은 한국어, 클래스/필드/메서드 식별자는 영어.
- 단언은 AssertJ(`assertThat`)만 사용한다.
- 영속성 테스트는 `AbstractJpaTest`(Testcontainers PostgreSQL) 기반, H2 등 인메모리 DB 금지.
- 마이그레이션 파일명: `src/main/resources/db/migration/V{n}__설명_스네이크케이스.sql`, 스키마 접두사 없이 작성.
- 커밋 메시지 형식: `type: 한국어 설명` (본문 필요 시 불릿), AI 협업 시 마지막에 `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>` 트레일러.
- 이 작업은 조회 API를 만들지 않는다 — write-side(엔티티/이벤트/마이그레이션)만 변경한다.

---

### Task 1: Refund 실패 코드(failure_code) 저장

**Files:**
- Create: `src/main/resources/db/migration/V10__add_refund_failure_code.sql`
- Modify: `src/main/java/com/prompthub/payment/domain/model/Refund.java`
- Modify: `src/main/java/com/prompthub/payment/application/service/RefundService.java`
- Modify: `src/test/java/com/prompthub/payment/infrastructure/persistence/RefundJpaRepositoryTest.java`
- Modify: `src/test/java/com/prompthub/payment/application/service/RefundServiceTest.java`
- Modify: `src/test/java/com/prompthub/payment/infrastructure/messaging/KafkaPaymentEventPublisherTest.java`
- Modify: `src/test/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListenerTest.java`

**Interfaces:**
- Produces: `Refund.fail(String failureCode, String failureReason, OffsetDateTime failedAt)` — 이후 Task 2의 `AuditLog.forRefundFailed`가 `refund.getFailureCode()`로 사용한다.
- Produces: `Refund.getFailureCode()` (Lombok `@Getter`가 자동 생성).

이 태스크는 `Refund.fail(...)`을 2-인자에서 3-인자로 바꾼다. 이 메서드를 호출하는 모든 파일(`RefundService`, 4개 테스트 파일)을 같은 커밋에서 함께 고쳐야 컴파일이 유지된다.

- [x] **Step 1: 실패 코드 포함 라운드트립 실패 테스트 작성**

`RefundJpaRepositoryTest.java`의 기존 테스트를 아래로 교체한다(`refund.fail(...)` 호출이 아직 2-인자라 컴파일이 깨지는 게 정상):

```java
    @Test
    void 환불_실패_시_failure_code와_failure_reason이_저장되고_사용자_사유는_보존된다() {
        UUID paymentId = UUID.randomUUID();
        Refund refund = Refund.create(paymentId, UUID.randomUUID(), 5_000, "단순 변심");
        OffsetDateTime failedAt = OffsetDateTime.now();

        refund.fail("PG_ERROR", "PG 오류", failedAt);
        Refund saved = refundJpaRepository.saveAndFlush(refund);

        Refund found = refundJpaRepository.findById(saved.getId())
            .orElseThrow(() -> new AssertionError("Refund not found"));

        assertThat(found.getReason()).isEqualTo("단순 변심");
        assertThat(found.getFailureCode()).isEqualTo("PG_ERROR");
        assertThat(found.getFailureReason()).isEqualTo("PG 오류");
        assertThat(found.getFailedAt()).isNotNull();
        assertThat(found.getStatus()).isEqualTo(RefundStatus.FAILED);
    }
```

- [x] **Step 2: 컴파일 실패로 테스트가 실패하는 것을 확인**

Run: `JAVA_HOME=/Users/anjinpyo/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:compileTestJava`
Expected: FAIL — `cannot find symbol: method fail(String,String,OffsetDateTime)` (2-인자 `fail`만 존재)

- [x] **Step 3: 마이그레이션 작성**

`src/main/resources/db/migration/V10__add_refund_failure_code.sql`:

```sql
-- refund: PG 환불 실패 코드(또는 내부 사유 코드) 보존 (#539)

ALTER TABLE refund ADD COLUMN failure_code varchar(50);
```

- [x] **Step 4: Refund 엔티티에 failure_code 필드 추가 및 fail() 시그니처 변경**

`Refund.java`에 필드 추가(`failureReason` 필드 바로 아래):

```java
    @Column(name = "failure_code", length = 50)
    private String failureCode;
```

`fail(...)` 메서드 교체:

```java
    public void fail(String failureCode, String failureReason, OffsetDateTime failedAt) {
        if (this.status != RefundStatus.REQUESTED) {
            throw new InvalidRefundStateException("REQUESTED 상태에서만 FAILED로 전환할 수 있습니다.");
        }
        log.debug("Refund 상태 전이 — id={}, {} → FAILED", id, status);
        this.status = RefundStatus.FAILED;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
        this.failedAt = failedAt;
    }
```

- [x] **Step 5: RefundService 호출부 갱신 — 실패 코드 채우기**

`RefundService.java`에 클래스 상수 추가:

```java
    private static final String REFUND_LIMIT_EXCEEDED_CODE = "REFUND_LIMIT_EXCEEDED";
```

`failByExceedingLimit` 메서드 내부:

```java
        refund.fail(REFUND_LIMIT_EXCEEDED_CODE, "환불 가능 잔액을 초과했습니다.", OffsetDateTime.now());
```

`executeGatewayRefund`의 catch 블록 내부:

```java
        } catch (PaymentGatewayException e) {
            log.error("PG 환불 실패 — paymentId={}, refundRequestId={}, code={}, reason={}",
                payment.getId(), refund.getRefundRequestId(), e.getFailureCode(), e.getFailureReason());
            refund.fail(e.getFailureCode(), e.getFailureReason(), OffsetDateTime.now());
            refundRepository.save(refund);
            applicationEventPublisher.publishEvent(
                new PaymentRefundFailedEvent(payment, refund, e.getFailureReason()));
        }
```

(이 시점엔 `PaymentRefundFailedEvent`가 아직 3-인자다 — Task 2에서 2-인자로 축소한다. 여기선 기존 3-인자 생성자 호출을 그대로 유지한다.)

- [x] **Step 6: 나머지 호출부(테스트) 시그니처 갱신**

`RefundServiceTest.java` — `누적_환불액_초과_시_예외_없이_FAILED_row_생성_및_실패_이벤트_발행` 테스트의 기존 단언 블록 뒤에 추가:

```java
        assertThat(refundCaptor.getValue().getFailureCode()).isEqualTo("REFUND_LIMIT_EXCEEDED");
```

같은 파일의 `PG_실패_시_Refund_FAILED_Payment_상태_불변_및_실패_이벤트_발행` 테스트의 `assertThat(refundCaptor.getValue().getFailureReason()).isEqualTo("환불 실패");` 다음 줄에 추가:

```java
        assertThat(refundCaptor.getValue().getFailureCode()).isEqualTo("CANCEL_FAILED");
```

이 테스트의 PG 예외 스텁도 코드값이 `"CANCEL_FAILED"`인지 확인 — 기존 스텁이 이미 `new PaymentGatewayException(PaymentErrorCode.PG_INVALID_REQUEST, "CANCEL_FAILED", "환불 실패", null, null)`이므로 코드 값은 변경 불필요, 위 단언만 추가하면 된다.

`KafkaPaymentEventPublisherTest.java` 137번 줄:

```java
        refund.fail("CANCEL_FAILED", "PG 오류", OffsetDateTime.now());
```

`AuditLogEventListenerTest.java` 96번 줄(`환불_실패_이벤트_수신_시_감사로그를_저장한다` 테스트 내부):

```java
        refund.fail("CANCEL_FAILED", "PG 오류", OffsetDateTime.now());
```

- [x] **Step 7: 테스트 통과 확인**

Run: `JAVA_HOME=/Users/anjinpyo/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.persistence.RefundJpaRepositoryTest" --tests "com.prompthub.payment.application.service.RefundServiceTest" --tests "com.prompthub.payment.infrastructure.messaging.KafkaPaymentEventPublisherTest" --tests "com.prompthub.payment.infrastructure.persistence.AuditLogEventListenerTest"`
Expected: PASS (전체 GREEN)

- [x] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/V10__add_refund_failure_code.sql \
  src/main/java/com/prompthub/payment/domain/model/Refund.java \
  src/main/java/com/prompthub/payment/application/service/RefundService.java \
  src/test/java/com/prompthub/payment/infrastructure/persistence/RefundJpaRepositoryTest.java \
  src/test/java/com/prompthub/payment/application/service/RefundServiceTest.java \
  src/test/java/com/prompthub/payment/infrastructure/messaging/KafkaPaymentEventPublisherTest.java \
  src/test/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListenerTest.java
git commit -m "$(cat <<'EOF'
feat: 환불 실패 코드(failure_code) 저장

- Refund 엔티티에 failure_code 컬럼 추가, fail() 시그니처에 코드 파라미터 추가
- PG 환불 실패 시 TossPaymentGateway가 이미 넘겨주던 실패 코드를 실제로 저장
- 잔액 초과 등 내부 사유는 REFUND_LIMIT_EXCEEDED 코드 부여

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: audit_log에 order_id/failure_code 추가, 환불 이벤트 타입 명명 정리

**Files:**
- Create: `src/main/resources/db/migration/V11__extend_audit_log_order_and_failure_code.sql`
- Modify: `src/main/java/com/prompthub/payment/domain/model/AuditEventType.java`
- Modify: `src/main/java/com/prompthub/payment/domain/model/AuditLog.java`
- Modify: `src/main/java/com/prompthub/payment/domain/event/PaymentRefundFailedEvent.java`
- Modify: `src/main/java/com/prompthub/payment/application/service/RefundService.java`
- Modify: `src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListener.java`
- Modify: `src/test/java/com/prompthub/payment/infrastructure/persistence/AuditLogJpaRepositoryTest.java`
- Modify: `src/test/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListenerTest.java`
- Modify: `src/test/java/com/prompthub/payment/infrastructure/messaging/KafkaPaymentEventPublisherTest.java`
- Modify: `src/test/java/com/prompthub/payment/application/service/RefundServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `Refund.getFailureCode()`, `Refund.fail(String, String, OffsetDateTime)`.
- Produces: `AuditLog.forPaymentApproved(Payment)`, `AuditLog.forPaymentFailed(Payment)`(기존 이름 유지, `orderId`/`failureCode` 반영), `AuditLog.forRefundRequested(Payment, Refund)`, `AuditLog.forRefundCompleted(Payment, Refund)`, `AuditLog.forRefundFailed(Payment, Refund)`(신규 — Task 3이 참조하진 않지만 이후 조회 API 작업에서 그대로 사용됨).
- Produces: `PaymentRefundFailedEvent(Payment payment, Refund refund)` — `failureReason` 파라미터 제거됨.

`AuditEventType`을 리네이밍하는 태스크라 `AuditLog`/`AuditLogEventListener`/관련 테스트를 한 번에 갱신해야 컴파일이 유지된다. `forPaymentRequested`/`PAYMENT_REQUESTED` 관련 프로덕션 코드는 Task 3에서 추가한다 — 이 태스크에서는 enum 값만 먼저 정의해둔다(CHECK 제약과 함께 6종을 한 번에 맞추기 위함).

- [x] **Step 1: 실패하는 테스트 작성 — order_id/failure_code 포함 라운드트립 + REFUND_REQUESTED 동반 저장**

`AuditLogJpaRepositoryTest.java` 전체를 아래로 교체:

```java
package com.prompthub.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.payment.domain.model.AuditEntityType;
import com.prompthub.payment.domain.model.AuditEventType;
import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.domain.model.Refund;
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
        assertThat(found.getOrderId()).isEqualTo(payment.getOrderId());
        assertThat(found.getEntityType()).isEqualTo(AuditEntityType.PAYMENT);
        assertThat(found.getEntityId()).isEqualTo(payment.getId());
        assertThat(found.getEventType()).isEqualTo(AuditEventType.PAYMENT_APPROVED);
        assertThat(found.getActorId()).isEqualTo(payment.getUserId());
        assertThat(found.getNewStatus()).isEqualTo("PAID");
        assertThat(found.getFailureCode()).isNull();
        assertThat(found.getDetail()).isNull();
        assertThat(found.getOccurredAt()).isNotNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void 환불_실패_감사로그는_failure_code와_detail을_함께_저장한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pg-tx-audit-2", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        Refund refund = Refund.create(payment.getId(), UUID.randomUUID(), 4_000, null);
        refund.fail("CANCEL_FAILED", "PG 오류", OffsetDateTime.now());

        AuditLog auditLog = AuditLog.forRefundFailed(payment, refund);
        AuditLog saved = auditLogJpaRepository.saveAndFlush(auditLog);

        AuditLog found = auditLogJpaRepository.findById(saved.getId())
            .orElseThrow(() -> new AssertionError("AuditLog not found"));

        assertThat(found.getOrderId()).isEqualTo(payment.getOrderId());
        assertThat(found.getEntityType()).isEqualTo(AuditEntityType.REFUND);
        assertThat(found.getEventType()).isEqualTo(AuditEventType.REFUND_FAILED);
        assertThat(found.getFailureCode()).isEqualTo("CANCEL_FAILED");
        assertThat(found.getDetail()).isEqualTo("PG 오류");
    }
}
```

`AuditLogEventListenerTest.java` 전체를 아래로 교체(`PAYMENT_REQUESTED` 리스너 테스트는 Task 3에서 추가):

```java
package com.prompthub.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import java.util.List;
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
        assertThat(auditLog.getOrderId()).isEqualTo(payment.getOrderId());
        assertThat(auditLog.getEntityType()).isEqualTo(AuditEntityType.PAYMENT);
        assertThat(auditLog.getEntityId()).isEqualTo(payment.getId());
        assertThat(auditLog.getEventType()).isEqualTo(AuditEventType.PAYMENT_APPROVED);
        assertThat(auditLog.getActorId()).isEqualTo(payment.getUserId());
        assertThat(auditLog.getNewStatus()).isEqualTo("PAID");
        assertThat(auditLog.getFailureCode()).isNull();
        assertThat(auditLog.getDetail()).isNull();
    }

    @Test
    void 결제_실패_이벤트_수신_시_실패코드와_사유를_감사로그에_저장한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-2", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.fail("REJECT", "카드 거절", "{}", "{}", OffsetDateTime.now());

        listener.onPaymentFailed(new PaymentFailedEvent(payment));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog auditLog = captor.getValue();
        assertThat(auditLog.getOrderId()).isEqualTo(payment.getOrderId());
        assertThat(auditLog.getEventType()).isEqualTo(AuditEventType.PAYMENT_FAILED);
        assertThat(auditLog.getNewStatus()).isEqualTo("FAILED");
        assertThat(auditLog.getFailureCode()).isEqualTo("REJECT");
        assertThat(auditLog.getDetail()).isEqualTo("카드 거절");
    }

    @Test
    void 환불_완료_이벤트_수신_시_REFUND_REQUESTED와_REFUND_COMPLETED_두_건을_저장한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-3", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        Refund refund = Refund.create(payment.getId(), UUID.randomUUID(), 4_000, "단순 변심");
        refund.complete(OffsetDateTime.now());

        listener.onPaymentRefunded(new PaymentRefundedEvent(payment, refund));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(2)).save(captor.capture());
        List<AuditLog> saved = captor.getAllValues();

        assertThat(saved).extracting(AuditLog::getEventType)
            .containsExactly(AuditEventType.REFUND_REQUESTED, AuditEventType.REFUND_COMPLETED);
        assertThat(saved).allSatisfy(log -> {
            assertThat(log.getOrderId()).isEqualTo(payment.getOrderId());
            assertThat(log.getEntityType()).isEqualTo(AuditEntityType.REFUND);
            assertThat(log.getEntityId()).isEqualTo(refund.getId());
        });
    }

    @Test
    void 환불_실패_이벤트_수신_시_REFUND_REQUESTED와_REFUND_FAILED_두_건을_저장한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-4", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        Refund refund = Refund.create(payment.getId(), UUID.randomUUID(), 4_000, "단순 변심");
        refund.fail("CANCEL_FAILED", "PG 오류", OffsetDateTime.now());

        listener.onPaymentRefundFailed(new PaymentRefundFailedEvent(payment, refund));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(2)).save(captor.capture());
        List<AuditLog> saved = captor.getAllValues();

        assertThat(saved).extracting(AuditLog::getEventType)
            .containsExactly(AuditEventType.REFUND_REQUESTED, AuditEventType.REFUND_FAILED);
        assertThat(saved.get(1).getFailureCode()).isEqualTo("CANCEL_FAILED");
        assertThat(saved.get(1).getDetail()).isEqualTo("PG 오류");
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

- [x] **Step 2: 컴파일 실패 확인**

Run: `JAVA_HOME=/Users/anjinpyo/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:compileTestJava`
Expected: FAIL — `getOrderId()`/`getFailureCode()` 심볼 없음, `forRefundFailed` 심볼 없음, `REFUND_REQUESTED` 등 enum 상수 없음

- [x] **Step 3: 마이그레이션 작성**

`src/main/resources/db/migration/V11__extend_audit_log_order_and_failure_code.sql`:

```sql
-- audit_log: order_id/failure_code 확장, event_type 값 집합을 결제/환불 대칭 구조로 정리 (#539)

ALTER TABLE audit_log ADD COLUMN order_id uuid;
ALTER TABLE audit_log ADD COLUMN failure_code varchar(50);

UPDATE audit_log a
SET order_id = p.order_id
FROM payment p
WHERE a.entity_type = 'PAYMENT' AND a.entity_id = p.id;

UPDATE audit_log a
SET order_id = p.order_id
FROM refund r
JOIN payment p ON r.payment_id = p.id
WHERE a.entity_type = 'REFUND' AND a.entity_id = r.id;

UPDATE audit_log SET event_type = 'REFUND_COMPLETED' WHERE event_type = 'PAYMENT_REFUNDED';
UPDATE audit_log SET event_type = 'REFUND_FAILED' WHERE event_type = 'PAYMENT_REFUND_FAILED';

ALTER TABLE audit_log ALTER COLUMN order_id SET NOT NULL;

ALTER TABLE audit_log DROP CONSTRAINT audit_log_event_type_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_event_type_check
    CHECK (event_type IN (
        'PAYMENT_REQUESTED', 'PAYMENT_APPROVED', 'PAYMENT_FAILED',
        'REFUND_REQUESTED', 'REFUND_COMPLETED', 'REFUND_FAILED'
    ));

CREATE INDEX idx_audit_log_order ON audit_log (order_id);
```

- [x] **Step 4: AuditEventType 재정의**

`AuditEventType.java` 전체 교체:

```java
package com.prompthub.payment.domain.model;

public enum AuditEventType {
    PAYMENT_REQUESTED,
    PAYMENT_APPROVED,
    PAYMENT_FAILED,
    REFUND_REQUESTED,
    REFUND_COMPLETED,
    REFUND_FAILED
}
```

- [x] **Step 5: AuditLog 엔티티/팩토리 재구성**

`AuditLog.java` 전체 교체(`forPaymentRequested`는 Task 3에서 추가):

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

    @Column(name = "order_id", columnDefinition = "uuid", nullable = false)
    private UUID orderId;

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

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    private AuditLog(
        UUID id, UUID orderId, AuditEntityType entityType, UUID entityId, AuditEventType eventType,
        UUID actorId, String newStatus, String failureCode, String detail, OffsetDateTime occurredAt
    ) {
        this.id = id;
        this.orderId = orderId;
        this.entityType = entityType;
        this.entityId = entityId;
        this.eventType = eventType;
        this.actorId = actorId;
        this.newStatus = newStatus;
        this.failureCode = failureCode;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public static AuditLog forPaymentApproved(Payment payment) {
        return new AuditLog(
            UUID.randomUUID(), payment.getOrderId(), AuditEntityType.PAYMENT, payment.getId(),
            AuditEventType.PAYMENT_APPROVED, payment.getUserId(), payment.getStatus().name(),
            null, null, payment.getApprovedAt()
        );
    }

    public static AuditLog forPaymentFailed(Payment payment) {
        return new AuditLog(
            UUID.randomUUID(), payment.getOrderId(), AuditEntityType.PAYMENT, payment.getId(),
            AuditEventType.PAYMENT_FAILED, payment.getUserId(), payment.getStatus().name(),
            payment.getFailureCode(), payment.getFailureReason(), payment.getFailedAt()
        );
    }

    public static AuditLog forRefundRequested(Payment payment, Refund refund) {
        return new AuditLog(
            UUID.randomUUID(), payment.getOrderId(), AuditEntityType.REFUND, refund.getId(),
            AuditEventType.REFUND_REQUESTED, payment.getUserId(), RefundStatus.REQUESTED.name(),
            null, null, refund.getRequestedAt()
        );
    }

    public static AuditLog forRefundCompleted(Payment payment, Refund refund) {
        return new AuditLog(
            UUID.randomUUID(), payment.getOrderId(), AuditEntityType.REFUND, refund.getId(),
            AuditEventType.REFUND_COMPLETED, payment.getUserId(), refund.getStatus().name(),
            null, null, refund.getCompletedAt()
        );
    }

    public static AuditLog forRefundFailed(Payment payment, Refund refund) {
        return new AuditLog(
            UUID.randomUUID(), payment.getOrderId(), AuditEntityType.REFUND, refund.getId(),
            AuditEventType.REFUND_FAILED, payment.getUserId(), refund.getStatus().name(),
            refund.getFailureCode(), refund.getFailureReason(), refund.getFailedAt()
        );
    }
}
```

- [x] **Step 6: PaymentRefundFailedEvent 파라미터 축소**

`PaymentRefundFailedEvent.java` 전체 교체:

```java
package com.prompthub.payment.domain.event;

import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.domain.model.Refund;

public record PaymentRefundFailedEvent(Payment payment, Refund refund) {}
```

`RefundService.java`에는 `PaymentRefundFailedEvent`를 발행하는 호출부가 **두 곳** 있다 — 둘 다 2-인자로 고친다.

`failByExceedingLimit` 메서드 마지막 줄:

```java
        applicationEventPublisher.publishEvent(new PaymentRefundFailedEvent(payment, refund));
```

`executeGatewayRefund` catch 블록 마지막 줄:

```java
            applicationEventPublisher.publishEvent(new PaymentRefundFailedEvent(payment, refund));
```

`KafkaPaymentEventPublisherTest.java` 139~140번 줄:

```java
        publisher.onPaymentRefundFailed(
            new com.prompthub.payment.domain.event.PaymentRefundFailedEvent(payment, refund));
```

`RefundServiceTest.java`의 `PG_실패_시_Refund_FAILED_Payment_상태_불변_및_실패_이벤트_발행` 테스트 마지막 단언(`eventCaptor.getValue().failureReason()` 참조) 교체:

```java
        assertThat(eventCaptor.getValue().refund().getFailureReason()).isEqualTo("환불 실패");
```

- [x] **Step 7: AuditLogEventListener 갱신 — 환불 이벤트당 2건 저장**

`AuditLogEventListener.java` 전체 교체(`PAYMENT_REQUESTED` 리스너는 Task 3에서 추가):

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
        save(AuditLog.forRefundRequested(event.payment(), event.refund()), event.refund().getId());
        save(AuditLog.forRefundCompleted(event.payment(), event.refund()), event.refund().getId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRefundFailed(PaymentRefundFailedEvent event) {
        save(AuditLog.forRefundRequested(event.payment(), event.refund()), event.refund().getId());
        save(AuditLog.forRefundFailed(event.payment(), event.refund()), event.refund().getId());
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

- [x] **Step 8: 테스트 통과 확인**

Run: `JAVA_HOME=/Users/anjinpyo/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.persistence.AuditLogJpaRepositoryTest" --tests "com.prompthub.payment.infrastructure.persistence.AuditLogEventListenerTest" --tests "com.prompthub.payment.infrastructure.messaging.KafkaPaymentEventPublisherTest" --tests "com.prompthub.payment.application.service.RefundServiceTest"`
Expected: PASS (전체 GREEN)

- [x] **Step 9: Commit**

```bash
git add src/main/resources/db/migration/V11__extend_audit_log_order_and_failure_code.sql \
  src/main/java/com/prompthub/payment/domain/model/AuditEventType.java \
  src/main/java/com/prompthub/payment/domain/model/AuditLog.java \
  src/main/java/com/prompthub/payment/domain/event/PaymentRefundFailedEvent.java \
  src/main/java/com/prompthub/payment/application/service/RefundService.java \
  src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListener.java \
  src/test/java/com/prompthub/payment/infrastructure/persistence/AuditLogJpaRepositoryTest.java \
  src/test/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListenerTest.java \
  src/test/java/com/prompthub/payment/infrastructure/messaging/KafkaPaymentEventPublisherTest.java \
  src/test/java/com/prompthub/payment/application/service/RefundServiceTest.java
git commit -m "$(cat <<'EOF'
feat: audit_log에 order_id/failure_code 추가, 환불 이벤트 타입 명명 정리

- audit_log에 order_id(NOT NULL)/failure_code 컬럼 추가, CS 문의가 주로 order_id
  기준인 점을 반영해 조인 없이 바로 조회 가능하게 함
- 환불 이벤트 타입을 PAYMENT_REFUNDED/PAYMENT_REFUND_FAILED에서
  REFUND_COMPLETED/REFUND_FAILED로 개명하고 REFUND_REQUESTED를 신설해
  결제(PAYMENT_REQUESTED/APPROVED/FAILED)와 대칭 구조로 정리
- REFUND_REQUESTED는 별도 도메인 이벤트 없이 기존 환불 완료/실패 리스너가
  감사로그 2건(REQUESTED+터미널)을 함께 저장하는 방식으로 구현
- PaymentRefundFailedEvent에서 중복이던 failureReason 파라미터 제거

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 결제 시도(PAYMENT_REQUESTED) 감사로그 추가

**Files:**
- Create: `src/main/java/com/prompthub/payment/domain/event/PaymentRequestedEvent.java`
- Modify: `src/main/java/com/prompthub/payment/application/service/ConfirmPaymentService.java`
- Modify: `src/main/java/com/prompthub/payment/domain/model/AuditLog.java`
- Modify: `src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListener.java`
- Modify: `src/test/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListenerTest.java`
- Modify: `src/test/java/com/prompthub/payment/application/service/ConfirmPaymentServiceTest.java`

**Interfaces:**
- Consumes: Task 2의 `AuditEventType.PAYMENT_REQUESTED`(이미 정의됨), `AuditLog` private 생성자(같은 클래스 내부에서만 사용).
- Produces: `PaymentRequestedEvent(Payment payment)`, `AuditLog.forPaymentRequested(Payment)`, `AuditLogEventListener.onPaymentRequested(PaymentRequestedEvent)`.

**⚠️ 중요 — Mockito 오버로드 함정:** `ApplicationEventPublisher`는 `publishEvent(Object)`와 `publishEvent(ApplicationEvent)` 두 오버로드를 갖는다. `PaymentRequestedEvent`/`PaymentApprovedEvent`/`PaymentFailedEvent` 등은 전부 `ApplicationEvent`를 상속하지 않는 순수 record라 실제로는 항상 `publishEvent(Object)` 오버로드로 호출된다. 그런데 테스트 코드에서 `verify(mock).publishEvent(any())`처럼 타입 파라미터 없이 쓰면, javac가 컴파일 시 **더 특수한** `publishEvent(ApplicationEvent)` 오버로드를 선택해버려 실제 호출(`publishEvent(Object)`)과 어긋난다. 기존 `ConfirmPaymentServiceTest`의 `verify(applicationEventPublisher, never()).publishEvent(any());` 두 곳이 바로 이 함정에 걸려 있었다 — 실제로는 `PaymentFailedEvent`가 발행되는데도 겉보기엔 "발행 안 됨"을 검증하는 것처럼 보이는 죽은 단언이었다. 이번 태스크에서 `ArgumentCaptor<Object>`로 명시적으로 캡처하는 방식으로 고쳐서, `PaymentRequestedEvent` 추가 후에도 실제 호출을 정확히 검증한다.

- [x] **Step 1: 실패하는 테스트 작성 — AuditLogEventListener의 PAYMENT_REQUESTED 처리**

`AuditLogEventListenerTest.java`에 아래 테스트와 import를 추가한다(`import com.prompthub.payment.domain.event.PaymentRequestedEvent;` 추가):

```java
    @Test
    void 결제_요청_이벤트_수신_시_감사로그를_저장한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-6", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());

        listener.onPaymentRequested(new PaymentRequestedEvent(payment));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog auditLog = captor.getValue();
        assertThat(auditLog.getOrderId()).isEqualTo(payment.getOrderId());
        assertThat(auditLog.getEntityType()).isEqualTo(AuditEntityType.PAYMENT);
        assertThat(auditLog.getEntityId()).isEqualTo(payment.getId());
        assertThat(auditLog.getEventType()).isEqualTo(AuditEventType.PAYMENT_REQUESTED);
        assertThat(auditLog.getActorId()).isEqualTo(payment.getUserId());
        assertThat(auditLog.getNewStatus()).isEqualTo("REQUESTED");
        assertThat(auditLog.getFailureCode()).isNull();
        assertThat(auditLog.getDetail()).isNull();
    }
```

- [x] **Step 2: 컴파일 실패 확인**

Run: `JAVA_HOME=/Users/anjinpyo/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:compileTestJava`
Expected: FAIL — `PaymentRequestedEvent` 심볼 없음, `onPaymentRequested` 메서드 없음

- [x] **Step 3: PaymentRequestedEvent 생성**

`src/main/java/com/prompthub/payment/domain/event/PaymentRequestedEvent.java`:

```java
package com.prompthub.payment.domain.event;

import com.prompthub.payment.domain.model.Payment;

public record PaymentRequestedEvent(Payment payment) {}
```

- [x] **Step 4: AuditLog.forPaymentRequested 팩토리 추가**

`AuditLog.java`의 `forPaymentApproved` 메서드 바로 위에 추가:

```java
    public static AuditLog forPaymentRequested(Payment payment) {
        return new AuditLog(
            UUID.randomUUID(), payment.getOrderId(), AuditEntityType.PAYMENT, payment.getId(),
            AuditEventType.PAYMENT_REQUESTED, payment.getUserId(), payment.getStatus().name(),
            null, null, payment.getRequestedAt()
        );
    }

```

- [x] **Step 5: AuditLogEventListener에 리스너 추가**

`AuditLogEventListener.java` — import에 `com.prompthub.payment.domain.event.PaymentRequestedEvent;` 추가, `onPaymentApproved` 위에 추가:

```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRequested(PaymentRequestedEvent event) {
        save(AuditLog.forPaymentRequested(event.payment()), event.payment().getId());
    }

```

- [x] **Step 6: ConfirmPaymentService에서 TX1 커밋 직후 이벤트 발행**

`ConfirmPaymentService.java` — import에 `com.prompthub.payment.domain.event.PaymentRequestedEvent;` 추가, TX1 블록 수정:

```java
        UUID paymentId;
        try {
            paymentId = transactionTemplate.execute(status -> {
                Payment payment = Payment.create(
                    command.orderId(), command.userId(),
                    command.paymentKey(), PG_PROVIDER, PAYMENT_METHOD,
                    orderInfo.totalAmount()
                );
                paymentRepository.saveAndFlush(payment);
                payment.markRequested(OffsetDateTime.now());
                paymentRepository.save(payment);
                applicationEventPublisher.publishEvent(new PaymentRequestedEvent(payment));
                return payment.getId();
            });
        } catch (DataIntegrityViolationException e) {
            // payment_key/orderId 사전 체크와 INSERT 사이의 좁은 레이스 — 최종 방어선
            throw new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT);
        }
```

- [x] **Step 7: ConfirmPaymentServiceTest 갱신 — 오버로드 함정 수정 + PaymentRequestedEvent 검증**

`ConfirmPaymentServiceTest.java` — import에 `com.prompthub.payment.domain.event.PaymentFailedEvent;`, `com.prompthub.payment.domain.event.PaymentRequestedEvent;`, `java.util.List` 추가.

`Toss_성공_시_PAID_상태_이벤트_발행` 테스트의 이벤트 검증 블록을 아래로 교체:

```java
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher, org.mockito.Mockito.times(2)).publishEvent(eventCaptor.capture());
        List<Object> events = eventCaptor.getAllValues();
        assertThat(events.get(0)).isInstanceOf(PaymentRequestedEvent.class);
        assertThat(events.get(1)).isInstanceOf(PaymentApprovedEvent.class);

        PaymentApprovedEvent approvedEvent = (PaymentApprovedEvent) events.get(1);
        assertThat(approvedEvent.payment().getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(approvedEvent.payment().getApprovedAmount()).isEqualTo(10_000);
        assertThat(approvedEvent.payment().getRequestPayload()).isEqualTo("{\"paymentKey\":\"toss-key\"}");
```

`Toss_서버오류성_4xx_시_FAILED_상태_PG_INVALID_REQUEST_예외` 테스트의 `verify(applicationEventPublisher, never()).publishEvent(any());` 줄을 아래로 교체:

```java
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher, org.mockito.Mockito.times(2)).publishEvent(eventCaptor.capture());
        List<Object> events = eventCaptor.getAllValues();
        assertThat(events.get(0)).isInstanceOf(PaymentRequestedEvent.class);
        assertThat(events.get(1)).isInstanceOf(PaymentFailedEvent.class);
```

`Toss_실패_시_FAILED_상태_PAY_FAILED_예외` 테스트의 `verify(applicationEventPublisher, never()).publishEvent(any());` 줄도 동일하게 교체:

```java
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher, org.mockito.Mockito.times(2)).publishEvent(eventCaptor.capture());
        List<Object> events = eventCaptor.getAllValues();
        assertThat(events.get(0)).isInstanceOf(PaymentRequestedEvent.class);
        assertThat(events.get(1)).isInstanceOf(PaymentFailedEvent.class);
```

(`금액_불일치_시_AMOUNT_MISMATCH_예외_및_결제시도_미기록` 테스트는 TX1 진입 전에 실패하므로 손대지 않는다 — 기존 `verify(applicationEventPublisher, never()).publishEvent(any());` 그대로 유효.)

- [x] **Step 8: 테스트 통과 확인**

Run: `JAVA_HOME=/Users/anjinpyo/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.persistence.AuditLogEventListenerTest" --tests "com.prompthub.payment.application.service.ConfirmPaymentServiceTest"`
Expected: PASS (전체 GREEN)

- [x] **Step 9: 전체 테스트 스위트 실행(회귀 확인)**

Run: `JAVA_HOME=/Users/anjinpyo/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test`
Expected: PASS (전체 GREEN) — Testcontainers 기반 통합 테스트(`ConfirmPaymentIntegrationTest`, `PartialRefundIntegrationTest` 등) 포함해 전부 통과해야 한다.

- [x] **Step 10: Commit**

```bash
git add src/main/java/com/prompthub/payment/domain/event/PaymentRequestedEvent.java \
  src/main/java/com/prompthub/payment/application/service/ConfirmPaymentService.java \
  src/main/java/com/prompthub/payment/domain/model/AuditLog.java \
  src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListener.java \
  src/test/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListenerTest.java \
  src/test/java/com/prompthub/payment/application/service/ConfirmPaymentServiceTest.java
git commit -m "$(cat <<'EOF'
feat: 결제 시도(PAYMENT_REQUESTED) 감사로그 추가

- READY→REQUESTED 전이(TX1 커밋 직후)에 PaymentRequestedEvent를 신설해
  감사로그에 남김 — 이후 PG 응답 전 크래시/타임아웃으로 멈춘 결제 시도도
  시간순 이력에 남게 됨
- Kafka는 구독하지 않음(AuditLogEventListener 전용, 외부 이벤트 계약 미확장)
- ConfirmPaymentServiceTest의 오버로드 검증 함정(publishEvent(Object) vs
  publishEvent(ApplicationEvent))을 바로잡아 실제 발행 이벤트를 검증하도록 수정

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: 문서 갱신 — db-schema.md

**Files:**
- Modify: `.claude/docs/db-schema.md`

**Interfaces:**
- Consumes: Task 1~3에서 확정된 최종 스키마(참고용, 코드 인터페이스 없음).

- [x] **Step 1: refund 테이블 섹션에 failure_code 행 추가**

`refund` 테이블 표의 `failure_reason` 행 바로 위에 추가:

```markdown
| `failure_code` | VARCHAR(50) | — | NULL | PG사 환불 실패 코드 또는 내부 사유 코드(예: `REFUND_LIMIT_EXCEEDED`). `fail()` 호출 시에만 값 설정 |
```

- [x] **Step 2: audit_log 섹션 전체를 최신 스키마로 교체**

`## audit_log 테이블` 섹션 전체를 아래로 교체:

```markdown
## audit_log 테이블

결제·환불의 시도/종결 상태 전이(요청/승인/실패/환불요청/환불완료/환불실패) 6종에 한해 행위자·시각·사유를 append-only로 기록하는 이력 테이블(#484, #539). 조회 API는 아직 없다 — 저장까지만 구현됨.

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|---|---|---|---|---|
| `id` | UUID | ✅ | — | PK |
| `order_id` | UUID | ✅ | — | 연결된 주문 ID. `payment.order_id`(REFUND는 `refund.payment_id`→`payment.order_id`)에서 획득 |
| `entity_type` | VARCHAR(20) | ✅ | — | `PAYMENT` / `REFUND` |
| `entity_id` | UUID | ✅ | — | `payment.id` 또는 `refund.id`. FK 없음 — entity_type에 따라 대상 테이블이 갈리는 폴리모픽 참조 |
| `event_type` | VARCHAR(30) | ✅ | — | `PAYMENT_REQUESTED` / `PAYMENT_APPROVED` / `PAYMENT_FAILED` / `REFUND_REQUESTED` / `REFUND_COMPLETED` / `REFUND_FAILED` |
| `actor_id` | UUID | ✅ | — | 행위자 user_id. `Payment.userId`에서 획득(Refund엔 user_id 컬럼 없음 — #398로 제거됨) |
| `new_status` | VARCHAR(20) | ✅ | — | 전이 후 상태 스냅샷 |
| `failure_code` | VARCHAR(50) | — | NULL | 실패 코드. `PAYMENT_FAILED`/`REFUND_FAILED`만 값 있음 |
| `detail` | TEXT | — | NULL | 실패 사유 원문. `PAYMENT_FAILED`/`REFUND_FAILED`만 값 있음 |
| `occurred_at` | TIMESTAMPTZ | ✅ | — | 엔티티 기준 실제 발생 시각 |
| `created_at` | TIMESTAMPTZ | ✅ | — | 감사로그 레코드 삽입 시각 |

`updated_at` 없음 — append-only, 절대 수정하지 않는다.

`REFUND_REQUESTED`는 별도 도메인 이벤트가 아니라, 환불 완료/실패 이벤트 처리 시 터미널 이벤트(`REFUND_COMPLETED`/`REFUND_FAILED`)와 함께 같은 트랜잭션 커밋 시점에 기록된다(`occurred_at`은 `refund.requestedAt`). 따라서 PG 호출 도중 크래시/타임아웃으로 멈춘 환불 시도는 여전히 감사로그에 남지 않는다 — `RefundService`가 결제 승인 흐름과 달리 단일 트랜잭션 구조라 REQUESTED 상태 단독 커밋 시점이 없기 때문이다(구조 변경은 별도 이슈).

**인덱스** (Flyway):

| 인덱스 | 대상 | 목적 |
|---|---|---|
| `idx_audit_log_entity` | (`entity_type`, `entity_id`) | 엔티티별 이력 조회 |
| `idx_audit_log_order` | (`order_id`) | CS 문의 등 주문 단위 조회 |
```

- [x] **Step 3: Commit**

```bash
git add .claude/docs/db-schema.md
git commit -m "$(cat <<'EOF'
docs: audit_log/refund 스키마 문서를 감사로그 확장 내용으로 갱신

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```
